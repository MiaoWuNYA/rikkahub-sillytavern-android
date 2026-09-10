package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.voice.VideoCallArchiveEntry
import me.rerere.rikkahub.ui.pages.voice.VideoCallArchiveStore
import kotlin.math.max

private val VIDEO_CALL_ARCHIVE_REGEX = Regex("\\[VIDEO_CALL_ARCHIVE:([^\\]]+)]")

fun extractVideoCallArchiveSessionId(text: String): String? =
    VIDEO_CALL_ARCHIVE_REGEX.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

@Composable
fun VideoCallArchiveCard(
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { VideoCallArchiveStore(context) }
    val archive = remember(sessionId) { store.find(sessionId) }
    var showDetails by remember { mutableStateOf(false) }

    val durationLabel = archive?.let(::durationLabel).orEmpty().ifBlank { "已结束" }
    val turns = archive?.messages?.count { it.role == "user" } ?: 0
    val assistantName = archive?.assistantName.orEmpty().ifBlank { "视频通话" }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = archive != null) { showDetails = true },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "📹 视频通话记录",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = durationLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = assistantName,
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = if (archive == null) {
                    "通话档案暂时不可用"
                } else {
                    "$turns 回合 · 点击查看完整通话"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDetails && archive != null) {
        VideoCallArchiveDialog(
            archive = archive,
            onDismiss = { showDetails = false },
        )
    }
}

@Composable
private fun VideoCallArchiveDialog(
    archive: VideoCallArchiveEntry,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("📹 与 ${archive.assistantName.ifBlank { "TA" }} 的视频通话")
                Text(
                    text = "${durationLabel(archive)} · ${archive.messages.count { it.role == "user" }} 回合",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (archive.messages.isEmpty()) {
                    Text(
                        "这次通话没有可显示的文字记录。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    archive.messages.forEach { message ->
                        val isUser = message.role == "user"
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (isUser) "你" else archive.assistantName.ifBlank { "TA" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

private fun durationLabel(archive: VideoCallArchiveEntry): String {
    val end = archive.endedAtEpochMillis ?: archive.startedAtEpochMillis
    val durationSeconds = max(0L, (end - archive.startedAtEpochMillis) / 1000L)
    val minutes = durationSeconds / 60L
    val seconds = durationSeconds % 60L
    return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
}
