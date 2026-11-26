package me.rerere.rikkahub.ui.pages.rescue

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.components.BackupActionIcon
import me.rerere.rikkahub.ui.pages.backup.components.backupContentItem
import me.rerere.ui.toast.ToastType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// 与设置页的备份页同一套导出/导入，只是嵌在数据救助里省得再去翻菜单
@Composable
fun RescueBackupSection(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit,
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selectedItems by vm.localBackupItems.collectAsStateWithLifecycle()
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var importType by remember { mutableStateOf("local") }
    var exportType by remember { mutableStateOf("local") }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    val exportFile = if (exportType == "upstream") vm.exportUpstreamToFile() else vm.exportToFile()
                    context.contentResolver.openOutputStream(targetUri)?.use { output ->
                        FileInputStream(exportFile).use { it.copyTo(output) }
                    }
                    exportFile.delete()
                    toaster.show(context.getString(R.string.backup_page_backup_success), type = ToastType.Success)
                }.onFailure { e ->
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error,
                    )
                }
                isExporting = false
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            scope.launch {
                isRestoring = true
                runCatching {
                    when (importType) {
                        "chatbox" -> {
                            val tempFile = File(context.cacheDir, "rescue_chatbox_${System.currentTimeMillis()}.zip")
                            try {
                                copyToTemp(context.contentResolver.openInputStream(sourceUri), tempFile)
                                vm.restoreFromChatBox(tempFile)
                            } finally {
                                tempFile.delete()
                            }
                        }

                        "cherry" -> {
                            val tempFile = File(context.cacheDir, "rescue_cherry_${System.currentTimeMillis()}.zip")
                            try {
                                copyToTemp(context.contentResolver.openInputStream(sourceUri), tempFile)
                                vm.restoreFromCherryStudio(tempFile)
                            } finally {
                                tempFile.delete()
                            }
                        }

                        "deepseek" -> withContext(Dispatchers.IO) {
                            val tempFile = File.createTempFile("rescue_deepseek_", ".zip", context.cacheDir)
                            try {
                                requireNotNull(context.contentResolver.openInputStream(sourceUri)) {
                                    context.getString(R.string.backup_page_import_deepseek_read_error)
                                }.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                                // 导入结果这里只报成功与否，详细数字留给设置页
                                vm.restoreFromDeepSeek(tempFile)
                            } finally {
                                tempFile.delete()
                            }
                            Unit
                        }

                        else -> {
                            val tempFile = File(context.cacheDir, "rescue_restore_${System.currentTimeMillis()}.zip")
                            try {
                                copyToTemp(context.contentResolver.openInputStream(sourceUri), tempFile)
                                vm.restoreFromLocalFile(tempFile)
                            } finally {
                                tempFile.delete()
                            }
                        }
                    }

                    toaster.show(context.getString(R.string.backup_page_restore_success), type = ToastType.Success)
                    onShowRestartDialog()
                }.onFailure { e ->
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error,
                    )
                }
                isRestoring = false
            }
        }
    }

    val timestamp = remember { LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) }

    CardGroup(title = { Text(stringResource(R.string.backup_page_local_backup_export)) }) {
        backupContentItem(
            options = WebDavConfig.BackupItem.entries,
            selected = selectedItems,
            onChange = { vm.updateLocalBackupItems(it) },
        ) { item ->
            when (item) {
                WebDavConfig.BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                WebDavConfig.BackupItem.FILES -> stringResource(R.string.backup_page_files)
            }
        }
        item(
            onClick = {
                exportType = "local"
                createDocumentLauncher.launch("rikkahub_backup_$timestamp.zip")
            },
            leadingContent = { BackupActionIcon(HugeIcons.FileExport, loading = isExporting && exportType == "local") },
            headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
            supportingContent = { Text(stringResource(R.string.backup_page_export_desc)) },
        )
        item(
            onClick = {
                exportType = "upstream"
                createDocumentLauncher.launch("rikkahub_upstream_backup_$timestamp.zip")
            },
            leadingContent = { BackupActionIcon(HugeIcons.FileExport, loading = isExporting && exportType == "upstream") },
            headlineContent = { Text(stringResource(R.string.backup_page_export_upstream)) },
            supportingContent = { Text(stringResource(R.string.backup_page_export_upstream_desc)) },
        )
        item(
            onClick = { showImportConfirm = true },
            leadingContent = { BackupActionIcon(HugeIcons.FileImport, loading = isRestoring) },
            headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            supportingContent = { Text(stringResource(R.string.backup_page_import_desc)) },
        )
    }

    CardGroup(title = { Text(stringResource(R.string.backup_page_import_from_other_app)) }) {
        item(
            onClick = {
                importType = "deepseek"
                openDocumentLauncher.launch(arrayOf("application/zip"))
            },
            leadingContent = { BackupActionIcon(HugeIcons.FileImport) },
            headlineContent = { Text("DeepSeek") },
            supportingContent = { Text(stringResource(R.string.backup_page_import_deepseek_desc)) },
        )
        item(
            onClick = {
                importType = "chatbox"
                openDocumentLauncher.launch(arrayOf("application/zip"))
            },
            leadingContent = { BackupActionIcon(HugeIcons.FileImport) },
            headlineContent = { Text("Chatbox") },
            supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
        )
        item(
            onClick = {
                importType = "cherry"
                openDocumentLauncher.launch(arrayOf("application/zip"))
            },
            leadingContent = { BackupActionIcon(HugeIcons.FileImport) },
            headlineContent = { Text("Cherry Studio") },
            supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_desc)) },
        )
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            text = { Text(stringResource(R.string.backup_page_import_overwrite_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        importType = "local"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun copyToTemp(input: java.io.InputStream?, target: File) {
    requireNotNull(input) { "empty stream" }
    input.use { source -> FileOutputStream(target).use { output -> source.copyTo(output) } }
}
