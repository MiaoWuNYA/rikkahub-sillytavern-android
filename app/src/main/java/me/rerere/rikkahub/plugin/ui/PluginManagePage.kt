package me.rerere.rikkahub.plugin.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Reload
import me.rerere.rikkahub.R
import me.rerere.rikkahub.plugin.model.PluginFolder
import me.rerere.rikkahub.plugin.model.PluginInfo
import org.koin.androidx.compose.koinViewModel

private const val TAG = "PluginManagePage"

/**
 * 插件管理页面（文件夹列表页）
 * 显示文件夹列表和未分组插件，点进文件夹才看到插件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagePage(
    onNavigateToFolder: (String) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    viewModel: PluginViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val plugins by viewModel.plugins.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val operationState by viewModel.operationState.collectAsState()
    val pendingImport by viewModel.pendingImport.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val importSuccessMessage = stringResource(R.string.plugin_import_success)
    val importFailedTemplate = stringResource(R.string.plugin_import_failed)

    val hasStoragePermission = remember { mutableStateOf(checkStoragePermission()) }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showRenameFolderDialog by remember { mutableStateOf<PluginFolder?>(null) }
    var showDeleteFolderConfirm by remember { mutableStateOf<PluginFolder?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStoragePermission.value = checkStoragePermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.previewPlugin(it) }
    }

    LaunchedEffect(importState) {
        when (importState) {
            is PluginViewModel.ImportState.Success -> {
                snackbarHostState.showSnackbar(message = importSuccessMessage)
                viewModel.resetImportState()
            }
            is PluginViewModel.ImportState.Error -> {
                snackbarHostState.showSnackbar(
                    message = importFailedTemplate.format(
                        (importState as PluginViewModel.ImportState.Error).message
                    )
                )
                viewModel.resetImportState()
            }
            else -> {}
        }
    }

    LaunchedEffect(operationState) {
        when (operationState) {
            is PluginViewModel.OperationState.Error -> {
                snackbarHostState.showSnackbar(
                    message = (operationState as PluginViewModel.OperationState.Error).message
                )
                viewModel.resetOperationState()
            }
            else -> {}
        }
    }

    LaunchedEffect(hasStoragePermission.value) {
        if (hasStoragePermission.value) {
            viewModel.refreshPlugins()
        }
    }

    pendingImport?.let { pending ->
        val manifest = pending.manifest
        AlertDialog(
            onDismissRequest = { viewModel.cancelImportPreview() },
            title = { Text(stringResource(R.string.plugin_install_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.plugin_install_confirm_name, manifest.name))
                    Text(stringResource(R.string.plugin_install_confirm_version, manifest.version))
                    Text(stringResource(R.string.plugin_install_confirm_author, manifest.author))
                    if (manifest.description.isNotBlank()) {
                        Text(stringResource(R.string.plugin_install_confirm_desc, manifest.description))
                    }
                    if (manifest.tools.isNotEmpty()) {
                        Text(
                            stringResource(R.string.plugin_install_confirm_tools),
                            style = MaterialTheme.typography.labelLarge
                        )
                        manifest.tools.forEach { tool ->
                            Text("• ${tool.name}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (manifest.allowedHosts.isNotEmpty()) {
                        Text(
                            stringResource(R.string.plugin_install_confirm_hosts),
                            style = MaterialTheme.typography.labelLarge
                        )
                        manifest.allowedHosts.forEach { host ->
                            Text("• $host", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(
                            stringResource(R.string.plugin_install_confirm_no_network),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImport() }) {
                    Text(stringResource(R.string.plugin_install_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImportPreview() }) {
                    Text(stringResource(R.string.plugin_action_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plugin_page_title)) },
                actions = {
                    if (hasStoragePermission.value) {
                        IconButton(onClick = { viewModel.refreshPlugins() }) {
                            Icon(imageVector = HugeIcons.Reload, contentDescription = stringResource(R.string.plugin_page_refresh))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (hasStoragePermission.value) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateFolderDialog = true },
                    icon = { Icon(HugeIcons.PlusSign, null) },
                    text = { Text(stringResource(R.string.plugin_page_new_folder)) }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!hasStoragePermission.value) {
                StoragePermissionGate(
                    onGrantClick = { openStoragePermissionSettings(context) }
                )
            } else if (isLoading && plugins.isEmpty() && folders.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                PluginFolderContent(
                    folders = folders,
                    plugins = plugins,
                    onFolderClick = { folder -> onNavigateToFolder(folder.id) },
                    onFolderLongClick = { folder -> showRenameFolderDialog = folder },
                    onPluginClick = { plugin -> onNavigateToDetail(plugin.manifest.id) },
                    onTogglePlugin = { plugin, enabled ->
                        viewModel.togglePlugin(plugin.manifest.id, enabled)
                    },
                    onDeletePlugin = { plugin -> viewModel.deletePlugin(plugin.manifest.id) },
                    onImportPlugin = { filePickerLauncher.launch("application/zip") }
                )
            }

            AnimatedVisibility(
                visible = importState is PluginViewModel.ImportState.Loading,
                modifier = Modifier.align(Alignment.Center)
            ) {
                CircularProgressIndicator()
            }
        }
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { name ->
                viewModel.createFolder(name)
                showCreateFolderDialog = false
            }
        )
    }

    showRenameFolderDialog?.let { folder ->
        RenameFolderDialog(
            folder = folder,
            onDismiss = { showRenameFolderDialog = null },
            onConfirm = { newName ->
                viewModel.renameFolder(folder.id, newName)
                showRenameFolderDialog = null
            },
            onDelete = {
                showRenameFolderDialog = null
                showDeleteFolderConfirm = folder
            }
        )
    }

    showDeleteFolderConfirm?.let { folder ->
        val count = plugins.count { it.folderId == folder.id }
        AlertDialog(
            onDismissRequest = { showDeleteFolderConfirm = null },
            title = { Text(stringResource(R.string.plugin_delete_folder_title)) },
            text = {
                Text(
                    if (count > 0) {
                        stringResource(
                            R.string.plugin_delete_folder_message,
                            folder.name,
                            count
                        )
                    } else {
                        stringResource(R.string.plugin_delete_folder_message_empty, folder.name)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder.id)
                    showDeleteFolderConfirm = null
                }) {
                    Text(stringResource(R.string.plugin_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFolderConfirm = null }) {
                    Text(stringResource(R.string.plugin_action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PluginFolderContent(
    folders: List<PluginFolder>,
    plugins: List<PluginInfo>,
    onFolderClick: (PluginFolder) -> Unit,
    onFolderLongClick: (PluginFolder) -> Unit,
    onPluginClick: (PluginInfo) -> Unit,
    onTogglePlugin: (PluginInfo, Boolean) -> Unit,
    onDeletePlugin: (PluginInfo) -> Unit,
    onImportPlugin: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.plugin_import_security_warning_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = stringResource(R.string.plugin_import_security_warning_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.plugin_page_ungrouped),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                IconButton(onClick = onImportPlugin) {
                    Icon(HugeIcons.PlusSign, contentDescription = stringResource(R.string.plugin_import))
                }
            }
        }

        if (folders.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.plugin_page_folders),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(items = folders, key = { it.id }) { folder ->
                val count = plugins.count { it.folderId == folder.id }
                FolderCard(
                    folder = folder,
                    pluginCount = count,
                    onClick = { onFolderClick(folder) },
                    onLongClick = { onFolderLongClick(folder) }
                )
            }
        }

        val ungroupedPlugins = plugins.filter { it.folderId == null }

        if (ungroupedPlugins.isEmpty() && folders.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(R.string.plugin_page_ungrouped_empty))
                        Text(
                            text = stringResource(R.string.plugin_page_create_folder_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(items = ungroupedPlugins, key = { it.manifest.id }) { plugin ->
            PluginCard(
                plugin = plugin,
                onClick = { onPluginClick(plugin) },
                onToggle = { enabled -> onTogglePlugin(plugin, enabled) },
                onDelete = { onDeletePlugin(plugin) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: PluginFolder,
    pluginCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = HugeIcons.Folder01,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.plugin_folder_plugin_count, pluginCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun PluginCard(
    plugin: PluginInfo,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = plugin.manifest.icon,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(end = 16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.manifest.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "v${plugin.manifest.version} · ${plugin.manifest.author}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (plugin.loadError != null) {
                    Text(
                        text = stringResource(R.string.plugin_load_error, plugin.loadError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Switch(
                checked = plugin.isEnabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    imageVector = HugeIcons.Delete02,
                    contentDescription = stringResource(R.string.plugin_action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.plugin_delete_confirm_title)) },
            text = { Text(stringResource(R.string.plugin_delete_confirm_message, plugin.manifest.name)) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text(stringResource(R.string.plugin_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.plugin_action_cancel))
                }
            }
        )
    }
}

@Composable
private fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_create_folder_title)) },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text(stringResource(R.string.plugin_create_folder_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(folderName) },
                enabled = folderName.isNotBlank()
            ) { Text(stringResource(R.string.plugin_action_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.plugin_action_cancel)) }
        }
    )
}

@Composable
private fun RenameFolderDialog(
    folder: PluginFolder,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onDelete: () -> Unit
) {
    var folderName by remember(folder.id) { mutableStateOf(folder.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_edit_folder_title)) },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text(stringResource(R.string.plugin_create_folder_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(folderName) },
                enabled = folderName.isNotBlank() && folderName != folder.name
            ) { Text(stringResource(R.string.plugin_action_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.plugin_action_delete), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.plugin_action_cancel)) }
            }
        }
    )
}

private fun checkStoragePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun openStoragePermissionSettings(context: android.content.Context) {
    try {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e(TAG, "openStoragePermissionSettings: precise intent failed, pkg=${context.packageName}", e)
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (ex: Exception) {
            Log.e(TAG, "openStoragePermissionSettings: fallback intent also failed", ex)
        }
    }
}

@Composable
private fun StoragePermissionGate(onGrantClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.plugin_storage_permission_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.plugin_storage_permission_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onGrantClick) {
            Text(stringResource(R.string.plugin_storage_permission_grant))
        }
    }
}
