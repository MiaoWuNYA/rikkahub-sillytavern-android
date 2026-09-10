package me.rerere.rikkahub.data.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * AI 主动发消息前台服务.
 *
 * 流程: 取助手 + 最近会话 → 构建主动消息上下文（上次聊天距今、当前时间、电量）
 * → 用 provider.streamText 直接生成（带 maxSteps 上限，流式写入会话）
 * → 空回复/[PASS] 回滚 → 保存 + 通知 + 重排下次。
 *
 * 并发保护: 通过 chatService.tryClaimGeneration 礼貌性抢占会话生成权；
 * 若正在生成（正常聊天或另一路主动消息），直接放弃本次触发，不排队等待、不打断。
 */
class ProactiveMessageTriggerService : Service(), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val providerManager: ProviderManager by inject()
    private val templateTransformer: TemplateTransformer by inject()
    private val localTools: LocalTools by inject()
    private val mcpManager: McpManager by inject()
    private val json: Json by inject()
    private val chatService: ChatService by inject()
    private val proactiveMessageService = ProactiveMessageService()

    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "ProactiveMessageTrigger"
        private const val NOTIFICATION_ID = 20001
        private const val PROACTIVE_NOTIFICATION_ID = 20002
        private const val MAX_TOOL_STEPS = 5 // 主动消息最大工具调用步数

        // 保护 last_triggered_time 的 check-then-act 竞态（防止 AlarmManager 与 WorkManager
        // 前后脚触发导致"最小间隔"被砍半）。纯同步 SharedPreferences 读写，无挂起点，用对象锁即可。
        private val prefsLock = Any()
    }

    // 输入转换器（与 ChatService 保持一致的子集）
    private val inputTransformers by lazy {
        listOf(
            TimeReminderTransformer,
            PromptInjectionTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
            OcrTransformer,
        )
    }

    // 输出转换器（与 ChatService 保持一致的子集）
    private val outputTransformers by lazy {
        listOf(
            ThinkTagTransformer,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("正在思考...")
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }

        scope.launch {
            var conversationId: Uuid? = null
            try {
                val settings = settingsStore.settingsFlow.first()
                val proactiveSetting = settings.proactiveMessageSetting
                if (!proactiveSetting.enabled) {
                    stopSelf()
                    return@launch
                }

                val prefs = getSharedPreferences(ProactiveMessageService.PREFS_NAME, MODE_PRIVATE)

                // 去重判断：防止 AlarmManager 和 WorkManager 在同一窗口内重复触发。
                // 把"读取 last_triggered_time -> 判断 -> 写入"整段放在同步块里，避免 check-then-act 竞态。
                val skipDueToInterval = synchronized(prefsLock) {
                    val lastTriggeredTime = prefs.getLong("last_triggered_time", 0L)
                    val minIntervalMs = proactiveSetting.minIntervalMinutes.coerceAtLeast(1) * 60 * 1000L
                    if (System.currentTimeMillis() - lastTriggeredTime < minIntervalMs) {
                        true
                    } else {
                        // 立即写入触发时间，防止并发重复
                        prefs.edit().putLong("last_triggered_time", System.currentTimeMillis()).apply()
                        false
                    }
                }
                if (skipDueToInterval) {
                    Log.d(TAG, "Duplicate trigger within min interval, skipping")
                    ProactiveMessageService.scheduleNext(this@ProactiveMessageTriggerService, proactiveSetting)
                    stopSelf()
                    return@launch
                }

                // 获取助手（设置里指定或当前助手）
                val assistant = settings.assistants.find { it.id.toString() == proactiveSetting.assistantId }
                    ?: settings.getCurrentAssistant()
                val assistantUuid = assistant.id
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                if (model == null) {
                    Log.e(TAG, "No model found for proactive message")
                    ProactiveMessageService.scheduleNext(this@ProactiveMessageTriggerService, proactiveSetting)
                    stopSelf()
                    return@launch
                }

                // 找到最近的对话（没有就不新建，空会话没有主动发消息的意义）
                val recentConversations = conversationRepository.getRecentConversations(assistantUuid, limit = 1)
                val conversation = if (recentConversations.isNotEmpty()) {
                    conversationRepository.getConversationById(recentConversations.first().id)
                } else null
                if (conversation == null) {
                    Log.d(TAG, "No recent conversation for assistant, skipping proactive message")
                    ProactiveMessageService.scheduleNext(this@ProactiveMessageTriggerService, proactiveSetting)
                    stopSelf()
                    return@launch
                }
                conversationId = conversation.id

                // 持有会话引用，防止生成期间 session 被 idle 清除（finally 块会对应 release）。
                // 同时把数据库里的完整对话同步到 session，防止流式更新覆盖历史。
                chatService.addConversationReference(conversationId)
                chatService.updateConversationState(conversationId) { conversation }

                // 抢占生成权：如果当前已有生成在跑（正常聊天或另一路主动消息），
                // 直接放弃本次触发，不排队等待、不重试。理由：等对方生成结束后，
                // 上下文（用户可能已在聊别的话题）大概率已过时，硬等没有意义。
                val myJob = coroutineContext[Job]
                if (myJob == null || !chatService.tryClaimGeneration(conversationId, myJob)) {
                    Log.d(
                        TAG,
                        "Skip proactive trigger: session $conversationId already generating " +
                            "(normal chat or another proactive trigger in progress)"
                    )
                    stopSelf()
                    return@launch
                }

                // 构建上下文与历史
                val idleMinutes = runCatching {
                    val last = proactiveMessageService.getLastMessageTimeMs(assistantUuid)
                    if (last > 0) ((System.currentTimeMillis() - last) / 60_000L).toInt() else Int.MAX_VALUE
                }.getOrDefault(Int.MAX_VALUE)

                val contextStr = proactiveMessageService.buildProactiveContext(
                    this@ProactiveMessageTriggerService,
                    assistantUuid,
                )

                // 获取历史消息（先过滤掉悬空的工具调用消息，避免 tool_use 结构不完整触发 400）
                val historyMessages = filterInvalidToolMessages(
                    conversation.currentMessages.let {
                        if (assistant.contextMessageLimit > 0) {
                            it.takeLast(assistant.contextMessageLimit)
                        } else it
                    }
                )

                // 构建系统提示词（助手人设 + 记忆 + 触发说明与上下文，上下文放最后避免被淹没）
                val systemPrompt = buildSystemPrompt(
                    assistant = assistant,
                    settings = settings,
                    idleMinutes = idleMinutes,
                    context = contextStr,
                )

                // user message 只放简短指令（上下文已在系统提示词中）
                val userMessage = UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(
                        "请根据以上上下文决定是否发消息。没什么好说的就回复 [PASS] 即可，不要强行找话题。"
                    ))
                )

                // 应用输入转换器
                val processedUserMessage = listOf(userMessage).transforms(
                    transformers = inputTransformers + templateTransformer,
                    context = this@ProactiveMessageTriggerService,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                ).first()

                // 组合完整消息列表：System + History + User，合并相邻同角色消息避免 API 400
                val messages = mergeAdjacentSameRoleMessages(
                    buildList {
                        add(UIMessage(
                            role = MessageRole.SYSTEM,
                            parts = listOf(UIMessagePart.Text(systemPrompt))
                        ))
                        addAll(historyMessages)
                        add(processedUserMessage)
                    }
                )

                val providerSetting = model.findProvider(settings.providers)
                if (providerSetting == null) {
                    Log.e(TAG, "No provider found for proactive message")
                    stopSelf()
                    return@launch
                }
                val providerImpl = providerManager.getProviderByType(providerSetting)

                // 构建工具列表（主动消息场景精简版：本地工具 + MCP 工具）
                val tools = buildTools(settings, assistant, model)

                val params = TextGenerationParams(
                    model = model,
                    temperature = assistant.temperature,
                    topP = assistant.topP,
                    maxTokens = assistant.maxTokens,
                    tools = tools,
                    reasoningLevel = assistant.reasoningLevel,
                    customHeaders = assistant.customHeaders + model.customHeaders,
                    customBody = assistant.customBodies + model.customBodies,
                )

                Log.d(
                    TAG,
                    "Calling AI API for proactive message with ${historyMessages.size} history messages, " +
                        "${tools.size} tools (model=${model.modelId})"
                )

                // 执行生成，支持工具调用（流式写入会话）
                val (finalMessages, hasJumpFlag) = generateWithTools(
                    conversationId = conversationId,
                    providerImpl = providerImpl,
                    providerSetting = providerSetting,
                    initialMessages = messages,
                    params = params,
                    tools = tools,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                )

                // 提取 AI 消息
                val aiMessage = finalMessages.lastOrNull()
                    ?: UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())

                // 解析 [JUMP] 标记
                val rawText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }.trim()
                val replyText = rawText.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim()
                val shouldJump = hasJumpFlag &&
                    proactiveSetting.allowForceJump &&
                    idleMinutes >= proactiveSetting.jumpIdleThresholdMinutes

                // 若移除了标记，同步更新 session 里 aiMessage 的文本 parts
                if (rawText != replyText) {
                    val cleanedAiMessage = aiMessage.copy(
                        parts = aiMessage.parts.map { part ->
                            if (part is UIMessagePart.Text) {
                                UIMessagePart.Text(part.text.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim())
                            } else {
                                part
                            }
                        }
                    )
                    updateOrAppendAiMessage(conversationId, cleanedAiMessage)
                }

                Log.d(TAG, "Proactive message generated: '${replyText.take(100)}' (${replyText.length} chars), shouldJump=$shouldJump")

                if (replyText.isBlank() || rawText.contains("[PASS]")) {
                    // AI 选择跳过，移除本次生成的 aiMessage node（基于 id 匹配，不误删历史）
                    Log.d(TAG, "AI chose to skip proactive message")
                    val aiId = aiMessage.id
                    val session = chatService.acquireSessionForBackground(conversationId)
                    session.saveMutex.withLock {
                        chatService.updateConversationState(conversationId) { conv ->
                            conv.copy(
                                messageNodes = conv.messageNodes.filterNot { node ->
                                    node.messages.any { it.id == aiId }
                                }
                            )
                        }
                        chatService.saveConversation(conversationId, chatService.getConversationFlow(conversationId).value)
                    }
                } else {
                    // 有效回复：session 里已有 aiMessage（流式过程已追加），持久化并发通知
                    saveProactiveMessage(conversationId)
                    showProactiveNotification(conversationId, assistant.name.ifBlank { "AI" }, replyText)
                    // 拉起聊天界面（AI 通过 [JUMP] 标记自行判断，且需满足开关与闲置阈值）
                    if (shouldJump) {
                        try {
                            val jumpIntent = Intent(this@ProactiveMessageTriggerService, RouteActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                                putExtra("conversationId", conversationId.toString())
                            }
                            startActivity(jumpIntent)
                            Log.d(TAG, "Force jump to conversation $conversationId")
                        } catch (e: Exception) {
                            Log.e(TAG, "Force jump failed", e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 协程被取消（通常是用户发了新消息，打断本次主动生成），属正常情况。
                // 重新抛出后 finally 块仍会正常执行（scheduleNext 已用 NonCancellable 保护）。
                Log.d(TAG, "Proactive generation cancelled (likely user started a new message), conversationId=$conversationId")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger proactive message", e)
                e.cause?.let { cause ->
                    Log.e(TAG, "Underlying cause: ${cause::class.simpleName}: ${cause.message}", cause)
                }
                // 清理本次触发中流式写入的不完整 AI 消息，防止污染历史导致下一轮请求失败
                conversationId?.let { cid ->
                    try {
                        val session = chatService.acquireSessionForBackground(cid)
                        session.saveMutex.withLock {
                            val conv = chatService.getConversationFlow(cid).value
                            chatService.saveConversation(cid, conv)
                        }
                    } catch (cleanupErr: Exception) {
                        Log.w(TAG, "Failed to cleanup conversation after error", cleanupErr)
                    }
                }
            } finally {
                // 确保无论成功/失败/取消都安排下一次，避免一次 API 错误或用户打断永久中断定时链。
                // 用 NonCancellable 包裹：协程被取消后挂起点会立刻抛 CancellationException，
                // NonCancellable 保证这段收尾逻辑跑完。
                withContext(NonCancellable) {
                    try {
                        val currentSettings = settingsStore.settingsFlow.first()
                        ProactiveMessageService.scheduleNext(
                            this@ProactiveMessageTriggerService,
                            currentSettings.proactiveMessageSetting
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reschedule after completion/error/cancellation", e)
                    }
                }
                conversationId?.let { chatService.removeConversationReference(it) }
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * 构建系统提示词：助手人设 + 记忆 + 主动消息触发说明与上下文。
     */
    private suspend fun buildSystemPrompt(
        assistant: Assistant,
        settings: Settings,
        idleMinutes: Int,
        context: String,
    ): String {
        return buildString {
            if (assistant.systemPrompt.isNotBlank()) {
                append(assistant.systemPrompt)
            }

            // 记忆
            if (assistant.enableMemory) {
                val memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                }
                if (memories.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine("## 记忆")
                    memories.forEach { memory ->
                        appendLine("- ${memory.content}")
                    }
                }
            }

            appendLine()
            appendLine()
            appendLine("## 主动消息触发（定时触发）")
            appendLine("距离用户上次回复已过去 $idleMinutes 分钟。")
            appendLine("这是定时触发的主动消息。绝对不要复述上一轮的对话内容，要发新的话题或新的关心。")
            appendLine("如果你觉得现在没什么好说的，或者没什么有趣的话题，请只回复 [PASS] 即可。")
            appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
            appendLine()
            appendLine(context)
        }
    }

    /**
     * 保存主动消息：流式过程中已实时追加 aiMessage 到 session，这里直接持久化当前 session 状态。
     * synchronized(session) 防止与用户发送消息等并发操作互相覆盖。
     */
    private suspend fun saveProactiveMessage(conversationId: Uuid) {
        val session = chatService.acquireSessionForBackground(conversationId)
        session.saveMutex.withLock {
            chatService.saveConversation(conversationId, chatService.getConversationFlow(conversationId).value)
        }
        Log.d(TAG, "Saved proactive message to conversation $conversationId")
    }

    private fun showProactiveNotification(
        conversationId: Uuid,
        senderName: String,
        message: String
    ) {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = PROACTIVE_NOTIFICATION_ID
        ) {
            title = senderName
            content = message.take(100)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = pendingIntent
            useBigTextStyle = true
        }
    }

    /**
     * 构建工具列表（主动消息场景精简版）：本地工具 + MCP 工具。
     * 不加载搜索/Skill 工具，避免工具过多导致请求体过大触发 API 400。
     */
    private suspend fun buildTools(settings: Settings, assistant: Assistant, model: Model): List<Tool> {
        return buildList {
            // 本地工具（助手已启用的）
            addAll(localTools.getTools(assistant.localTools))

            // MCP 工具：对齐 ChatService，工具名带服务器名前缀
            mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = {
                            mcpManager.callTool(serverId, tool.name, it.jsonObject)
                        },
                    )
                )
            }
        }
    }

    /**
     * 基于 AI 消息 id 在对话里就地更新（保留 MessageNode.id，避免 Compose 重建/状态丢失）
     * 或追加新 node。synchronized(session) 保护 read-modify-write，防止并发覆盖。
     */
    private suspend fun updateOrAppendAiMessage(
        conversationId: Uuid,
        aiMessage: UIMessage
    ) {
        val session = chatService.acquireSessionForBackground(conversationId)
        session.saveMutex.withLock {
            val conv = chatService.getConversationFlow(conversationId).value
            val existingNodeIndex = conv.messageNodes.indexOfFirst { node ->
                node.messages.any { it.id == aiMessage.id }
            }
            val updated = if (existingNodeIndex >= 0) {
                // 已存在该 id 的 node：保留 node id，只更新其 messages
                val oldNode = conv.messageNodes[existingNodeIndex]
                val updatedNode = oldNode.copy(
                    messages = oldNode.messages.map {
                        if (it.id == aiMessage.id) aiMessage else it
                    }
                )
                conv.copy(
                    messageNodes = conv.messageNodes.toMutableList().apply {
                        this[existingNodeIndex] = updatedNode
                    }
                )
            } else {
                // 本次生成的 node 还没有：追加（首次调用时才创建新 node）
                conv.copy(messageNodes = conv.messageNodes + aiMessage.toMessageNode())
            }
            chatService.updateConversationState(conversationId) { updated }
            chatService.saveConversation(conversationId, updated)
        }
    }

    /**
     * 过滤历史消息中"悬空"的工具调用：
     * 若某条消息存在未执行且不可恢复的工具调用，说明工具调用链没有走完，
     * 直接把整条消息剔除，避免把结构不完整的 tool_use 发给 API 触发 400。
     * （判断逻辑与 ChatService.checkInvalidMessages 保持一致）
     */
    private fun filterInvalidToolMessages(messages: List<UIMessage>): List<UIMessage> {
        return messages.filterNot { message ->
            val tools = message.getTools()
            val hasPendingTools = tools.any { !it.isExecuted }
            if (!hasPendingTools) return@filterNot false
            val hasResumableTool = tools.any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
            !hasResumableTool
        }
    }

    /**
     * 合并相邻同角色消息（ASSISTANT-ASSISTANT / USER-USER 都要合并），
     * 避免相邻同角色消息触发 Anthropic 等 API 的 400 错误。
     */
    private fun mergeAdjacentSameRoleMessages(messages: List<UIMessage>): List<UIMessage> {
        if (messages.size < 2) return messages
        return messages.fold(emptyList()) { acc, msg ->
            val prev = acc.lastOrNull()
            if (prev != null && prev.role == msg.role) {
                acc.dropLast(1) + prev.copy(parts = prev.parts + msg.parts)
            } else {
                acc + msg
            }
        }
    }

    /**
     * 生成消息，支持工具调用。流式写入会话（打开的聊天界面可实时看到）。
     * 返回最终消息列表和 AI 原始输出是否含 [JUMP] 标记。
     */
    private suspend fun generateWithTools(
        conversationId: Uuid,
        providerImpl: Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        initialMessages: List<UIMessage>,
        params: TextGenerationParams,
        tools: List<Tool>,
        model: Model,
        assistant: Assistant,
        settings: Settings,
    ): Pair<List<UIMessage>, Boolean> {
        var messages = initialMessages.toMutableList()
        var hasJumpFlag = false // AI 原始输出是否含 [JUMP] 标记（在输出转换器处理前检测）

        for (step in 0 until MAX_TOOL_STEPS) {
            Log.d(TAG, "generateWithTools: step $step/$MAX_TOOL_STEPS")

            // 防御性：每轮调用前合并相邻同角色消息，避免多步工具调用产生相邻 assistant 消息触发 400
            messages = mergeAdjacentSameRoleMessages(messages).toMutableList()

            // 流式调用 AI
            var streamMessages = messages.toList()
            val chunkHandler = StreamChunkHandler(model)
            providerImpl.streamText(
                providerSetting = providerSetting,
                messages = messages,
                params = params
            ).collect { chunk ->
                streamMessages = chunkHandler.handle(streamMessages, chunk)

                // 实时更新 session 状态，让打开的聊天界面能看到消息生成
                val currentAiMessage = streamMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                if (currentAiMessage != null) {
                    // 用 id 匹配就地更新（保留 node id，避免覆盖上一条 assistant）
                    updateOrAppendAiMessage(conversationId, currentAiMessage)
                }
            }

            // 流式结束，更新 messages
            messages = streamMessages.toMutableList()
            val aiMessage = streamMessages.lastOrNull() ?: run {
                Log.w(TAG, "No message in AI response")
                break
            }

            // 在输出转换器处理前，检测 AI 原始输出是否含 [JUMP] 标记
            val rawAiText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            if (rawAiText.contains("[JUMP]")) {
                hasJumpFlag = true
                Log.d(TAG, "[JUMP] flag detected in raw AI output")
            }
            // 应用输出转换器
            val processedMessage = listOf(aiMessage).transforms(
                transformers = outputTransformers,
                context = this@ProactiveMessageTriggerService,
                model = model,
                assistant = assistant,
                settings = settings,
            ).first()
            messages[messages.lastIndex] = processedMessage

            // 检查是否有工具调用
            val toolCalls = processedMessage.getTools().filter { !it.isExecuted }

            if (toolCalls.isEmpty()) {
                // 没有工具调用，生成完成
                // 设置 Reasoning 的 finishedAt，否则 UI 会一直显示"思考中"
                val now = kotlin.time.Clock.System.now()
                val finalMessage = processedMessage.copy(
                    parts = processedMessage.parts.map { part ->
                        if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                            part.copy(finishedAt = now)
                        } else {
                            part
                        }
                    }
                )
                messages[messages.lastIndex] = finalMessage
                // 最终更新 session 状态（用 id 匹配就地更新）
                updateOrAppendAiMessage(conversationId, finalMessage)
                break
            }

            Log.d(TAG, "Tool calls detected: ${toolCalls.size}")

            // 执行工具（后台模式下自动执行；需要审批的工具自动拒绝）
            val executedTools = mutableListOf<UIMessagePart.Tool>()
            for (toolCall in toolCalls) {
                val toolDef = tools.find { it.name == toolCall.toolName }
                if (toolDef == null) {
                    Log.w(TAG, "Tool ${toolCall.toolName} not found")
                    executedTools.add(toolCall.copy(
                        output = listOf(UIMessagePart.Text("""{"error":"Tool not found"}"""))
                    ))
                    continue
                }

                // toolCall.input 可能因为流式截断而是不完整的 JSON, 回退为空对象
                val args = try {
                    json.parseToJsonElement(toolCall.input.ifBlank { "{}" })
                } catch (e: Exception) {
                    Log.w(TAG, "Tool ${toolCall.toolName} input JSON is incomplete, falling back to empty object")
                    JsonObject(emptyMap())
                }

                if (toolDef.needsApproval(args)) {
                    // 后台模式下，需要审批的工具自动拒绝
                    Log.w(TAG, "Tool ${toolCall.toolName} needs approval, auto-denying in proactive mode")
                    executedTools.add(toolCall.copy(
                        output = listOf(UIMessagePart.Text("""{"error":"Tool execution denied: requires user approval in proactive mode"}""")),
                        approvalState = ToolApprovalState.Denied("Proactive mode: requires approval")
                    ))
                } else {
                    try {
                        val result = toolDef.execute(args)
                        executedTools.add(toolCall.copy(output = result))
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool execution failed: ${toolCall.toolName}", e)
                        executedTools.add(toolCall.copy(
                            output = listOf(UIMessagePart.Text("""{"error":"${e.message}"}"""))
                        ))
                    }
                }
            }

            // 更新消息中的工具状态
            val updatedParts = processedMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else {
                    part
                }
            }
            val updatedMessage = processedMessage.copy(parts = updatedParts)
            messages[messages.lastIndex] = updatedMessage
            // 更新 session 状态（带工具结果的消息，用 id 匹配就地更新）
            updateOrAppendAiMessage(conversationId, updatedMessage)
        }

        return messages to hasJumpFlag
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
