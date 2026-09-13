package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * AI 主动发消息：AlarmManager 精确闹钟 + WorkManager 兜底双通道调度.
 *
 * 本类只负责"调度"（scheduleNext/cancel/triggerNow）与上下文构建；
 * 真正的生成逻辑在 [ProactiveMessageTriggerService]（前台服务）。
 * 触发链路：闹钟/WorkManager → ProactiveMessageReceiver → ProactiveMessageTriggerService。
 */
class ProactiveMessageService : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()

    companion object {
        const val TAG = "ProactiveMessageService"
        const val ACTION_PROACTIVE_MESSAGE = "me.rerere.rikkahub.PROACTIVE_MESSAGE"
        private const val REQUEST_CODE = 10001

        internal const val PREFS_NAME = "proactive_message_prefs"
        private const val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"

        /**
         * 在 [minMinutes, maxMinutes] 之间随机取下次触发的延迟分钟数（纯函数，便于单测）。
         */
        fun computeDelayMinutes(minMinutes: Int, maxMinutes: Int, random: Random = Random): Int {
            val min = minMinutes.coerceAtLeast(1)
            val max = maxMinutes.coerceAtLeast(min)
            return random.nextInt(min, max + 1)
        }

        fun scheduleNext(context: Context, setting: ProactiveMessageSetting) {
            if (!setting.enabled) {
                cancel(context)
                return
            }

            val delayMinutes = computeDelayMinutes(setting.minIntervalMinutes, setting.maxIntervalMinutes)
            val triggerTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes.toLong())

            // 保存下次触发时间到 SharedPreferences（供设置页展示）
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_NEXT_TRIGGER_TIME, triggerTime)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Android 12+ 需要 canScheduleExactAlarms() 检查，无权限时降级非精确闹钟
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.w(TAG, "Exact alarm permission not granted, using inexact alarm")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }

            Log.d(TAG, "Scheduled proactive message in $delayMinutes minutes")

            // WorkManager 兜底：电池优化激进机型上 AlarmManager 更可靠的后备
            ProactiveMessageWorker.scheduleNext(context, setting)
        }

        fun getNextTriggerTime(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val triggerTime = prefs.getLong(KEY_NEXT_TRIGGER_TIME, 0L)
            return if (triggerTime > 0) triggerTime else null
        }

        fun cancel(context: Context) {
            // 清除保存的触发时间
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_TRIGGER_TIME)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                Log.d(TAG, "Cancelled proactive message alarm")
            }

            // 同时取消 WorkManager 兜底
            ProactiveMessageWorker.cancel(context)
        }

        /** 用户回复后重置计时器：重新随机一个下次触发时间。 */
        fun resetTimer(context: Context, setting: ProactiveMessageSetting) {
            scheduleNext(context, setting)
        }

        /** 立即触发一次（先安排下一次，再启动前台服务生成）。 */
        fun triggerNow(context: Context, setting: ProactiveMessageSetting) {
            scheduleNext(context, setting)
            val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java)
            try {
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "triggerNow: failed to start trigger service", e)
            }
        }
    }

    /**
     * 构建主动消息上下文（精简版：上次聊天距今 + 当前时间 + 电量）。
     * 不依赖定位/App 使用统计/通知监听等外部服务。
     */
    suspend fun buildProactiveContext(context: Context, assistantId: kotlin.uuid.Uuid): String {
        val sb = StringBuilder()
        sb.appendLine("[主动消息上下文]")

        // 距上次聊天
        try {
            val lastMs = getLastMessageTimeMs(assistantId)
            if (lastMs > 0) {
                val diffMs = System.currentTimeMillis() - lastMs
                val minutesAgo = diffMs / 60_000
                val hoursAgo = diffMs / 3_600_000
                when {
                    hoursAgo > 24 -> sb.appendLine("距离上次聊天: ${hoursAgo / 24}天${hoursAgo % 24}小时")
                    hoursAgo > 0 -> sb.appendLine("距离上次聊天: ${hoursAgo}小时${minutesAgo % 60}分钟")
                    else -> sb.appendLine("距离上次聊天: ${minutesAgo}分钟")
                }
            } else {
                sb.appendLine("距离上次聊天: 很久没有聊天了")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last message time", e)
        }

        // 当前时间：对齐 5 分钟边界。主动消息请求复用对话的消息历史，秒级时间戳会让
        // 每次触发的请求前缀都不同，也和普通聊天的缓存前缀互相打架
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val nowMs = System.currentTimeMillis()
        val rounded = nowMs - (nowMs % (5 * 60_000))
        sb.appendLine("当前时间: ${sdf.format(java.util.Date(rounded))}")

        sb.appendLine()
        sb.appendLine("请根据以上上下文，以自然、关心、有趣的方式主动给用户发一条消息。")
        sb.appendLine()
        sb.appendLine("重要规则：")
        sb.appendLine("- 绝对不要复述上一轮的对话内容，要发新的话题或新的关心")
        sb.appendLine("- 如果上一轮已经说过类似的话，这次换一个完全不同的角度")
        sb.appendLine("- 不要提及你是在定时发消息，要像自然想起对方一样")
        sb.appendLine("- 绝对不要提及任何数据来源、工具使用、传感器数据等技术细节")
        sb.appendLine("- 不要说\"根据xxx\"、\"我注意到xxx数据\"之类暴露信息来源的话")
        sb.appendLine("- 直接以朋友聊天的语气开口，就像你突然想到了什么想跟对方说")
        sb.appendLine("- 不要使用任何XML标签、思考标记或特殊格式，只输出纯文本的消息内容")
        sb.appendLine("- 不要调用任何工具或函数，只输出纯文本回复")
        sb.appendLine("- 不要输出思考过程、推理过程或内部独白，只输出你想对用户说的话")
        return sb.toString()
    }

    /** 最近一条消息的时间戳（毫秒）；没有会话/消息时返回 0。 */
    suspend fun getLastMessageTimeMs(assistantId: kotlin.uuid.Uuid): Long {
        return try {
            val recentConversations = conversationRepository.getRecentConversations(assistantId, limit = 1)
            if (recentConversations.isNotEmpty()) {
                val conv = conversationRepository.getConversationById(recentConversations.first().id)
                val localDateTime = conv?.messageNodes?.lastOrNull()?.messages?.lastOrNull()?.createdAt
                localDateTime?.toInstant(TimeZone.currentSystemDefault())?.toEpochMilliseconds() ?: 0L
            } else 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last message time", e)
            0L
        }
    }
}

/**
 * 闹钟/开机广播接收器：
 *  - 主动消息闹钟触发 → 启动 ProactiveMessageTriggerService
 *  - 开机完成 → 主动消息开启时重新排程
 */
class ProactiveMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ProactiveMessageService.ACTION_PROACTIVE_MESSAGE -> {
                Log.d(ProactiveMessageService.TAG, "Alarm fired, starting ProactiveMessageTriggerService...")
                try {
                    context.startForegroundService(Intent(context, ProactiveMessageTriggerService::class.java))
                } catch (e: Exception) {
                    Log.e(ProactiveMessageService.TAG, "Failed to start trigger service", e)
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(ProactiveMessageService.TAG, "Boot completed, rescheduling proactive message")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settingsStore = org.koin.core.context.GlobalContext.get().get<SettingsStore>()
                        val settings = settingsStore.settingsFlow.first()
                        val proactiveSetting = settings.proactiveMessageSetting
                        if (proactiveSetting.enabled) {
                            ProactiveMessageService.scheduleNext(context, proactiveSetting)
                        }
                    } catch (e: Exception) {
                        Log.e(ProactiveMessageService.TAG, "Failed to reschedule after boot", e)
                    }
                }
            }
        }
    }
}
