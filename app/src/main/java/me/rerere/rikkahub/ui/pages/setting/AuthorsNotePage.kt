package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.authorNoteEntriesOrLegacy
import me.rerere.rikkahub.data.model.AuthorNoteEntry
import me.rerere.rikkahub.data.model.AuthorNotePosition
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorsNotePage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val entries = settings.authorNoteEntriesOrLegacy()
    var editingEntry by remember { mutableStateOf<AuthorNoteEntry?>(null) }
    var editingBuiltin by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Author's Note · 导演备注") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup {
                item(
                    headlineContent = {
                        Text("启用导演备注", style = MaterialTheme.typography.titleSmall)
                    },
                    supportingContent = {
                        Text(
                            "总开关关闭时，下面所有条目都不会注入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.authorNoteEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settingsStore.update { it.copy(authorNoteEnabled = enabled) }
                                }
                            },
                        )
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("自定义与旧版备注", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                TextButton(
                    onClick = {
                        editingBuiltin = false
                        editingEntry = AuthorNoteEntry(
                            id = "custom:${Uuid.random()}",
                            name = "自定义导演备注",
                        )
                    },
                ) { Text("新增") }
            }

            val customEntries = entries.filterNot { it.id.startsWith(BUILTIN_AUTHOR_NOTE_PREFIX) }
            if (customEntries.isEmpty()) {
                Text(
                    "暂无自定义备注",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                customEntries.forEach { entry ->
                    AuthorNoteEntryRow(
                        entry = entry,
                        onEnabledChange = { enabled ->
                            scope.launch {
                                settingsStore.update { current ->
                                    current.copy(
                                        authorNoteEntries = current.authorNoteEntriesOrLegacy().map {
                                            if (it.id == entry.id) it.copy(enabled = enabled) else it
                                        }
                                    )
                                }
                            }
                        },
                        onEdit = {
                            editingBuiltin = false
                            editingEntry = entry
                        },
                    )
                }
            }

            Text(
                "内置快速预设（${authorsNotePresets.size}）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "每个预设独立开关；启用第二个不会覆盖已启用的预设。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            authorsNotePresets.forEach { (label, content) ->
                val presetId = builtinAuthorNoteId(label)
                val stored = entries.firstOrNull { it.id == presetId }
                val displayEntry = stored ?: AuthorNoteEntry(
                    id = presetId,
                    name = label,
                    content = content,
                    enabled = false,
                )
                AuthorNoteEntryRow(
                    entry = displayEntry,
                    onEnabledChange = { enabled ->
                        scope.launch {
                            settingsStore.update { current ->
                                val currentEntries = current.authorNoteEntriesOrLegacy()
                                val exists = currentEntries.any { it.id == presetId }
                                val updated = if (exists) {
                                    currentEntries.map {
                                        if (it.id == presetId) it.copy(enabled = enabled) else it
                                    }
                                } else {
                                    currentEntries + displayEntry.copy(enabled = enabled)
                                }
                                current.copy(authorNoteEntries = updated)
                            }
                        }
                    },
                    onEdit = {
                        editingBuiltin = true
                        editingEntry = displayEntry
                    },
                )
            }
        }
    }

    editingEntry?.let { entry ->
        AuthorNoteEntryDialog(
            initial = entry,
            builtin = editingBuiltin,
            onDismiss = { editingEntry = null },
            onSave = { saved ->
                scope.launch {
                    settingsStore.update { current ->
                        val currentEntries = current.authorNoteEntriesOrLegacy()
                        val exists = currentEntries.any { it.id == saved.id }
                        current.copy(
                            authorNoteEntries = if (exists) {
                                currentEntries.map { if (it.id == saved.id) saved else it }
                            } else {
                                currentEntries + saved
                            }
                        )
                    }
                }
                editingEntry = null
            },
            onDelete = if (editingBuiltin) null else {
                {
                    scope.launch {
                        settingsStore.update { current ->
                            current.copy(
                                authorNoteEntries = current.authorNoteEntriesOrLegacy()
                                    .filterNot { it.id == entry.id }
                            )
                        }
                    }
                    editingEntry = null
                }
            },
        )
    }
}

@Composable
private fun AuthorNoteEntryRow(
    entry: AuthorNoteEntry,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(checked = entry.enabled, onCheckedChange = onEnabledChange)
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name.ifBlank { "未命名导演备注" }, style = MaterialTheme.typography.bodyMedium)
                Text(
                    authorNoteSummary(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) { Text("编辑") }
        }
    }
}

@Composable
private fun AuthorNoteEntryDialog(
    initial: AuthorNoteEntry,
    builtin: Boolean,
    onDismiss: () -> Unit,
    onSave: (AuthorNoteEntry) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var content by remember(initial) { mutableStateOf(initial.content) }
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var position by remember(initial) { mutableStateOf(initial.position) }
    var depthText by remember(initial) { mutableStateOf(initial.depth.toString()) }
    var role by remember(initial) { mutableStateOf(initial.role) }
    var intervalText by remember(initial) { mutableStateOf(initial.interval.toString()) }
    val depth = depthText.toIntOrNull()
    val interval = intervalText.toIntOrNull()
    val valid = name.isNotBlank() && content.isNotBlank() &&
        depth != null && depth in 0..9999 && interval != null && interval in 0..9999

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (builtin) "编辑预设参数" else "编辑导演备注") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("单独启用")
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (!builtin) name = it },
                    readOnly = builtin,
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { if (!builtin) content = it },
                    readOnly = builtin,
                    label = { Text("内容") },
                    minLines = if (builtin) 3 else 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("注入位置（Position）", style = MaterialTheme.typography.titleSmall)
                AuthorNotePosition.entries.forEach { option ->
                    FilterChip(
                        modifier = Modifier.fillMaxWidth(),
                        selected = position == option,
                        onClick = { position = option },
                        label = {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(authorNotePositionLabel(option))
                                Text(
                                    authorNotePositionDescription(option),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                if (position == AuthorNotePosition.IN_CHAT) {
                    OutlinedTextField(
                        value = depthText,
                        onValueChange = { depthText = it.filter(Char::isDigit) },
                        label = { Text("Depth（0–9999）") },
                        supportingText = { Text("官方范围 0–9999；0 = 对话最末尾") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = depth == null || depth !in 0..9999,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("注入角色（Role）", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT).forEach { option ->
                        FilterChip(
                            selected = role == option,
                            onClick = { role = option },
                            label = { Text(authorNoteRoleLabel(option)) },
                        )
                    }
                }
                Text(
                    "以什么角色注入备注内容（三个位置均生效）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "间隔（Interval）",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "按当前对话的用户消息条数计数（0 = 关闭，1 = 每次注入）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it.filter(Char::isDigit) },
                    label = { Text("Interval（0–9999）") },
                    supportingText = {
                        Text(
                            when (interval) {
                                0 -> "不注入"
                                1 -> "每次注入"
                                null -> "请输入数字"
                                else -> "每 $interval 条真实用户消息注入一次"
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = interval == null || interval !in 0..9999,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDelete) { Text("删除这条备注") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            content = content,
                            enabled = enabled,
                            position = position,
                            depth = depth ?: initial.depth,
                            role = role,
                            interval = interval ?: initial.interval,
                        )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private const val BUILTIN_AUTHOR_NOTE_PREFIX = "builtin:"

private fun builtinAuthorNoteId(label: String): String = "$BUILTIN_AUTHOR_NOTE_PREFIX$label"

private fun authorNoteSummary(entry: AuthorNoteEntry): String = buildString {
    append(authorNotePositionLabel(entry.position))
    if (entry.position == AuthorNotePosition.IN_CHAT) append(" · Depth ${entry.depth}")
    append(" · ${authorNoteRoleLabel(entry.role)}")
    append(
        when (entry.interval) {
            0 -> " · 已按间隔关闭"
            1 -> " · 每次"
            else -> " · 每 ${entry.interval} 条用户消息"
        }
    )
}

private fun authorNotePositionLabel(position: AuthorNotePosition): String = when (position) {
    AuthorNotePosition.BEFORE_PROMPT -> "主提示词/场景之前（Before Main Prompt / Story String）"
    AuthorNotePosition.IN_PROMPT -> "主提示词/场景之后（After Main Prompt / Story String）"
    AuthorNotePosition.IN_CHAT -> "聊天内指定深度（In-chat @ Depth）"
}

private fun authorNotePositionDescription(position: AuthorNotePosition): String = when (position) {
    AuthorNotePosition.BEFORE_PROMPT -> "位于角色设定之前，影响整段上下文"
    AuthorNotePosition.IN_PROMPT -> "紧跟角色设定，位于示例消息之前"
    AuthorNotePosition.IN_CHAT -> "按下方深度插入对话历史（官方默认）"
}

private fun authorNoteRoleLabel(role: MessageRole): String = when (role) {
    MessageRole.SYSTEM -> "系统"
    MessageRole.USER -> "用户"
    MessageRole.ASSISTANT -> "助手"
    else -> role.name
}
