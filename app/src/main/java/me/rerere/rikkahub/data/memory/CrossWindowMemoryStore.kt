package me.rerere.rikkahub.data.memory

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "CrossWindowMemory"
private const val PREFS_NAME = "cross_window_memory_v1"
private const val STATE_KEY = "state"

/**
 * 跨窗口生活流存储（SharedPreferences，v1 不引入 Room 迁移）。
 * 状态机逻辑在 [CrossWindowMemoryCore]（纯 Kotlin），本类只负责持久化与加锁。
 */
class CrossWindowMemoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun append(
        assistantId: String,
        conversationId: String,
        messageId: String,
        role: String,
        text: String,
    ) = synchronized(lock) {
        mutate { it.append(assistantId, conversationId, messageId, role, text) }
    }

    fun consumeForeignDelta(
        assistantId: String,
        conversationId: String,
        maxEntries: Int = 12,
        maxChars: Int = 4000,
    ): CrossWindowMemoryCore.Delta = synchronized(lock) {
        var delta = CrossWindowMemoryCore.Delta("", 0, 0, null)
        mutate {
            delta = it.consumeForeignDelta(assistantId, conversationId, maxEntries, maxChars)
        }
        delta
    }

    fun peekRecent(assistantId: String, limit: Int = 50): List<CrossWindowMemoryCore.Entry> =
        synchronized(lock) { core().peekRecent(assistantId, limit) }

    fun peekSummary(assistantId: String): CrossWindowMemoryCore.Summary? =
        synchronized(lock) { core().peekSummary(assistantId) }

    fun claimCompression(
        assistantId: String,
        thresholdChars: Int,
        tailEntries: Int,
    ): CrossWindowMemoryCore.CompressionWork? = synchronized(lock) {
        var work: CrossWindowMemoryCore.CompressionWork? = null
        mutate {
            work = it.claimCompression(assistantId, thresholdChars, tailEntries)
        }
        work
    }

    fun completeCompression(work: CrossWindowMemoryCore.CompressionWork, summaryText: String) =
        synchronized(lock) {
            mutate { it.completeCompression(work, summaryText) }
        }

    fun failCompression(work: CrossWindowMemoryCore.CompressionWork) = synchronized(lock) {
        mutate { it.failCompression(work) }
    }

    fun clearAssistant(assistantId: String) = synchronized(lock) {
        mutate { it.clearAssistant(assistantId) }
    }

    private fun core(): CrossWindowMemoryCore = readState()

    private fun readState(): CrossWindowMemoryCore {
        val raw = prefs.getString(STATE_KEY, null) ?: return CrossWindowMemoryCore()
        val state = runCatching { json.decodeFromString<CrossWindowState>(raw) }
            .onFailure { Log.w(TAG, "Failed to decode cross-window memory state; resetting", it) }
            .getOrDefault(CrossWindowState())
        return CrossWindowMemoryCore(state)
    }

    private inline fun mutate(block: (CrossWindowMemoryCore) -> Unit) {
        val core = readState()
        block(core)
        prefs.edit().putString(STATE_KEY, json.encodeToString(core.state)).apply()
    }

    private companion object {
        val lock = Any()
    }
}
