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
import androidx.compose.foundation.shape.RoundedCornerShape
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
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.authors_note_title)) },
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
                        Text(stringResource(R.string.authors_note_enable), style = MaterialTheme.typography.titleSmall)
                    },
                    supportingContent = {
                        Text(
                            stringResource(R.string.authors_note_enable_desc),
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
                Text(stringResource(R.string.authors_note_custom_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                TextButton(
                    onClick = {
                        editingBuiltin = false
                        editingEntry = AuthorNoteEntry(
                            id = "custom:${Uuid.random()}",
                            name = stringResource(R.string.authors_note_custom_default_name),
                        )
                    },
                ) { Text(stringResource(R.string.authors_note_add)) }
            }

            val customEntries = entries.filterNot { it.id.startsWith(BUILTIN_AUTHOR_NOTE_PREFIX) }
            if (customEntries.isEmpty()) {
                Text(
                    stringResource(R.string.authors_note_empty),
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
                stringResource(R.string.authors_note_builtin_presets, authorsNotePresets.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.authors_note_presets_desc),
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(checked = entry.enabled, onCheckedChange = onEnabledChange)
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name.ifBlank { stringResource(R.string.authors_note_unnamed) }, style = MaterialTheme.typography.bodyMedium)
                Text(
                    authorNoteSummary(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.authors_note_edit)) }
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
        title = { Text(stringResource(if (builtin) R.string.authors_note_edit_preset_title else R.string.authors_note_edit_title)) },
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
                    Text(stringResource(R.string.authors_note_enable_individually))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (!builtin) name = it },
                    readOnly = builtin,
                    label = { Text(stringResource(R.string.authors_note_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { if (!builtin) content = it },
                    readOnly = builtin,
                    label = { Text(stringResource(R.string.authors_note_content)) },
                    minLines = if (builtin) 3 else 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
                Text(stringResource(R.string.authors_note_position_title), style = MaterialTheme.typography.titleSmall)
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
                        supportingText = { Text(stringResource(R.string.authors_note_depth_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = depth == null || depth !in 0..9999,
                        modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    )
                }
                Text(stringResource(R.string.authors_note_role_title), style = MaterialTheme.typography.titleSmall)
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
                    stringResource(R.string.authors_note_role_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.authors_note_interval_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.authors_note_interval_desc),
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
                                0 -> stringResource(R.string.authors_note_interval_off)
                                1 -> stringResource(R.string.authors_note_interval_every)
                                null -> stringResource(R.string.authors_note_interval_enter_number)
                                else -> stringResource(R.string.authors_note_interval_every_n, interval)
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = interval == null || interval !in 0..9999,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
                if (onDelete != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.authors_note_delete)) }
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
            ) { Text(stringResource(R.string.authors_note_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.authors_note_cancel)) } },
    )
}

private const val BUILTIN_AUTHOR_NOTE_PREFIX = "builtin:"

private fun builtinAuthorNoteId(label: String): String = "$BUILTIN_AUTHOR_NOTE_PREFIX$label"

@Composable
private fun authorNoteSummary(entry: AuthorNoteEntry): String {
    val position = authorNotePositionLabel(entry.position)
    val role = authorNoteRoleLabel(entry.role)
    val interval = when (entry.interval) {
        0 -> stringResource(R.string.authors_note_summary_interval_off)
        1 -> stringResource(R.string.authors_note_summary_every)
        else -> stringResource(R.string.authors_note_summary_every_n, entry.interval)
    }
    return buildString {
        append(position)
        if (entry.position == AuthorNotePosition.IN_CHAT) append(" · Depth ${entry.depth}")
        append(" · $role")
        append(interval)
    }
}

@Composable
private fun authorNotePositionLabel(position: AuthorNotePosition): String = when (position) {
    AuthorNotePosition.BEFORE_PROMPT -> stringResource(R.string.authors_note_position_before_prompt)
    AuthorNotePosition.IN_PROMPT -> stringResource(R.string.authors_note_position_in_prompt)
    AuthorNotePosition.IN_CHAT -> stringResource(R.string.authors_note_position_in_chat)
}

@Composable
private fun authorNotePositionDescription(position: AuthorNotePosition): String = when (position) {
    AuthorNotePosition.BEFORE_PROMPT -> stringResource(R.string.authors_note_position_before_prompt_desc)
    AuthorNotePosition.IN_PROMPT -> stringResource(R.string.authors_note_position_in_prompt_desc)
    AuthorNotePosition.IN_CHAT -> stringResource(R.string.authors_note_position_in_chat_desc)
}

@Composable
private fun authorNoteRoleLabel(role: MessageRole): String = when (role) {
    MessageRole.SYSTEM -> stringResource(R.string.authors_note_role_system)
    MessageRole.USER -> stringResource(R.string.authors_note_role_user)
    MessageRole.ASSISTANT -> stringResource(R.string.authors_note_role_assistant)
    else -> role.name
}
