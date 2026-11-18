package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.PromptInjection

@Composable
internal fun LorebookBulkSettingsDialog(entries: List<PromptInjection.RegexInjection>, onDismiss: () -> Unit,
    onApply: (LorebookSettingsPatch) -> Unit) {
    if (entries.isEmpty()) return
    val state = remember(entries) { LorebookBulkEditState(entries) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("批量设置 · 已选 ${entries.size} 条") }, text = {
        Column(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("仅应用主动选中的字段；混合字段需要明确设置新值。")
            RegexEntryEditorFields(state.draft, {}, bulk = state, modifier = Modifier.weight(1f, fill = false))
            Text("将覆盖：${state.selected.joinToString { it.label }.ifBlank { "尚未选择字段" }}")
        }
    }, confirmButton = { TextButton(onClick = { if (state.canApply) onApply(state.patch()) }, enabled = state.canApply) {
        Text("应用到 ${entries.size} 个条目")
    } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消设置") } })
}
