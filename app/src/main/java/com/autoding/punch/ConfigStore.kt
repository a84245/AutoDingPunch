package com.autoding.punch

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * 配置存储：公司位置、围栏半径、工作时段、开关状态
 */
object ConfigStore {

    private const val PREFS_NAME = "autoding_config"

    const val KEY_OFFICE_LAT = "office_lat"
    const val KEY_OFFICE_LNG = "office_lng"
    const val KEY_OFFICE_ADDR = "office_addr"
    const val KEY_RADIUS = "radius"              // 围栏半径（米）
    const val KEY_MONITOR_ENABLED = "monitor_enabled"
    const val KEY_LAST_PUNCH_IN_TS = "last_punch_in_ts"
    const val KEY_LAST_PUNCH_OUT_TS = "last_punch_out_ts"
    const val KEY_LAST_STATE = "last_zone_state" // inside / outside
    const val KEY_LAST_IN_POS = "last_in_pos"    // 已触发上班提醒的位置
    const val KEY_LAST_OUT_POS = "last_out_pos"  // 已触发下班提醒的位置
    const val KEY_NOTIFY_ASKED = "notify_permission_asked" // 是否已请求过通知权限（避免每次打开都弹）

    // ---- 监测时段（省电） ----
    const val KEY_TIME_LIMIT_ENABLED = "time_limit_enabled"   // 是否启用时段限制
    const val KEY_WINDOW_IN_START = "window_in_start"         // 上班监测开始（分钟 0-1439）
    const val KEY_WINDOW_IN_END = "window_in_end"             // 上班监测结束
    const val KEY_WINDOW_OUT_START = "window_out_start"       // 下班监测开始
    const val KEY_WINDOW_OUT_END = "window_out_end"           // 下班监测结束

    /** 默认上班监测窗口：07:00 ~ 09:30 */
    private const val DEFAULT_IN_START = 7 * 60
    private const val DEFAULT_IN_END = 9 * 60 + 30
    /** 默认下班监测窗口：17:30 ~ 20:00 */
    private const val DEFAULT_OUT_START = 17 * 60 + 30
    private const val DEFAULT_OUT_END = 20 * 60

    const val ZONE_INSIDE = "inside"
    const val ZONE_OUTSIDE = "outside"
    const val ZONE_UNKNOWN = "unknown"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- 公司位置 ----
    fun hasOffice(context: Context): Boolean =
        prefs(context).contains(KEY_OFFICE_LAT) && prefs(context).contains(KEY_OFFICE_LNG)

    fun getOfficeLat(context: Context): Double =
        prefs(context).getFloat(KEY_OFFICE_LAT, 0f).toDouble()

    fun getOfficeLng(context: Context): Double =
        prefs(context).getFloat(KEY_OFFICE_LNG, 0f).toDouble()

    fun getOfficeAddr(context: Context): String =
        prefs(context).getString(KEY_OFFICE_ADDR, "") ?: ""

    fun setOffice(context: Context, lat: Double, lng: Double, addr: String) {
        prefs(context).edit()
            .putFloat(KEY_OFFICE_LAT, lat.toFloat())
            .putFloat(KEY_OFFICE_LNG, lng.toFloat())
            .putString(KEY_OFFICE_ADDR, addr)
            .apply()
    }

    // ---- 围栏半径 ----
    fun getRadius(context: Context): Int =
        prefs(context).getInt(KEY_RADIUS, 300)

    fun setRadius(context: Context, meters: Int) {
        prefs(context).edit().putInt(KEY_RADIUS, meters).apply()
    }

    // ---- 服务开关 ----
    fun isMonitorEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MONITOR_ENABLED, false)

    fun setMonitorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONITOR_ENABLED, enabled).apply()
    }

    // ---- 当前区域状态 ----
    fun getLastZoneState(context: Context): String =
        prefs(context).getString(KEY_LAST_STATE, ZONE_UNKNOWN) ?: ZONE_UNKNOWN

    fun setLastZoneState(context: Context, state: String) {
        prefs(context).edit().putString(KEY_LAST_STATE, state).apply()
    }

    // ---- 触发去重（防止同一地点反复打卡） ----
    fun getLastInPos(context: Context): String =
        prefs(context).getString(KEY_LAST_IN_POS, "") ?: ""

    fun getLastOutPos(context: Context): String =
        prefs(context).getString(KEY_LAST_OUT_POS, "") ?: ""

    fun setLastInPos(context: Context, pos: String) {
        prefs(context).edit().putString(KEY_LAST_IN_POS, pos).apply()
    }

    fun setLastOutPos(context: Context, pos: String) {
        prefs(context).edit().putString(KEY_LAST_OUT_POS, pos).apply()
    }

    // ---- 通知权限请求记录（Android 13+ 只弹一次，避免每次打开都弹授权框） ----
    fun hasAskedNotifyPermission(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_ASKED, false)

    fun setNotifyPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_ASKED, true).apply()
    }

    // ==================== 监测时段（省电） ====================

    /** 是否启用监测时段限制（关闭 = 全天监测） */
    fun isTimeLimitEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TIME_LIMIT_ENABLED, false)

    fun setTimeLimitEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TIME_LIMIT_ENABLED, enabled).apply()
    }

    fun getWindowInStart(context: Context): Int =
        prefs(context).getInt(KEY_WINDOW_IN_START, DEFAULT_IN_START)

    fun getWindowInEnd(context: Context): Int =
        prefs(context).getInt(KEY_WINDOW_IN_END, DEFAULT_IN_END)

    fun getWindowOutStart(context: Context): Int =
        prefs(context).getInt(KEY_WINDOW_OUT_START, DEFAULT_OUT_START)

    fun getWindowOutEnd(context: Context): Int =
        prefs(context).getInt(KEY_WINDOW_OUT_END, DEFAULT_OUT_END)

    /** 一次设置全部时段；返回是否合法（时间值均在 0-1439 且起早于止或为跨天窗口） */
    fun setTimeWindows(
        context: Context,
        inStart: Int, inEnd: Int,
        outStart: Int, outEnd: Int
    ): Boolean {
        val valid = listOf(inStart, inEnd, outStart, outEnd).all { it in 0..1439 }
        if (!valid) return false
        prefs(context).edit()
            .putInt(KEY_WINDOW_IN_START, inStart)
            .putInt(KEY_WINDOW_IN_END, inEnd)
            .putInt(KEY_WINDOW_OUT_START, outStart)
            .putInt(KEY_WINDOW_OUT_END, outEnd)
            .apply()
        return true
    }

    /** 当前时间是否落在任意监测窗口内（支持跨天窗口，如 22:00~02:00） */
    fun inMonitorWindow(context: Context): Boolean {
        if (!isTimeLimitEnabled(context)) return true
        val now = minutesNow()
        return inWindow(now, getWindowInStart(context), getWindowInEnd(context)) ||
                inWindow(now, getWindowOutStart(context), getWindowOutEnd(context))
    }

    /** 下一个监测窗口开始的时间戳（毫秒）。未启用时段限制时返回当前时间（立即） */
    fun nextMonitorStartMillis(context: Context): Long {
        if (!isTimeLimitEnabled(context)) return System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val starts = listOf(getWindowInStart(context), getWindowOutStart(context))
        var best = Long.MAX_VALUE
        for (minutes in starts) {
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, minutes / 60)
                set(Calendar.MINUTE, minutes % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
            if (c.timeInMillis < best) best = c.timeInMillis
        }
        return best
    }

    private fun inWindow(nowMin: Int, start: Int, end: Int): Boolean =
        if (start <= end) nowMin in start..end else nowMin >= start || nowMin <= end

    private fun minutesNow(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    // ---- 时间格式工具（分钟 <-> "HH:mm"） ----

    fun formatMinutes(minutes: Int): String {
        val safe = minutes.coerceIn(0, 1439)
        return String.format("%02d:%02d", safe / 60, safe % 60)
    }

    /** 解析 "HH:mm"；非法返回 null */
    fun parseMinutes(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}
