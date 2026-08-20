package com.autoding.punch

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 打卡日志记录：保存最近 100 条打卡记录
 */
object LogStore {

    private const val PREFS_NAME = "autoding_logs"
    private const val KEY_LOGS = "punch_logs"
    private const val MAX_LOGS = 100

    /** 日志变化时发送的广播，用于刷新界面 */
    const val ACTION_LOG_CHANGED = "com.autoding.punch.action.LOG_CHANGED"

    enum class PunchType(val label: String) {
        IN("上班打卡"),
        OUT("下班打卡")
    }

    enum class PunchResult(val label: String) {
        SUCCESS("成功"),
        FAILED("失败"),
        SKIPPED("已跳过")
    }

    data class PunchLog(
        val id: Long,
        val type: PunchType,
        val result: PunchResult,
        val message: String,
        val timestamp: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("type", type.name)
            put("result", result.name)
            put("message", message)
            put("timestamp", timestamp)
        }

        companion object {
            fun fromJson(o: JSONObject): PunchLog = PunchLog(
                id = o.optLong("id"),
                type = PunchType.valueOf(o.optString("type", PunchType.IN.name)),
                result = PunchResult.valueOf(o.optString("result", PunchResult.SUCCESS.name)),
                message = o.optString("message", ""),
                timestamp = o.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun addLog(context: Context, type: PunchType, result: PunchResult, message: String) {
        val list = getLogs(context).toMutableList()
        list.add(0, PunchLog(System.currentTimeMillis(), type, result, message, System.currentTimeMillis()))
        val trimmed = if (list.size > MAX_LOGS) list.subList(0, MAX_LOGS) else list
        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_LOGS, arr.toString()).apply()
        // 通知界面刷新
        try {
            context.sendBroadcast(Intent(ACTION_LOG_CHANGED))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun getLogs(context: Context): List<PunchLog> {
        val raw = prefs(context).getString(KEY_LOGS, "") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { PunchLog.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clearLogs(context: Context) {
        prefs(context).edit().remove(KEY_LOGS).apply()
    }

    fun formatTime(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
}
