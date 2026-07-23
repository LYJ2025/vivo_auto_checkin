package com.vivo.autocheckin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：拉起前台保活服务。
 * （无障碍服务由系统在用户开启后自动拉起，无需此处启动。）
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            CheckinService.start(context)
        }
    }
}
