package com.autoding.punch

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Color

/**
 * 钉钉无障碍自动打卡服务
 *
 * 状态机流程：
 *   LAUNCH(打开钉钉) -> WAIT_MAIN(等待首页, 找"工作"Tab) -> WAIT_WORK(找"考勤打卡"入口)
 *   -> WAIT_ATT(找打卡按钮) -> WAIT_CONFIRM(处理确认弹窗) -> DONE
 *
 * 每个"等待"步骤都会同时检测：是否已经出现打卡按钮（防止页面已就绪）
 */
class DingTalkAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_PUNCH_IN = "com.autoding.punch.action.PUNCH_IN"
        const val ACTION_PUNCH_OUT = "com.autoding.punch.action.PUNCH_OUT"
        const val ACTION_CANCEL = "com.autoding.punch.action.CANCEL"
        const val ACTION_TEST = "com.autoding.punch.action.TEST" // 调试：只打开钉钉不点击

        const val PACKAGE_DINGTALK = "com.alibaba.android.rimet"
        private const val CHANNEL_ID = "autoding_notify"
        private const val NOTIFY_ID = 9527
        private const val FOREGROUND_ID = 1001

        @Volatile
        var instance: DingTalkAccessibilityService? = null
            private set

        /** 当前是否在自动化流程中 */
        val isRunning: Boolean
            get() = instance?.isBusy ?: false
    }

    private enum class Step {
        IDLE, LAUNCH, WAIT_MAIN, WAIT_WORK, WAIT_ATT, WAIT_CONFIRM, REFRESHING, DONE
    }

    private var step = Step.IDLE
    private var targetType: LogStore.PunchType? = null
    private var startTime = 0L
    private var attempt = 0
    private var lastActionText = ""
    private var refreshAttempts = 0          // 外勤误判后的定位刷新重试次数
    private val MAX_REFRESH = 4             // 最多自动刷新重试次数
    private var polling = false             // 考勤页轮询（补偿钉钉静态无事件的情况）

    /** 考勤页轮询：钉钉有时定位刷新后不触发无障碍事件，靠轮询兜底重新评估外勤/打卡按钮 */
    private val pollRunnable = Runnable {
        if (step != Step.WAIT_ATT && step != Step.WAIT_CONFIRM) {
            stopPolling()
            return@Runnable
        }
        val root = rootInActiveWindow
        if (root != null) {
            // 已打卡则跳过，避免重复打卡
            if (targetType != null && step != Step.WAIT_CONFIRM && checkAlreadyPunched(root, targetType!!)) return@Runnable
            // 外勤处理：WAIT_ATT 自动刷新重试；WAIT_CONFIRM 直接放弃（不确认外勤）
            if (step == Step.WAIT_ATT && checkOutOfRange(root)) {
                handleOutOfRange()
                return@Runnable
            }
            if (step == Step.WAIT_CONFIRM && checkOutOfRange(root)) {
                finishWithResult(LogStore.PunchResult.FAILED, "弹出外勤打卡确认框，已放弃（不执行外勤打卡）")
                return@Runnable
            }
            handleUi(root)
        }
        handler.postDelayed(pollRunnable, 2000)
    }

    private fun startPolling() {
        stopPolling()
        polling = true
        handler.postDelayed(pollRunnable, 2000)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (step != Step.IDLE && step != Step.DONE) {
            finishWithResult(LogStore.PunchResult.FAILED, "操作超时（${(System.currentTimeMillis() - startTime) / 1000}s），未完成打卡")
        }
    }

    val isBusy: Boolean get() = step != Step.IDLE

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            createChannel()
        } catch (e: Exception) {
            LogStore.addLog(
                this, LogStore.PunchType.IN, LogStore.PunchResult.FAILED,
                "无障碍服务连接异常（已兜底，服务保持开启）：${e.message}"
            )
        }
        // 提升为前台服务，常驻通知以提高在激进省电策略（如 OriginOS）下的存活率
        startForegroundIfNeeded()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val wasBusy = step != Step.IDLE
        instance = null
        val dev = "${Build.MODEL} / Android ${Build.VERSION.SDK_INT}"
        if (wasBusy) {
            finishWithResult(LogStore.PunchResult.FAILED, "无障碍服务被系统断开 [$dev]")
        } else {
            // 空闲态被解绑：记录设备信息，便于判断是系统回收（如 OriginOS 省电策略）还是崩溃
            LogStore.addLog(
                this, LogStore.PunchType.IN, LogStore.PunchResult.SKIPPED,
                "无障碍服务被系统解绑（空闲）[$dev]"
            )
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // 通过 startService 接收打卡指令
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PUNCH_IN -> startFlow(LogStore.PunchType.IN)
            ACTION_PUNCH_OUT -> startFlow(LogStore.PunchType.OUT)
            ACTION_TEST -> {
                step = Step.LAUNCH
                targetType = null
                launchDingTalk()
                handler.postDelayed({ reset() }, 8000)
            }
            ACTION_CANCEL -> {
                handler.removeCallbacks(timeoutRunnable)
                reset()
                LogStore.addLog(this, LogStore.PunchType.IN, LogStore.PunchResult.SKIPPED, "已手动取消")
            }
        }
        return START_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 全局兜底：任何未预期异常都拦截，避免系统因服务崩溃而强制关闭无障碍服务
        try {
            if (step == Step.IDLE || step == Step.DONE) return
            if (event?.packageName?.toString() != PACKAGE_DINGTALK) {
                // 钉钉启动过程中可能出现其他包名的窗口（如系统启动器），忽略
                return
            }
            val root = rootInActiveWindow ?: return

            // 前置状态检查 1：已打卡则跳过，避免重复打卡（WAIT_CONFIRM 除外——点击成功后页面出现"已打卡"属正常成功）
            if (targetType != null && step != Step.WAIT_CONFIRM && checkAlreadyPunched(root, targetType!!)) return

            // 前置状态检查 2：考勤页面中若处于外勤状态（不在打卡范围）
            // - WAIT_ATT：定位可能不准，自动退出考勤页刷新定位后重试
            // - WAIT_CONFIRM：已弹出外勤确认框，绝不确认，直接放弃
            if (step == Step.WAIT_ATT && checkOutOfRange(root)) {
                handleOutOfRange()
                return
            }
            if (step == Step.WAIT_CONFIRM && checkOutOfRange(root)) {
                finishWithResult(LogStore.PunchResult.FAILED, "弹出外勤打卡确认框，已放弃（不执行外勤打卡）")
                return
            }

            handleUi(root)
        } catch (e: Exception) {
            LogStore.addLog(
                this, targetType ?: LogStore.PunchType.IN, LogStore.PunchResult.FAILED,
                "无障碍事件异常已被拦截，服务保持开启：${e.message}"
            )
        }
    }

    override fun onInterrupt() {
        // 无障碍服务被中断，静默处理
    }

    // ============ 流程控制 ============

    private fun startFlow(type: LogStore.PunchType) {
        if (step != Step.IDLE) {
            LogStore.addLog(this, type, LogStore.PunchResult.SKIPPED, "已有流程在进行中，忽略本次触发")
            return
        }
        targetType = type
        attempt++
        refreshAttempts = 0
        lastActionText = if (type == LogStore.PunchType.IN) "上班打卡" else "下班打卡"
        step = Step.LAUNCH
        startTime = System.currentTimeMillis()

        // 检查钉钉是否安装
        if (!isDingTalkInstalled()) {
            finishWithResult(LogStore.PunchResult.FAILED, "未检测到钉钉应用，请先安装")
            return
        }

        notify("正在自动${lastActionText}", "已启动钉钉…")
        LogStore.addLog(this, type, LogStore.PunchResult.SKIPPED, "开始自动${lastActionText}")

        launchDingTalk()
        // 留足外勤误判自动刷新重试的时间（最多 4 次，每次约 6s）
        handler.postDelayed(timeoutRunnable, 90000)
    }

    private fun launchDingTalk() {
        try {
            val launchIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(PACKAGE_DINGTALK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(launchIntent)
            // 给启动留一点时间
            handler.postDelayed({ if (step == Step.LAUNCH) step = Step.WAIT_MAIN }, 1500)
        } catch (e: Exception) {
            finishWithResult(LogStore.PunchResult.FAILED, "启动钉钉失败: ${e.message}")
        }
    }

    private fun handleUi(root: AccessibilityNodeInfo) {
        when (step) {
            Step.WAIT_MAIN -> handleMainPage(root)
            Step.WAIT_WORK -> handleWorkPage(root)
            Step.WAIT_ATT -> handleAttendancePage(root)
            Step.WAIT_CONFIRM -> handleConfirmDialog(root)
            else -> {}
        }
    }

    // 首页 / 任意页面：优先找打卡按钮（可能钉钉停在上次考勤页），再找"工作"Tab
    private fun handleMainPage(root: AccessibilityNodeInfo) {
        // 1. 尝试直接找打卡按钮（钉钉可能直接打开考勤页）
        if (targetType != null && tryClickPunchButton(root, targetType!!)) return

        // 2. 找"工作"底部 Tab
        if (clickNodeByText(root, "工作", clickableOnly = true)) {
            step = Step.WAIT_WORK
            notify("自动${lastActionText}", "已进入「工作」页面，正在找考勤打卡…")
        } else if (clickNodeByText(root, "工作台", clickableOnly = true)) {
            step = Step.WAIT_WORK
        } else {
            // 3. 首页找"考勤打卡"快捷入口（部分版本首页直接有）
            if (clickNodeByText(root, "考勤打卡")) {
                step = Step.WAIT_ATT
                startPolling()
            }
        }
    }

    private fun handleWorkPage(root: AccessibilityNodeInfo) {
        // 1. 直接找打卡按钮（工作台内嵌考勤卡片的情况）
        if (targetType != null && tryClickPunchButton(root, targetType!!)) return

        // 2. 找"考勤打卡"入口
        if (clickNodeByText(root, "考勤打卡")) {
            step = Step.WAIT_ATT
            startPolling()
            notify("自动${lastActionText}", "已进入考勤页面…")
            return
        }

        // 3. 部分版本需要先点"全部"或滚动，尝试滚动寻找
        if (scrollToFind(root, "考勤打卡")) {
            handler.postDelayed({
                rootInActiveWindow?.let { if (step == Step.WAIT_WORK) clickNodeByText(it, "考勤打卡") }
                step = Step.WAIT_ATT
            }, 600)
        }
    }

    private fun handleAttendancePage(root: AccessibilityNodeInfo) {
        // 找打卡按钮
        if (targetType != null && tryClickPunchButton(root, targetType!!)) return

        // 找不到时滚动一次再试
        if (scrollDown(root)) {
            handler.postDelayed({
                rootInActiveWindow?.let { if (step == Step.WAIT_ATT) tryClickPunchButton(it, targetType!!) }
            }, 500)
        }
    }

    private fun handleConfirmDialog(root: AccessibilityNodeInfo) {
        // 外勤打卡确认弹窗：绝不确认，直接放弃（防止误点"确认"触发外勤打卡）
        val outRangeTexts = listOf("不在打卡范围", "外勤打卡", "确认外勤")
        for (t in outRangeTexts) {
            val node = findNodeByText(root, t, clickableOnly = false, exact = false)
            if (node != null) {
                node.recycle()
                finishWithResult(LogStore.PunchResult.FAILED, "弹出外勤打卡确认框，已放弃（不执行外勤打卡）")
                return
            }
        }
        // 打卡后可能弹出确认/成功提示
        val confirmTexts = listOf("确认", "确定", "继续打卡", "我知道了", "好的")
        for (t in confirmTexts) {
            if (clickNodeByText(root, t, clickableOnly = true)) {
                handler.postDelayed({ finishWithResult(LogStore.PunchResult.SUCCESS, "自动${lastActionText}完成") }, 800)
                return
            }
        }
        // 没有确认弹窗，直接判定成功
        finishWithResult(LogStore.PunchResult.SUCCESS, "自动${lastActionText}完成")
    }

    // ============ 元素查找与点击 ============

    /**
     * 检测是否已打卡（页面出现"已打卡"标记）。
     * 命中时直接跳过并结束流程，避免重复打卡产生异常记录。
     */
    private fun checkAlreadyPunched(root: AccessibilityNodeInfo, type: LogStore.PunchType): Boolean {
        val label = if (type == LogStore.PunchType.IN) "上班" else "下班"
        val markers = listOf("已打卡", "打卡成功")
        for (m in markers) {
            val node = findNodeByText(root, m, clickableOnly = false, exact = false) ?: continue
            node.recycle()
            finishWithResult(LogStore.PunchResult.SKIPPED, "${label}已打卡，自动跳过")
            return true
        }
        return false
    }

    /**
     * 检测是否处于外勤状态（不在打卡范围）。
     * 仅返回是否命中，不做结束处理 —— 命中后由调用方决定「自动刷新重试」还是「放弃」。
     * 本工具只执行正常打卡，绝不触发外勤打卡。
     */
    private fun checkOutOfRange(root: AccessibilityNodeInfo): Boolean {
        val markers = listOf("外勤打卡", "不在打卡范围", "打卡范围外")
        for (m in markers) {
            val node = findNodeByText(root, m, clickableOnly = false, exact = false) ?: continue
            node.recycle()
            return true
        }
        return false
    }

    /**
     * 检测到外勤状态：定位可能不准，自动退出考勤页并重新进入以刷新钉钉定位，最多重试 MAX_REFRESH 次。
     * 超过次数仍显示外勤，则判定为确不在打卡范围并放弃（绝不执行外勤打卡）。
     */
    private fun handleOutOfRange() {
        refreshAttempts++
        if (refreshAttempts <= MAX_REFRESH) {
            LogStore.addLog(
                this, targetType ?: LogStore.PunchType.IN, LogStore.PunchResult.SKIPPED,
                "检测到外勤状态（定位可能不准），自动刷新定位（第 $refreshAttempts/$MAX_REFRESH 次）"
            )
            notify("定位可能不准", "自动退出考勤页刷新定位（第 $refreshAttempts/$MAX_REFRESH 次）…")
            stopPolling()
            step = Step.REFRESHING
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({
                if (step == Step.REFRESHING) {
                    step = Step.WAIT_MAIN
                    launchDingTalk()
                }
            }, 1500)
        } else {
            finishWithResult(
                LogStore.PunchResult.SKIPPED,
                "多次刷新定位后仍显示外勤，已放弃（可能确不在打卡范围）"
            )
        }
    }

    /** 尝试点击打卡按钮；返回是否已点击 */
    private fun tryClickPunchButton(root: AccessibilityNodeInfo, type: LogStore.PunchType): Boolean {
        val inText = "上班打卡"
        val outText = "下班打卡"
        val genericText = "打卡"

        // 优先精确匹配
        if (type == LogStore.PunchType.IN) {
            if (clickNodeByText(root, inText, clickableOnly = true, exact = true)) {
                afterPunchClicked()
                return true
            }
        } else {
            if (clickNodeByText(root, outText, clickableOnly = true, exact = true)) {
                afterPunchClicked()
                return true
            }
        }

        // 其次匹配通用"打卡"（排除"考勤打卡"入口和说明文字）
        if (clickNodeByText(root, genericText, clickableOnly = true, exact = true)) {
            afterPunchClicked()
            return true
        }
        return false
    }

    private fun afterPunchClicked() {
        step = Step.WAIT_CONFIRM
        notify("自动${lastActionText}", "已点击按钮，等待确认…")
        // 确认弹窗一般很快出现；3 秒后如果还没有确认元素，判定成功
        handler.postDelayed({
            if (step == Step.WAIT_CONFIRM) {
                finishWithResult(LogStore.PunchResult.SUCCESS, "自动${lastActionText}完成（未弹确认框）")
            }
        }, 3000)
    }

    /**
     * 在节点树中查找文本匹配的节点并点击。
     * @param clickableOnly 只匹配可点击节点（避免点到文字标签）
     * @param exact 精确匹配
     */
    private fun clickNodeByText(
        root: AccessibilityNodeInfo,
        text: String,
        clickableOnly: Boolean = false,
        exact: Boolean = false
    ): Boolean {
        val node = findNodeByText(root, text, clickableOnly, exact) ?: return false
        return clickNode(node)
    }

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        text: String,
        clickableOnly: Boolean,
        exact: Boolean
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isClickable) return true
                parent = parent.parent
                depth++
            }
            return false
        }

        var scannedNodes = 0
        fun dfs(node: AccessibilityNodeInfo, depth: Int) {
            // 深度/总量保护：钉钉复杂页面节点树很深，防止栈溢出或耗时过长
            if (depth > 80 || scannedNodes > 8000) return
            scannedNodes++
            if (node.packageName?.toString() != PACKAGE_DINGTALK) return
            val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            if (nodeText.isNotEmpty()) {
                val match = if (exact) nodeText == text else nodeText.contains(text)
                if (match) {
                    // clickableOnly 时要求：节点自身可点击，或 5 层内的祖先可点击
                    val usable = !clickableOnly || node.isClickable || hasClickableAncestor(node)
                    if (usable) {
                        candidates.add(node)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                dfs(child, depth + 1)
            }
        }
        dfs(root, 0)

        // 优先可点击的节点
        val best = candidates.firstOrNull { it.isClickable } ?: candidates.firstOrNull()
        return best?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // 如果节点本身不可点击，尝试找它的可点击父节点
        var target = node
        var parent = target.parent
        var depth = 0
        while (!target.isClickable && parent != null && depth < 5) {
            target = parent
            parent = parent.parent
            depth++
        }
        val ok = try {
            if (target.isClickable) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                // 找不到可点击祖先，尝试在目标中心坐标上执行手势点击
                val bounds = android.graphics.Rect()
                target.getBoundsInScreen(bounds)
                if (bounds.isEmpty) {
                    false
                } else {
                    val centerX = (bounds.left + bounds.right) / 2f
                    val centerY = (bounds.top + bounds.bottom) / 2f
                    try {
                        dispatchGesture(
                            android.accessibilityservice.GestureDescription.Builder()
                                .addStroke(
                                    android.accessibilityservice.GestureDescription.StrokeDescription(
                                        android.graphics.Path().apply {
                                            moveTo(centerX, centerY)
                                            lineTo(centerX + 0.01f, centerY)
                                        },
                                        0,
                                        80
                                    )
                                )
                                .build(),
                            null,
                            null
                        )
                    } catch (e: Exception) {
                        false
                    }
                }
            }
        } finally {
            try {
                node.recycle()
            } catch (_: Exception) {
            }
        }
        return ok
    }

    /** 尝试在可滚动容器中滚动以找到目标文本 */
    private fun scrollToFind(root: AccessibilityNodeInfo, text: String): Boolean {
        // 查找包含该文本的节点（即使不可见）
        val found = findNodeByText(root, text, clickableOnly = false, exact = false)
        if (found != null) {
            found.recycle()
            // 找到但可能不在屏幕内，尝试向上滚动列表
            val scroller = findScrollable(root) ?: return false
            return scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        val scroller = findScrollable(root) ?: return false
        return scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun scrollDown(root: AccessibilityNodeInfo): Boolean {
        val scroller = findScrollable(root) ?: return false
        return scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }
        return null
    }

    // ============ 结束与通知 ============

    private fun finishWithResult(result: LogStore.PunchResult, message: String) {
        stopPolling()
        val type = targetType ?: LogStore.PunchType.IN
        LogStore.addLog(this, type, result, message)
        notify("自动${lastActionText} ${result.label}", message, success = result != LogStore.PunchResult.FAILED)
        // 让钉钉保持几秒给用户看到结果，然后回到桌面
        handler.postDelayed({ reset() }, 2500)
        step = Step.DONE
    }

    private fun reset() {
        stopPolling()
        handler.removeCallbacks(timeoutRunnable)
        step = Step.IDLE
        targetType = null
    }

    private fun isDingTalkInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(PACKAGE_DINGTALK, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ============ 通知 ============

    /** 将无障碍服务提升为前台服务并常驻通知，防止被系统回收 */
    private fun startForegroundIfNeeded() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("自动打卡辅助服务运行中")
                .setContentText("常驻前台，防止被系统回收")
                .setOngoing(true)
                .setShowWhen(false)
                .build()
            startForeground(FOREGROUND_ID, notification)
        } catch (e: Exception) {
            LogStore.addLog(
                this, LogStore.PunchType.IN, LogStore.PunchResult.SKIPPED,
                "前台化失败，服务仍运行：${e.message}"
            )
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "打卡结果通知", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "自动打卡的结果通知"
            enableVibration(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notify(title: String, content: String, success: Boolean = true) {
        try {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(content)
                .setColor(if (success) Color.rgb(0, 181, 120) else Color.rgb(255, 77, 79))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            NotificationManagerCompat.from(this).notify(NOTIFY_ID, notification)
        } catch (e: Exception) {
            // 通知权限未开启时静默失败
        }
    }
}
