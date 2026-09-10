package me.rerere.rikkahub.ui.pages.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.time.Instant
import kotlin.math.max
import kotlin.uuid.Uuid

@Serializable
data class VideoCallArchiveMessage(
    val role: String,
    val text: String,
)

@Serializable
data class VideoCallArchiveEntry(
    val id: String,
    val conversationId: String,
    val assistantId: String,
    val assistantName: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val endedNormally: Boolean = false,
    val messages: List<VideoCallArchiveMessage> = emptyList(),
)

/**
 * Lightweight app-private archive for story video calls.
 *
 * It stores only visible USER/ASSISTANT text snapshots. Tool calls, tool results,
 * reasoning and internal metadata are intentionally excluded.
 */
class VideoCallArchiveStore(context: Context) {
    private val appContext = context.applicationContext
    private val archiveDir = File(appContext.filesDir, "video_call_archives").apply { mkdirs() }
    private val archiveFile = File(archiveDir, "archives.json")

    @Synchronized
    fun list(): List<VideoCallArchiveEntry> = readAll()
        .sortedByDescending { it.startedAtEpochMillis }

    @Synchronized
    fun find(sessionId: String): VideoCallArchiveEntry? =
        readAll().firstOrNull { it.id == sessionId }

    @Synchronized
    fun startSession(
        conversationId: Uuid,
        assistantId: Uuid,
        assistantName: String,
        messages: List<UIMessage>,
    ): String {
        recoverInterruptedSessions()
        val id = Uuid.random().toString()
        val entry = VideoCallArchiveEntry(
            id = id,
            conversationId = conversationId.toString(),
            assistantId = assistantId.toString(),
            assistantName = assistantName,
            startedAtEpochMillis = Instant.now().toEpochMilli(),
            messages = visibleSnapshot(messages),
        )
        writeAll(readAll() + entry)
        return id
    }

    @Synchronized
    fun updateSession(sessionId: String, messages: List<UIMessage>) {
        val all = readAll()
        val index = all.indexOfFirst { it.id == sessionId }
        if (index < 0) return
        val snapshot = visibleSnapshot(messages)
        if (all[index].messages == snapshot) return
        val next = all.toMutableList()
        next[index] = next[index].copy(messages = snapshot)
        writeAll(next)
    }

    @Synchronized
    fun finishSession(
        sessionId: String,
        messages: List<UIMessage>,
        endedNormally: Boolean = true,
    ): VideoCallArchiveEntry? {
        val all = readAll()
        val index = all.indexOfFirst { it.id == sessionId }
        if (index < 0) return null
        val finished = all[index].copy(
            endedAtEpochMillis = Instant.now().toEpochMilli(),
            endedNormally = endedNormally,
            messages = visibleSnapshot(messages),
        )
        val next = all.toMutableList()
        next[index] = finished
        writeAll(next)
        return finished
    }

    @Synchronized
    fun deleteSession(sessionId: String) {
        writeAll(readAll().filterNot { it.id == sessionId })
    }

    /** Mark sessions left open by process death as interrupted, without deleting content. */
    @Synchronized
    fun recoverInterruptedSessions() {
        val all = readAll()
        var changed = false
        val now = Instant.now().toEpochMilli()
        val recovered = all.map { entry ->
            if (entry.endedAtEpochMillis == null) {
                changed = true
                entry.copy(endedAtEpochMillis = now, endedNormally = false)
            } else entry
        }
        if (changed) writeAll(recovered)
    }

    private fun visibleSnapshot(messages: List<UIMessage>): List<VideoCallArchiveMessage> =
        messages.mapNotNull { message ->
            val role = when (message.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                else -> return@mapNotNull null
            }
            val text = runCatching { message.toText().trim() }.getOrDefault("")
            if (text.isBlank()) null else VideoCallArchiveMessage(role = role, text = text)
        }

    private fun readAll(): List<VideoCallArchiveEntry> = runCatching {
        if (!archiveFile.exists()) return@runCatching emptyList()
        val raw = archiveFile.readText()
        if (raw.isBlank()) emptyList()
        else JsonInstant.decodeFromString<List<VideoCallArchiveEntry>>(raw)
    }.getOrDefault(emptyList())

    private fun writeAll(entries: List<VideoCallArchiveEntry>) {
        archiveDir.mkdirs()
        val temp = File(archiveDir, "archives.json.tmp")
        temp.writeText(JsonInstant.encodeToString(entries))
        if (archiveFile.exists()) archiveFile.delete()
        temp.renameTo(archiveFile)
    }
}

/**
 * Keeps the active video-call archive alive even if VideoCallPage leaves composition.
 * The runtime follows the shared conversation flow and seals the archive when the
 * foreground call service is actually hung up (including notification-bar hangup).
 *
 * While a call is active, its turns remain in the live Conversation so the model can
 * naturally continue the same call. When the call ends, those temporary turns are
 * removed from the normal chat history and replaced by one compact archive marker.
 * The complete transcript remains in VideoCallArchiveStore.
 */
object VideoCallArchiveRuntime : KoinComponent {
    private const val PERSIST_INTERVAL_MS = 750L
    const val ARCHIVE_MARKER_PREFIX = "[VIDEO_CALL_ARCHIVE:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chatService: ChatService by inject()
    private var archiveJob: Job? = null
    private var activeConversationId: String? = null

    @Synchronized
    fun attach(
        store: VideoCallArchiveStore,
        conversationId: Uuid,
        assistantId: Uuid,
        assistantName: String,
        conversationFlow: StateFlow<Conversation>,
    ) {
        val conversationKey = conversationId.toString()
        if (activeConversationId == conversationKey && archiveJob?.isActive == true) return

        archiveJob?.cancel()
        activeConversationId = conversationKey
        val baselineCount = conversationFlow.value.currentMessages.size
        val sessionId = store.startSession(
            conversationId = conversationId,
            assistantId = assistantId,
            assistantName = assistantName,
            messages = emptyList(),
        )

        archiveJob = scope.launch {
            var endedNormally = false
            val messageJob = launch {
                var lastPersistAt = 0L
                conversationFlow.collect { conversation ->
                    val now = System.currentTimeMillis()
                    if (now - lastPersistAt < PERSIST_INTERVAL_MS) return@collect
                    store.updateSession(
                        sessionId,
                        conversation.currentMessages.drop(baselineCount),
                    )
                    lastPersistAt = now
                }
            }

            try {
                VoiceCallService.activeConversationId
                    .filter { it == conversationKey }
                    .first()
                VoiceCallService.activeConversationId
                    .filter { it != conversationKey }
                    .first()
                endedNormally = true
            } finally {
                messageJob.cancelAndJoin()
                val callMessages = conversationFlow.value.currentMessages.drop(baselineCount)
                val finished = store.finishSession(
                    sessionId = sessionId,
                    messages = callMessages,
                    endedNormally = endedNormally,
                )

                if (endedNormally && finished != null) {
                    collapseConversationToArchiveRecord(
                        conversationId = conversationId,
                        conversationFlow = conversationFlow,
                        baselineCount = baselineCount,
                        archive = finished,
                    )
                }

                synchronized(this@VideoCallArchiveRuntime) {
                    if (activeConversationId == conversationKey) activeConversationId = null
                }
            }
        }
    }

    private suspend fun collapseConversationToArchiveRecord(
        conversationId: Uuid,
        conversationFlow: StateFlow<Conversation>,
        baselineCount: Int,
        archive: VideoCallArchiveEntry,
    ) {
        val current = conversationFlow.value
        if (current.currentMessages.size <= baselineCount) return

        val archiveText = buildArchiveMarkerText(archive)
        val archiveNode = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(archiveText)),
        ).toMessageNode()

        val updated = current.copy(
            messageNodes = current.messageNodes.take(baselineCount) + archiveNode,
            updateAt = Instant.now(),
        )

        chatService.saveConversation(conversationId, updated)
    }

    private fun buildArchiveMarkerText(archive: VideoCallArchiveEntry): String {
        val end = archive.endedAtEpochMillis ?: archive.startedAtEpochMillis
        val durationSeconds = max(0L, (end - archive.startedAtEpochMillis) / 1000L)
        val minutes = durationSeconds / 60L
        val seconds = durationSeconds % 60L
        val turns = archive.messages.count { it.role == "user" }
        val duration = if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
        return buildString {
            append("📹 视频通话记录")
            if (archive.assistantName.isNotBlank()) append(" · ${archive.assistantName}")
            append("\n通话时长：$duration · $turns 回合")
            append("\n完整通话已存档")
            append("\n$ARCHIVE_MARKER_PREFIX${archive.id}]")
        }
    }
}
