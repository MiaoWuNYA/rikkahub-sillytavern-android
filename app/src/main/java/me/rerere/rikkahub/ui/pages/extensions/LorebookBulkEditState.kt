package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.runtime.*
import me.rerere.rikkahub.data.model.PromptInjection

/** Original values, field participation and newly chosen values are separate state. */
internal class LorebookBulkEditState(val entries: List<PromptInjection.RegexInjection>) {
    private val values = EntrySettingKey.entries.associateWith { key -> entries.map { key.read(it) }.distinct() }
    val mixed = values.filterValues { it.size > 1 }.keys
    var draft by mutableStateOf(EntrySettingKey.entries.fold(PromptInjection.RegexInjection()) { value, key ->
        if (values.getValue(key).size == 1) key.write(value, values.getValue(key).single()) else value
    })
        private set
    var selected by mutableStateOf(emptySet<EntrySettingKey>())
        private set
    var editing by mutableStateOf(emptySet<EntrySettingKey>())
        private set
    var resolved by mutableStateOf(EntrySettingKey.entries.toSet() - mixed)
        private set
    fun select(key: EntrySettingKey, value: Boolean) { selected = if (value) selected + key else selected - key }
    fun open(key: EntrySettingKey) { editing = editing + key }
    fun change(key: EntrySettingKey, value: Any?) {
        draft = key.write(draft, value)
        resolved = resolved + key
    }
    fun confirmValue(key: EntrySettingKey) { resolved = resolved + key }
    val canApply get() = selected.isNotEmpty() && resolved.containsAll(selected) &&
        selected.all { it.accepts(it.read(draft)) }
    fun patch(): LorebookSettingsPatch {
        check(canApply) { "请为选中的混合字段明确指定新值" }
        val remaining = selected - setOf(EntrySettingKey.ENABLED, EntrySettingKey.PRIORITY, EntrySettingKey.POSITION, EntrySettingKey.DEPTH, EntrySettingKey.PROBABILITY)
        return LorebookSettingsPatch(
            priority = draft.priority.takeIf { EntrySettingKey.PRIORITY in selected },
            position = draft.position.takeIf { EntrySettingKey.POSITION in selected },
            depth = draft.injectDepth.takeIf { EntrySettingKey.DEPTH in selected },
            probability = draft.probability.takeIf { EntrySettingKey.PROBABILITY in selected },
            enabled = draft.enabled.takeIf { EntrySettingKey.ENABLED in selected },
            overrides = remaining.associateWith { it.read(draft) },
        )
    }
}
