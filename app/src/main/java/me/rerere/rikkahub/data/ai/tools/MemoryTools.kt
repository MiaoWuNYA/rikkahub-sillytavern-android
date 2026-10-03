package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
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
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryType
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate

private const val DEFAULT_LIST_LIMIT = 50
private const val MAX_LIST_LIMIT = 100

fun buildMemoryTools(
    json: Json,
    allowEpisodicMemory: Boolean = false,
    onCreation: suspend (String, MemoryType) -> AssistantMemory,
    onUpdate: suspend (Int, String, MemoryType?) -> AssistantMemory?,
    onDelete: suspend (Int) -> Boolean,
    onList: suspend () -> List<AssistantMemory>,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        // 描述精简过：原先含 4 行 Examples 与整段存储规则，约 1336 字符，
        // 而 schema 已经把 action / id / content / type / offset / limit 全列了出来，
        // 示例与规则属于重复表述。现压到约 400 字符，语义信息不丢。
        description = """
            Long-term memory across conversations. `action`: create (add) / edit (update) / delete (remove) / list (read).
            create needs `content`; edit needs `id` + `content`; delete needs `id`; list takes optional `offset`/`limit`.
            ${if (allowEpisodicMemory) "type=fact for durable preferences and profile; type=episodic for a concrete event or decision." else "Use type=fact. Episodic memory is disabled for this assistant."}
            Merge similar records instead of adding duplicates. Never store sensitive information.
            Do not show memory content in the conversation unless the user asks. Today is ${LocalDate.now().toLocalString(true)}.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                                add("list")
                            }
                        )
                        put("description", "Operation to perform: create, edit, delete, or list")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                    put("type", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("fact")
                                if (allowEpisodicMemory) add("episodic")
                            }
                        )
                        put("description", "Memory category for create/edit: fact or episodic; omitted edit values keep the existing category")
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Offset for list, defaults to 0")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Page size for list, from 1 to $MAX_LIST_LIMIT; defaults to $DEFAULT_LIST_LIMIT")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val content = params.requiredMemoryContent()
                    val rawType = params["type"]?.jsonPrimitive?.contentOrNull ?: "fact"
                    val type = parseMemoryType(rawType, allowEpisodicMemory)
                    buildJsonObject {
                        put("status", "created")
                        put("memory", json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content, type)))
                    }
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params.requiredMemoryContent()
                    val type = params["type"]?.jsonPrimitive?.contentOrNull?.let { rawType ->
                        parseMemoryType(rawType, allowEpisodicMemory)
                    }
                    onUpdate(id, content, type)?.let { memory ->
                        buildJsonObject {
                            put("status", "updated")
                            put("memory", json.encodeToJsonElement(AssistantMemory.serializer(), memory))
                        }
                    } ?: buildJsonObject {
                        put("status", "not_found")
                        put("id", id)
                        put("action", "edit")
                    }
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    if (onDelete(id)) {
                        buildJsonObject {
                            put("status", "deleted")
                            put("id", id)
                        }
                    } else buildJsonObject {
                        put("status", "not_found")
                        put("id", id)
                        put("action", "delete")
                    }
                }

                "list" -> {
                    val allMemories = onList()
                    val offset = (params["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
                    val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIST_LIMIT)
                        .coerceIn(1, MAX_LIST_LIMIT)
                    val page = allMemories.drop(offset).take(limit)
                    buildJsonObject {
                        put("status", "ok")
                        put("total", allMemories.size)
                        put("offset", offset)
                        put("limit", limit)
                        put("hasMore", offset + page.size < allMemories.size)
                        put("memories", json.encodeToJsonElement(ListSerializer(AssistantMemory.serializer()), page))
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete, list]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

private fun kotlinx.serialization.json.JsonObject.requiredMemoryContent(): String =
    this["content"]?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: error("content must not be blank")

private fun parseMemoryType(rawType: String, allowEpisodicMemory: Boolean): MemoryType =
    when (rawType.lowercase()) {
        "fact" -> MemoryType.FACT
        "episodic" -> {
            check(allowEpisodicMemory) { "episodic memory is disabled" }
            MemoryType.EPISODIC
        }
        else -> error("unknown memory type: $rawType")
    }
