package me.rerere.rikkahub.service.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R

/** 熄屏后悬浮条不可见，通知是唯一急停入口 */
object PhoneControlNotifier {
    private const val CHANNEL_ID = "phone_control"
    private const val NOTIFICATION_ID = 0x5048
    const val ACTION_PAUSE = "me.rerere.rikkahub.phone.PAUSE"
    const val ACTION_RESUME = "me.rerere.rikkahub.phone.RESUME"
    const val ACTION_STOP = "me.rerere.rikkahub.phone.STOP"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.phone_control_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    fun showPaused(context: Context, step: Int, limit: Int) {
        post(
            context,
            title = context.getString(R.string.phone_control_notification_paused),
            content = context.getString(R.string.phone_control_notification_paused_desc, step, limit),
            actions = listOf(
                ACTION_RESUME to context.getString(R.string.phone_control_resume),
                ACTION_STOP to context.getString(R.string.phone_control_stop),
            ),
            ongoing = true,
        )
    }

    fun showRunning(context: Context, appLabel: String, step: Int, limit: Int) {
        post(
            context,
            title = context.getString(R.string.phone_control_notification_running, step, limit),
            content = appLabel.ifBlank { context.getString(R.string.phone_control_notification_idle) },
            actions = listOf(
                ACTION_PAUSE to context.getString(R.string.phone_control_pause),
                ACTION_STOP to context.getString(R.string.phone_control_stop),
            ),
            ongoing = true,
        )
    }

    fun notifyDone(context: Context, steps: Int) {
        post(
            context,
            title = context.getString(R.string.phone_control_notification_done),
            content = context.getString(R.string.phone_control_notification_done_desc, steps),
            actions = emptyList(),
            ongoing = false,
        )
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun post(
        context: Context,
        title: String,
        content: String,
        actions: List<Pair<String, String>>,
        ongoing: Boolean,
    ) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
        actions.forEach { (action, label) ->
            builder.addAction(0, label, broadcast(context, action))
        }
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, builder.build() as Notification)
        }
    }

    private fun broadcast(context: Context, action: String): PendingIntent {
        val intent = Intent(context, PhoneControlActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class PhoneControlActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            PhoneControlNotifier.ACTION_PAUSE -> PhoneControlHub.pause(PhonePauseReason.USER)
            PhoneControlNotifier.ACTION_RESUME -> PhoneControlHub.resume()
            PhoneControlNotifier.ACTION_STOP -> PhoneControlHub.abort()
        }
        context?.let { PhoneControlNotifier.cancel(it) }
    }
}
