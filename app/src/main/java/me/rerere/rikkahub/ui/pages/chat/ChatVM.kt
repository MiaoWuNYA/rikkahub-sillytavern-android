package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GenerationType
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.st.runtime.TavernRuntimeManager
import me.rerere.rikkahub.data.st.runtime.TavernVariableStore
import me.rerere.rikkahub.data.st.runtime.applyLorebookDisablePatch
import me.rerere.rikkahub.data.st.runtime.applyLorebookUpsertPatch
import me.rerere.rikkahub.data.st.runtime.applyTavernChatUpdates
import me.rerere.rikkahub.data.st.runtime.buildLorebookFromJsrEntries
import me.rerere.rikkahub.data.st.runtime.extractWorldName
import me.rerere.rikkahub.data.st.runtime.findLorebookIndexByName
import me.rerere.rikkahub.data.st.runtime.lorebookNameMatches
import me.rerere.rikkahub.data.st.runtime.parseRuntimeChatInserts
import me.rerere.rikkahub.data.st.runtime.updateWorldNameInExtensionsRaw
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics?, // Firebase 遥测默认关闭（移植自 Rikkahub-Revised）: 未启用时为 null
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    val voiceSession = VoiceSessionController(viewModelScope, context::getString) {
        chatService.enqueueVoiceMessage(_conversationId, it)
    }

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        voiceSession.stop()
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 流式输出时会话每个 chunk 都在变，助手和模型只取决于下面这几个字段
    private val configuredConversation = conversation.distinctUntilChanged { old, new ->
        old.assistantId == new.assistantId && old.config == new.config &&
            old.modeInjectionIds == new.modeInjectionIds && old.lorebookIds == new.lorebookIds
    }

    // 会话视角下的助手：会话开始后，模型、思考级别、搜索等以会话上固定的配置为准
    val assistant: StateFlow<Assistant> = combine(settings, configuredConversation) { settings, conversation ->
        settings.getAssistantOf(conversation)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.value.getAssistantOf(conversation.value))

    // 当前模型
    val currentChatModel: StateFlow<Model?> = combine(settings, configuredConversation) { settings, conversation ->
        settings.getChatModelOf(conversation)
    }.stateIn(viewModelScope, SharingStarted.Lazily, settings.value.getChatModelOf(conversation.value))

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    val messageQueue = chatService.getMessageQueueFlow(_conversationId)

    fun removeQueuedMessage(id: Uuid) = chatService.removeQueuedMessage(_conversationId, id)

    fun beginEditQueuedMessage(id: Uuid) = chatService.beginEditQueuedMessage(_conversationId, id)

    fun finishEditQueuedMessage(id: Uuid, parts: List<UIMessagePart>?) =
        chatService.finishEditQueuedMessage(_conversationId, id, parts)

    fun resumeMessageQueue() = chatService.resumeMessageQueue(_conversationId)

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(fn: (Settings) -> Settings): Job {
        return viewModelScope.launch {
            var oldSettings: Settings? = null
            var newSettings: Settings? = null
            settingsStore.update { old ->
                fn(old).also { new ->
                    oldSettings = old
                    newSettings = new
                }
            }
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings ?: return@launch, newSettings ?: return@launch)
        }
    }

    /** STscript 宿主用：settings 当前快照（同步读，任意线程安全） */
    fun latestSettingsSnapshot(): Settings = settings.value

    /** STscript 宿主用：函数式写入设置（沿用 [updateSettings] 的既有写通道） */
    fun patchSettings(transform: (Settings) -> Settings) {
        updateSettings(transform)
    }

    /** 同步更新设置并等待完成 — 供切换助手等场景使用 */
    suspend fun updateSettingsAndWait(newSettings: Settings) {
        val oldSettings = settings.value
        checkUserAvatarDelete(oldSettings, newSettings)
        settingsStore.update(newSettings)
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 聊天页对助手的修改：新会话直接改助手，会话开始后只改当前会话
    fun updateAssistant(assistant: Assistant) {
        viewModelScope.launch {
            chatService.updateChatAssistant(_conversationId) { assistant }
        }
    }

    fun updateWorkspaceCwd(workspaceCwd: String?) {
        viewModelScope.launch {
            chatService.updateWorkspaceCwd(_conversationId, workspaceCwd)
        }
    }

    // 设置聊天模型
    fun setChatModel(model: Model) {
        viewModelScope.launch {
            chatService.updateChatAssistant(_conversationId) { it.copy(chatModelId = model.id) }
        }
    }

    // 切换搜索方式
    fun updateSearch(enableWebSearch: Boolean, builtInSearch: Boolean) {
        viewModelScope.launch {
            chatService.updateChatSearch(_conversationId, enableWebSearch, builtInSearch)
        }
    }

    // 更新检查。暂停期内（设置里选的 7/14/21 天）不再发起请求。
    val updateState = settingsStore.settingsFlow
        .map { settings ->
            !settings.init &&
                settings.displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()
        }
        .distinctUntilChanged()
        .flatMapLatest { enabled ->
            if (enabled) updateChecker.updateState else flowOf(UiState.Loading)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            UiState.Loading,
        )

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        analytics?.logEvent("ai_send_message", null)

        chatService.sendMessage(_conversationId, content, answer)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics?.logEvent("ai_edit_message", null)
        val index = conversation.value.messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
            fireMessageEvent(index, null, TavernRuntimeManager.EVENT_MESSAGE_EDITED)
        }
    }

    /**
     * MVU 变量按楼层写回：状态栏是逐楼快照，变量必须挂在消息上，
     * 卡片被回收重组后才能恢复当轮状态。
     */
    fun updateMessageMvu(messageId: String, dataJson: String) {
        val id = runCatching { Uuid.parse(messageId) }.getOrNull() ?: return
        chatService.updateConversationState(_conversationId) { conv ->
            conv.copy(
                messageNodes = conv.messageNodes.map { node ->
                    if (node.messages.none { it.id == id }) node
                    else node.copy(messages = node.messages.map { m ->
                        if (m.id == id) m.copy(mvuData = dataJson) else m
                    })
                },
            )
        }
    }

    /**
     * 插入指定角色消息（/sys、/sendas，不触发生成）
     */
    fun handleInsertMessage(role: MessageRole, text: String, name: String? = null, at: Int? = null) {
        if (text.isBlank()) return
        chatService.insertMessage(_conversationId, role, listOf(UIMessagePart.Text(text)), name, at)
    }

    /**
     * 触发一次 AI 回复（/trigger，不添加新消息）
     */
    fun handleTriggerGeneration() {
        chatService.triggerGeneration(_conversationId)
    }

    /**
     * 生成系统旁白并插入聊天（/sysgen）
     */
    fun handleGenerateSystemNarration(prompt: String, name: String? = null, at: Int? = null, trim: Boolean = false) {
        if (prompt.isBlank()) return
        chatService.generateSystemNarration(_conversationId, prompt, name, at, trim)
    }

    /**
     * 压缩上下文
     */
    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return viewModelScope.launch {
            chatService.compressConversation(
                _conversationId,
                conversation.value,
                additionalPrompt,
                targetTokens,
                keepRecentMessages
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    // 新会话发出第一条消息前跟随抽屉里选中的文件夹
    suspend fun updateDraftFolder(folderId: Uuid?) {
        chatService.updateDraftConversationFolder(_conversationId, folderId)
    }

    // 切换备选回复（swipe）
    fun selectMessageNode(nodeId: Uuid, selectIndex: Int) {
        viewModelScope.launch {
            runCatching { chatService.selectMessageNode(_conversationId, nodeId, selectIndex) }
        }
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun deleteMessages(messages: List<UIMessage>) {
        if (messages.isEmpty()) return
        val deleteIds = messages.map { it.id }.toSet()
        val indices = conversation.value.messageNodes.mapIndexedNotNull { idx, node ->
            if (node.messages.any { it.id in deleteIds }) idx else null
        }

        viewModelScope.launch {
            chatService.deleteMessages(_conversationId, deleteIds)
            indices.sortedDescending().forEach { fireMessageEvent(it, null, TavernRuntimeManager.EVENT_MESSAGE_DELETED) }
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException(context.getString(R.string.chat_delete_while_generating)),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        analytics?.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics?.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics?.logEvent("ai_tool_answer", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }
    /** 官方 /continue：续写最后一条助手回复 */
    fun continueGeneration(prompt: String) {
        chatService.continueGeneration(_conversationId, prompt.ifBlank { null })
    }

    /** 官方 /impersonate：生成你的发言草稿，填入输入框待发送 */
    fun impersonateDraft(prefill: String = "", onDraft: (String) -> Unit) {
        chatService.impersonateDraft(_conversationId, prefill.ifBlank { null }, onDraft)
    }

    /** 官方 /gen：安静生成，结果填入输入框待确认 */
    fun quietGenerate(args: ChatService.GenArgs, onDraft: (String) -> Unit) {
        chatService.quietGenerate(_conversationId, args, onDraft)
    }

    /**
     * STscript /gen、/genraw：同步等待一次安静生成的结果（不写会话、不触发 UI 生成任务）。
     */
    suspend fun quietGenerateSync(
        prompt: String,
        includeHistory: Boolean = true,
        systemPrompt: String = "",
        asRole: ChatService.QuietPromptAs = ChatService.QuietPromptAs.SYSTEM,
        length: Int = 0,
        name: String? = null,
        trim: Boolean = false,
    ): Result<String> = runCatching {
        val currentSettings = settings.value
        val currentConversation = conversation.value
        val currentAssistant = currentSettings.getAssistantOf(currentConversation)
        val history = if (includeHistory) currentConversation.currentMessages else emptyList()
        val extraSystem = if (systemPrompt.isBlank()) {
            emptyList()
        } else {
            listOf(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(systemPrompt))))
        }
        val text = chatService.generateForAssistant(
            assistant = if (name.isNullOrBlank()) currentAssistant else currentAssistant.copy(name = name),
            settings = currentSettings,
            prompt = prompt,
            history = history,
            conversationId = _conversationId,
            generationType = GenerationType.QUIET,
            promptRole = if (asRole == ChatService.QuietPromptAs.CHAR) MessageRole.ASSISTANT else MessageRole.USER,
            extraSystemMessages = extraSystem,
            maxTokensOverride = if (length > 0) length * 4 else null,
        )
        if (trim) text.trim() else text
    }

    /**
     * STscript /del：按节点索引删除消息，同步返回被删消息文本（/del cut 需要回显）。
     */
    fun deleteChatMessageIndices(indices: Set<Int>): List<String> {
        val nodes = conversation.value.messageNodes
        val targets = indices.sorted().mapNotNull { nodes.getOrNull(it) }
        if (targets.isEmpty()) return emptyList()
        val texts = targets.mapNotNull { node ->
            (node.messages.getOrNull(node.selectIndex) ?: node.messages.firstOrNull())
        }.map { it.toText() }
        viewModelScope.launch {
            chatService.deleteMessages(_conversationId, targets.flatMap { node -> node.messages.map { it.id } })
        }
        return texts
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            chatService.moveConversationToAssistant(conversation.id, targetAssistantId)
            if (conversation.id == _conversationId) {
                settingsStore.selectAssistant(targetAssistantId)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

    // ==================== 酒馆运行时桥：ChatPage 绑定的落点（JS 侧回写） ====================

    private fun fireMessageEvent(index: Int, swipeId: Int?, eventName: String) {
        if (index < 0) return
        val payload = buildJsonObject {
            put("message_id", JsonPrimitive(index))
            if (swipeId != null) put("swipe_id", JsonPrimitive(swipeId))
            put("args", buildJsonArray { add(JsonPrimitive(index)) })
        }.toString()
        TavernRuntimeManager.fireConversationEvent(_conversationId.toString(), eventName, payload)
    }

    /** 手机端切换备选回复 → 补发消息事件，脚本侧 listChatMessages 之外也可即时感知。 */
    fun notifyMessageSwiped(node: MessageNode) {
        val index = conversation.value.messageNodes.indexOfFirst { it.id == node.id }
        fireMessageEvent(index, node.selectIndex, TavernRuntimeManager.EVENT_MESSAGE_SWIPED)
        fireMessageEvent(index, node.selectIndex, TavernRuntimeManager.EVENT_MESSAGE_UPDATED)
    }

    /** JS setChatMessages / saveChat：同步改内存（脚本随后要读），异步落盘。 */
    fun applyRuntimeChatUpdates(jsonText: String): Boolean {
        val current = conversation.value
        val alternateGreetings = settings.value.getAssistantOf(current).tavernData?.alternateGreetings.orEmpty()
        val updated = applyTavernChatUpdates(current, jsonText, alternateGreetings) ?: return false
        updateConversation(updated)
        viewModelScope.launch {
            runCatching { chatService.saveConversation(_conversationId, updated) }
        }
        return true
    }

    /** JS createChatMessages：同步追加楼层（卡片紧接着 /trigger，排队任务会被取消）。 */
    fun insertRuntimeChatMessages(jsonText: String): Boolean {
        val items = parseRuntimeChatInserts(jsonText)
        if (items.isEmpty()) return false
        val current = conversation.value
        val nodes = items.map { item ->
            UIMessage(
                role = item.role,
                parts = listOf(UIMessagePart.Text(item.message)),
                name = item.name,
            ).toMessageNode()
        }
        // 消息级变量按楼层下标对齐，新楼层补空对象
        runCatching {
            val key = _conversationId.toString()
            val existing = TavernVariableStore.getMessageVars(key)
            val list = (existing as? JsonArray)?.toMutableList() ?: mutableListOf()
            items.forEach { list += (it.data ?: JsonObject(emptyMap())) }
            TavernVariableStore.replaceMessageVars(key, JsonArray(list))
        }
        val updated = current.copy(messageNodes = current.messageNodes + nodes)
        updateConversation(updated)
        viewModelScope.launch {
            runCatching { chatService.saveConversation(_conversationId, updated) }
        }
        TavernRuntimeManager.appendLog("info", "[chat] insertChatMessages n=${items.size}")
        return true
    }

    /** JS deleteChatMessages(idsJson)：按 0 基楼层下标删除，同步裁剪消息级变量。 */
    fun deleteRuntimeChatMessages(idsJson: String): Boolean {
        val ids = runCatching {
            (Json.parseToJsonElement(idsJson) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())
        if (ids.isEmpty()) return false
        val nodes = conversation.value.messageNodes
        val valid = ids.filter { it in nodes.indices }.toSet()
        if (valid.isEmpty()) return false
        runCatching {
            val key = _conversationId.toString()
            val existing = TavernVariableStore.getMessageVars(key)
            val list = (existing as? JsonArray)?.toMutableList()
            if (list != null && list.isNotEmpty()) {
                TavernVariableStore.replaceMessageVars(
                    key,
                    JsonArray(list.filterIndexed { idx, _ -> idx !in valid }),
                )
            }
        }
        val deleted = deleteChatMessageIndices(valid).isNotEmpty()
        if (deleted) valid.forEach { fireMessageEvent(it, null, TavernRuntimeManager.EVENT_MESSAGE_DELETED) }
        return deleted
    }

    /** JS upsertLorebookEntries：卡内嵌世界书优先，其次全局世界书库。 */
    fun applyLorebookUpsert(bookName: String, payloadJson: String): Boolean {
        val currentSettings = settings.value
        val requested = bookName.trim()
        if (requested.isEmpty()) return false
        val assistant = currentSettings.getAssistantOf(conversation.value)
        val tavernData = assistant.tavernData
        val embedded = tavernData?.embeddedBook
        if (tavernData != null && embedded != null) {
            val worldName = extractWorldName(tavernData.extensionsRaw)
            val embeddedName = embedded.name.ifBlank { worldName ?: "" }
            val isMatch = requested == "current" ||
                lorebookNameMatches(embeddedName, bookName) ||
                lorebookNameMatches(worldName, bookName)
            if (isMatch) {
                val updatedBook = applyLorebookUpsertPatch(embedded, payloadJson) ?: return false
                patchSettings { s ->
                    s.copy(
                        assistants = s.assistants.map { a ->
                            if (a.id == assistant.id) {
                                a.copy(tavernData = tavernData.copy(embeddedBook = updatedBook))
                            } else {
                                a
                            }
                        },
                    )
                }
                TavernRuntimeManager.appendLog("info", "[lorebook] upsert embedded '$embeddedName'")
                return true
            }
        }
        if (findLorebookIndexByName(currentSettings.lorebooks, requested) < 0) {
            TavernRuntimeManager.appendLog("warn", "[lorebook] upsert miss '$requested'")
            return false
        }
        patchSettings { s ->
            val index = findLorebookIndexByName(s.lorebooks, requested)
            if (index < 0) return@patchSettings s
            val updatedBook = applyLorebookUpsertPatch(s.lorebooks[index], payloadJson)
                ?: return@patchSettings s
            s.copy(lorebooks = s.lorebooks.toMutableList().also { it[index] = updatedBook })
        }
        TavernRuntimeManager.appendLog("info", "[lorebook] upsert global '$requested'")
        return true
    }

    /** JS setLorebookEntries / 启停：同名书存在即视为命中。 */
    fun applyLorebookUpdate(bookName: String, entriesJson: String): Boolean {
        val currentSettings = settings.value
        val requested = bookName.trim()
        if (requested.isEmpty()) return false
        val assistant = currentSettings.getAssistantOf(conversation.value)
        val tavernData = assistant.tavernData
        val embedded = tavernData?.embeddedBook
        if (tavernData != null && embedded != null) {
            val worldName = extractWorldName(tavernData.extensionsRaw)
            val embeddedName = embedded.name.ifBlank { worldName ?: "" }
            val isMatch = requested == "current" ||
                lorebookNameMatches(embeddedName, bookName) ||
                lorebookNameMatches(worldName, bookName)
            if (isMatch) {
                val updatedBook = applyLorebookDisablePatch(embedded, entriesJson) ?: return true
                patchSettings { s ->
                    s.copy(
                        assistants = s.assistants.map { a ->
                            if (a.id == assistant.id) {
                                a.copy(tavernData = tavernData.copy(embeddedBook = updatedBook))
                            } else {
                                a
                            }
                        },
                    )
                }
                return true
            }
        }
        if (findLorebookIndexByName(currentSettings.lorebooks, requested) < 0) {
            TavernRuntimeManager.appendLog("warn", "[lorebook] update miss '$requested'")
            return false
        }
        patchSettings { s ->
            val index = findLorebookIndexByName(s.lorebooks, requested)
            if (index < 0) return@patchSettings s
            val updatedBook = applyLorebookDisablePatch(s.lorebooks[index], entriesJson)
                ?: return@patchSettings s
            s.copy(lorebooks = s.lorebooks.toMutableList().also { it[index] = updatedBook })
        }
        return true
    }

    /** JS createOrReplaceLorebook：写入宿主世界书库。 */
    fun createOrReplaceLorebook(
        bookName: String,
        entriesJson: String,
        replaceIfExists: Boolean = true,
    ): Boolean {
        val requested = bookName.trim()
        if (requested.isEmpty()) return false
        if (findLorebookIndexByName(settings.value.lorebooks, requested) >= 0 && !replaceIfExists) return true
        patchSettings { s ->
            val index = findLorebookIndexByName(s.lorebooks, requested)
            val existing = if (index >= 0) s.lorebooks[index] else null
            val built = buildLorebookFromJsrEntries(requested, entriesJson, existing)
            val nextBooks = s.lorebooks.toMutableList().also {
                if (index >= 0) it[index] = built else it.add(built)
            }
            s.copy(lorebooks = nextBooks)
        }
        TavernRuntimeManager.appendLog("info", "[lorebook] createOrReplace '$requested'")
        return true
    }

    /** JS deleteLorebook：删书并解除助手/会话绑定。 */
    fun deleteLorebookByName(bookName: String): Boolean {
        val requested = bookName.trim()
        if (requested.isEmpty()) return false
        val currentSettings = settings.value
        val index = findLorebookIndexByName(currentSettings.lorebooks, requested)
        if (index < 0) return false
        val removedId = currentSettings.lorebooks[index].id
        patchSettings { s ->
            val currentIndex = findLorebookIndexByName(s.lorebooks, requested)
            if (currentIndex < 0) return@patchSettings s
            val targetId = s.lorebooks[currentIndex].id
            s.copy(
                lorebooks = s.lorebooks.filterIndexed { i, _ -> i != currentIndex },
                assistants = s.assistants.map { a -> a.copy(lorebookIds = a.lorebookIds - targetId) },
            )
        }
        if (removedId in conversation.value.lorebookIds) {
            updateConversation(conversation.value.copy(lorebookIds = conversation.value.lorebookIds - removedId))
            saveConversationAsync()
        }
        TavernRuntimeManager.appendLog("info", "[lorebook] delete '$requested'")
        return true
    }

    /** JS rebindLorebooks：scope=global/char/chat，按名字重新绑定。 */
    fun rebindLorebooks(scope: String, namesJson: String): Boolean {
        val names = runCatching {
            (Json.parseToJsonElement(namesJson) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
                .orEmpty()
        }.getOrDefault(emptyList()).filter { it.isNotEmpty() }
        val matchedIds = settings.value.lorebooks
            .filter { book -> names.any { lorebookNameMatches(book.name, it) } }
            .map { it.id }
            .toSet()
        return when (scope.trim().lowercase()) {
            "global" -> {
                patchSettings { s ->
                    s.copy(
                        lorebooks = s.lorebooks.map { book ->
                            book.copy(enabled = names.any { lorebookNameMatches(book.name, it) })
                        },
                    )
                }
                true
            }

            "char", "character" -> {
                val assistantId = conversation.value.assistantId
                patchSettings { s ->
                    s.copy(
                        assistants = s.assistants.map { a ->
                            if (a.id != assistantId) {
                                a
                            } else {
                                val tavernData = a.tavernData
                                val reboundData = if (tavernData == null) {
                                    null
                                } else {
                                    tavernData.copy(
                                        extensionsRaw = updateWorldNameInExtensionsRaw(
                                            tavernData.extensionsRaw,
                                            names.firstOrNull(),
                                        ),
                                    )
                                }
                                a.copy(lorebookIds = matchedIds, tavernData = reboundData)
                            }
                        },
                    )
                }
                true
            }

            "chat" -> {
                updateConversation(conversation.value.copy(lorebookIds = matchedIds))
                saveConversationAsync()
                true
            }

            else -> false
        }
    }

}
