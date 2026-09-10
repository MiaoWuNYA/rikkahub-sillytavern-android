package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = "recent_chats",
        description = """
            List the user's recent conversations with you to understand their preferences and ongoing topics.
            Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated.
            Use this when you need quick context about what the user has been discussing lately.
            Only titles and dates are returned; use `conversation_search` to look up the actual content.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of recent conversations to return (default: 10, max: 30)"
                        )
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
            val recent = conversationRepo.getRecentConversations(
                assistantId = assistantId,
                limit = limit,
            )
            val payload = buildJsonArray {
                recent.forEach { conversation ->
                    add(buildJsonObject {
                        put("id", conversation.id.toString())
                        put("title", conversation.title.ifBlank { "Untitled" })
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "conversation_search",
        description = """
            Full-text search across the user's past conversations to recall specific information they mentioned before.
            Use focused keywords. Run multiple searches with different keywords if needed.
            Each result includes the conversation title, a snippet with matched keywords wrapped in [brackets], and the date.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to search for in past conversation messages")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of results to return (default: 15, max: 50)"
                        )
                    })
                },
                required = listOf("query")
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull
                ?: error("query is required")
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)
            val results = conversationRepo
                .searchMessages(query, MessageSearchSort.RELEVANCE)
                .take(limit)
            val payload = buildJsonArray {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("conversation_id", result.conversationId)
                        put("title", result.title.ifBlank { "Untitled" })
                        put("snippet", result.snippet)
                        put("date", result.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    )
)

/**
 * 取回被上下文裁剪省略的历史消息原文（瞬态内容裁剪的配套工具）。
 * 占位说明里带有消息 ID，AI 需要原文时按 ID 调用本工具。
 */
fun createHistoryMessageTool(
    conversationRepo: ConversationRepository,
    conversationId: Uuid,
): Tool = Tool(
    name = "read_history_message",
    description = """
        Retrieve the original content of a past message in the current conversation by its message ID.
        Use it when a placeholder in the history says content was omitted (omitted images, web search
        results, media attachments) and you need the original text/content again.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("message_id", buildJsonObject {
                    put("type", "string")
                    put("description", "The message ID shown in the omission placeholder")
                })
            },
            required = listOf("message_id")
        )
    },
    execute = { args ->
        val messageId = args.jsonObject["message_id"]?.jsonPrimitive?.contentOrNull
            ?: error("message_id is required")
        val targetId = runCatching { Uuid.parse(messageId) }
            .getOrElse { error("invalid message_id: $messageId") }
        val conversation = conversationRepo.getConversationById(conversationId)
            ?: error("conversation not found")
        val message = conversation.currentMessages.firstOrNull { it.id == targetId }
            ?: error("message not found in current conversation: $messageId")
        val payload = buildJsonObject {
            put("message_id", messageId)
            put("role", message.role.name)
            if (message.name != null) put("name", message.name.orEmpty())
            put("parts", buildJsonArray {
                message.parts.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        })
                        is UIMessagePart.Image -> add(buildJsonObject {
                            put("type", "image")
                            put("url", part.url)
                        })
                        is UIMessagePart.Video -> add(buildJsonObject {
                            put("type", "video")
                            put("url", part.url)
                        })
                        is UIMessagePart.Audio -> add(buildJsonObject {
                            put("type", "audio")
                            put("url", part.url)
                        })
                        is UIMessagePart.Document -> add(buildJsonObject {
                            put("type", "document")
                            put("file_name", part.fileName)
                            put("url", part.url)
                        })
                        is UIMessagePart.Tool -> add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_name", part.toolName)
                            put("tool_input", part.input)
                            put("output", part.output.joinToString("\n") { outputPart ->
                                (outputPart as? UIMessagePart.Text)?.text.orEmpty()
                            })
                        })
                        is UIMessagePart.ServerTool -> add(buildJsonObject {
                            put("type", "server_tool_result")
                            put("tool_name", part.toolName)
                            put("output", part.output?.toString().orEmpty())
                        })
                        else -> add(buildJsonObject {
                            put("type", part::class.simpleName?.lowercase().orEmpty())
                        })
                    }
                }
            })
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    }
)
