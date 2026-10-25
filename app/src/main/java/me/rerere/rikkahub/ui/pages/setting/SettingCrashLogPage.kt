package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.l10n

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingCrashLogPage(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(CrashHandler.listCrashLogs(context)) }
    var viewing by remember { mutableStateOf<File?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(l10n("崩溃日志", "Crash Logs")) },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = {
                            CrashHandler.clearCrashLogs(context)
                            logs = emptyList()
                        }) {
                            Text(l10n("清空", "Clear"), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        if (logs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🛡️", style = MaterialTheme.typography.headlineLarge)
                Text(
                    l10n("没有崩溃记录", "No crash logs"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    l10n("应用发生闪退时会在这里留下现场日志", "Crash reports will appear here after a crash"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        l10n("遇到闪退后把对应日志发出来即可定位问题", "Send the matching log after a crash to help debugging"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(logs, key = { it.absolutePath }) { file ->
                    ListItem(
                        headlineContent = { Text(formatCrashLogTime(file.name)) },
                        supportingContent = { Text(file.name) },
                        trailingContent = {
                            Text(
                                formatCrashLogSize(file.length()),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier.clickable { viewing = file },
                    )
                }
            }
        }
    }

    viewing?.let { file ->
        CrashLogDetailDialog(
            file = file,
            onDismiss = { viewing = null },
            onDeleted = {
                file.delete()
                logs = CrashHandler.listCrashLogs(context)
                viewing = null
            },
        )
    }
}

@Composable
private fun CrashLogDetailDialog(
    file: File,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val content = remember(file) { CrashHandler.readCrashLog(file) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(formatCrashLogTime(file.name)) },
        text = {
            val vScroll = rememberScrollState()
            val hScroll = rememberScrollState()
            Text(
                content.ifBlank { l10n("(日志为空)", "(empty log)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        },
        confirmButton = {
            Button(onClick = { shareCrashLog(context, file) }) {
                Text(l10n("分享", "Share"))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss) { Text(l10n("关闭", "Close")) }
                OutlinedButton(onClick = onDeleted) { Text(l10n("删除", "Delete")) }
            }
        },
    )
}

private fun shareCrashLog(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, l10n("分享崩溃日志", "Share crash log")))
    }
}

// 文件名形如 java-1730000000000.log，还原成可读时间
private fun formatCrashLogTime(name: String): String {
    val stem = name.removeSuffix(".log").substringAfter('-')
    val millis = stem.toLongOrNull() ?: return name
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(millis))
}

private fun formatCrashLogSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
