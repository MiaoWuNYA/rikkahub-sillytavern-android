package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.TavernBookEntry
import me.rerere.rikkahub.data.model.TavernEmbeddedBook
import kotlin.uuid.Uuid

class PromptVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            val syncedAssistants = syncExternalToEmbedded(settings)
            settingsStore.update(settings.copy(assistants = syncedAssistants))
        }
    }

    /** 外置 lorebook → 内嵌世界书同步 */
    private fun syncExternalToEmbedded(settings: Settings): List<me.rerere.rikkahub.data.model.Assistant> {
        if (settings.lorebooks.isEmpty()) return settings.assistants
        return settings.assistants.map { assistant ->
            val tav = assistant.tavernData ?: return@map assistant
            val oldBook = tav.embeddedBook ?: return@map assistant
            val matchedLb = settings.lorebooks.find { it.id in assistant.lorebookIds } ?: return@map assistant
            if (matchedLb.entries.isEmpty() || oldBook.entries.isEmpty()) return@map assistant

            val newEntries = oldBook.entries.mapIndexed { i, oldEntry ->
                val injection = matchedLb.entries.getOrNull(i) ?: return@mapIndexed oldEntry
                oldEntry.copy(
                    keys = injection.keywords,
                    secondaryKeys = injection.secondaryKeys,
                    content = injection.content,
                    comment = injection.name,
                    constant = injection.constantActive,
                    selective = injection.selective,
                    selectiveLogic = when (injection.selectiveLogic) {
                        me.rerere.rikkahub.data.model.SelectiveLogic.AND_ALL -> 1
                        me.rerere.rikkahub.data.model.SelectiveLogic.OR_ANY -> 2
                        me.rerere.rikkahub.data.model.SelectiveLogic.NOT_ANY -> 3
                        me.rerere.rikkahub.data.model.SelectiveLogic.NOT_ALL -> 4
                        else -> 0
                    },
                    group = injection.group,
                    position = when (injection.position) {
                        InjectionPosition.BEFORE_SYSTEM_PROMPT -> 0
                        InjectionPosition.AFTER_SYSTEM_PROMPT -> 1
                        InjectionPosition.TOP_OF_CHAT, InjectionPosition.AUTHOR_NOTE -> 2
                        InjectionPosition.BOTTOM_OF_CHAT -> 3
                        InjectionPosition.AT_DEPTH -> 4
                        InjectionPosition.BEFORE_CHARACTER -> 5
                        InjectionPosition.AFTER_CHARACTER -> 6
                        InjectionPosition.ANTAGONIZE -> 7
                        InjectionPosition.AFTER_DIALOG -> 8
                    },
                    priority = injection.priority,
                    disable = !injection.enabled,
                    caseSensitive = injection.caseSensitive,
                    matchWholeWords = injection.matchWholeWords,
                    excludeRecursion = injection.excludeRecursion,
                    preventRecursion = injection.preventRecursion,
                    delayUntilRecursion = injection.delayUntilRecursion,
                    useRegex = injection.useRegex,
                    probability = injection.probability,
                    sticky = injection.sticky,
                    cooldown = injection.cooldown,
                    delay = injection.delay,
                    depth = injection.injectDepth,
                    scanDepth = injection.scanDepth,
                    role = when (injection.role) { me.rerere.ai.core.MessageRole.USER -> "user"; me.rerere.ai.core.MessageRole.ASSISTANT -> "assistant"; else -> "system" },
                    groupWeight = injection.groupWeight,
                    groupOverride = injection.groupOverride,
                    useProbability = injection.useProbability,
                    inclusionGroup = injection.inclusionGroup,
                    useGroupScoring = injection.useGroupScoring,
                    groupPriority = injection.groupPriority,
                    automationId = injection.automationId,
                    displayIndex = injection.displayIndex,
                    displayPosition = injection.displayPosition,
                    triggers = injection.triggers,
                )
            }
            assistant.copy(tavernData = tav.copy(embeddedBook = oldBook.copy(entries = newEntries)))
        }
    }
}
