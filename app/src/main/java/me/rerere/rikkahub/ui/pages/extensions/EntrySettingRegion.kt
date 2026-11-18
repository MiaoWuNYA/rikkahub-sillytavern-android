package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.PromptInjection

/** Individual mode passes through unchanged; batch mode decorates the SAME field controls. */
@Composable
internal fun EntrySettingRegion(key: EntrySettingKey, entry: PromptInjection.RegexInjection,
    onEdit: (PromptInjection.RegexInjection) -> Unit, bulk: LorebookBulkEditState?, modifier: Modifier = Modifier,
    content: @Composable ((PromptInjection.RegexInjection) -> Unit) -> Unit) {
    if (bulk == null) {
        if (modifier == Modifier) content(onEdit) else Box(modifier) { content(onEdit) }
        return
    }
    val checked = key in bulk.selected
    Surface(modifier.testTag("bulk-field-${key.name}"), shape = MaterialTheme.shapes.medium,
        color = if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        border = if (checked) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, { bulk.select(key, it) }, Modifier.testTag("bulk-select-${key.name}")
                    .semantics { contentDescription = "批量应用 ${key.label}" })
                Text("批量应用 · ${key.label}", style = MaterialTheme.typography.labelLarge)
            }
            if (key in bulk.mixed) Text("多个不同值", style = MaterialTheme.typography.bodySmall)
            if (key in bulk.mixed && key !in bulk.editing) {
                TextButton(onClick = { bulk.open(key) }, Modifier.testTag("bulk-edit-${key.name}")) { Text("设置新值") }
            } else {
                content { value -> bulk.change(key, key.read(value)) }
                if (key in bulk.mixed) TextButton(onClick = { bulk.confirmValue(key) }) { Text("明确使用此新值") }
            }
            if (checked && key !in bulk.resolved) Text("请明确设置新值后再应用", color = MaterialTheme.colorScheme.error)
            if (checked && !key.accepts(key.read(bulk.draft))) Text("请设置有效值后再应用", color = MaterialTheme.colorScheme.error)
        }
    }
}
