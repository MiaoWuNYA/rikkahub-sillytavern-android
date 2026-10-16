package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.export.SillyTavernRegexImporter
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.RegexGroup
import me.rerere.rikkahub.data.model.RegexGroupSource
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantRegexCard
import me.rerere.rikkahub.ui.pages.assistant.detail.SectionAddButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel

/**
 * 消息正则集中页。
 *
 * 全局正则按「文件夹」分组：手动新建/导入的落在「自定义」文件夹（始终存在），
 * 导入酒馆预设时按预设名自动建一个文件夹。角色卡自带的正则仍随卡保存，页面里按卡分组。
 */
@Composable
fun TavernRegexPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    val customFolderName = stringResource(R.string.tavern_regex_custom_folder)
    val unnamedFolder = stringResource(R.string.tavern_regex_unnamed_group)
    val importedMsg = stringResource(R.string.assistant_page_regex_imported)
    val importFailedMsg = stringResource(R.string.assistant_page_regex_import_failed)
    val importErrorMsg = stringResource(R.string.export_import_failed)

    var expandedCards by remember { mutableStateOf(emptySet<Uuid>()) }
    var expandedRegexes by remember { mutableStateOf(emptySet<Uuid>()) }
    var collapsedGroups by remember { mutableStateOf(emptySet<Uuid>()) }
    var importTargetId by remember { mutableStateOf<Uuid?>(null) }
    var groupImportTargetId by remember { mutableStateOf<Uuid?>(null) }
    var renameTarget by remember { mutableStateOf<RegexGroup?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<RegexGroup?>(null) }

    // 「自定义」文件夹始终存在，作为手动新建/导入脚本的默认落点
    LaunchedEffect(Unit) {
        vm.updateSettings { current ->
            if (current.regexGroups.any { it.source == RegexGroupSource.CUSTOM }) {
                current
            } else {
                current.copy(
                    regexGroups = listOf(
                        RegexGroup(name = customFolderName, source = RegexGroupSource.CUSTOM),
                    ) + current.regexGroups,
                )
            }
        }
    }

    fun updateGroups(transform: (List<RegexGroup>) -> List<RegexGroup>) {
        vm.updateSettings { current -> current.copy(regexGroups = transform(current.regexGroups)) }
    }

    // 只回写某个分组的 regexes：其余字段以设置里的最新值为准
    fun updateGroupRegexes(groupId: Uuid, regexes: List<AssistantRegex>) {
        updateGroups { groups -> groups.map { if (it.id == groupId) it.copy(regexes = regexes) else it } }
    }

    // 只回写 regexes：卡片其他字段以设置里的最新值为准
    fun applyRegexes(cardId: Uuid, regexes: List<AssistantRegex>) {
        vm.updateSettings { current ->
            current.copy(
                assistants = current.assistants.map { card ->
                    if (card.id == cardId) card.copy(regexes = regexes) else card
                },
            )
        }
    }

    val groupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetGroupId = groupImportTargetId
        groupImportTargetId = null
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error(importErrorMsg)
                    val parsed = SillyTavernRegexImporter.parse(text)
                    if (parsed.isEmpty()) error(importFailedMsg)
                    parsed
                }
            }
            result.onSuccess { parsed ->
                if (targetGroupId != null) {
                    updateGroups { groups ->
                        groups.map {
                            if (it.id == targetGroupId) it.copy(regexes = it.regexes + parsed) else it
                        }
                    }
                }
                toaster.show(importedMsg.format(parsed.size))
            }.onFailure {
                toaster.show(importErrorMsg.format(it.message))
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetId = importTargetId
        importTargetId = null
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error(importErrorMsg)
                    val parsed = SillyTavernRegexImporter.parse(text)
                    if (parsed.isEmpty()) error(importFailedMsg)
                    parsed
                }
            }
            result.onSuccess { parsed ->
                if (targetId != null) {
                    vm.updateSettings { current ->
                        current.copy(
                            assistants = current.assistants.map { card ->
                                if (card.id == targetId) card.copy(regexes = card.regexes + parsed) else card
                            },
                        )
                    }
                }
                toaster.show(importedMsg.format(parsed.size))
            }.onFailure {
                toaster.show(importErrorMsg.format(it.message))
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tavern_regex)) },
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
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("regexIntro") {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    formItem {
                        Text(
                            text = stringResource(R.string.tavern_regex_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item("newGroup") {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        onClick = { updateGroups { it + RegexGroup(source = RegexGroupSource.CUSTOM) } },
                        leadingContent = { CardGroupIcon(HugeIcons.FolderAdd) },
                        headlineContent = { Text(stringResource(R.string.tavern_regex_new_folder)) },
                    )
                }
            }

            items(settings.regexGroups, key = { it.id }) { group ->
                val collapsed = group.id in collapsedGroups
                val isCustom = group.source == RegexGroupSource.CUSTOM
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        onClick = {
                            collapsedGroups =
                                if (collapsed) collapsedGroups - group.id else collapsedGroups + group.id
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Folder01) },
                        headlineContent = {
                            Text(
                                text = group.name.ifBlank { unnamedFolder },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.tavern_regex_group_count, group.regexes.size))
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (group.regexes.isNotEmpty()) {
                                    Switch(
                                        checked = group.regexes.all { it.enabled },
                                        onCheckedChange = { enabled ->
                                            updateGroupRegexes(
                                                group.id,
                                                group.regexes.map { it.copy(enabled = enabled) },
                                            )
                                        },
                                    )
                                }
                                ItemActionMenu(
                                    actions = buildList {
                                        add(
                                            ItemAction(
                                                text = stringResource(R.string.tavern_regex_folder_rename),
                                                icon = HugeIcons.Edit01,
                                            ) {
                                                renameTarget = group
                                                renameText = group.name
                                            },
                                        )
                                        if (!isCustom) {
                                            add(
                                                ItemAction(
                                                    text = stringResource(R.string.tavern_regex_folder_delete),
                                                    icon = HugeIcons.Delete01,
                                                    destructive = true,
                                                ) { deleteTarget = group },
                                            )
                                        }
                                    },
                                )
                                Icon(
                                    imageVector = HugeIcons.ArrowDown01,
                                    contentDescription = null,
                                    modifier = Modifier.rotate(if (collapsed) 0f else 180f),
                                )
                            }
                        },
                    )
                    if (!collapsed) {
                        group.regexes.forEachIndexed { index, regex ->
                            formItem {
                                AssistantRegexCard(
                                    regex = regex,
                                    regexes = group.regexes,
                                    index = index,
                                    shape = MaterialTheme.shapes.large,
                                    expanded = regex.id in expandedRegexes,
                                    onExpandedChange = { open ->
                                        expandedRegexes =
                                            if (open) expandedRegexes + regex.id else expandedRegexes - regex.id
                                    },
                                    onUpdate = { newRegexes -> updateGroupRegexes(group.id, newRegexes) },
                                )
                            }
                        }
                        formItem {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SectionAddButton(
                                    onClick = {
                                        updateGroupRegexes(group.id, group.regexes + AssistantRegex(id = Uuid.random()))
                                    },
                                )
                                IconButton(
                                    onClick = {
                                        groupImportTargetId = group.id
                                        groupImportLauncher.launch(arrayOf("*/*"))
                                    },
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.FileImport,
                                        contentDescription = stringResource(R.string.assistant_page_regex_import),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (settings.assistants.isEmpty()) {
                item("regexEmpty") {
                    CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                        formItem { Text(stringResource(R.string.tavern_regex_empty)) }
                    }
                }
            }

            items(settings.assistants, key = { it.id }) { card ->
                val expanded = card.id in expandedCards
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(card.name.ifBlank { stringResource(R.string.setting_memory_unnamed_assistant) })
                    },
                ) {
                    item(
                        onClick = {
                            expandedCards = if (expanded) expandedCards - card.id else expandedCards + card.id
                        },
                        headlineContent = { Text(stringResource(R.string.assistant_page_regex_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.tavern_regex_card_count, card.regexes.size))
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (card.regexes.isNotEmpty()) {
                                    Switch(
                                        checked = card.regexes.all { it.enabled },
                                        onCheckedChange = { enabled ->
                                            applyRegexes(
                                                card.id,
                                                card.regexes.map { it.copy(enabled = enabled) },
                                            )
                                        },
                                    )
                                }
                                Icon(
                                    imageVector = HugeIcons.ArrowDown01,
                                    contentDescription = null,
                                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                                )
                            }
                        },
                    )
                    if (expanded) {
                        card.regexes.forEachIndexed { index, regex ->
                            formItem {
                                AssistantRegexCard(
                                    regex = regex,
                                    regexes = card.regexes,
                                    index = index,
                                    shape = MaterialTheme.shapes.large,
                                    expanded = regex.id in expandedRegexes,
                                    onExpandedChange = { open ->
                                        expandedRegexes =
                                            if (open) expandedRegexes + regex.id else expandedRegexes - regex.id
                                    },
                                    onUpdate = { newRegexes -> applyRegexes(card.id, newRegexes) },
                                )
                            }
                        }
                        formItem {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SectionAddButton(
                                    onClick = {
                                        applyRegexes(card.id, card.regexes + AssistantRegex(id = Uuid.random()))
                                    },
                                )
                                IconButton(
                                    onClick = {
                                        importTargetId = card.id
                                        importLauncher.launch(arrayOf("*/*"))
                                    },
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.FileImport,
                                        contentDescription = stringResource(R.string.assistant_page_regex_import),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.tavern_regex_folder_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tavern_regex_folder_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = renameText.trim()
                        updateGroups { groups ->
                            groups.map { if (it.id == target.id) it.copy(name = name) else it }
                        }
                        renameTarget = null
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deleteTarget?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.tavern_regex_folder_delete),
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                updateGroups { groups -> groups.filterNot { it.id == target.id } }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.tavern_regex_delete_group_confirm, target.name.ifBlank { unnamedFolder }))
        }
    }
}
