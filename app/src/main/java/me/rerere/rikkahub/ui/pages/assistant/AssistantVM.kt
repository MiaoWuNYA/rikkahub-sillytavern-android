package me.rerere.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.repository.memoryId

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
    private val appScope: AppScope,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            settingsStore.update { it.copy(assistants = it.assistants.plus(assistant)) }
        }
    }

    // 助手与世界书同一次落库；曾只存助手导致世界书全部丢失
    fun addAssistantWithLorebooks(assistant: Assistant, lorebooks: List<Lorebook>) {
        viewModelScope.launch {
            val settings = settings.value
            val linked = if (lorebooks.isEmpty()) {
                assistant
            } else {
                assistant.copy(lorebookIds = assistant.lorebookIds + lorebooks.map { it.id })
            }
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(linked),
                    lorebooks = (settings.lorebooks + lorebooks).distinctBy { it.id },
                )
            )
        }
    }

    fun removeAssistant(assistant: Assistant) {
        // 助手移除后用户无法再触发清理，放到应用级作用域里，离开页面也要跑完
        appScope.launch {
            // 同一次更新里按最新列表校验，连续删除不会把最后一个助手也删掉
            var removed: Assistant? = null
            settingsStore.update { settings ->
                val target = settings.assistants.find { it.id == assistant.id }
                if (target == null || settings.assistants.size <= 1) return@update settings
                removed = target
                settings.copy(assistants = settings.assistants.filter { it.id != target.id })
            }
            // 真删掉了才清理关联数据
            val target = removed ?: return@launch
            cleanupAssistantFiles(target)
            memoryRepository.deleteFilesOfAssistant(target.id.toString())
            conversationRepo.deleteConversationOfAssistant(target.id)
        }
    }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }

        if (uris.isNotEmpty()) {
            filesManager.deleteChatFiles(uris)
        }
    }

    fun copyAssistant(assistant: Assistant, copyMemories: Boolean = false) {
        viewModelScope.launch {
            val copiedAssistant = assistant.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
            )
            settingsStore.update { it.copy(assistants = it.assistants.plus(copiedAssistant)) }
            if (copyMemories) {
                memoryRepository.copyFiles(
                    fromAssistantId = assistant.id.toString(),
                    toAssistantId = copiedAssistant.id.toString(),
                )
            }
        }
    }

    fun getMemories(assistant: Assistant) = memoryRepository.getFilesFlow(assistant.memoryId)
}
