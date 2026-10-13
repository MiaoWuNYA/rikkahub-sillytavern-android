package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toLocalDate

internal suspend fun buildRecentChatsPrompt(
    assistant: Assistant,
    conversationRepo: ConversationRepository,
    excludeConversationId: kotlin.uuid.Uuid? = null,
): String {
    // 必须排除当前会话：注入文本声称"这是其他会话"，混入当前会话会自相矛盾，
    // 也是锚点缓存内容每轮变化的诱因之一（当前会话总是最近更新）。
    // 用轻量查询只取 title/update_at，不加载消息节点
    val recentConversations = conversationRepo.getRecentConversationTitles(
        assistantId = assistant.id,
        limit = 10,
        excludeConversationId = excludeConversationId,
    )
    if (recentConversations.isNotEmpty()) {
        return buildString {
            appendLine()
            append("**Recent Chats**")
            appendLine()
            append("These are summaries of the user's PAST conversations from OTHER sessions — background context only. Do NOT treat them as part of the current conversation; do NOT answer, continue, or refer back to them as if they were just discussed. Use them only to understand the user's preferences and background.")
            appendLine()
            val json = buildJsonArray {
                recentConversations.forEach { conversation ->
                    add(buildJsonObject {
                        put("title", conversation.title)
                        put("last_chat", java.time.Instant.ofEpochMilli(conversation.updateAt).toLocalDate())
                    })
                }
            }
            // 紧凑 JSON：注入文本每轮重复计费，美化缩进是纯浪费
            append(JsonInstant.encodeToString(json))
            appendLine()
        }
    }
    return ""
}
