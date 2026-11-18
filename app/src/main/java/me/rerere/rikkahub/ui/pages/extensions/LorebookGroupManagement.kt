package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Lorebook

@Composable
internal fun LorebookGroupTargetSelector(groups: List<String>, selected: String, enabled: Boolean, prefix: String = "范围", tag: String = "lorebook-target-group", allSelected: Boolean = false, onSelectAll: (() -> Unit)? = null, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
            TextButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.height(32.dp).widthIn(max = if (onSelectAll != null) 160.dp else 112.dp).testTag(tag),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                Text("$prefix：${if (allSelected) "全部条目" else groupLabel(selected)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onSelectAll != null) DropdownMenuItem(text = { Text("全部条目") },
                onClick = { expanded = false; onSelectAll() })
            (groups + selected + "").map { it.ifBlank { "" } }.distinct().forEach { group ->
                DropdownMenuItem(text = { Text("组内：${groupLabel(group)}") },
                    onClick = { expanded = false; onSelect(group) })
            }
        }
    }
}

/** Names only: these operations never create dummy entries or change trigger configuration. */
@Composable
internal fun LorebookGroupManagement(book: Lorebook, onEdit: (Lorebook) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var dissolving by remember { mutableStateOf<String?>(null) }
    dissolving?.let { group ->
        AlertDialog(onDismissRequest = { dissolving = null }, title = { Text("解散互斥组？") },
            text = { Text("将解散「$group」的组关系，保留 ${book.entries.count { it.group == group }} 条条目及其独立设置，移入普通未分类区域。不会删除条目。") },
            confirmButton = { TextButton(onClick = { onEdit(dissolveLorebookExclusiveGroup(book, group)); dissolving = null }) { Text("解散并保留条目") } },
            dismissButton = { TextButton(onClick = { dissolving = null }) { Text("取消") } })
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("管理互斥组") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it; error = null }, modifier = Modifier.testTag("lorebook-group-name"), label = { Text("新互斥组名称") }, singleLine = true)
            TextButton(onClick = {
                runCatching { createLorebookEntryGroup(book, name) }.onSuccess { onEdit(it); name = "" }
                    .onFailure { error = it.message }
            }) { Text("创建互斥组") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val groups = lorebookEntryGroupNames(book)
            groups.forEachIndexed { index, group ->
                Column(Modifier.fillMaxWidth()) {
                    var renamed by remember(group) { mutableStateOf(group) }
                    val count = book.entries.count { it.group == group }
                    OutlinedTextField(renamed, { renamed = it }, label = { Text("$count 条 · 互斥组名称") }, singleLine = true)
                    Row {
                        TextButton(onClick = { runCatching { renameLorebookExclusiveGroup(book, group, renamed) }.onSuccess(onEdit).onFailure { error = it.message } }) { Text("重命名") }
                        TextButton(onClick = {
                            val order = groups.toMutableList().apply { add(index - 1, removeAt(index)) }
                            onEdit(book.copy(entryGroups = order))
                        }, enabled = index > 0) { Text("上移") }
                        TextButton(onClick = { dissolving = group }) { Text(if (count == 0) "删除空组" else "解散组") }
                    }
                }
            }
            Text("互斥组保留原竞争规则。空组可保存；权重等设置请使用组设置。分类请使用独立的管理分类组入口。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成互斥组管理") } })
}
