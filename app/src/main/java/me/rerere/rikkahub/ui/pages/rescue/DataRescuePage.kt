package me.rerere.rikkahub.ui.pages.rescue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DatabaseRestore
import me.rerere.hugeicons.stroke.FileSearch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.LightConversationEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.components.BackupDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

/**
 * 数据救助：桌面长按图标即可进入的兜底页。
 *
 * 它只读数据库投影和消息节点，不碰角色卡正文，所以角色卡本身把应用拖垮时这里还能用。
 */
@Composable
fun DataRescuePage(
    vm: RescueVM = koinViewModel(),
    backupVM: BackupVM = koinViewModel(),
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val conversationCounts by vm.conversationCounts.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showRestartDialog by remember { mutableStateOf(false) }
    var assistantToDelete by remember { mutableStateOf<Assistant?>(null) }
    var repairConversation by remember { mutableStateOf<LightConversationEntity?>(null) }
    var crashStack by remember { mutableStateOf<String?>(null) }
    val hasCrash = remember { vm.hasCrashRecord() }

    val onActionDone: (Boolean) -> Unit = { ok ->
        toaster.show(
            if (ok) context.getString(R.string.rescue_page_action_success)
            else context.getString(R.string.rescue_page_action_failed),
            type = if (ok) ToastType.Success else ToastType.Error,
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.rescue_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.rescue_page_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                RescueBackupSection(vm = backupVM, onShowRestartDialog = { showRestartDialog = true })
            }

            item {
                val containerColor = MaterialTheme.colorScheme.errorContainer
                val contentColor = MaterialTheme.colorScheme.onErrorContainer
                CardGroup(title = { Text(stringResource(R.string.rescue_page_crash_title)) }) {
                    item(
                        onClick = { crashStack = vm.crashStackTrace() ?: vm.latestCrashLog() },
                        leadingContent = { CardGroupIcon(HugeIcons.Bug01, containerColor = containerColor, contentColor = contentColor) },
                        headlineContent = { Text(stringResource(R.string.rescue_page_crash_view)) },
                        supportingContent = {
                            Text(if (hasCrash) stringResource(R.string.rescue_page_crash_desc) else stringResource(R.string.rescue_page_crash_none))
                        },
                    )
                    item(
                        onClick = {
                            vm.clearCrashRecord()
                            toaster.show(context.getString(R.string.rescue_page_action_success), type = ToastType.Success)
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.DatabaseRestore) },
                        headlineContent = { Text(stringResource(R.string.rescue_page_crash_clear)) },
                    )
                }
            }

            item {
                val containerColor = MaterialTheme.colorScheme.secondaryContainer
                val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                CardGroup(title = { Text(stringResource(R.string.rescue_page_assistant_title)) }) {
                    settings.assistants.forEach { assistant ->
                        item(
                            onClick = { assistantToDelete = assistant },
                            leadingContent = {
                                UIAvatar(
                                    name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                                    value = assistant.avatar,
                                    modifier = Modifier.size(40.dp),
                                )
                            },
                            headlineContent = { Text(assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.rescue_page_assistant_count,
                                        conversationCounts[assistant.id] ?: 0,
                                    )
                                )
                            },
                            trailingContent = {
                                CardGroupIcon(HugeIcons.Delete01, containerColor = containerColor, contentColor = contentColor)
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.rescue_page_chat_repair_title)) }) {
                    if (conversations.isEmpty()) {
                        item(
                            leadingContent = { CardGroupIcon(HugeIcons.FileSearch) },
                            headlineContent = { Text(stringResource(R.string.rescue_page_chat_repair_empty)) },
                        )
                    }
                    conversations.forEach { conversation ->
                        item(
                            onClick = {
                                repairConversation = conversation
                                vm.loadMessages(Uuid.parse(conversation.id))
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.DatabaseRestore) },
                            headlineContent = {
                                Text(
                                    text = conversation.title.ifBlank { conversation.id },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = { Text(stringResource(R.string.rescue_page_chat_repair_desc)) },
                        )
                    }
                }
            }
        }
    }

    assistantToDelete?.let { assistant ->
        val count = conversationCounts[assistant.id] ?: 0
        RikkaConfirm(
            title = stringResource(R.string.rescue_page_assistant_title),
            text = stringResource(
                R.string.rescue_page_assistant_delete_confirm,
                assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                count,
            ),
            confirmText = stringResource(R.string.confirm),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                assistantToDelete = null
                vm.deleteAssistant(assistant, onActionDone)
            },
            onDismiss = { assistantToDelete = null },
        )
    }

    repairConversation?.let { conversation ->
        RescueMessageSheet(
            conversation = conversation,
            vm = vm,
            busy = busy,
            onDismiss = { repairConversation = null },
            onDone = onActionDone,
        )
    }

    crashStack?.let { stack ->
        AlertDialog(
            onDismissRequest = { crashStack = null },
            title = { Text(stringResource(R.string.rescue_page_crash_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxHeight(0.6f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(text = stack, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { crashStack = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    if (showRestartDialog) {
        BackupDialog()
    }
}

@Composable
private fun RescueMessageSheet(
    conversation: LightConversationEntity,
    vm: RescueVM,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDone: (Boolean) -> Unit,
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()
    var pendingDelete by remember { mutableStateOf<RescueMessage?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val conversationId = Uuid.parse(conversation.id)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = conversation.title.ifBlank { conversation.id },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.rescue_page_chat_repair_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.rescue_page_chat_clear))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            if (messages.isEmpty()) {
                Text(
                    text = stringResource(R.string.rescue_page_chat_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages, key = { it.messageId }) { message ->
                    CardGroup {
                        item(
                            onClick = { pendingDelete = message },
                            headlineContent = {
                                Text(
                                    text = message.preview.ifBlank { message.messageId },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = { Text(message.role) },
                            trailingContent = { CardGroupIcon(HugeIcons.Delete01) },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { message ->
        RikkaConfirm(
            title = stringResource(R.string.rescue_page_chat_repair_title),
            text = stringResource(R.string.rescue_page_message_delete_confirm),
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                pendingDelete = null
                vm.deleteMessage(conversationId, message, onDone)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (confirmClear) {
        RikkaConfirm(
            title = stringResource(R.string.rescue_page_chat_clear),
            text = stringResource(R.string.rescue_page_chat_clear_confirm),
            confirmText = stringResource(R.string.confirm),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                confirmClear = false
                vm.clearConversation(conversationId, onDone)
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun RikkaConfirm(
    title: String,
    text: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    me.rerere.ui.components.RikkaConfirmDialog(
        show = true,
        title = title,
        confirmText = confirmText,
        dismissText = dismissText,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        text = { Text(text) },
    )
}
