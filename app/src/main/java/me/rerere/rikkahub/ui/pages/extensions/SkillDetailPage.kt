package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import me.rerere.rikkahub.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.RefreshCw
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SkillDetailPage(skillName: String) {
    val vm = koinViewModel<SkillDetailVM>()
    val skillsVM = koinViewModel<SkillsVM>()
    LaunchedEffect(skillName) { vm.init(skillName) }

    val tree by vm.tree.collectAsStateWithLifecycle()
    val updating by vm.updating.collectAsStateWithLifecycle()
    val hasUpdateSource by vm.hasUpdateSource.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val context = LocalContext.current

    var editingFile by remember { mutableStateOf<SkillFile?>(null) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillFile?>(null) }
    var deleteDirTarget by remember { mutableStateOf<SkillFileNode.DirNode?>(null) }
    val deleteFailedMsg = stringResource(R.string.skill_detail_page_delete_failed)

    // 更新状态
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdatePicker by remember { mutableStateOf(false) }
    var scannedForUpdate by remember { mutableStateOf<List<SkillsVM.GitHubSkillInfo>>(emptyList()) }
    var selectedForUpdate by remember { mutableStateOf(setOf<Int>()) }
    var updateRepoUrl by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(skillName) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Lucide.Plus, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding + PaddingValues(8.dp)),
        ) {
            // 元数据头部
            val skill = vm.currentSkill
            if (skill != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            skill.category?.let { cat ->
                                SuggestionChip(onClick = {}, label = { Text(cat) })
                            }
                            if (skill.version != null) {
                                SuggestionChip(onClick = {}, label = { Text("v${skill.version}") })
                            }
    val totalFiles = skill.linkedFiles.values.sumOf { it.size }
    if (totalFiles > 0) {
        SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.skill_detail_page_file_count, totalFiles)) })
    }
}

// 命令列表
if (skill.commands.isNotEmpty()) {
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.skill_detail_page_slash_commands),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    skill.commands.forEach { cmd ->
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "/${cmd.name}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            if (cmd.argumentHint.isNotBlank()) {
                Text(
                    cmd.argumentHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// 工具权限
if (skill.allowedTools.isNotEmpty()) {
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        skill.allowedTools.forEach { tool ->
            SuggestionChip(
                onClick = {},
                label = { Text(tool, style = MaterialTheme.typography.labelSmall) },
                shape = RoundedCornerShape(4.dp),
            )
        }
    }
}

// 技能属性标签
Spacer(Modifier.height(4.dp))
Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    if (skill.userInvocable) {
        SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.skill_detail_page_user_invocable)) })
    }
    if (skill.disableModelInvocation) {
        SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.skill_detail_page_disable_model_invocation)) })
    }
    if (skill.injectPosition != null) {
        SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.skill_detail_page_inject_position, skill.injectPosition)) })
    }
}

// MCP 服务器
if (skill.mcpServers.isNotEmpty()) {
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.skill_detail_page_mcp_servers),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    skill.mcpServers.forEach { server ->
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                server.name,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "[${server.transport}]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
                        if (skill.triggers.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.skill_detail_page_triggers, skill.triggers.joinToString(", ")),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }

                        // region 更新
                        if (hasUpdateSource) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End,
                            ) {
                                if (updating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            if (!isCheckingUpdate) {
                                                isCheckingUpdate = true
                                                skillsVM.scanForUpdates(skillName) { success, skills, repoUrl ->
                                                    isCheckingUpdate = false
                                                    if (success) {
                                                        scannedForUpdate = skills
                                                        updateRepoUrl = repoUrl
                                                        // 默认勾选有更新的和新增的
                                                        selectedForUpdate = skills.indices
                                                            .filter { skills[it].hasUpdate || skills[it].isNew }
                                                            .toSet()
                                                        showUpdatePicker = true
                                                    } else {
                                                        toaster.show(repoUrl)
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !isCheckingUpdate,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    ) {
                                        if (isCheckingUpdate) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(
                                                HugeIcons.Download01,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(stringResource(R.string.skill_detail_page_check_update), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                        // endregion
                    }
                }
            }

            FileTree(
                nodes = tree,
                depth = 0,
                onEdit = { editingFile = it },
                onDelete = { deleteTarget = it },
                onDeleteDir = { deleteDirTarget = it },
            )
        }
    }

    editingFile?.let { skillFile ->
        EditFileDialog(
            skillFile = skillFile,
            initialContent = remember(skillFile.relativePath) { vm.readFile(skillFile) },
            onDismiss = { editingFile = null },
            onConfirm = { content ->
                vm.saveFile(skillFile.relativePath, content) { error ->
                    if (error == null) editingFile = null
                    else toaster.show(error)
                }
            },
        )
    }

    if (showAddDialog) {
        AddFileDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { fileName, content ->
                vm.saveFile(fileName, content) { error ->
                    if (error == null) showAddDialog = false
                    else toaster.show(error)
                }
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.skill_detail_page_delete_file),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { skillFile ->
                vm.deleteFile(skillFile) { success ->
                    if (!success) toaster.show(deleteFailedMsg)
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.skill_detail_page_delete_confirm, deleteTarget?.relativePath ?: ""))
    }

    RikkaConfirmDialog(
        show = deleteDirTarget != null,
        title = stringResource(R.string.skill_detail_page_delete_file),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteDirTarget?.let { node ->
                vm.deleteDir(node) { success ->
                    if (!success) toaster.show(deleteFailedMsg)
                }
            }
            deleteDirTarget = null
        },
        onDismiss = { deleteDirTarget = null },
    ) {
        Text(stringResource(R.string.skill_detail_page_delete_confirm, deleteDirTarget?.relativePath ?: ""))
    }

    // 更新 skill 多选弹窗
    if (showUpdatePicker && scannedForUpdate.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showUpdatePicker = false },
            title = {
                Text(
                    stringResource(R.string.skills_page_found_skills, scannedForUpdate.size),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        updateRepoUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = {
                            selectedForUpdate = if (selectedForUpdate.size == scannedForUpdate.size) emptySet()
                            else scannedForUpdate.indices.toSet()
                        }) {
                            Text(
                                if (selectedForUpdate.size == scannedForUpdate.size) stringResource(R.string.skills_page_deselect_all)
                                else stringResource(R.string.skills_page_select_all),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            stringResource(R.string.skills_page_selected_count, selectedForUpdate.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    ) {
                        items(scannedForUpdate.size) { idx ->
                            val skillInfo = scannedForUpdate[idx]
                            val selected = idx in selectedForUpdate
                            Surface(
                                onClick = {
                                    selectedForUpdate = if (selected)
                                        selectedForUpdate - idx
                                    else
                                        selectedForUpdate + idx
                                },
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        else MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    ) {
                                        if (selected) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                Text("✓", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                                    color = MaterialTheme.colorScheme.onPrimary)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(skillInfo.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                            if (skillInfo.hasUpdate) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.errorContainer) {
                                                    Text(stringResource(R.string.skills_page_has_update), modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                                }
                                            }
                                            if (skillInfo.isNew) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                                                    Text(stringResource(R.string.skills_page_new_badge), modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                                }
                                            }
                                        }
                                        if (skillInfo.description.isNotBlank()) {
                                            Text(skillInfo.description, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                                        }
                                        Text(skillInfo.mdPath, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            maxLines = 1, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (selectedForUpdate.isNotEmpty() && !updating) {
                            showUpdatePicker = false
                            vm.setUpdating(true)
                            val toUpdate = selectedForUpdate.map { scannedForUpdate[it] }
                            // 逐个更新（仅选中的）
                            updateNext(
                                0, toUpdate, skillsVM, vm, toaster, updateRepoUrl,
                                updateDoneText = context.getString(R.string.skill_detail_page_update_done_count, toUpdate.size),
                                installFailedText = context.getString(R.string.skill_detail_page_install_failed),
                                updateFailedText = context.getString(R.string.skill_detail_page_update_failed),
                            )
                        }
                    },
                    enabled = selectedForUpdate.isNotEmpty() && !updating,
                ) {
                    Text(
                        if (updating) stringResource(R.string.skill_detail_page_updating)
                        else stringResource(R.string.skill_detail_page_update_selected, selectedForUpdate.size)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun updateNext(
    index: Int,
    skills: List<SkillsVM.GitHubSkillInfo>,
    skillsVM: SkillsVM,
    detailVM: SkillDetailVM,
    toaster: com.dokar.sonner.ToasterState,
    repoUrl: String = "",
    updateDoneText: String = "",
    installFailedText: String = "",
    updateFailedText: String = "",
) {
    if (index >= skills.size) {
        detailVM.setUpdating(false)
        detailVM.loadFiles()
        detailVM.refreshSourceStatus()
        toaster.show(updateDoneText)
        return
    }
    val skill = skills[index]
    if (skill.isNew) {
        // 新增（未安装）skill → 走安装路径
        val fullUrl = if (repoUrl.startsWith("http")) repoUrl else "https://github.com/$repoUrl"
        skillsVM.downloadSkillFromGitHub(fullUrl, skill) { ok, msg ->
            if (ok) {
                updateNext(index + 1, skills, skillsVM, detailVM, toaster, repoUrl, updateDoneText, installFailedText, updateFailedText)
            } else {
                detailVM.setUpdating(false)
                toaster.show("$installFailedText: $msg")
            }
        }
    } else {
        // 已安装 skill → 走更新路径
        skillsVM.updateSkillFromGitHub(skill.name) { ok, msg ->
            if (ok) {
                updateNext(index + 1, skills, skillsVM, detailVM, toaster, repoUrl, updateDoneText, installFailedText, updateFailedText)
            } else {
                detailVM.setUpdating(false)
                toaster.show("$updateFailedText: $msg")
            }
        }
    }
}

@Composable
private fun FileTree(
    nodes: List<SkillFileNode>,
    depth: Int,
    onEdit: (SkillFile) -> Unit,
    onDelete: (SkillFile) -> Unit,
    onDeleteDir: (SkillFileNode.DirNode) -> Unit,
) {
    nodes.fastForEach { node ->
        when (node) {
            is SkillFileNode.FileNode -> FileItem(
                skillFile = node.skillFile,
                depth = depth,
                onEdit = { onEdit(node.skillFile) },
                onDelete = { onDelete(node.skillFile) },
            )

            is SkillFileNode.DirNode -> DirItem(
                node = node,
                depth = depth,
                onEdit = onEdit,
                onDelete = onDelete,
                onDeleteDir = { onDeleteDir(node) },
            )
        }
    }
}

@Composable
private fun FileItem(
    skillFile: SkillFile,
    depth: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (16 + depth * 20).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Lucide.FileText,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = skillFile.file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            Text(
                text = "${skillFile.file.length()} B",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Lucide.FilePen,
                    contentDescription = stringResource(R.string.edit),
                    modifier = Modifier.size(16.dp),
                )
            }
            if (skillFile.relativePath != "SKILL.md") {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Lucide.Trash2,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DirItem(
    node: SkillFileNode.DirNode,
    depth: Int,
    onEdit: (SkillFile) -> Unit,
    onDelete: (SkillFile) -> Unit,
    onDeleteDir: () -> Unit,
) {
    var expanded by rememberSaveable(node.relativePath) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = (16 + depth * 20).dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (expanded) Lucide.FolderOpen else Lucide.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDeleteDir, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Lucide.Trash2,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    FileTree(
                        nodes = node.children,
                        depth = depth + 1,
                        onEdit = onEdit,
                        onDelete = onDelete,
                        onDeleteDir = { onDeleteDir() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditFileDialog(
    skillFile: SkillFile,
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (content: String) -> Unit,
) {
    var content by rememberSaveable(skillFile.relativePath) { mutableStateOf(initialContent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skillFile.relativePath, fontFamily = FontFamily.Monospace) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.skill_detail_page_content)) },
                minLines = 10,
                maxLines = 20,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(content) }) { Text(stringResource(R.string.skill_detail_page_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun AddFileDialog(
    onDismiss: () -> Unit,
    onConfirm: (fileName: String, content: String) -> Unit,
) {
    var fileName by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    val fileNameError = fileName.isNotBlank() && (fileName.contains('\\'))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skill_detail_page_new_file)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(stringResource(R.string.skill_detail_page_file_name)) },
                    placeholder = { Text("examples/basic.md", fontFamily = FontFamily.Monospace) },
                    supportingText = {
                        if (fileNameError) Text(
                            stringResource(R.string.skill_detail_page_file_name_invalid),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    isError = fileNameError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.skill_detail_page_content)) },
                    minLines = 6,
                    maxLines = 14,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(fileName.trim(), content) },
                enabled = fileName.isNotBlank() && !fileNameError,
            ) {
                Text(stringResource(R.string.skill_detail_page_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}