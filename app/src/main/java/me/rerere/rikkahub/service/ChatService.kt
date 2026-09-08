package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import org.koin.java.KoinJavaComponent
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createFileTools
import me.rerere.rikkahub.data.ai.tools.createShellTools
import me.rerere.rikkahub.data.ai.tools.createPythonTool
import me.rerere.rikkahub.data.ai.tools.createDatabaseQueryTool
import me.rerere.rikkahub.data.ai.tools.createCalculatorTool
import me.rerere.rikkahub.data.ai.tools.createWebFetchTool
import me.rerere.rikkahub.data.ai.tools.createTaskTools
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.ynufe.createYnufeTool
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.context.RollingContextSummary
import me.rerere.rikkahub.data.ai.context.automaticRollingContextThreshold
import me.rerere.rikkahub.data.ai.context.coveredMessageCount
import me.rerere.rikkahub.data.ai.context.createRollingContextPlan
import me.rerere.rikkahub.data.ai.context.isStillApplicableTo
import me.rerere.rikkahub.data.ai.context.rollingContextWindowStartIndex
import me.rerere.rikkahub.data.ai.context.splitTextForTokenBudget
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import me.rerere.rikkahub.data.ai.context.MAX_SUMMARY_TOKENS
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.AuthorsNoteTransformer
import me.rerere.rikkahub.data.ai.transformers.MemoryRetrievalTransformer
import me.rerere.rikkahub.data.ai.transformers.SkillAutoTriggerTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.GenerationType
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.rikkahub.utils.sendNotification
import me.rerere.rikkahub.utils.cancelNotification
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

private const val TAG = "ChatService"

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        AuthorsNoteTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        SkillAutoTriggerTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val generationHandler: GenerationLoop,
    private val translationHandler: TranslationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val chatToolFactory: ChatToolFactory,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val folderRepository: FolderRepository,
    private val memoryRetrievalTransformer: MemoryRetrievalTransformer,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)



    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    private val database: AppDatabase by lazy {
        KoinJavaComponent.get<AppDatabase>(AppDatabase::class.java)
    }

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                _isForeground.value = true
                stopGenerationForeground()
            }
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    init {
        // 添加生命周期观察者
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) },
                onGenerationFinished = { id, cause ->
                    val session = sessions[id]
                    if (cause != null) session?.messageQueue?.pause()
                    if (session?.state?.value?.currentMessages?.any { message ->
                            message.parts.any { it is UIMessagePart.Tool && it.isPending }
                        } == true) {
                        session.messageQueue.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
                    }
                    appScope.launch { dispatchNextQueuedMessage(id) }
                },
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return getOrCreateSession(conversationId).processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground) return appScope.launch(start = CoroutineStart.LAZY) { block() }

        return appScope.launch(start = CoroutineStart.LAZY) {
            val generationId = Uuid.random()
            val foregroundStarted = ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )
            try {
                block()
            } finally {
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            // 开场白不经过生成管线，插入前先替换宏（{{user}}/{{char}} 等），否则界面显示原文
            val presetMessages = assistant.presetMessages.map { msg ->
                msg.copy(parts = msg.parts.map { part ->
                    if (part is UIMessagePart.Text) {
                        part.copy(
                            text = PlaceholderTransformer.substituteGreetingMacros(
                                context = context,
                                settings = currentSettings,
                                assistant = assistant,
                                text = part.text,
                            )
                        )
                    } else {
                        part
                    }
                })
            }
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        getOrCreateSession(conversationId).messageQueue.state

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessions[conversationId]?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessions[conversationId]?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessions[conversationId]?.messageQueue?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessions.values.toList()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessions[conversationId]?.messageQueue?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        val session = getOrCreateSession(conversationId)
        synchronized(session) {
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(content, answer)
            dispatchNextQueuedMessage(conversationId)
        }
    }

    /** Enqueue immediately; the result belongs to this item even after edits or later turns. */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = getOrCreateSession(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(session.state.value.currentMessages.none { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) { context.getString(R.string.chat_page_voice_tools_before_resume) }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
            dispatchNextQueuedMessage(conversationId)
        }
        return reply
    }

    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessions[conversationId] ?: return null
        synchronized(session) {
            // A pending tool approval is still part of the current turn.
            if (session.getJob() != null || session.state.value.currentMessages.any { message ->
                    message.parts.any { it is UIMessagePart.Tool && it.isPending }
                }) return null
            val next = session.messageQueue.takeNext() ?: return null
            session.submittingMessage = next
            return sendQueuedMessage(session, next)
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = answer,
        ) {
            try {
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)
                session.submittingMessage = null

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                queued.reply?.completeWith(runCatching {
                    val messages = session.state.value.currentMessages
                    check(!session.messageQueue.state.value.paused) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check(messages.none { message -> message.parts.any { it is UIMessagePart.Tool && it.isPending } }) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    val previousIds = currentConversation.currentMessages.map { it.id }.toSet()
                    messages.filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // The ordinary autoplay collector must not read a late voice reply again.
                if (queued.reply == null) _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                queued.reply?.completeExceptionally(e)
                e.printStackTrace()
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) queued.reply?.completeExceptionally(cause)
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        session.setJob(job)
        return job
    }

    /**
     * 插入指定角色消息（不触发生成）
     *
     * 用于 /sys（系统消息）和 /sendas（以助手身份发言）等官方斜杠命令
     */
    fun insertMessage(
        conversationId: Uuid,
        role: MessageRole,
        content: List<UIMessagePart>,
        name: String? = null,
        at: Int? = null,
    ) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                // 官方：/send 应用 USER_INPUT 正则，/sendas 应用 SLASH_COMMAND 正则（本地无 SLASH_COMMAND placement，映射 ASSISTANT scope），/sys 不应用
                val scope = when (role) {
                    MessageRole.USER -> AssistantAffectScope.USER
                    MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
                    else -> null
                }
                val processedContent = if (scope != null) {
                    val settings = settingsStore.settingsFlow.first()
                    val assistant = settings.getAssistantById(currentConversation.assistantId)
                        ?: settings.getCurrentAssistant()
                    // 按插入位置换算官方深度语义（1 = 最新一条）
                    val insertDepth = if (at == null) {
                        1
                    } else {
                        val index = when {
                            at < 0 -> (currentConversation.messageNodes.size + at).coerceIn(0, currentConversation.messageNodes.size)
                            else -> at.coerceIn(0, currentConversation.messageNodes.size)
                        }
                        currentConversation.messageNodes.size + 1 - index
                    }
                    content.map { part ->
                        if (part is UIMessagePart.Text) {
                            part.copy(text = part.text.replaceRegexes(assistant, scope, visual = false, depth = insertDepth))
                        } else {
                            part
                        }
                    }
                } else {
                    content
                }
                val node = UIMessage(
                    role = role,
                    parts = processedContent,
                    name = name,
                ).toMessageNode()
                val messageNodes = if (at == null) {
                    currentConversation.messageNodes + node
                } else {
                    // 官方 at 语义：非负按索引插入，负数从末尾往前（-1 = 最后一条之前），越界安全截断
                    val index = when {
                        at < 0 -> (currentConversation.messageNodes.size + at).coerceIn(0, currentConversation.messageNodes.size)
                        else -> at.coerceIn(0, currentConversation.messageNodes.size)
                    }
                    currentConversation.messageNodes.toMutableList().apply { add(index, node) }
                }
                val newConversation = currentConversation.copy(messageNodes = messageNodes)
                saveConversation(conversationId, newConversation)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    /**
     * 触发一次 AI 回复（/trigger，不添加新消息）
     */
    fun triggerGeneration(conversationId: Uuid, generationType: GenerationType = GenerationType.NORMAL) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)
                handleMessageComplete(conversationId, generationType = generationType)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    /**
     * 生成系统旁白并插入聊天（/sysgen，不触发普通回复）
     *
     * 参考官方 /sysgen：按提示词让模型写一条系统叙述消息，
     * 生成结果以 SYSTEM 角色插入对话历史（AI 下次回复可见）。
     */
    fun generateSystemNarration(conversationId: Uuid, prompt: String, name: String? = null, at: Int? = null, trim: Boolean = false) {
        if (prompt.isBlank()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                if (currentConversation.currentMessages.isEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.slash_error_sysgen_no_messages)),
                        conversationId,
                        title = context.getString(R.string.error_title_send_message),
                    )
                    return@launch
                }

                session.processingStatus.value = context.getString(R.string.slash_sysgen_status)

                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                val provider = model?.findProvider(settings.providers)
                if (model == null || provider == null) {
                    addError(
                        IllegalStateException(context.getString(R.string.slash_error_no_model)),
                        conversationId,
                        title = context.getString(R.string.error_title_send_message),
                    )
                    return@launch
                }

                val providerHandler = providerManager.getProviderByType(provider)
                val history = currentConversation.currentMessages.takeLast(12)
                val narratorSystem = UIMessage.system(
                    "You are the system narrator of a roleplay story. " +
                        "Read the chat history, then write a short narration according to the user's instruction. " +
                        "Output ONLY the narration text itself, without quotes, prefixes, or explanations."
                )
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(narratorSystem) + history + UIMessage.user(prompt),
                    params = backgroundTextGenerationParams(model),
                )
                // 官方 /sysgen trim=true：先按最后一个句子边界裁剪（trimToEndSentence），再走 getRegexedString(message, SLASH_COMMAND)
                val rawNarration = result.message.toText()
                val trimmed = if (trim) trimToEndSentence(rawNarration) else rawNarration.trim()
                val narration = trimmed.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = false, depth = 1)
                if (narration.isBlank()) {
                    addError(
                        IllegalStateException(context.getString(R.string.slash_error_sysgen_empty)),
                        conversationId,
                        title = context.getString(R.string.error_title_send_message),
                    )
                    return@launch
                }

                // 以 SYSTEM 角色插入对话历史（不触发回复），支持官方 name= 与 at=
                val latest = getConversationFlow(conversationId).value
                val node = UIMessage(
                    role = MessageRole.SYSTEM,
                    parts = listOf(UIMessagePart.Text(narration)),
                    name = name,
                ).toMessageNode()
                val messageNodes = if (at == null) {
                    latest.messageNodes + node
                } else {
                    val index = when {
                        at < 0 -> (latest.messageNodes.size + at).coerceIn(0, latest.messageNodes.size)
                        else -> at.coerceIn(0, latest.messageNodes.size)
                    }
                    latest.messageNodes.toMutableList().apply { add(index, node) }
                }
                saveConversation(
                    conversationId,
                    latest.copy(messageNodes = messageNodes),
                )
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            } finally {
                session.processingStatus.value = null
                _generationDoneFlow.emit(conversationId)
            }
        }
        session.setJob(job)
    }

    /**
     * 官方 utils.js trimToEndSentence：从尾部向前找最后一个标点/emoji 作为句子边界，
     * 标点前是空白则连标点一起裁掉，否则保留标点；找不到边界时整体 trimEnd。
     */
    private fun trimToEndSentence(input: String): String {
        if (input.isEmpty()) return ""
        val punctuation = setOf('.', '!', '?', '*', '"', ')', '}', '`', ']', '$', '。', '！', '？', '”', '）', '】', '’', '」', '_')
            .map { it.code }
        val cps = input.codePoints().toArray()
        var last = -1
        for (i in cps.indices.reversed()) {
            val cp = cps[i]
            val emoji = isEmojiCodePoint(cp)
            if (cp in punctuation || emoji) {
                last = if (!emoji && i > 0 && Character.isWhitespace(cps[i - 1])) i - 1 else i
                break
            }
        }
        if (last == -1) return input.trimEnd()
        return String(cps, 0, last + 1).trimEnd()
    }

    // 近似官方 \p{Emoji_Presentation}|\p{Extended_Pictographic}：覆盖常用 emoji 区段
    private fun isEmojiCodePoint(cp: Int): Boolean {
        return cp == 0xFE0F || cp in 0x1F300..0x1F5FF || cp in 0x1F600..0x1F64F ||
            cp in 0x1F680..0x1F6FF || cp in 0x1F700..0x1F77F || cp in 0x1F900..0x1F9FF ||
            cp in 0x1FA70..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false,
                            // 用户输入即将作为最新消息发出，官方深度语义 1 = 最新
                            depth = 1
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) = synchronized(getOrCreateSession(conversationId)) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = message.role == MessageRole.USER || regenerateAssistantMsg,
        ) {
            try {
                previousJob?.join()
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId, generationType = GenerationType.REGENERATE)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex, generationType = GenerationType.REGENERATE)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) = synchronized(getOrCreateSession(conversationId)) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()

        val hasOtherPendingTools = session.state.value.messageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = !hasOtherPendingTools,
        ) {
            try {
                afterPreviousGeneration(previousJob) {
                    val conversation = session.state.value
                    // Ignore double taps and stale approvals for completed or inactive tools.
                    if (conversation.currentMessages.none { message ->
                            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                        }) return@afterPreviousGeneration
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // Update the tool approval state
                    val updatedNodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        when {
                                            part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                                part.copy(approvalState = newApprovalState)
                                            }

                                            else -> part
                                        }
                                    }
                                )
                            }
                        )
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                    saveConversation(conversationId, updatedConversation)

                    // Check if there are still pending tools
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        handleMessageComplete(conversationId)
                    }

                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
    }

    // ---- 继续上一条回复（官方 /continue） ----

    /**
     * 官方 /continue：续写最后一条助手消息（追加到原消息，不新开一条）
     */
    fun continueGeneration(conversationId: Uuid, extraPrompt: String? = null) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val settings = settingsStore.settingsFlow.first()
                val conversation = getConversationFlow(conversationId).value
                val nodes = conversation.messageNodes
                // 官方（script.js Generate continue 分支）：最后一条不是助手消息时退化为正常回复，
                // 在用户最新消息后生成回复，用户发言不会从上下文丢失
                if (nodes.lastOrNull()?.role != MessageRole.ASSISTANT) {
                    handleMessageComplete(conversationId, generationType = GenerationType.NORMAL)
                    _generationDoneFlow.emit(conversationId)
                    return@launch
                }
                val lastAssistantIndex = nodes.indexOfLast { it.role == MessageRole.ASSISTANT }
                if (lastAssistantIndex < 0) {
                    addError(
                        IllegalStateException(context.getString(R.string.slash_error_continue_no_message)),
                        conversationId,
                        title = context.getString(R.string.error_title_generation),
                    )
                    return@launch
                }
                val assistant = settings.getAssistantById(conversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val targetNode = nodes[lastAssistantIndex]
                val lastText = targetNode.messages.getOrNull(targetNode.selectIndex)?.toText().orEmpty()
                // 官方语义：把最后一条助手消息作为“预填”，模型接着它继续写。
                // 可选参数作为预填的追加文本（quiet_prompt），由模型继续接写，而不是当作指令。
                val prompt = buildString {
                    append(lastText)
                    extraPrompt?.trim()?.takeIf { it.isNotBlank() }?.let {
                        appendLine()
                        append(it)
                    }
                }
                val continuation = generateForAssistant(
                    assistant = assistant,
                    settings = settings,
                    prompt = prompt,
                    promptRole = MessageRole.ASSISTANT,
                    history = nodes.take(lastAssistantIndex).map { node ->
                        UIMessage(
                            role = node.role,
                            parts = listOf(UIMessagePart.Text(
                                node.messages.getOrNull(node.selectIndex)?.toText().orEmpty()
                            )),
                        )
                    },
                    conversationId = conversationId,
                    generationType = GenerationType.CONTINUE,
                )
                if (continuation.isBlank()) return@launch

                updateConversationState(conversationId) { conv ->
                    val idx = conv.messageNodes.indexOfLast { it.role == MessageRole.ASSISTANT }
                    if (idx < 0) return@updateConversationState conv
                    val node = conv.messageNodes[idx]
                    conv.copy(
                        messageNodes = conv.messageNodes.mapIndexed { i, n ->
                            if (i != idx) n else node.copy(
                                messages = node.messages.mapIndexed { mi, m ->
                                    if (mi == node.selectIndex) {
                                        m.copy(parts = m.parts + UIMessagePart.Text("\n\n" + continuation))
                                    } else m
                                }
                            )
                        }
                    )
                }
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    // ---- 生成你的发言草稿（官方 /impersonate） ----

    /**
     * 官方 /impersonate：让 AI 站在 {{user}} 的视角生成“你下一条要说的话”，
     * 结果通过 onDraft 交回（本地填入输入框），不保存进聊天、不自动发送。
     * extraInstruction 作为补充系统提示词（官方 quiet_prompt）。
     */
    fun impersonateDraft(conversationId: Uuid, extraInstruction: String?, onDraft: (String) -> Unit) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val settings = settingsStore.settingsFlow.first()
                val conversation = getConversationFlow(conversationId).value
                val assistant = settings.getAssistantById(conversation.assistantId)
                    ?: settings.getCurrentAssistant()
                // 官方 name1：激活人设名优先，其次临时用户名
                val userName = settings.personas.firstOrNull { it.id == settings.activePersonaId }?.name
                    ?: settings.displaySetting.userNickname.ifBlank { "User" }
                val history = conversation.messageNodes.map { node ->
                    UIMessage(
                        role = node.role,
                        parts = listOf(UIMessagePart.Text(
                            node.messages.getOrNull(node.selectIndex)?.toText().orEmpty()
                        )),
                    )
                }
                // 官方 /impersonate：prompt 作为 quiet_prompt（quietToLoud=true）追加到历史最后一行
                // （script.js modifyLastPromptLine，非 instruct 模式 \n${prompt}），无额外系统指令；
                // 提示词末尾加 "name1:" 引导，模型续写即用户发言（non-instruct impersonation line）
                val effectiveHistory = history.toMutableList()
                val instruction = extraInstruction?.trim()?.takeIf { it.isNotBlank() }
                if (instruction != null && effectiveHistory.isNotEmpty()) {
                    val last = effectiveHistory.last()
                    val lastText = last.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    effectiveHistory[effectiveHistory.size - 1] = last.copy(
                        parts = listOf(UIMessagePart.Text("$lastText\n$instruction"))
                    )
                }
                val draftHistory = effectiveHistory + UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("$userName:")),
                )
                val draft = generateForAssistant(
                    assistant = assistant,
                    settings = settings,
                    prompt = "",
                    promptRole = MessageRole.USER,
                    history = draftHistory,
                    conversationId = conversationId,
                    generationType = GenerationType.IMPERSONATE,
                    // 官方 onProgressStreaming：结果流式写入输入框（sendTextarea.value = processedText）
                    onChunk = { text, _ ->
                        onDraft(text)
                    },
                )
                if (draft.isBlank()) return@launch
                onDraft(draft.trim())
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    // ---- 安静生成（官方 /gen） ----

    /**
     * 官方 /gen 的 as= 参数：generateCallback 中 `as = args?.as || 'system'`，
     * 只有 char 特殊（quietToLoud=true），其余一律按系统指令处理。
     */
    enum class QuietPromptAs {
        /** as=system / 缺省：prompt 作为系统指令注入 */
        SYSTEM,

        /** as=char：quietToLoud，prompt 以角色发言注入（名字用角色名） */
        CHAR,
    }

    /**
     * 官方 /gen 参数集（generateCallback）：lock/trim/as/length/name + 本地适配。
     */
    data class GenArgs(
        val prompt: String,
        val asRole: QuietPromptAs = QuietPromptAs.SYSTEM,
        val lock: Boolean = false,
        val length: Int = 0,
        val name: String? = null,
        val trim: Boolean = false,
    )

    /**
     * 官方 /gen：安静生成（quiet generation）。
     * 输入/输出都不写入聊天历史，结果通过 onDraft 交回（本地填入输入框，
     * trim=true 替换、否则追加到命令文本后，对齐官方 setInputText / setInputTextAfterPrompt）。
     */
    fun quietGenerate(
        conversationId: Uuid,
        args: GenArgs,
        onDraft: (String) -> Unit,
    ) {
        if (args.prompt.isBlank()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val settings = settingsStore.settingsFlow.first()
                val conversation = getConversationFlow(conversationId).value
                val assistant = settings.getAssistantById(conversation.assistantId)
                    ?: settings.getCurrentAssistant()

                // 官方 lock=true：生成期间禁用发送按钮防递归；本地生成期间 UI 已有 loading 保护，
                // 且 /gen 不写入聊天，lock 无需特殊处理，保留参数兼容。
                val history = conversation.messageNodes.map { node ->
                    UIMessage(
                        role = node.role,
                        parts = listOf(UIMessagePart.Text(
                            node.messages.getOrNull(node.selectIndex)?.toText().orEmpty()
                        )),
                    )
                }
                // 官方 as=char（quietToLoud）：prompt 以角色发言注入，instruct 模式名字用 name2；
                // name= 优先，其次角色卡名（本地单角色，官方 name= 是多角色选卡）
                val charName = if (args.asRole == QuietPromptAs.CHAR) {
                    args.name?.takeIf { it.isNotBlank() }
                        ?: (assistant.tavernData?.name?.takeIf { it.isNotBlank() } ?: assistant.name)
                } else {
                    null
                }
                val effectivePrompt = if (!charName.isNullOrBlank()) {
                    "$charName: ${args.prompt}"
                } else {
                    args.prompt
                }
                val promptRole = when (args.asRole) {
                    QuietPromptAs.SYSTEM -> MessageRole.SYSTEM
                    QuietPromptAs.CHAR -> MessageRole.ASSISTANT
                }
                val result = generateForAssistant(
                    assistant = assistant,
                    settings = settings,
                    prompt = effectivePrompt,
                    promptRole = promptRole,
                    history = history,
                    conversationId = conversationId,
                    generationType = GenerationType.QUIET,
                    // 官方 length=：TempResponseLength 临时设置模型响应长度（max_tokens），非拼 prompt
                    maxTokensOverride = args.length.takeIf { it > 0 },
                )
                // 官方 trim=true：trimToEndSentence 按最后一个句子边界裁剪
                val finalText = if (args.trim) trimToEndSentence(result.trim()) else result.trim()
                if (finalText.isBlank()) return@launch
                onDraft(finalText)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        generationType: GenerationType = GenerationType.NORMAL,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: throw IllegalStateException("No chat model selected")

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            val tools = try {
                chatToolFactory.createTools(
                    settings = settings,
                    assistant = assistant,
                    model = model,
                    workspaceCwd = conversation.workspaceCwd,
                )
            } catch (error: InvalidMcpServerNamesException) {
                sessions[conversationId]?.messageQueue?.pause()
                addError(
                    error = IllegalStateException(
                        context.getString(
                            R.string.error_mcp_invalid_server_name,
                            error.names.joinToString(", "),
                        )
                    ),
                    conversationId = conversationId,
                )
                return
            }

            // start generating
            val session = getOrCreateSession(conversationId)

            // ── 上下文滚动压缩（移植自 Rikkahub-Revised）：达到阈值时先生成/刷新摘要 ──
            val generationMessages = conversation.currentMessages.let {
                if (messageRange != null) {
                    it.subList(messageRange.start, messageRange.endInclusive + 1)
                } else {
                    it
                }
            }
            val rollingSummary = if (messageRange == null) {
                prepareRollingContextForGeneration(
                    conversationId = conversationId,
                    conversation = conversation,
                    assistant = assistant,
                    model = model,
                    settings = settings,
                    processingStatus = session.processingStatus,
                )?.takeIf { it.coveredMessageCount(generationMessages) > 0 }
            } else {
                null
            }
            val rollingSummaryMessageCount = rollingSummary?.coveredMessageCount(generationMessages) ?: 0
            val rollingThresholdTokens = automaticRollingContextThreshold(
                enabled = assistant.enableRollingContextCompression,
                configuredThresholdTokens = assistant.rollingContextCompressionThresholdTokens,
                modelContextWindowTokens = model.contextWindowTokens,
                maxOutputTokens = assistant.maxTokens,
            )
            // 摘要无法刷新时的兜底：直接把请求窗口裁剪到最近的对话窗口
            val fallbackWindowStartIndex = rollingThresholdTokens?.takeIf { threshold ->
                messageRange == null &&
                    createRollingContextPlan(
                        messages = generationMessages,
                        storedSummary = conversation.rollingContextSummary,
                        thresholdTokens = threshold,
                    ) != null
            }?.let { threshold ->
                rollingContextWindowStartIndex(generationMessages, threshold)
            } ?: 0
            val requestMessageStartIndex = maxOf(rollingSummaryMessageCount, fallbackWindowStartIndex)

            // 如果不在前台，提前启动前台 Service：异步启动 + 失败兜底，绝不让 Service 启动阻塞/中断生成
            if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                appScope.launch {
                    runCatching { startGenerationForeground(senderName, conversationId.toString()) }
                }
            }

            // 监听前后台切换：切后台 600ms 后才启动 FG Service 保活（防短暂切走造成反复启停），
            // 启动失败不影响生成（Android 12+ 后台启动限制等）
            val fgJob: Job? = if (settings.displaySetting.enableNotificationOnMessageGeneration) {
                appScope.launch {
                    isForeground.drop(1).debounce(600).collect { foreground ->
                        if (!foreground) {
                            runCatching { startGenerationForeground(senderName, conversationId.toString()) }
                        } else {
                            stopGenerationForeground()
                        }
                    }
                }
            } else null

            generationHandler.generateText(
                settings = settings,
                model = model,
                generationType = generationType,
                processingStatus = session.processingStatus,
                messages = generationMessages,
                rollingContextSummary = rollingSummary?.content,
                requestMessageStartIndex = requestMessageStartIndex,
                assistant = assistant,
                maxSteps = assistant.totalStepsLimit,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                conversationId = conversation.id,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                    add(memoryRetrievalTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (assistant.localTools.contains(LocalToolOption.FileTools)) {
                        addAll(createFileTools())
                    }
                    if (useExternalWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    if (assistant.enableRecentChatsReference) {
                        addAll(createConversationTools(conversationRepo, assistant.id))
                    }
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    addAll(localTools.getTools(assistant.localTools))
                    if (assistant.localTools.contains(LocalToolOption.ShellTools)) {
                        addAll(createShellTools())
                    }
                    if (assistant.localTools.contains(LocalToolOption.PythonEngine)) {
                        add(createPythonTool(context, assistant.toolExecTimeout))
                    }
                    // 云南财经教务系统工具（课表/成绩/考试/公告）
                    if (assistant.enableYnufeTools) {
                        add(createYnufeTool(context))
                    }
                    if (assistant.localTools.contains(LocalToolOption.DatabaseQuery)) {
                        add(createDatabaseQueryTool(database))
                    }
                    if (assistant.localTools.contains(LocalToolOption.Calculator)) {
                        add(createCalculatorTool(context))
                    }
                    add(createWebFetchTool())
                    if (assistant.localTools.contains(LocalToolOption.TaskTools)) {
                        addAll(createTaskTools())
                    }
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                            )
                        )
                    }
                    // 对齐上游：MCP 工具名带服务器名前缀，且校验服务器名（仅字母数字），非法名直接报错返回
                    mcpManager.getAllAvailableTools().also { allTools ->
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
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
                },
            ).onCompletion {
                // 取消前台服务；通知由 ChatNotificationManager 通过 AppEventBus 消费
                fgJob?.cancel()
                stopGenerationForeground()

                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                        // 前台时停止前台 Service（用户切回来了）
                        if (isForeground.value) {
                            stopGenerationForeground()
                        }

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            if (it is CancellationException) throw it
            sessions[conversationId]?.messageQueue?.pause()

            // 取消前台服务
            stopGenerationForeground()

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            )
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText() })
                    ),
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.message.toText().trim())
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText() }),
                    )
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    /**
     * 为指定 Assistant 生成回复（群聊用），支持流式回调
     */
    suspend fun generateForAssistant(
        assistant: Assistant,
        settings: Settings,
        prompt: String,
        history: List<UIMessage>,
        conversationId: Uuid? = null,
        generationType: GenerationType = GenerationType.NORMAL,
        promptRole: MessageRole = MessageRole.USER,
        extraSystemMessages: List<UIMessage> = emptyList(),
        maxTokensOverride: Int? = null,
        onChunk: ((String, List<UIMessagePart>?) -> Unit)? = null,
    ): String {
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: error("No model configured for assistant '${assistant.name}'")

        val messages = history + extraSystemMessages + if (prompt.isBlank()) {
            emptyList()
        } else {
            listOf(UIMessage(
                role = promptRole,
                parts = listOf(UIMessagePart.Text(prompt)),
            ))
        }
        var result = ""

        // MCP 工具：对齐上游校验服务器名（仅字母数字），非法名直接报错返回
        val mcpTools = mcpManager.getAllAvailableTools()
        val invalidMcpNames = mcpTools
            .map { it.second }
            .distinct()
            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidMcpNames.isNotEmpty()) {
            addError(
                error = IllegalStateException(
                    context.getString(
                        R.string.error_mcp_invalid_server_name,
                        invalidMcpNames.joinToString(", ")
                    )
                ),
                conversationId = conversationId,
            )
            return ""
        }

        generationHandler.generateText(
            settings = settings,
            model = model,
            messages = messages,
            assistant = assistant,
            generationType = generationType,
            conversationId = conversationId,
            memories = if (assistant.useGlobalMemory) {
                memoryRepository.getGlobalMemories()
            } else {
                memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
            },
            tools = buildList {
                if (assistant.localTools.contains(LocalToolOption.FileTools)) {
                    addAll(createFileTools())
                }
                if (assistant.enableWebSearch) {
                    addAll(createSearchTools(settings))
                }
                addAll(localTools.getTools(assistant.localTools))
                if (assistant.localTools.contains(LocalToolOption.ShellTools)) {
                    addAll(createShellTools())
                }
                if (assistant.localTools.contains(LocalToolOption.DatabaseQuery)) {
                    add(createDatabaseQueryTool(database))
                }
                if (assistant.localTools.contains(LocalToolOption.Calculator)) {
                    add(createCalculatorTool(context))
                }
                add(createWebFetchTool())
                if (assistant.localTools.contains(LocalToolOption.TaskTools)) {
                    addAll(createTaskTools())
                }
                if (assistant.enabledSkills.isNotEmpty()) {
                    addAll(
                        createSkillTools(
                            enabledSkills = assistant.enabledSkills,
                            allSkills = skillManager.listSkills(),
                            skillManager = skillManager,
                        )
                    )
                }
                mcpTools.forEach { (serverId, serverName, tool) ->
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
            },
            inputTransformers = buildList {
                addAll(inputTransformers)
                add(templateTransformer)
                add(memoryRetrievalTransformer)
            },
            outputTransformers = outputTransformers,
            // 官方 /gen length=：临时覆盖响应长度（TempResponseLength 语义），用完即弃
            maxTokensOverride = maxTokensOverride,
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> {
                    val lastMsg = chunk.messages.lastOrNull()
                    val text = lastMsg?.toText() ?: ""
                    result = text
                    onChunk?.invoke(text, lastMsg?.parts)
                }
            }
        }

        return result
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText() }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    // ---- 上下文滚动压缩（移植自 Rikkahub-Revised） ----

    /**
     * 生成前检查是否需要刷新滚动压缩摘要；需要时同步生成并写回会话（非破坏式，原文保留）。
     * 返回生成请求可用的滚动摘要（可能为 null）。
     */
    private suspend fun prepareRollingContextForGeneration(
        conversationId: Uuid,
        conversation: Conversation,
        assistant: Assistant,
        model: Model,
        settings: Settings,
        processingStatus: MutableStateFlow<String?>,
    ): RollingContextSummary? {
        if (!assistant.enableRollingContextCompression) return null
        val thresholdTokens = automaticRollingContextThreshold(
            enabled = true,
            configuredThresholdTokens = assistant.rollingContextCompressionThresholdTokens,
            modelContextWindowTokens = model.contextWindowTokens,
            maxOutputTokens = assistant.maxTokens,
        ) ?: return null
        val contextMessages = DocumentAsPromptTransformer.transformDocumentContents(conversation.currentMessages)
        if (
            createRollingContextPlan(
                messages = contextMessages,
                storedSummary = conversation.rollingContextSummary,
                thresholdTokens = thresholdTokens,
            ) == null
        ) {
            return conversation.rollingContextSummary
        }

        val previousStatus = processingStatus.value
        return try {
            processingStatus.value = context.getString(R.string.chat_page_rolling_context_compressing)
            refreshRollingContextSummary(
                conversationId = conversationId,
                conversation = conversation,
                settings = settings,
                thresholdTokens = thresholdTokens,
                force = false,
                planningMessages = contextMessages,
            )
            getConversationFlow(conversationId).value.rollingContextSummary
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            addError(
                error = error,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_compress_conversation),
            )
            conversation.rollingContextSummary
        } finally {
            processingStatus.value = previousStatus
        }
    }

    private suspend fun refreshRollingContextSummary(
        conversationId: Uuid,
        conversation: Conversation,
        settings: Settings,
        thresholdTokens: Int,
        force: Boolean,
        targetTokensOverride: Int? = null,
        additionalPrompt: String = "",
        planningMessages: List<UIMessage>? = null,
    ): Conversation? {
        val messagesForPlanning = planningMessages
            ?: DocumentAsPromptTransformer.transformDocumentContents(conversation.currentMessages)
        val plan = createRollingContextPlan(
            messages = messagesForPlanning,
            storedSummary = conversation.rollingContextSummary,
            thresholdTokens = thresholdTokens,
            force = force,
            targetTokensOverride = targetTokensOverride,
        ) ?: return null
        val assistant = settings.getAssistantById(conversation.assistantId)
            ?: settings.getCurrentAssistant()
        val summary = generateRollingSummary(
            settings = settings,
            content = plan.toCompressionContent(),
            targetTokens = plan.targetTokens,
            additionalPrompt = additionalPrompt,
        )
        val latestConversation = getConversationFlow(conversationId).value
        val latestPlanningMessages = DocumentAsPromptTransformer.transformDocumentContents(
            latestConversation.currentMessages,
        )
        if (
            latestConversation.rollingContextSummary != conversation.rollingContextSummary ||
            !plan.isStillApplicableTo(latestPlanningMessages)
        ) {
            throw IllegalStateException("Conversation changed while compression was running")
        }
        // 提示词缓存：摘要采用追加式——旧摘要文本字节级冻结（由代码拼接，不依赖模型复述），
        // 新摘要追加在其后。重新压缩时 token 前缀在旧摘要结束前保持一致，不再清零缓存。
        // 仅当旧摘要超出承载上限（或用户手动强制压缩）时才整体重写（一次性缓存失效）。
        val carried = plan.previousSummary?.content.orEmpty()
        val appendCarried = !force &&
            carried.isNotBlank() &&
            estimateTextTokens(carried) + estimateTextTokens(summary) < MAX_SUMMARY_TOKENS
        val finalContent = if (appendCarried) "$carried\n\n$summary" else summary
        val newSummary = RollingContextSummary(
            content = finalContent,
            sourceMessageIds = plan.sourceMessageIds,
            updatedAtMillis = System.currentTimeMillis(),
        )
        val updatedConversation = latestConversation.copy(rollingContextSummary = newSummary)
        updateConversation(conversationId, updatedConversation)
        conversationRepo.updateRollingContextSummary(conversationId, newSummary)
        return updatedConversation
    }

    /**
     * 生成滚动摘要：单次调用压缩模型；输入超预算时按 token 分段先做中间摘要再汇总（最多两层）。
     */
    private suspend fun generateRollingSummary(
        settings: Settings,
        content: String,
        targetTokens: Int,
        additionalPrompt: String,
    ): String {
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        val providerHandler = providerManager.getProviderByType(provider)

        fun buildPrompt(input: String, requestedTokens: Int): String =
            settings.compressPrompt.applyPlaceholders(
                "content" to input,
                "target_tokens" to requestedTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName,
            )

        suspend fun requestSummary(input: String, requestedTokens: Int): String {
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(buildPrompt(input, requestedTokens))),
                params = backgroundTextGenerationParams(model),
            )
            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate rolling summary")
        }

        // 压缩输入预算：以压缩模型上下文窗口为基准，预留输出空间
        val compressionInputBudget = (model.contextWindowTokens ?: 32_000) / 2
        var segments = splitTextForTokenBudget(content, compressionInputBudget)
        var depth = 0
        while (segments.size > 1 && depth < 2) {
            val intermediateTarget = targetTokens.coerceAtLeast(512)
            val combined = segments.mapIndexed { index, segment ->
                "[Segment ${index + 1}/${segments.size}]\n" + requestSummary(segment, intermediateTarget)
            }.joinToString("\n\n")
            segments = splitTextForTokenBudget(combined, compressionInputBudget)
            depth++
        }
        if (segments.size != 1) {
            throw IllegalStateException("Rolling summary hierarchy did not converge")
        }
        return requestSummary(segments.single(), targetTokens)
    }

    private fun me.rerere.rikkahub.data.ai.context.RollingContextPlan.toCompressionContent(): String = buildString {
        previousSummary?.let { summary ->
            // 追加式摘要：旧摘要仅作为参考上下文，模型输出只覆盖新增消息；
            // 旧文本由调用方代码原样拼接，不经模型复述（保证前缀缓存字节级稳定）
            appendLine("[Existing summary for reference only — do NOT repeat it in your output]")
            appendLine(summary.content)
            appendLine()
        }
        append(messagesToSummarize.joinToString("\n\n") { it.summaryAsText() })
    }

    // 通知已迁移至 ChatNotificationManager（通过 AppEventBus 通信）

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    // 通知已迁移至 ChatNotificationManager（通过 AppEventBus 通信）

    // region Foreground Service — 后台生成时保持进程存活

    private fun startGenerationForeground(title: String, conversationId: String) {
        val intent = Intent(context, GenerationForegroundService::class.java).apply {
            action = GenerationForegroundService.ACTION_START
            putExtra(GenerationForegroundService.EXTRA_TITLE, title)
            putExtra(GenerationForegroundService.EXTRA_CONVERSATION_ID, conversationId)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun updateGenerationForeground(text: String) {
        val intent = Intent(context, GenerationForegroundService::class.java).apply {
            action = GenerationForegroundService.ACTION_UPDATE
            putExtra(GenerationForegroundService.EXTRA_TEXT, text.take(200))
        }
        context.startService(intent)
    }

    private fun stopGenerationForeground() {
        val intent = Intent(context, GenerationForegroundService::class.java).apply {
            action = GenerationForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }

    // endregion

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 文件夹内是否存在正在生成回复的会话（对齐上游行为）。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹前，先把内存中归属该文件夹的活跃 session folderId 置空，
     * 避免后续整对象保存写回一个已被删除的 folder_id（对齐上游 deleteFolder 语义）。
     */
    fun clearFolderFromSessions(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val session = sessions[newConversation.id]
        val queuedFiles = (session?.messageQueue?.state?.value?.messages.orEmpty() +
                listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls().map { it.toUri() }
        val newFiles = newConversation.files + queuedFiles
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }

        // 删除消息或切换分支也可能解除工具审批阻塞，保存成功后重新检查队列。
        // 调度器仍会检查当前生成任务、待审批工具、暂停状态及编辑占位。
        dispatchNextQueuedMessage(conversationId)
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                translationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = createForkConversation(currentConversation, copiedNodes)

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        // 同步清理群聊 speakerMap，避免残留已删除节点的发言人映射
        return conversation.copy(
            messageNodes = updatedNodes,
            speakerMap = conversation.speakerMap.filterKeys { id -> updatedNodes.any { it.id == id } },
        )
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val jobs = synchronized(session) {
            session.messageQueue.pause()
            session.cancelJobs()
        }
        if (jobs.isEmpty()) return
        jobs.forEach { it.join() }
        finishInterruptedPendingTools(conversationId)
    }
}
