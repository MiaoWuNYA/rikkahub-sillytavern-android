package me.rerere.rikkahub.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.weixin.WeixinBotClient
import me.rerere.rikkahub.data.weixin.WeixinMessageType
import me.rerere.rikkahub.data.weixin.extractInboundText
import me.rerere.rikkahub.data.weixin.isWeixinTokenExpiredError
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * 微信 Bot 后台长轮询服务.
 *
 * 设计: 微信 bot 是某个助手的"微信消息通道". 收到入站文字消息后, 复用助手最近的会话,
 * 调 chatService.sendMessage 触发 AI, 等生成完成, 把回复发回微信.
 *
 * 生命周期: enabled=true 时 startForegroundService 启动; enabled=false 或 token 过期时 stopSelf.
 */
class WeixinBotService : Service(), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val chatService: ChatService by inject()
    private val client: WeixinBotClient by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { runPollLoop() }
        }
        // START_STICKY: 被杀后系统会重启本服务
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     * 长轮询主循环. 每次 getUpdates 最多 hold 35s. 收到入站文字消息转给 AI 处理.
     */
    private suspend fun runPollLoop() {
        var getUpdatesBuf = ""
        Log.i(TAG, "poll loop started")
        while (true) {
            try {
                val settings = settingsStore.settingsFlow.first()
                val botSetting = settings.wechatBotSetting
                if (!botSetting.enabled || botSetting.botToken.isBlank()) {
                    Log.w(TAG, "disabled or no token, stopping service")
                    stopSelf()
                    return
                }

                val result = client.getUpdates(
                    token = botSetting.botToken,
                    baseUrl = botSetting.baseUrl,
                    getUpdatesBuf = getUpdatesBuf,
                )
                // 更新游标 (服务器下发的, 下次请求带上, 防止重复收消息)
                getUpdatesBuf = result.getUpdatesBuf

                for (msg in result.msgs) {
                    // 只处理用户发给 bot 的消息 (message_type=1)
                    val msgType = msg["message_type"]?.jsonPrimitive?.content?.toIntOrNull()
                    if (msgType != WeixinMessageType.INBOUND) continue

                    val fromUserId = msg["from_user_id"]?.jsonPrimitive?.content
                    val contextToken = msg["context_token"]?.jsonPrimitive?.content
                    if (fromUserId.isNullOrBlank() || contextToken.isNullOrBlank()) {
                        Log.w(TAG, "inbound msg missing from_user_id/context_token, skip")
                        continue
                    }

                    val text = extractInboundText(msg.jsonObject)
                    Log.i(TAG, "inbound from=$fromUserId text=${text.take(80)}")

                    // 转给 AI 并等待回复
                    val reply = handleInboundMessage(text, botSetting.assistantId)
                    if (reply.isBlank()) continue
                    // 发回微信
                    client.sendTextMessage(
                        token = botSetting.botToken,
                        baseUrl = botSetting.baseUrl,
                        toUserId = fromUserId,
                        text = reply,
                        contextToken = contextToken,
                    )
                    Log.i(TAG, "replied to=$fromUserId len=${reply.length}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                Log.e(TAG, "poll error: $msg", e)
                // session 过期: 停止服务, 提示用户重新登录
                if (isWeixinTokenExpiredError(msg)) {
                    Log.w(TAG, "token expired/invalid, stopping service — user must re-login")
                    notifyTokenExpired()
                    stopSelf()
                    return
                }
                // 其它错误 (网络/临时) 稍等重试
                delay(3000)
            }
        }
    }

    /**
     * 把一条微信消息转给关联的助手处理, 等待 AI 生成完成, 返回回复文本.
     *
     * 复用助手最近会话. 留空 assistantId 用当前助手.
     * 走 chatService.sendMessage (fire-and-forget) + 监听 generationDoneFlow 等回复.
     */
    private suspend fun handleInboundMessage(text: String, assistantIdStr: String): String {
        val settings = settingsStore.settingsFlow.first()
        val assistant = if (assistantIdStr.isBlank()) {
            settings.getCurrentAssistant()
        } else {
            settings.assistants.find { it.id.toString() == assistantIdStr }
                ?: settings.getCurrentAssistant()
        }

        // 复用助手最近一个会话; 没有就新建一个
        val recent = conversationRepository.getRecentConversations(assistant.id, limit = 1)
        val conversationId = recent.firstOrNull()?.id ?: Uuid.random()

        // 同步会话到 ChatService 的 session 缓存, 防止流式更新覆盖历史
        chatService.addConversationReference(conversationId)
        try {
            // 把数据库中的完整对话同步进内存 session：
            // getOrCreateSession 的初始状态是空对话，若不同步，sendMessage 会基于空历史
            // 落库覆盖该会话已有消息；新建会话时也顺便写入正确的 assistantId。
            // 仅在 session 为空态时同步，避免覆盖用户正在聊的内存态。
            if (chatService.getConversationFlow(conversationId).value.messageNodes.isEmpty()) {
                val existing = conversationRepository.getConversationById(conversationId)
                if (existing != null) {
                    chatService.updateConversationState(conversationId) { existing }
                } else {
                    chatService.updateConversationState(conversationId) {
                        me.rerere.rikkahub.data.model.Conversation.ofId(
                            id = conversationId,
                            assistantId = assistant.id,
                            newConversation = true,
                        )
                    }
                }
            }

            // 触发 AI 生成 (fire-and-forget)
            chatService.sendMessage(
                conversationId = conversationId,
                content = listOf(UIMessagePart.Text(text)),
                answer = true,
            )

            // 等待这次会话的生成完成事件 (最多 120s), 然后取最后一条 assistant 消息的文本
            val success = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                chatService.generationDoneFlow.first { it == conversationId }
            }
            if (success == null) {
                return "（思考超时，请稍后再试）"
            }

            val conversation = chatService.getConversationFlow(conversationId).value
            val lastAssistant = conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            val replyText = lastAssistant?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("\n") { it.text }
                ?.trim()
            return replyText?.takeIf { it.isNotBlank() } ?: "（无回复）"
        } finally {
            chatService.removeConversationReference(conversationId)
        }
    }

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("微信 Bot 运行中")
            .setContentText("正在监听微信消息")
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            // 部分机型/低版本不支持 specialUse, 降级普通 startForeground
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** token 过期时更新通知, 提示用户去设置页重新扫码. */
    private fun notifyTokenExpired() {
        try {
            val notification = NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("微信 Bot 已断开")
                .setContentText("登录已过期, 请到设置页重新扫码登录")
                .setSmallIcon(R.drawable.small_icon)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID + 1, notification)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "WeixinBotService"
        private const val NOTIFICATION_ID = 20010
        private const val REPLY_TIMEOUT_MS = 120_000L

        fun start(context: Context) {
            val intent = Intent(context, WeixinBotService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                }
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WeixinBotService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
