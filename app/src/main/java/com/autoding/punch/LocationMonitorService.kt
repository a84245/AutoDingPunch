package com.autoding.punch

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 位置监测前台服务
 *
 * 持续获取定位 -> 计算与公司的距离 -> 状态机判断进入/离开围栏 -> 发送提醒通知
 *
 * 状态机：UNKNOWN -> (定位成功) -> INSIDE / OUTSIDE
 *         OUTSIDE --进入半径--> INSIDE  (发送上班提醒)
 *         INSIDE  --离开半径*1.3--> OUTSIDE (发送下班提醒，带回滞防止GPS抖动)
 */
class LocationMonitorService : Service(), LocationListener {

    companion object {
        private const val TAG = "LocationMonitor"
        private const val CHANNEL_ID = "autoding_monitor"
        private const val NOTIFY_ID = 1001

        const val ACTION_START = "com.autoding.punch.action.START_MONITOR"
        const val ACTION_STOP = "com.autoding.punch.action.STOP_MONITOR"
        const val ACTION_MANUAL_IN = "com.autoding.punch.action.MANUAL_IN"
        const val ACTION_MANUAL_OUT = "com.autoding.punch.action.MANUAL_OUT"

        /** 同一类型打卡的冷却时间：防止GPS抖动导致的反复打卡 */
        private const val PUNCH_COOLDOWN_MS = 60 * 60 * 1000L // 60 分钟
        /** 决策所需的最低定位精度（米），太差的定位不参与判断 */
        private const val MIN_ACCURACY = 150f
        /** 定位更新间隔 */
        private const val LOC_MIN_TIME = 30_000L
        private const val LOC_MIN_DISTANCE = 20f

        fun isRunning(context: Context): Boolean = ConfigStore.isMonitorEnabled(context)

        fun start(context: Context) {
            val intent = Intent(context, LocationMonitorService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LocationMonitorService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private lateinit var locationManager: LocationManager
    private var lastTriggerInTime = 0L
    private var lastTriggerOutTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lastTriggerInTime = ConfigStore.prefs(this).getLong(ConfigStore.KEY_LAST_PUNCH_IN_TS, 0)
        lastTriggerOutTime = ConfigStore.prefs(this).getLong(ConfigStore.KEY_LAST_PUNCH_OUT_TS, 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务被系统杀死后重启（START_STICKY）时 intent 为 null，按启动处理恢复监测
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                if (!ConfigStore.hasOffice(this)) {
                    LogStore.addLog(this, LogStore.PunchType.IN, LogStore.PunchResult.FAILED, "未设置公司位置，无法启动监测")
                    stopSelf()
                    return START_NOT_STICKY
                }
                ConfigStore.setMonitorEnabled(this, true)
                try {
                    startForeground(NOTIFY_ID, buildNotification("监测运行中", windowDesc()))
                } catch (e: Exception) {
                    // 个别机型在未授予通知权限时可能抛异常
                    LogStore.addLog(this, LogStore.PunchType.IN, LogStore.PunchResult.FAILED, "前台服务启动异常：${e.message}")
                }
                applyTimeWindow()
            }
            ACTION_STOP -> {
                stopLocationUpdates()
                cancelAlarm()
                ConfigStore.setMonitorEnabled(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_MANUAL_IN -> triggerPunch(LogStore.PunchType.IN, force = true)
            ACTION_MANUAL_OUT -> triggerPunch(LogStore.PunchType.OUT, force = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        cancelAlarm()
        super.onDestroy()
    }

    // ============ 定位 ============

    private fun startLocationUpdates() {
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                LogStore.addLog(this, LogStore.PunchType.IN, LogStore.PunchResult.FAILED, "没有定位权限，监测暂停")
                return
            }
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            ) {
                LogStore.addLog(this, LogStore.PunchType.IN, LogStore.PunchResult.FAILED, "定位服务未开启，请打开定位")
                // 尝试注册，等用户开启后恢复
            }

            // 先取一次缓存定位，快速判断当前状态
            val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastKnown?.let { onLocationChanged(it) }

            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, LOC_MIN_TIME, LOC_MIN_DISTANCE, this
                )
            } catch (_: Exception) {
            }
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, LOC_MIN_TIME, LOC_MIN_DISTANCE, this
                )
            } catch (_: Exception) {
            }
        } catch (e: SecurityException) {
            LogStore.addLog(this, LogStore.PunchType.IN, LogStore.PunchResult.FAILED, "定位权限被拒绝：${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    // ============ 监测时段（省电） ============

    /**
     * 根据监测时段决定定位状态：
     *  - 窗口内：正常高频定位
     *  - 窗口外：停止 GPS（省电），设闹钟到下一个窗口开始自动恢复
     */
    private fun applyTimeWindow() {
        if (!ConfigStore.hasOffice(this)) return
        if (ConfigStore.inMonitorWindow(this)) {
            startLocationUpdates()
            try {
                startForeground(NOTIFY_ID, buildNotification("监测运行中", windowDesc()))
            } catch (_: Exception) {
            }
        } else {
            stopLocationUpdates()
            scheduleNextWindow()
            val nextTime = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(ConfigStore.nextMonitorStartMillis(this)))
            try {
                startForeground(NOTIFY_ID, buildNotification("监测暂停（省电）", "$nextTime 自动恢复监测"))
            } catch (_: Exception) {
            }
        }
    }

    /** 设置闹钟：到下一个监测窗口开始时间唤醒本服务 */
    private fun scheduleNextWindow() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                this, 100,
                Intent(this, LocationMonitorService::class.java).apply { action = ACTION_START },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
            val triggerAt = ConfigStore.nextMonitorStartMillis(this)
            // setWindow 允许约 60 秒误差，无需 SCHEDULE_EXACT_ALARM 权限
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 60_000L, pi)
        } catch (e: Exception) {
            LogStore.addLog(this, LogStore.PunchType.IN, LogStore.PunchResult.FAILED, "定时唤醒设置失败：${e.message}")
        }
    }

    private fun cancelAlarm() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                this, 100,
                Intent(this, LocationMonitorService::class.java).apply { action = ACTION_START },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        } catch (_: Exception) {
        }
    }

    /** 通知栏显示的监测窗口描述 */
    private fun windowDesc(): String {
        if (!ConfigStore.isTimeLimitEnabled(this)) return "已到达公司将提醒您打卡"
        val inS = ConfigStore.formatMinutes(ConfigStore.getWindowInStart(this))
        val inE = ConfigStore.formatMinutes(ConfigStore.getWindowInEnd(this))
        val outS = ConfigStore.formatMinutes(ConfigStore.getWindowOutStart(this))
        val outE = ConfigStore.formatMinutes(ConfigStore.getWindowOutEnd(this))
        return "监测时段 $inS-$inE / $outS-$outE"
    }

    override fun onLocationChanged(location: Location) {
        if (!ConfigStore.hasOffice(this)) return
        if (location.accuracy > MIN_ACCURACY) {
            // 定位精度不足，跳过本次判断
            return
        }

        val officeLat = ConfigStore.getOfficeLat(this)
        val officeLng = ConfigStore.getOfficeLng(this)
        val radius = ConfigStore.getRadius(this).toDouble()
        val dist = distanceMeters(location.latitude, location.longitude, officeLat, officeLng)

        // 滞回判断：进入按 radius，离开按 radius * 1.3
        val inside = dist <= radius
        val outside = dist > radius * 1.3

        val currentState = ConfigStore.getLastZoneState(this)
        when (currentState) {
            ConfigStore.ZONE_UNKNOWN -> {
                // 首次定位：只建立初始状态，不打卡
                ConfigStore.setLastZoneState(this, if (inside) ConfigStore.ZONE_INSIDE else ConfigStore.ZONE_OUTSIDE)
                Log.d(TAG, "初始化状态: ${if (inside) "公司内" else "公司外"}，距离 $dist m")
            }
            ConfigStore.ZONE_OUTSIDE -> {
                if (inside) {
                    ConfigStore.setLastZoneState(this, ConfigStore.ZONE_INSIDE)
                    triggerPunch(LogStore.PunchType.IN)
                }
            }
            ConfigStore.ZONE_INSIDE -> {
                if (outside) {
                    ConfigStore.setLastZoneState(this, ConfigStore.ZONE_OUTSIDE)
                    triggerPunch(LogStore.PunchType.OUT)
                }
            }
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ============ 触发提醒 ============

    private fun triggerPunch(type: LogStore.PunchType, force: Boolean = false) {
        // 冷却时间检查（手动触发不受限，避免反复弹提醒）
        if (!force) {
            val now = System.currentTimeMillis()
            val lastTs = if (type == LogStore.PunchType.IN) lastTriggerInTime else lastTriggerOutTime
            if (now - lastTs < PUNCH_COOLDOWN_MS) {
                Log.d(TAG, "冷却中，跳过${type.label}")
                return
            }
        }

        // 记录触发时间（防止同一状态反复提醒）
        if (type == LogStore.PunchType.IN) {
            lastTriggerInTime = System.currentTimeMillis()
            ConfigStore.prefs(this).edit().putLong(ConfigStore.KEY_LAST_PUNCH_IN_TS, lastTriggerInTime).apply()
        } else {
            lastTriggerOutTime = System.currentTimeMillis()
            ConfigStore.prefs(this).edit().putLong(ConfigStore.KEY_LAST_PUNCH_OUT_TS, lastTriggerOutTime).apply()
        }

        // 本应用只做提醒，不自动打卡：发送高优先级通知，点击可直接打开钉钉
        val title = "记得${type.label}啦"
        val content = if (type == LogStore.PunchType.IN) {
            "您已进入公司范围，记得打开钉钉打上班卡～"
        } else {
            "您已离开公司范围，记得打开钉钉打下班卡～"
        }
        LogStore.addLog(this, type, LogStore.PunchResult.SUCCESS, content)
        notifyReminder(title, content)
    }

    // ============ 工具 ============

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ============ 通知 ============

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "位置监测", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "常驻通知，显示监测状态"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, content: String): Notification {
        createChannel()

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val inIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LocationMonitorService::class.java).apply { action = ACTION_MANUAL_IN },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val outIntent = PendingIntent.getService(
            this, 2,
            Intent(this, LocationMonitorService::class.java).apply { action = ACTION_MANUAL_OUT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 3,
            Intent(this, LocationMonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "提醒上班", inIntent)
            .addAction(0, "提醒下班", outIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    /** 提醒通知（高优先级）：到/离公司时提醒用户手动去钉钉打卡，点击可直接打开钉钉 */
    private fun notifyReminder(title: String, content: String) {
        createReminderChannel()
        val n = NotificationCompat.Builder(this, "autoding_reminder")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openDingTalkPendingIntent())
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(9527, n)
        } catch (_: Exception) {
        }
    }

    private fun createReminderChannel() {
        val channel = NotificationChannel(
            "autoding_reminder", "考勤提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "到/离公司时提醒您去钉钉打卡"
            setShowBadge(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 点击通知时打开钉钉 App（若已安装）；未安装则打开本应用 */
    private fun openDingTalkPendingIntent(): PendingIntent {
        val pi = PendingIntent.getActivity(
            this, 0,
            openDingTalkIntent() ?: Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return pi
    }

    private fun openDingTalkIntent(): Intent? {
        val launch = packageManager.getLaunchIntentForPackage("com.alibaba.android.rimet")
        return launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
