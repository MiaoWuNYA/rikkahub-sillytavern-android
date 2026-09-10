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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.QqBotSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.qq.QqApiException
import me.rerere.rikkahub.data.qq.isQqAccessTokenExpired
import me.rerere.rikkahub.data.qq.QqBotClient
import me.rerere.rikkahub.data.repository.ConversationRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * QQ Bot 后台 WebSocket 服务.
 *
 * 设计: QQ bot 是当前助手的"QQ 私聊通道" (同微信 bot). 收到 C2C_MESSAGE_CREATE 后,
 * 复用助手最近会话, 调 chatService.sendMessage 触发 AI, 等生成完成, 把回复发回 QQ.
 *
 * 流程:
 *  1. getAccessToken(AppID, Secret) → access_token (缓存, 过期前刷新并持久化)
 *  2. getGateway(token) → wss URL
 *  3. newWebSocket → 收 op:10 HELLO → 发 op:2 IDENTIFY (带 token + intents=1<<25)
 *  4. 收 op:0 READY → 存 session_id, 启动心跳
 *  5. 心跳: 每 heartbeat_interval 发 op:1 {d: seq}
 *  6. 收 op:0 C2C_MESSAGE_CREATE → 处理消息
 *  7. 断线 → 指数退避重连
 */
class QqBotService : Service(), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val chatService: ChatService by inject()
    private val client: QqBotClient by inject()
    private val okHttpClient: OkHttpClient by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // WebSocket 会话状态
    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var alive = false

    @Volatile
    private var heartbeatInterval = 30000L

    @Volatile
    private var sessionId = ""

    @Volatile
    private var seq: Int? = null

    @Volatile
    private var reconnectAttempt = 0
    private var heartbeatJob: Job? = null
    private val sendLock = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (!alive) {
            scope.launch { connect() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        alive = false
        heartbeatJob?.cancel()
        webSocket?.close(1000, "service destroyed")
        scope.cancel()
    }

    // ==================== 连接主流程 ====================

    private suspend fun connect() {
        val setting = settingsStore.settingsFlow.first().qqBotSetting
        if (!setting.enabled || setting.appId.isBlank() || setting.appSecret.isBlank()) {
            Log.w(TAG, "disabled or missing appId/secret, stopping")
            stopSelf()
            return
        }
        try {
            // 1. 拿 (或刷新) access_token
            val token = ensureToken(setting)
            // 2. 拿 gateway wss 地址
            val wssUrl = client.getGateway(token)
            Log.i(TAG, "connecting WebSocket: $wssUrl")
            // 3. 建立 WebSocket
            alive = true
            val request = Request.Builder().url(wssUrl).build()
            webSocket = okHttpClient.newWebSocket(request, QqWsListener())
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: ${e.message}", e)
            alive = false
            scheduleReconnect()
        }
    }

    /**
     * 确保 access_token 有效 (未过期直接用缓存, 过期则刷新并回写 setting). 返回有效 token.
     */
    private suspend fun ensureToken(setting: QqBotSetting): String {
        val now = System.currentTimeMillis()
        if (!isQqAccessTokenExpired(setting.accessToken, setting.accessTokenExpireAt, now)) {
            return setting.accessToken
        }
        Log.i(TAG, "refreshing access_token...")
        val tokenResult = client.getAccessToken(setting.appId, setting.appSecret)
        val newSetting = setting.copy(
            accessToken = tokenResult.accessToken,
            accessTokenExpireAt = now + tokenResult.expiresIn * 1000,
        )
        // 回写持久化
        settingsStore.update {
            it.copy(qqBotSetting = newSetting)
        }
        return tokenResult.accessToken
    }

    // ==================== WebSocket 监听器 ====================

    private inner class QqWsListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket opened")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handleWsMessage(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket closing: $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket closed: $code $reason")
            onDisconnect(code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            onDisconnect(-1)
        }
    }

    /**
     * 处理收到的 WebSocket 文本消息, 按 opcode 分发.
     */
    private suspend fun handleWsMessage(text: String) {
        val packet = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "parse ws message failed: ${e.message}, raw=$text")
            return
        }
        val op = packet["op"]?.jsonPrimitive?.intOrNull ?: return
        when (op) {
            OP_HELLO -> {
                // 含 heartbeat_interval, 接下来发 IDENTIFY
                heartbeatInterval = packet["d"]?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull
                    ?: 30000L
                Log.i(TAG, "HELLO: heartbeat_interval=$heartbeatInterval ms")
                val setting = settingsStore.settingsFlow.first().qqBotSetting
                val token = ensureToken(setting)
                sendIdentify(token)
            }

            OP_DISPATCH -> {
                val t = packet["t"]?.jsonPrimitive?.contentOrNull
                val s = packet["s"]?.jsonPrimitive?.intOrNull
                if (s != null) seq = s
                val d = packet["d"]?.jsonObject
                when (t) {
                    "READY" -> {
                        sessionId = d?.get("session_id")?.jsonPrimitive?.contentOrNull ?: ""
                        Log.i(TAG, "READY: session_id=$sessionId")
                        reconnectAttempt = 0
                        startHeartbeat()
                    }

                    "RESUMED" -> {
                        Log.i(TAG, "RESUMED: session resumed")
                        reconnectAttempt = 0
                        startHeartbeat()
                    }

                    "C2C_MESSAGE_CREATE" -> handleC2CMessage(d)
                    else -> Log.d(TAG, "dispatch event=$t (ignored)")
                }
            }

            OP_HEARTBEAT_ACK -> {
                // 心跳回应, 正常
            }

            OP_RECONNECT -> {
                Log.w(TAG, "server requested RECONNECT")
                webSocket?.close(4009, "server reconnect")
            }

            OP_INVALID_SESSION -> {
                Log.w(TAG, "INVALID_SESSION, re-identify")
                sessionId = ""
                seq = null
                webSocket?.close(4000, "invalid session")
            }

            else -> Log.d(TAG, "unknown op=$op, raw=$text")
        }
    }

    /**
     * 处理私聊消息事件. 提取文本/发送者/消息id → 转 AI → 发回复.
     */
    private suspend fun handleC2CMessage(d: JsonObject?) {
        if (d == null) return
        val content = d["content"]?.jsonPrimitive?.contentOrNull?.trim() ?: return
        val authorId = d["author"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: d["author"]?.jsonObject?.get("member_openid")?.jsonPrimitive?.contentOrNull
            ?: d["author"]?.jsonObject?.get("user_openid")?.jsonPrimitive?.contentOrNull
        val msgId = d["id"]?.jsonPrimitive?.contentOrNull
        if (authorId.isNullOrBlank() || msgId.isNullOrBlank()) {
            Log.w(TAG, "C2C message missing author/id, skip")
            return
        }
        Log.i(TAG, "C2C from=$authorId content=${content.take(80)}")

        // 去掉可能的 @机器人 前缀 (私聊一般没有, 但保险)
        val cleanContent = content.replace(Regex("<@!?\\d+>\\s*"), "").trim().ifBlank { return }

        val reply = handleInboundMessage(cleanContent)
        if (reply.isBlank()) return
        try {
            val setting = settingsStore.settingsFlow.first().qqBotSetting
            val token = ensureToken(setting)
            client.sendPrivateMessage(token, authorId, reply, msgId)
            Log.i(TAG, "replied to=$authorId len=${reply.length}")
        } catch (e: QqApiException) {
            Log.e(TAG, "send reply failed: ${e.code} ${e.body}", e)
        }
    }

    // ==================== WebSocket 发送 (IDENTIFY / 心跳) ====================

    private fun sendIdentify(token: String) {
        val payload = buildJsonObject {
            put("op", OP_IDENTIFY)
            putJsonObject("d") {
                put("token", "QQBot $token")
                put("intents", INTENT_GROUP_AND_C2C) // 1 << 25
                putJsonArray("shard") {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(1))
                }
            }
        }
        sendWs(payload)
    }

    private fun sendHeartbeat() {
        val payload = buildJsonObject {
            put("op", OP_HEARTBEAT)
            // d = 最后的 seq (没有就 null)
            if (seq != null) put("d", JsonPrimitive(seq)) else put("d", JsonPrimitive(null))
        }
        sendWs(payload)
    }

    private fun sendWs(payload: JsonObject) {
        val str = json.encodeToString(JsonObject.serializer(), payload)
        scope.launch {
            sendLock.withLock {
                val ws = webSocket
                if (ws != null) {
                    val ok = ws.send(str)
                    if (!ok) Log.w(TAG, "ws.send returned false (closing/closed)")
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (alive) {
                delay(heartbeatInterval)
                if (alive) sendHeartbeat()
            }
        }
    }

    // ==================== 重连 ====================

    private fun onDisconnect(code: Int) {
        heartbeatJob?.cancel()
        if (!alive) return
        // 4914 (下架) / 4915 (封禁) 不重连
        if (code == 4914 || code == 4915) {
            Log.e(TAG, "bot banned/unpublished (code=$code), stop")
            alive = false
            notifyBanned()
            stopSelf()
            return
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!alive) return
        reconnectAttempt++
        if (reconnectAttempt > MAX_RECONNECT) {
            Log.e(TAG, "max reconnect attempts reached, stop")
            alive = false
            stopSelf()
            return
        }
        // 指数退避, 上限 30s
        val delayMs = (RECONNECT_BASE * (1L shl (reconnectAttempt - 1).coerceAtMost(5))).coerceAtMost(30_000)
        Log.i(TAG, "reconnect in ${delayMs}ms (attempt=$reconnectAttempt)")
        scope.launch {
            delay(delayMs)
            if (alive) connect()
        }
    }

    // ==================== AI 回复 (复用 WeixinBotService 模式) ====================

    /**
     * 把一条 QQ 消息转给当前助手处理, 等待 AI 生成完成, 返回回复文本.
     * 复用助手最近会话. 走 chatService.sendMessage + 等 generationDoneFlow.
     */
    private suspend fun handleInboundMessage(text: String): String {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getCurrentAssistant()

        val recent = conversationRepository.getRecentConversations(assistant.id, limit = 1)
        val conversationId = recent.firstOrNull()?.id ?: Uuid.random()

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

            chatService.sendMessage(
                conversationId = conversationId,
                content = listOf(UIMessagePart.Text(text)),
                answer = true,
            )

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

    // ==================== 前台服务通知 ====================

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("QQ Bot 运行中")
            .setContentText("正在监听 QQ 私聊消息")
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
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyBanned() {
        try {
            val notification = NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("QQ Bot 已断开")
                .setContentText("机器人可能已下架或被封禁, 请到 q.qq.com 检查")
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
        private const val TAG = "QqBotService"
        private const val NOTIFICATION_ID = 20011
        private const val REPLY_TIMEOUT_MS = 120_000L
        private const val MAX_RECONNECT = 10
        private const val RECONNECT_BASE = 2000L

        // OpCode (来自 QQ 开放平台网关协议)
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11

        // Intent: 私聊+群消息事件
        private const val INTENT_GROUP_AND_C2C = 1 shl 25

        fun start(context: Context) {
            val intent = Intent(context, QqBotService::class.java)
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
                context.stopService(Intent(context, QqBotService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
