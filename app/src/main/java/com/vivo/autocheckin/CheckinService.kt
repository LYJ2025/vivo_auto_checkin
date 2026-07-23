package com.vivo.autocheckin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台保活服务
 *
 * 目的：弹出一条常驻通知，把进程提升为"前台"，避免 OriginOS / Android 系统
 * 在锁屏或后台一段时间后杀掉无障碍服务所在进程。
 *
 * 该服务本身不执行签到逻辑，签到由 [MyAccessibilityService] 完成。
 * 通过 [MainActivity.startForegroundService] 拉起，App 退出后仍常驻。
 */
class CheckinService : Service() {

    companion object {
        private const val CHANNEL_ID = "vivo_checkin_keepalive"
        private const val NOTI_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, CheckinService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CheckinService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNotification())
        Logger.info("前台保活服务已启动。")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY：被杀后系统会尝试重建
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Logger.warn("前台保活服务已停止。")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.noti_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "维持自动签到服务常驻后台"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.noti_title))
            .setContentText(getString(R.string.noti_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }
}
