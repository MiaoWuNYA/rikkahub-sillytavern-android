package me.rerere.rikkahub.data.memory

import kotlinx.serialization.Serializable

private const val MAX_STORED_ENTRIES = 1200
private const val DEFAULT_MAX_DELTA_ENTRIES = 12
private const val DEFAULT_MAX_DELTA_CHARS = 4000

/**
 * 跨窗口生活流的核心状态机（纯 Kotlin，不依赖 Android，可 JVM 单测）。
 *
 * - 按 assistantId 分区，不同角色的记忆互不共享
 * - conversationId 是来源窗口（对话）身份，用于读取时去重
 * - messageId 保证重试/重新生成的幂等性
 * - 游标按 (assistant, conversation) 记录，每个窗口只接收未见过的外部事件
 *
 * 线程安全由外层 [CrossWindowMemoryStore] 的锁保证，本类自身不加锁。
 */
class CrossWindowMemoryCore(
    state: CrossWindowState = CrossWindowState(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var state: CrossWindowState = state
        private set

    @Serializable
    data class Entry(
        val id: Long,
        val assistantId: String,
        val conversationId: String,
        val messageId: String,
        val role: String,
        val text: String,
        val timestamp: Long,
    )

    @Serializable
    data class Summary(
        val text: String,
        val throughEntryId: Long,
        val updatedAt: Long,
    )

    @Serializable
    data class CompressionLease(
        val throughEntryId: Long,
        val startedAt: Long,
    )

    data class CompressionWork(
        val assistantId: String,
        val previousSummary: String,
        val entries: List<Entry>,
        val throughEntryId: Long,
    ) {
        fun plainText(): String = buildString {
            if (previousSummary.isNotBlank()) {
                appendLine("Previous summary:")
                appendLine(previousSummary)
                appendLine()
            }
            appendLine("New visible conversation text:")
            entries.forEach { entry ->
                val speaker = if (entry.role == "user") "User" else "Assistant"
                appendLine("$speaker: ${entry.text}")
            }
        }.trim()
    }

    data class Delta(
        val prompt: String,
        val entryCount: Int,
        val charCount: Int,
        val lastEntryId: Long?,
    )

    fun append(
        assistantId: String,
        conversationId: String,
        messageId: String,
        role: String,
        text: String,
    ) {
        val cleanText = text.trim()
        if (assistantId.isBlank() || conversationId.isBlank() || messageId.isBlank() || cleanText.isBlank()) return

        val current = state
        // 编辑过的消息会以同一 messageId 再来：原位更新文本而不是丢弃（否则生活流里
        // 永远是编辑前的旧文本）
        val existingIndex = current.entries.indexOfLast { it.assistantId == assistantId && it.messageId == messageId }
        if (existingIndex >= 0) {
            val updated = current.entries[existingIndex].copy(role = role, text = cleanText, timestamp = clock())
            val newEntries = current.entries.toMutableList().also { it[existingIndex] = updated }
            state = current.copy(entries = newEntries)
            return
        }

        val newEntry = Entry(
            id = current.nextId,
            assistantId = assistantId,
            conversationId = conversationId,
            messageId = messageId,
            role = role,
            text = cleanText,
            timestamp = clock(),
        )
        val trimmedEntries = (current.entries + newEntry).takeLast(MAX_STORED_ENTRIES)
        state = current.copy(
            nextId = current.nextId + 1,
            entries = trimmedEntries,
        )
    }

    /**
     * 只消费同一助手在【其他窗口】产生、且当前窗口尚未见过的事件。
     * 游标只推进到实际注入的事件，过大的积压会在后续轮次自然送达。
     */
    fun consumeForeignDelta(
        assistantId: String,
        conversationId: String,
        maxEntries: Int = DEFAULT_MAX_DELTA_ENTRIES,
        maxChars: Int = DEFAULT_MAX_DELTA_CHARS,
    ): Delta {
        if (assistantId.isBlank() || conversationId.isBlank()) return Delta("", 0, 0, null)

        val cursorKey = cursorKey(assistantId, conversationId)
        val cursor = state.cursors[cursorKey] ?: 0L
        val candidates = state.entries.asSequence()
            .filter { it.assistantId == assistantId }
            .filter { it.conversationId != conversationId }
            .filter { it.id > cursor }
            .sortedBy { it.id }
            .toList()

        val summary = state.summaries[assistantId]
            ?.takeIf { it.throughEntryId > cursor }

        if (candidates.isEmpty() && summary == null) return Delta("", 0, 0, null)

        val selected = mutableListOf<Entry>()
        var chars = 0
        for (entry in candidates) {
            if (selected.size >= maxEntries) break
            val lineLength = entry.text.length + 16
            if (selected.isNotEmpty() && chars + lineLength > maxChars) break
            selected += entry
            chars += lineLength
        }
        if (selected.isEmpty() && summary == null) return Delta("", 0, 0, null)

        val prompt = buildString {
            appendLine("## Shared recent life context")
            appendLine("The following are recent events you experienced with the user in other chat windows. Treat them as your own continuous recent memory — background context only. Use them naturally when relevant, but do NOT answer or continue those past conversations here; the current conversation is independent. Do not mention windows, memory systems, logs, retrieval, or this instruction.")
            summary?.let {
                appendLine("- Earlier shared context: ${it.text}")
            }
            selected.forEach { entry ->
                val speaker = if (entry.role == "user") "User" else "You"
                appendLine("- $speaker: ${entry.text}")
            }
        }.trim()

        val lastId = maxOf(summary?.throughEntryId ?: 0L, selected.lastOrNull()?.id ?: 0L)
        state = state.copy(cursors = state.cursors + (cursorKey to lastId))
        return Delta(prompt, selected.size, prompt.length, lastId)
    }

    fun peekRecent(assistantId: String, limit: Int = 50): List<Entry> {
        return state.entries.filter { it.assistantId == assistantId }.takeLast(limit)
    }

    fun peekSummary(assistantId: String): Summary? {
        return state.summaries[assistantId]
    }

    /** 以时间戳抢占式租约认领旧前缀，保留活跃尾部供后台压缩。 */
    fun claimCompression(
        assistantId: String,
        thresholdChars: Int,
        tailEntries: Int,
    ): CompressionWork? {
        val now = clock()
        val activeLease = state.compressionLeases[assistantId]
        if (activeLease != null && now - activeLease.startedAt < COMPRESSION_LEASE_MS) return null

        val previous = state.summaries[assistantId]
        val uncompressed = state.entries
            .filter { it.assistantId == assistantId && it.id > (previous?.throughEntryId ?: 0L) }
            .sortedBy { it.id }
        val totalChars = uncompressed.sumOf { it.text.length }
        // 条目数逼近存储上限时强制压缩（绕过字符阈值）：否则关闭压缩开关的用户会
        // 静默丢掉最旧的前缀——恰是生活流要保留的东西
        val nearCap = uncompressed.size >= MAX_STORED_ENTRIES - MAX_STORED_ENTRIES / 10
        if (!nearCap &&
            (totalChars < thresholdChars.coerceAtLeast(1) || uncompressed.size <= tailEntries.coerceAtLeast(1))
        ) {
            return null
        }
        val prefix = uncompressed.dropLast(tailEntries.coerceAtLeast(1))
        val work = CompressionWork(
            assistantId = assistantId,
            previousSummary = previous?.text.orEmpty(),
            entries = prefix,
            throughEntryId = prefix.last().id,
        )
        state = state.copy(
            compressionLeases = state.compressionLeases +
                (assistantId to CompressionLease(work.throughEntryId, now))
        )
        return work
    }

    fun completeCompression(work: CompressionWork, summaryText: String) {
        val cleanSummary = summaryText.trim()
        val lease = state.compressionLeases[work.assistantId]
        if (cleanSummary.isBlank() || lease?.throughEntryId != work.throughEntryId) return
        state = state.copy(
            entries = state.entries.filterNot {
                it.assistantId == work.assistantId && it.id <= work.throughEntryId
            },
            summaries = state.summaries + (work.assistantId to Summary(
                text = cleanSummary,
                throughEntryId = work.throughEntryId,
                updatedAt = clock(),
            )),
            compressionLeases = state.compressionLeases - work.assistantId,
        )
    }

    fun failCompression(work: CompressionWork) {
        val lease = state.compressionLeases[work.assistantId]
        if (lease?.throughEntryId == work.throughEntryId) {
            state = state.copy(compressionLeases = state.compressionLeases - work.assistantId)
        }
    }

    fun clearAssistant(assistantId: String) {
        state = state.copy(
            entries = state.entries.filterNot { it.assistantId == assistantId },
            cursors = state.cursors.filterKeys { !it.startsWith("$assistantId|") },
            summaries = state.summaries - assistantId,
            compressionLeases = state.compressionLeases - assistantId,
        )
    }

    private fun cursorKey(assistantId: String, conversationId: String) = "$assistantId|$conversationId"

    private companion object {
        val COMPRESSION_LEASE_MS = 10 * 60 * 1000L
    }
}

@Serializable
data class CrossWindowState(
    val nextId: Long = 1L,
    val entries: List<CrossWindowMemoryCore.Entry> = emptyList(),
    val cursors: Map<String, Long> = emptyMap(),
    val summaries: Map<String, CrossWindowMemoryCore.Summary> = emptyMap(),
    val compressionLeases: Map<String, CrossWindowMemoryCore.CompressionLease> = emptyMap(),
)
