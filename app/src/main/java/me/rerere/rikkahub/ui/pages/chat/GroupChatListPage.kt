package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.model.GroupActivationStrategy
import me.rerere.rikkahub.data.model.GroupGenerationMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.Screen
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun GroupChatListPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showCreate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.group_chat_list_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Text("+")
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        if (settings.groupChats.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("👥", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.group_chat_empty), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.group_chat_empty_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(settings.groupChats, key = { it.id }) { gc ->
                    val members = gc.memberIds.mapNotNull { id ->
                        settings.assistants.find { it.id == id }
                    }
                    Card(
                        onClick = { navController.navigate(Screen.GroupChat(gc.id.toString())) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(gc.name.ifBlank { "未命名群聊" },
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                                Text(
                                    "${members.size} 位成员 · ${members.take(3).joinToString { it.name }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 创建对话框
    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var selectedIds by remember { mutableStateOf(setOf<Uuid>()) }
        var selectedMode by remember { mutableStateOf(GroupActivationStrategy.NATURAL) }
        var selectedGenMode by remember { mutableStateOf(GroupGenerationMode.SWAP) }
        var autoDelay by remember { mutableIntStateOf(5) }
        var autoRounds by remember { mutableIntStateOf(5) }
        var allowSelf by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.group_chat_create_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.group_chat_name)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    CardGroup(title = { Text(stringResource(R.string.group_activation_strategy)) }) {
                        GroupActivationStrategy.entries.forEach { mode ->
                            item(
                                onClick = { selectedMode = mode },
                                headlineContent = { Text(modeLabel(mode), style = MaterialTheme.typography.bodyMedium) },
                                trailingContent = {
                                    RadioButton(
                                        selected = selectedMode == mode,
                                        onClick = { selectedMode = mode },
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = modeDesc(selectedMode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    CardGroup(title = { Text(stringResource(R.string.group_generation_mode)) }) {
                        GroupGenerationMode.entries.forEach { mode ->
                            item(
                                onClick = { selectedGenMode = mode },
                                headlineContent = { Text(genModeLabel(mode), style = MaterialTheme.typography.bodyMedium) },
                                trailingContent = {
                                    RadioButton(
                                        selected = selectedGenMode == mode,
                                        onClick = { selectedGenMode = mode },
                                    )
                                },
                            )
                        }
                    }

                    Text(stringResource(R.string.group_auto_reply_delay, autoDelay), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = autoDelay.toFloat(),
                        onValueChange = { autoDelay = it.toInt() },
                        valueRange = 0f..30f,
                        steps = 29,
                    )

                    Text(stringResource(R.string.group_auto_reply_rounds, if (autoRounds <= 0) "无上限" else autoRounds.toString()), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = autoRounds.toFloat(),
                        onValueChange = { autoRounds = it.toInt() },
                        valueRange = 0f..30f,
                        steps = 29,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.group_allow_self_reply), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = allowSelf, onCheckedChange = { allowSelf = it })
                    }

                    // 成员列表：与上方"激活策略/生成模式"一致，用 CardGroup 行样式
                    CardGroup(title = { Text(stringResource(R.string.group_members, selectedIds.size, settings.assistants.size)) }) {
                        settings.assistants.forEach { a ->
                            item(
                                onClick = {
                                    selectedIds = if (a.id in selectedIds) selectedIds - a.id
                                    else selectedIds + a.id
                                },
                                headlineContent = {
                                    Text(a.name.ifBlank { "(未命名)" }, style = MaterialTheme.typography.bodyMedium)
                                },
                                trailingContent = {
                                    Checkbox(
                                        checked = a.id in selectedIds,
                                        onCheckedChange = { checked ->
                                            selectedIds = if (checked) selectedIds + a.id
                                            else selectedIds - a.id
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank() && selectedIds.size >= 2) {
                            val gc = GroupChat(
                                name = name,
                                memberIds = selectedIds.toList(),
                                activationStrategy = selectedMode,
                                generationMode = selectedGenMode,
                                autoModeDelay = autoDelay,
                                autoChatRounds = autoRounds,
                                allowSelfResponses = allowSelf,
                            )
                            scope.launch {
                                settingsStore.update(settings.copy(groupChats = settings.groupChats + gc))
                            }
                            showCreate = false
                        }
                    }
                ) { Text(stringResource(R.string.group_create)) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.group_cancel)) } },
        )
    }
}

@Composable
private fun modeLabel(mode: GroupActivationStrategy): String = when (mode) {
    GroupActivationStrategy.NATURAL -> stringResource(R.string.group_mode_natural)
    GroupActivationStrategy.LIST -> stringResource(R.string.group_mode_list)
    GroupActivationStrategy.MANUAL -> stringResource(R.string.group_mode_manual)
    GroupActivationStrategy.POOLED -> stringResource(R.string.group_mode_pooled)
}

@Composable
private fun modeDesc(mode: GroupActivationStrategy): String = when (mode) {
    GroupActivationStrategy.NATURAL -> stringResource(R.string.group_mode_natural_desc)
    GroupActivationStrategy.LIST -> stringResource(R.string.group_mode_list_desc)
    GroupActivationStrategy.MANUAL -> stringResource(R.string.group_mode_manual_desc)
    GroupActivationStrategy.POOLED -> stringResource(R.string.group_mode_pooled_desc)
}

@Composable
private fun genModeLabel(mode: GroupGenerationMode): String = when (mode) {
    GroupGenerationMode.SWAP -> stringResource(R.string.group_gen_swap)
    GroupGenerationMode.APPEND -> stringResource(R.string.group_gen_append)
    GroupGenerationMode.APPEND_DISABLED -> stringResource(R.string.group_gen_append_disabled)
}
