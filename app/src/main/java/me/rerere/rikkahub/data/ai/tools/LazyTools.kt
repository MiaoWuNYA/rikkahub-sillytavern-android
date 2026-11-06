package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具懒加载：默认只把核心工具发给模型，其余按组加载。
 *
 * 只在内存里记录“这个会话加载了哪些组”，不落盘也不进系统提示词——
 * 未加载的工具既不占 tools 参数，也不占 system prompt。
 */
object LazyToolGroups {
    private val CORE_GROUPS = setOf(
        "time", "calculator", "ask_user", "clipboard", "diff", "search", "memory", "skill",
        "settings", "plugin", "present", "phone",
    )

    @Volatile
    private var groupOfTool: Map<String, String> = emptyMap()

    @Volatile
    private var catalog: Map<String, List<String>> = emptyMap()

    private val loaded = ConcurrentHashMap<String, MutableSet<String>>()

    fun register(groups: Map<String, List<Tool>>) {
        // 核心组不进懒加载表：不登记就不会被过滤，也不出现在可加载列表里
        val lazy = groups.filterKeys { it !in CORE_GROUPS }
        groupOfTool = lazy.flatMap { (group, tools) -> tools.map { it.name to group } }.toMap()
        catalog = lazy.mapValues { (_, tools) -> tools.map { it.name } }
    }

    fun groupOf(toolName: String): String? = groupOfTool[toolName]

    fun isActive(conversationKey: String, toolName: String): Boolean {
        val group = groupOfTool[toolName] ?: return true
        if (group in CORE_GROUPS) return true
        return loaded[conversationKey]?.contains(group) == true
    }

    fun effective(conversationKey: String, tools: List<Tool>): List<Tool> =
        tools.filter { isActive(conversationKey, it.name) }

    fun markLoaded(conversationKey: String, group: String): List<String> {
        val tools = catalog[group] ?: return emptyList()
        loaded.getOrPut(conversationKey) { mutableSetOf() }.add(group)
        return tools
    }

    fun availableGroups(): Map<String, List<String>> = catalog

    fun clear(conversationKey: String) {
        loaded.remove(conversationKey)
    }
}

fun buildLoadToolsTool(conversationKey: String): Tool = Tool(
    name = "load_tools",
    description = "按组加载更多工具。默认只带核心工具，需要文件/命令/任务/Python 等能力时先调用它。" +
        "无参数时列出可加载的组；给 group 后该组工具在后续步骤可用。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("group", buildJsonObject {
                    put("type", "string")
                    put("description", "组名，如 file/shell/task/python；省略则列出全部组")
                })
            }
        )
    },
    execute = { args ->
        val group = (args as? JsonObject)?.get("group")?.jsonPrimitive?.contentOrNull?.trim()
        val text = if (group.isNullOrBlank()) {
            buildJsonObject {
                put("ok", true)
                put("groups", buildJsonArray {
                    LazyToolGroups.availableGroups().forEach { (name, tools) ->
                        add(buildJsonObject {
                            put("group", name)
                            put("tools", tools.joinToString(","))
                        })
                    }
                })
                put("hint", "action 传 group 加载")
            }.toString()
        } else {
            val tools = LazyToolGroups.markLoaded(conversationKey, group)
            if (tools.isEmpty()) {
                buildJsonObject {
                    put("ok", false)
                    put("error", "未知分组: $group")
                }.toString()
            } else {
                buildJsonObject {
                    put("ok", true)
                    put("group", group)
                    put("tools", JsonPrimitive(tools.joinToString(",")))
                    put("hint", "已加载，继续完成任务即可")
                }.toString()
            }
        }
        listOf(UIMessagePart.Text(text))
    }
)
