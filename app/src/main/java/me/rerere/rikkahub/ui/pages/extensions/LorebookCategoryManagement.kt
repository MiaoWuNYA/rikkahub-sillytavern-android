package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.*
import kotlin.uuid.Uuid

@Composable
internal fun LorebookCategorySelector(book: Lorebook, selected: Uuid?, enabled: Boolean = true,
    label: String = "分类", onSelect: (Uuid?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text("$label：${book.categories.firstOrNull { it.id == selected }?.name ?: "未分类"}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("未分类") }, onClick = { expanded = false; onSelect(null) })
            book.categories.forEach { category ->
                DropdownMenuItem(text = { Text(category.name) }, onClick = { expanded = false; onSelect(category.id) })
            }
        }
    }
}

/** One compact menu keeps old competitive destinations and new categories explicitly distinct. */
@Composable
internal fun LorebookInsertionTargetSelector(book: Lorebook, selected: String, categoryTarget: Boolean,
    enabled: Boolean, onSelect: (String, Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
            TextButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.height(32.dp).widthIn(max = 128.dp).testTag("lorebook-target-group"),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                val label = if (categoryTarget) "分类：${book.categories.firstOrNull { it.id.toString() == selected }?.name ?: "未分类"}"
                    else "互斥组：${groupLabel(selected)}"
                Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("普通条目（移出互斥组）") }, onClick = {}, enabled = false)
            DropdownMenuItem(text = { Text("分类：未分类") }, onClick = { expanded = false; onSelect("", true) })
            book.categories.forEach { category -> DropdownMenuItem(text = { Text("分类：${category.name}") },
                onClick = { expanded = false; onSelect(category.id.toString(), true) }) }
            DropdownMenuItem(text = { Text("互斥组（修改竞争关系）") }, onClick = {}, enabled = false)
            lorebookEntryGroupNames(book).forEach { group -> DropdownMenuItem(text = { Text("互斥组：${groupLabel(group)}") },
                onClick = { expanded = false; onSelect(group, false) }) }
        }
    }
}

@Composable
internal fun LorebookCategoryManagement(book: Lorebook, onEdit: (Lorebook) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Uuid?>(null) }
    deleting?.let { id ->
        val count = book.entries.count { it.group.isBlank() && it.categoryId == id }
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除分类？") },
            text = { Text("分类中的 $count 条条目会留在原位置，变为未分类。条目及互斥组不会删除。") },
            confirmButton = { TextButton(onClick = { onEdit(deleteLorebookCategory(book, id)); deleting = null }) { Text("确认删除分类") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    val nodes = book.orderedNodes()
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val order = book.orderedNodes().toMutableList()
        val source = order.indexOfFirst { it.toString() == from.key }
        val target = order.indexOfFirst { it.toString() == to.key }
        if (source >= 0 && target >= 0 && source != target) {
            order.add(target, order.removeAt(source))
            onEdit(book.copy(rootOrder = order))
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("管理分类组") }, text = {
        Column(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(name, { name = it; error = null }, label = { Text("新分类名称") }, singleLine = true)
            TextButton(onClick = {
                runCatching { createLorebookCategory(book, name) }.onSuccess { onEdit(it); name = "" }.onFailure { error = it.message }
            }) { Text("创建分类") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("分类组仅整理条目、支持折叠和排序，不改变触发条件，也不参与互斥竞争。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f, fill = false).testTag("lorebook-category-management-list"), state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(nodes, key = { it.toString() }) { node ->
                    ReorderableItem(reorder, node.toString()) { dragging ->
                        val category = book.categories.firstOrNull { it.id == node.categoryId }
                        if (category == null) {
                            Text("条目 · ${book.entries.firstOrNull { it.id == node.entryId }?.name.orEmpty()}",
                                Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall)
                        } else Column(Modifier.fillMaxWidth().promptReorderLift(dragging)) {
                            var edited by remember(category.id, category.name) { mutableStateOf(category.name) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(edited, { edited = it }, modifier = Modifier.weight(1f),
                                    label = { Text("${book.entries.count { it.group.isBlank() && it.categoryId == category.id }} 条") }, singleLine = true)
                                LorebookDragHandle("排序分类 ${category.name}", Modifier.longPressDraggableHandle())
                            }
                            Row {
                                TextButton(onClick = { onEdit(moveLorebookCategoryToEdge(book, category.id, false)) }) { Text("置顶") }
                                TextButton(onClick = { onEdit(moveLorebookCategoryToEdge(book, category.id, true)) }) { Text("移至末尾") }
                            }
                            Row {
                                TextButton(onClick = {
                                    runCatching { renameLorebookCategory(book, category.id, edited) }.onSuccess(onEdit).onFailure { error = it.message }
                                }) { Text("重命名") }
                                TextButton(onClick = { deleting = category.id }) { Text("删除分类") }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

@Composable
internal fun LorebookSelectionScopeSelector(book: Lorebook, scope: LorebookSelectionScope, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (scope) {
        LorebookSelectionScope.All -> "全部条目"
        LorebookSelectionScope.Unclassified -> "未分类条目"
        is LorebookSelectionScope.Exclusive -> "互斥组 · ${scope.name}"
        is LorebookSelectionScope.Category -> "分类组 · ${book.categories.firstOrNull { it.id == scope.id }?.name ?: "已删除分类"}"
    }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag("lorebook-selection-scope")) { Text("全选范围：$label") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("全部条目") }, onClick = { expanded = false; onSelect("all:") })
            DropdownMenuItem(text = { Text("未分类条目") }, onClick = { expanded = false; onSelect("ordinary:") })
            lorebookEntryGroupNames(book).forEach { name -> DropdownMenuItem(text = { Text("互斥组：$name") }, onClick = { expanded = false; onSelect("exclusive:$name") }) }
            book.categories.forEach { category -> DropdownMenuItem(text = { Text("分类组：${category.name}") }, onClick = { expanded = false; onSelect("category:${category.id}") }) }
        }
    }
}
