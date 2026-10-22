package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.CHAT_GENERATION_FOREGROUND_CHANNEL_ID
import me.rerere.rikkahub.utils.l10n

/**
 * 前台 Service — 后台生成时保持进程存活。
 * 通知显示"正在生成..."，生成结束或回到前台后自动停止。
 */
class GenerationForegroundService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.GENERATION_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.GENERATION_STOP"
        const val ACTION_UPDATE = "me.rerere.rikkahub.action.GENERATION_UPDATE"
        private const val TAG = "GenFGS"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        private const val NOTIFICATION_ID = 3001
    }

    private var title: String = l10n("正在生成回复", "Generating reply")
    private var text: String = ""
    private var conversationId: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 尽早调 startForeground()，避免 Android 12+ 超时崩溃
        // title/text 有默认值，onStartCommand 收到 intent 后会更新为实际内容
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground in onCreate failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                title = intent.getStringExtra(EXTRA_TITLE) ?: l10n("正在生成回复", "Generating reply")
                conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: ""
                updateNotification()
            }
            ACTION_UPDATE -> {
                text = intent.getStringExtra(EXTRA_TEXT) ?: text
                updateNotification()
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val pendingIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHAT_GENERATION_FOREGROUND_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text.ifEmpty { l10n("生成中...", "Generating...") })
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
    }
}
