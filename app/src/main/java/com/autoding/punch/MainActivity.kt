package com.autoding.punch

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.autoding.punch.databinding.ActivityMainBinding
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val df = DecimalFormat("0.000000")

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fine || coarse) {
                toast("定位权限已开启")
                // Android 11+ 可以继续请求后台定位
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bg = result[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true
                    if (!bg) requestBackgroundLocation()
                }
            } else {
                toast("定位权限被拒绝，无法自动打卡")
            }
            refreshAll()
        }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLogs()
            refreshStatus()
        }
    }

    private val notifyPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        refreshAll()

        // Android 13+ 需要通知权限才能显示打卡结果通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(logReceiver, IntentFilter(LogStore.ACTION_LOG_CHANGED))
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {
        }
    }

    private fun setupViews() {
        // ---- 监测开关 ----
        binding.btnToggleMonitor.setOnClickListener {
            if (ConfigStore.isMonitorEnabled(this)) {
                LocationMonitorService.stop(this)
                toast("监测已停止")
            } else {
                startMonitor()
            }
            refreshAll()
        }

        // ---- 公司位置 ----
        binding.btnCurrentLocation.setOnClickListener { setOfficeFromLocation() }
        binding.btnSetCoords.setOnClickListener {
            val lat = binding.etLat.text.toString().trim().toDoubleOrNull()
            val lng = binding.etLng.text.toString().trim().toDoubleOrNull()
            if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                toast("请输入合法的经纬度")
                return@setOnClickListener
            }
            ConfigStore.setOffice(this, lat, lng, "手动坐标 $df.format(lat), $df.format(lng)")
            toast("公司位置已设置")
            refreshAll()
        }

        // ---- 半径 ----
        binding.sbRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radius = progress + 10 // 10 ~ 1000 米
                binding.tvRadius.text = "${radius}m"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val radius = (seekBar?.progress ?: 290) + 10
                ConfigStore.setRadius(this@MainActivity, radius)
                toast("围栏半径已设为 ${radius} 米")
            }
        })

        // ---- 权限 ----
        binding.btnLocPerm.setOnClickListener { requestLocationPermissions() }
        binding.btnBgLocPerm.setOnClickListener { requestBackgroundLocation() }
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnBattery.setOnClickListener { requestBatteryExemption() }

        // ---- 监测时段（省电） ----
        binding.swTimeLimit.setOnCheckedChangeListener { _, checked ->
            ConfigStore.setTimeLimitEnabled(this, checked)
            reapplyMonitorIfRunning()
            refreshWindows()
        }
        binding.btnSaveWindows.setOnClickListener {
            val inS = ConfigStore.parseMinutes(binding.etWinInStart.text.toString())
            val inE = ConfigStore.parseMinutes(binding.etWinInEnd.text.toString())
            val outS = ConfigStore.parseMinutes(binding.etWinOutStart.text.toString())
            val outE = ConfigStore.parseMinutes(binding.etWinOutEnd.text.toString())
            if (inS == null || inE == null || outS == null || outE == null) {
                toast("时间格式应为 HH:mm，例如 07:00")
                return@setOnClickListener
            }
            if (!ConfigStore.setTimeWindows(this, inS, inE, outS, outE)) {
                toast("时间不合法，请检查输入")
                return@setOnClickListener
            }
            toast("监测时段已保存")
            reapplyMonitorIfRunning()
            refreshWindows()
        }

        // ---- 手动打卡 ----
        binding.btnManualIn.setOnClickListener {
            checkAccessibilityThen {
                startService(Intent(this, DingTalkAccessibilityService::class.java).apply {
                    action = DingTalkAccessibilityService.ACTION_PUNCH_IN
                })
                toast("已触发上班打卡")
            }
        }
        binding.btnManualOut.setOnClickListener {
            checkAccessibilityThen {
                startService(Intent(this, DingTalkAccessibilityService::class.java).apply {
                    action = DingTalkAccessibilityService.ACTION_PUNCH_OUT
                })
                toast("已触发下班打卡")
            }
        }
        binding.btnDebug.setOnClickListener {
            checkAccessibilityThen {
                startService(Intent(this, DingTalkAccessibilityService::class.java).apply {
                    action = DingTalkAccessibilityService.ACTION_TEST
                })
                toast("调试模式：正在打开钉钉…")
            }
        }

        // ---- 日志 ----
        binding.btnClearLogs.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空日志")
                .setMessage("确定要清空所有打卡日志吗？")
                .setPositiveButton("清空") { _, _ ->
                    LogStore.clearLogs(this)
                    refreshLogs()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ============ 启动监测 ============

    private fun startMonitor() {
        if (!ConfigStore.hasOffice(this)) {
            AlertDialog.Builder(this)
                .setTitle("未设置公司位置")
                .setMessage("请先在「公司位置」区域用当前定位设置公司位置，再开始监测。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        if (!checkLocationPermission()) {
            AlertDialog.Builder(this)
                .setTitle("缺少定位权限")
                .setMessage("需要开启定位权限才能监测位置。是否现在去开启？")
                .setPositiveButton("去开启") { _, _ -> requestLocationPermissions() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !checkBackgroundLocationPermission()) {
            AlertDialog.Builder(this)
                .setTitle("需要后台定位权限")
                .setMessage("Android 10+ 需要在系统设置中允许「始终允许」定位，否则锁屏后无法监测。是否去设置？")
                .setPositiveButton("去设置") { _, _ -> openAppSettings() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        if (!isGpsOn()) {
            AlertDialog.Builder(this)
                .setTitle("定位服务未开启")
                .setMessage("请开启手机定位服务（GPS）。是否去开启？")
                .setPositiveButton("去开启") {
                    _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("无障碍服务未开启")
                .setMessage("需要开启「自动打卡辅助服务」才能自动操作钉钉。是否去开启？")
                .setPositiveButton("去开启") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        LocationMonitorService.start(this)
        toast("监测已启动")
    }

    // ============ 设置公司位置 ============

    private fun setOfficeFromLocation() {
        if (!checkLocationPermission()) {
            requestLocationPermissions()
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            toast("请先开启定位服务")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        val loc = try {
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
        } catch (_: SecurityException) {
            null
        }
        if (loc == null) {
            toast("暂时拿不到定位，请到室外或开阔处重试")
            return
        }
        ConfigStore.setOffice(
            this, loc.latitude, loc.longitude,
            "当前位置 ${df.format(loc.latitude)}, ${df.format(loc.longitude)}"
        )
        ConfigStore.setLastZoneState(this, ConfigStore.ZONE_UNKNOWN)
        toast("公司位置已设置")
        refreshAll()
    }

    // ============ 权限检查与请求 ============

    private fun checkLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun checkBackgroundLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        locationPermLauncher.launch(perms.toTypedArray())
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：可以直接用运行时请求
            locationPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        } else {
            // Android 10：只能去系统设置里选"始终允许"
            AlertDialog.Builder(this)
                .setTitle("后台定位权限")
                .setMessage("Android 10 请在设置页选择「允许始终访问位置信息」。")
                .setPositiveButton("去设置") { _, _ -> openAppSettings() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun requestBatteryExemption() {
        val pm = packageManager
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        if (intent.resolveActivity(pm) != null) {
            startActivity(intent)
        } else {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "$packageName/${DingTalkAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun isGpsOn(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /** 无障碍未开启时弹窗引导；已开启则执行动作 */
    private fun checkAccessibilityThen(action: () -> Unit) {
        if (isAccessibilityEnabled()) {
            action()
        } else {
            AlertDialog.Builder(this)
                .setTitle("需要无障碍服务")
                .setMessage("自动打卡需要「自动打卡辅助服务」来操作钉钉。请在无障碍设置中开启本应用的辅助服务。")
                .setPositiveButton("去开启") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ============ 界面刷新 ============

    private fun refreshAll() {
        refreshStatus()
        refreshOffice()
        refreshRadius()
        refreshWindows()
        refreshLogs()
    }

    /** 回显监测时段设置 */
    private fun refreshWindows() {
        binding.swTimeLimit.isChecked = ConfigStore.isTimeLimitEnabled(this)
        binding.etWinInStart.setText(ConfigStore.formatMinutes(ConfigStore.getWindowInStart(this)))
        binding.etWinInEnd.setText(ConfigStore.formatMinutes(ConfigStore.getWindowInEnd(this)))
        binding.etWinOutStart.setText(ConfigStore.formatMinutes(ConfigStore.getWindowOutStart(this)))
        binding.etWinOutEnd.setText(ConfigStore.formatMinutes(ConfigStore.getWindowOutEnd(this)))
    }

    /** 监测运行中时，重新应用时段窗口（保存时段后生效） */
    private fun reapplyMonitorIfRunning() {
        if (ConfigStore.isMonitorEnabled(this)) {
            try {
                startService(Intent(this, LocationMonitorService::class.java).apply {
                    action = LocationMonitorService.ACTION_START
                })
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshStatus() {
        val enabled = ConfigStore.isMonitorEnabled(this)
        binding.btnToggleMonitor.text = if (enabled) "停止监测" else "开始监测"
        binding.tvStatus.text = if (enabled) "🟢 监测运行中" else "⚪ 监测未开启"

        // 更新权限按钮文案
        binding.btnLocPerm.text = if (checkLocationPermission()) {
            "1. 定位权限 ✅ 已开启"
        } else {
            "1. 开启定位权限"
        }
        binding.btnBgLocPerm.text = if (checkBackgroundLocationPermission()) {
            "2. 后台定位 ✅ 已允许"
        } else {
            "2. 允许后台定位（重要）"
        }
        binding.btnAccessibility.text = if (isAccessibilityEnabled()) {
            "3. 无障碍服务 ✅ 已开启"
        } else {
            "3. 开启无障碍服务"
        }
        binding.btnBattery.text = if (isIgnoringBatteryOptimizations()) {
            "4. 电池优化豁免 ✅ 已设置"
        } else {
            "4. 电池优化豁免（防杀后台）"
        }
    }

    private fun refreshOffice() {
        if (ConfigStore.hasOffice(this)) {
            val addr = ConfigStore.getOfficeAddr(this)
            val lat = ConfigStore.getOfficeLat(this)
            val lng = ConfigStore.getOfficeLng(this)
            binding.tvOffice.text = if (addr.isNotEmpty()) {
                "$addr\n（${df.format(lat)}, ${df.format(lng)}）"
            } else {
                "（${df.format(lat)}, ${df.format(lng)}）"
            }
            binding.etLat.setText(df.format(lat))
            binding.etLng.setText(df.format(lng))
        } else {
            binding.tvOffice.text = "未设置（点击下方按钮用当前定位设置）"
            binding.etLat.setText("")
            binding.etLng.setText("")
        }
    }

    private fun refreshRadius() {
        val radius = ConfigStore.getRadius(this)
        binding.sbRadius.progress = (radius - 10).coerceIn(0, 990)
        binding.tvRadius.text = "${radius}m"
    }

    private fun refreshLogs() {
        binding.logContainer.removeAllViews()
        val logs = LogStore.getLogs(this)
        if (logs.isEmpty()) {
            val tv = android.widget.TextView(this).apply {
                text = "暂无打卡记录"
                setTextColor(0xFF999999.toInt())
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            binding.logContainer.addView(tv)
            return
        }
        logs.take(50).forEach { log ->
            val typeColor = if (log.type == LogStore.PunchType.IN) 0xFF1677FF.toInt() else 0xFF00B578.toInt()
            val resultColor = when (log.result) {
                LogStore.PunchResult.SUCCESS -> 0xFF00B578.toInt()
                LogStore.PunchResult.FAILED -> 0xFFFF4D4F.toInt()
                LogStore.PunchResult.SKIPPED -> 0xFFFF8F1F.toInt()
            }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }
            row.addView(android.widget.TextView(this).apply {
                text = LogStore.formatTime(log.timestamp)
                setTextColor(0xFF999999.toInt())
                textSize = 12f
            })
            row.addView(android.widget.TextView(this).apply {
                text = log.type.label
                setTextColor(typeColor)
                textSize = 13f
                setPadding(24, 0, 0, 0)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            row.addView(android.widget.TextView(this).apply {
                text = log.result.label
                setTextColor(resultColor)
                textSize = 13f
                setPadding(12, 0, 0, 0)
            })
            binding.logContainer.addView(row)
            binding.logContainer.addView(android.widget.TextView(this).apply {
                text = log.message
                setTextColor(0xFF666666.toInt())
                textSize = 12f
                setPadding(0, 0, 0, 4)
            })
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = packageManager
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        if (intent.resolveActivity(pm) == null) return false
        return try {
            val pm2 = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm2.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
