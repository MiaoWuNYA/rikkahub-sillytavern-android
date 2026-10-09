package me.rerere.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Lorebook

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(assistant)
                )
            )
        }
    }

    /**
     * 新增助手，并同时登记随它一起导入的世界书。
     *
     * 世界书是**独立的实体**，存在 Settings.lorebooks 全局列表里，
     * 助手通过 lorebookIds 引用它——这样用户能在扩展页里单独看到、
     * 单独开关每一条，也能把同一本书挂到多个助手上。
     *
     * 之前导入时只保存 assistant、把 newLorebooks 丢掉，等于世界书
     * 白导入了：条目既不在全局列表里，也没有 id 被引用。用户看到的是
     * 「世界书没生效」，而真正的问题是它根本没被存下来。
     *
     * 挂 id 的动作放在这里而不是让调用方自己拼：两件事必须同时发生，
     * 分开写迟早会出现「书存了但没挂上」或「挂了不存在的 id」。
     */
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
                    // 按 id 去重：重复导入同一张卡不该产生两份同名世界书
                    lorebooks = (settings.lorebooks + lorebooks).distinctBy { it.id },
                )
            )
        }
    }

    fun removeAssistant(assistant: Assistant) {
        viewModelScope.launch {
            cleanupAssistantFiles(assistant)

            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.filter { it.id != assistant.id }
                )
            )
            memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
            conversationRepo.deleteConversationOfAssistant(assistant.id)
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
            val settings = settings.value
            val copiedAssistant = assistant.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
            )
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(copiedAssistant)
                )
            )
            if (copyMemories) {
                memoryRepository.copyMemories(
                    fromAssistantId = assistant.id.toString(),
                    toAssistantId = copiedAssistant.id.toString(),
                )
            }
        }
    }

    fun getMemories(assistant: Assistant) =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemoriesFlow()
        } else {
            memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
        }
}
