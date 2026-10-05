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
 * 真正的生成逻辑在 [ProactiveMessageRunner]。
 * 触发链路：闹钟 → ProactiveMessageReceiver → WorkManager → ProactiveMessageRunner。
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
        internal const val KEY_LAST_TRIGGERED_TIME = "last_triggered_time"

        /**
         * 上一次**尝试**的时刻（无论成功失败）。
         *
         * 和 KEY_LAST_TRIGGERED_TIME 的区别很关键：
         *   · KEY_LAST_TRIGGERED_TIME —— 上次**成功发出**的时间，
         *     用于 minInterval 去重，只在真正生成出结果后提交
         *   · KEY_LAST_ATTEMPT_TIME   —— 上次**跑过一轮**的时间，
         *     成功失败都记
         *
         * 为什么必须分开记：失败路径刻意不提交 KEY_LAST_TRIGGERED_TIME
         * （否则一次失败会把之后整个 minInterval 窗口里的真实触发全判掉）。
         * 但如果一个失败场景会持续复现（例如助手没配模型），
         * 「失败 -> 重排 -> 立刻再触发 -> 又失败」就成了一个每秒级死循环，
         * 日志会被刷屏、耗电、还可能反复调 API。
         *
         * 所以失败也要留痕，只是留在另一个键上，用于给重试设一个最小间隔。
         */
        internal const val KEY_LAST_ATTEMPT_TIME = "last_attempt_time"

        /**
         * 失败后的最小重试间隔（分钟）。
         *
         * 失败往往不是瞬时的（没配模型、没选对话、key 失效），
         * 一分钟一次纯粹是浪费。给个几分钟的冷静期。
         */
        internal const val FAILURE_BACKOFF_MINUTES = 5L

        /**
         * 在 [minMinutes, maxMinutes] 之间随机取下次触发的延迟分钟数（纯函数，便于单测）。
         */
        fun computeDelayMinutes(minMinutes: Int, maxMinutes: Int, random: Random = Random): Int {
            val min = minMinutes.coerceAtLeast(ProactiveMessageSetting.MIN_INTERVAL_MINUTES)
            val max = maxMinutes.coerceAtLeast(min)
            return random.nextInt(min, max + 1)
        }

        fun scheduleNext(context: Context, setting: ProactiveMessageSetting) {
            if (!setting.enabled) {
                cancel(context)
                return
            }

            // 归一化后再算延迟：设置里可能存着 0 或负数（老版本写入、
            // 手工改配置），直接拿去算会得到过去的时间点，闹钟会连环触发。
            val safe = setting.normalized()
            val delayMinutes = computeDelayMinutes(safe.minIntervalMinutes, safe.maxIntervalMinutes)
            val triggerTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes.toLong())

            // 保存下次触发时间到 SharedPreferences（供设置页展示）。
            // 这里必须同时覆盖 Alarm 与 WorkManager 两条通道——之前 worker
            // 只在值为 0 时才写入，导致它排的时间永远不会显示出来。
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

            // WorkManager 兜底：电池优化激进机型上 AlarmManager 更可靠的后备。
            // 传归一化后的值，保证两条通道算出的量级一致。
            ProactiveMessageWorker.scheduleNext(context, safe)
        }

        fun getNextTriggerTime(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val triggerTime = prefs.getLong(KEY_NEXT_TRIGGER_TIME, 0L)
            return if (triggerTime > 0) triggerTime else null
        }

        fun cancel(context: Context) {
            // 清除保存的触发时间。
            // last_triggered_time 也要清：它是「上一次实际触发」的痕迹，
            // 关掉再开时若留着，首条消息会被 min interval 误挡掉一次，
            // 表现是「刚开就等很久」。
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_TRIGGER_TIME)
                .remove(KEY_LAST_TRIGGERED_TIME)
                .remove(KEY_LAST_ATTEMPT_TIME)
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

        /**
         * 解析本次主动消息要发到哪个对话。
         *
         * 优先用设置里指定的固定对话；指定的对话被删掉时退回「该助手最近
         * 的一个对话」，而不是直接失败——用户删了对话却忘了改设置的话，
         * 功能不该就此静默停摆。
         *
         * 找不到任何对话时返回 null，调用方应跳过本次触发。
         */
        suspend fun resolveTargetConversation(
            repository: me.rerere.rikkahub.data.repository.ConversationRepository,
            assistantId: kotlin.uuid.Uuid,
            configuredConversationId: String,
        ): me.rerere.rikkahub.data.model.Conversation? {
            // 1) 指定的固定对话
            val pinnedId = configuredConversationId
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() }
            if (pinnedId != null) {
                val pinned = runCatching { repository.getConversationById(pinnedId) }.getOrNull()
                if (pinned != null) {
                    // 必须比对归属。
                    //
                    // 原注释写的是「不比对 assistantId，这样把对话改属主后仍然能用」，
                    // 但那是个陷阱：主动消息会把人设、记忆、上下文一起注入。
                    // 如果指定的对话属于助手 X，而当前触发用的是助手 Y，
                    // 结果是 **X 的对话里被灌进了 Y 的设定**——一条不可逆的
                    // 语义污染，用户只会觉得「这对话怎么突然变了个人」。
                    //
                    // 归属不符时明确拒绝并告诉用户，而不是将错就错。
                    if (pinned.assistantId != assistantId) {
                        Log.w(
                            TAG,
                            "Configured conversation $pinnedId belongs to assistant " +
                                "${pinned.assistantId}, but the proactive setting uses $assistantId",
                        )
                        return null
                    }
                    return pinned
                }
                Log.w(TAG, "Configured conversation $pinnedId not found, falling back to most recent")
            }

            // 2) 该助手最近的对话
            return runCatching {
                repository.getRecentConversations(assistantId, limit = 1)
                    .firstOrNull()
                    ?.let { repository.getConversationById(it.id) }
            }.getOrNull()
        }

        /** 用户回复后重置计时器：重新随机一个下次触发时间。 */
        fun resetTimer(context: Context, setting: ProactiveMessageSetting) {
            scheduleNext(context, setting)
        }

        /**
         * 立即触发一次。
         *
         * 这里**不排下一次**。前台服务跑完（或失败/被取消）时 finally 块会
         * 用 NonCancellable 排程，那才是唯一权威的排程点。之前这里也排一次，
         * 结果是两次 scheduleNext 各随机一个时间，后写的覆盖先写的——
         * 用户看到的下次时间会莫名跳变。
         */
        fun triggerNow(context: Context, setting: ProactiveMessageSetting) {
            // 走 WorkManager 而不是前台服务。
            //
            // 原来这里是 startForegroundService，在 targetSdk 31+ 的
            // 后台场景下系统会抛 ForegroundServiceStartNotAllowedException
            // ——被 catch(Exception) 吞掉后用户看到的就是「毫无反应」。
            // WorkManager 没有这个限制，它本身就有独立的前台服务豁免。
            ProactiveMessageWorker.runOnce(context)
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
 *  - 主动消息闹钟触发 → 派发 WorkManager 执行生成
 *  - 开机完成 → 主动消息开启时重新排程
 */
class ProactiveMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ProactiveMessageService.ACTION_PROACTIVE_MESSAGE -> {
                // 交棒给 WorkManager，不直接起前台服务。
                //
                // 闹钟回调本身是「后台启动前台服务」的豁免场景，但一旦
                // 降级成不精确闹钟（Android 14 起 SCHEDULE_EXACT_ALARM
                // 默认被拒）就失去豁免，startForegroundService 直接抛
                // ForegroundServiceStartNotAllowedException。用 WorkManager
                // 就没有这个不确定性，而且它跑不完会自动重试。
                Log.d(ProactiveMessageService.TAG, "Alarm fired, dispatching proactive work...")
                ProactiveMessageWorker.runOnce(context)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(ProactiveMessageService.TAG, "Boot completed, rescheduling proactive message")
                // goAsync：onReceive 返回后进程可能被立刻回收，裸协程
                // 跑到一半被掐断的话开机重排就静默失败了。持有
                // PendingResult 直到协程结束，系统会等我们。
                val pendingResult = goAsync()
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
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
