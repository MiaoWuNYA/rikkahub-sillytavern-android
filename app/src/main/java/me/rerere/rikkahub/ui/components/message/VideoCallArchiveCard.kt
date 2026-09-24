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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
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

    val endedLabel = stringResource(R.string.video_call_ended)
    val durationLabel = archive?.let { durationLabel(it) }.orEmpty().ifBlank { endedLabel }
    val turns = archive?.messages?.count { it.role == "user" } ?: 0
    val assistantName = archive?.assistantName.orEmpty().ifBlank {
        stringResource(R.string.video_call_title)
    }

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
                    text = "📹 " + stringResource(R.string.video_call_archive_title),
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
                    stringResource(R.string.video_call_archive_unavailable)
                } else {
                    stringResource(R.string.video_call_archive_turns_hint, turns)
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
                Text(
                    stringResource(
                        R.string.video_call_archive_dialog_title,
                        archive.assistantName.ifBlank { stringResource(R.string.video_call_fallback_name) },
                    )
                )
                Text(
                    text = stringResource(
                        R.string.video_call_archive_dialog_subtitle,
                        durationLabel(archive),
                        archive.messages.count { it.role == "user" },
                    ),
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
                        stringResource(R.string.video_call_archive_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    archive.messages.forEach { message ->
                        val isUser = message.role == "user"
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (isUser) {
                                    stringResource(R.string.common_you)
                                } else {
                                    archive.assistantName.ifBlank { stringResource(R.string.video_call_fallback_name) }
                                },
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
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

@Composable
private fun durationLabel(archive: VideoCallArchiveEntry): String {
    val end = archive.endedAtEpochMillis ?: archive.startedAtEpochMillis
    val durationSeconds = max(0L, (end - archive.startedAtEpochMillis) / 1000L)
    val minutes = durationSeconds / 60L
    val seconds = durationSeconds % 60L
    return if (minutes > 0) {
        stringResource(R.string.video_call_duration_min_sec, minutes, seconds)
    } else {
        stringResource(R.string.video_call_duration_sec, seconds)
    }
}
