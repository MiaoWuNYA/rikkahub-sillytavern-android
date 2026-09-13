package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    )
                }
            }
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: String? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = queryOnce(keyword, sort, assistantId)
        if (results.isNotEmpty() || keyword.isBlank()) return@withContext results

        // FTS5 隐式 AND：整句多关键词（如"最近 天气 情况"）会 0 命中。
        // 兜底：按空白拆词逐个检索再合并（按相关度去重），应用内搜索与 conversation_search 都受益。
        // 纯标点/符号 token 必须跳过：jieba 分不出词时 MATCH 空串会抛 SQLiteException
        val tokens = keyword.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it != keyword && hasWordCharacter(it) }
        if (tokens.size <= 1) return@withContext results
        return@withContext runCatching {
            tokens.flatMap { token -> queryOnce(token, sort, assistantId) }
                .distinctBy { it.messageId }
                .take(50)
        }.getOrElse { error ->
            Log.w(TAG, "per-token FTS fallback failed", error)
            results
        }
    }

    private fun hasWordCharacter(token: String): Boolean =
        token.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF }

    private fun queryOnce(
        keyword: String,
        sort: MessageSearchSort,
        assistantId: String?,
    ): List<MessageSearchResult> {
        val results = mutableListOf<MessageSearchResult>()
        val assistantFilter = if (assistantId != null) {
            """
            AND EXISTS (
                SELECT 1 FROM conversationentity AS conversation
                WHERE conversation.id = message_fts.conversation_id
                  AND conversation.assistant_id = ?
            )
            """.trimIndent()
        } else {
            ""
        }
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE text MATCH jieba_query(?)
            $assistantFilter
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            if (assistantId != null) arrayOf(keyword, assistantId) else arrayOf(keyword)
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        return results
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
