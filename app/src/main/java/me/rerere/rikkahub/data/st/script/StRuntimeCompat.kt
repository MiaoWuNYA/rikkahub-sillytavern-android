package me.rerere.rikkahub.data.st.script

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

// 最小等价实现，待 runtime 正式版替换：本仓库 ModeInjection 缺 presetGroup 字段，暂无分组来源
@Suppress("UNUSED_PARAMETER")
internal fun enabledPresetGroupsCompat(
    assistant: Assistant?,
    settings: Settings?,
    extraModeInjectionIds: Set<kotlin.uuid.Uuid> = emptySet(),
): Set<String> = emptySet()

/** 原子切换预设组启用态：同时改 modeInjections 条目 enabled 与 assistant.modeInjectionIds。 */
internal fun togglePresetGroupForAssistant(
    current: Settings,
    assistantId: kotlin.uuid.Uuid,
    groupName: String,
    enable: Boolean,
): Settings {
    val groupEntries = current.modeInjections.filter { it.presetGroup == groupName }
    if (groupEntries.isEmpty()) return current
    val updatedModeInjections = if (enable && groupEntries.none { it.enabled }) {
        current.modeInjections.map { e -> if (e.presetGroup == groupName) e.copy(enabled = true) else e }
    } else {
        current.modeInjections
    }
    val allGroupIds = groupEntries.map { it.id }.toSet()
    val activeGroupIds = updatedModeInjections
        .filter { it.presetGroup == groupName && it.enabled }
        .map { it.id }
        .toSet()
        .ifEmpty { allGroupIds }
    val updatedAssistants = current.assistants.map { a ->
        if (a.id == assistantId) {
            val newIds = if (enable) (a.modeInjectionIds - allGroupIds) + activeGroupIds else a.modeInjectionIds - allGroupIds
            a.copy(modeInjectionIds = newIds)
        } else {
            a
        }
    }
    return current.copy(modeInjections = updatedModeInjections, assistants = updatedAssistants)
}

/** 原子切换单个预设条目启用态：同时改 modeInjections 条目 enabled 与 assistant.modeInjectionIds。 */
internal fun togglePresetEntryForAssistant(
    current: Settings,
    assistantId: kotlin.uuid.Uuid,
    entryId: kotlin.uuid.Uuid,
    enable: Boolean,
): Settings {
    val target = current.modeInjections.firstOrNull { it.id == entryId } ?: return current
    val updatedModeInjections = if (enable && !target.enabled) {
        current.modeInjections.map { e -> if (e.id == entryId) e.copy(enabled = true) else e }
    } else {
        current.modeInjections
    }
    val updatedAssistants = current.assistants.map { a ->
        if (a.id == assistantId) {
            val newIds = if (enable) a.modeInjectionIds + entryId else a.modeInjectionIds - entryId
            a.copy(modeInjectionIds = newIds)
        } else {
            a
        }
    }
    return current.copy(modeInjections = updatedModeInjections, assistants = updatedAssistants)
}
