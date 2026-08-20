package com.autoding.punch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：如果之前开启了监测，手机重启后自动恢复监测服务
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            if (ConfigStore.isMonitorEnabled(context) && ConfigStore.hasOffice(context)) {
                LocationMonitorService.start(context)
            }
        }
    }
}
