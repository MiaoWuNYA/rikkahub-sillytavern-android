package me.rerere.rikkahub.ui.pages.rescue

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.LightConversationEntity
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.sync.ForeignBackupCompat
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import kotlin.uuid.Uuid

// 一条可单独删除的消息；只带预览文本，不反序列化角色卡
data class RescueMessage(
    val nodeId: String,
    val messageId: String,
    val role: String,
    val preview: String,
    val createdAt: String,
)

/**
 * 数据救助：备份恢复之外的兜底入口。
 *
 * 这里只碰数据库投影与消息节点，不加载角色卡设定、世界书或前端卡数据——出问题时
 * 那些东西往往正是崩的原因，绕开它们才救得动。
 */
class RescueVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val chatService: ChatService,
    private val memoryRepository: MemoryRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy(),
    )

    // 每个角色卡名下的对话数，用来在删除前让人看清代价
    private val _conversationCounts = MutableStateFlow<Map<Uuid, Int>>(emptyMap())
    val conversationCounts: StateFlow<Map<Uuid, Int>> = _conversationCounts

    private val _conversations = MutableStateFlow<List<LightConversationEntity>>(emptyList())
    val conversations: StateFlow<List<LightConversationEntity>> = _conversations

    private val _messages = MutableStateFlow<List<RescueMessage>>(emptyList())
    val messages: StateFlow<List<RescueMessage>> = _messages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    init {
        refreshStatistics()
        loadConversations()
    }

    fun refreshStatistics() {
        viewModelScope.launch {
            _conversationCounts.value = runCatching {
                conversationRepository.countConversationsByAssistant()
            }.getOrDefault(emptyMap())
        }
    }

    fun loadConversations() {
        viewModelScope.launch {
            _conversations.value = withContext(Dispatchers.IO) {
                runCatching { conversationDAO.getRecentTitlesAnyAssistant(CONVERSATION_LIST_LIMIT) }
                    .getOrDefault(emptyList())
            }
        }
    }

    fun loadMessages(conversationId: Uuid) {
        viewModelScope.launch {
            _messages.value = withContext(Dispatchers.IO) { readMessages(conversationId) }
        }
    }

    // 逐条丢弃读不懂的消息，既然目标是修闪退，解不开的那条也要能列出来删掉
    private suspend fun readMessages(conversationId: Uuid): List<RescueMessage> =
        messageNodeDAO.getNodesOfConversation(conversationId.toString()).flatMap { entity ->
            ForeignBackupCompat.decodeMessages(entity.messages, JsonInstant).map { message ->
                RescueMessage(
                    nodeId = entity.id,
                    messageId = message.id.toString(),
                    role = message.role.name,
                    preview = message.toText().replace('\n', ' ').take(MESSAGE_PREVIEW_LENGTH),
                    createdAt = message.createdAt.toString(),
                )
            }
        }

    fun deleteMessage(conversationId: Uuid, message: RescueMessage, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val ok = withContext(Dispatchers.IO) {
                runCatching { chatService.stopGeneration(conversationId) }
                val removedInSession = runCatching {
                    chatService.deleteMessage(
                        conversationId = conversationId,
                        messageId = Uuid.parse(message.messageId),
                        failIfMissing = false,
                    )
                }
                // 会话缓存不可用（例如它就是崩的那个）时退到直接改库，不留半删状态
                removedInSession.isSuccess || dropMessageFromNode(conversationId, message)
            }
            _messages.value = withContext(Dispatchers.IO) { readMessages(conversationId) }
            _busy.value = false
            onDone(ok)
        }
    }

    private suspend fun dropMessageFromNode(conversationId: Uuid, message: RescueMessage): Boolean {
        val entity = messageNodeDAO.getNodesOfConversation(conversationId.toString())
            .firstOrNull { it.id == message.nodeId } ?: return false
        val remaining = ForeignBackupCompat.decodeMessages(entity.messages, JsonInstant)
            .filterNot { it.id.toString() == message.messageId }
        if (remaining.isEmpty()) {
            messageNodeDAO.deleteById(entity.id)
        } else {
            messageNodeDAO.update(
                entity.copy(
                    messages = JsonInstant.encodeToString(remaining),
                    selectIndex = 0,
                )
            )
        }
        return true
    }

    fun clearConversation(conversationId: Uuid, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val ok = withContext(Dispatchers.IO) {
                runCatching { chatService.stopGeneration(conversationId) }
                runCatching {
                    messageNodeDAO.deleteByConversation(conversationId.toString())
                    conversationDAO.resetConversationNodes(conversationId.toString())
                }.isSuccess
            }
            _messages.value = emptyList()
            _busy.value = false
            onDone(ok)
        }
    }

    // 与角色卡详情页的删除保持同一套清理顺序，避免头像/记忆/对话成为孤儿
    fun deleteAssistant(assistant: Assistant, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val uris = buildList {
                        (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
                        assistant.background?.let { add(it.toUri()) }
                    }
                    if (uris.isNotEmpty()) filesManager.deleteChatFiles(uris)

                    settingsStore.update { settings ->
                        if (settings.assistants.size <= 1) return@update settings
                        settings.copy(assistants = settings.assistants.filter { it.id != assistant.id })
                    }
                    if (settingsStore.settingsFlow.value.assistants.any { it.id == assistant.id }) {
                        return@runCatching
                    }
                    memoryRepository.deleteFilesOfAssistant(assistant.id.toString())
                    conversationRepository.deleteConversationOfAssistant(assistant.id)
                }.isSuccess
            }
            refreshStatistics()
            loadConversations()
            _busy.value = false
            onDone(ok)
        }
    }

    fun hasCrashRecord(): Boolean = CrashHandler.hasCrashed(context)

    fun crashStackTrace(): String? = CrashHandler.getStackTrace(context)

    fun clearCrashRecord() {
        CrashHandler.clearCrashed(context)
    }

    fun latestCrashLog(): String? {
        val file: File = CrashHandler.listCrashLogs(context).firstOrNull() ?: return null
        return CrashHandler.readCrashLog(file)
    }

    private companion object {
        const val CONVERSATION_LIST_LIMIT = 200
        const val MESSAGE_PREVIEW_LENGTH = 160
    }
}
