package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsPatcher
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 设置控制工具：一个工具覆盖全部应用内设置，按 groups → fields → get/set 逐级懒加载。
 */

private const val PREVIEW_CHARS = 60
private const val GET_CHARS = 400

private data class SettingsGroup(val id: String, val label: String, val hint: String, val keys: List<String>)

private val SETTINGS_GROUPS = listOf(
    SettingsGroup(
        "appearance",
        "外观与主题",
        "动态取色、主题 id、自定义主题、字体/密度/气泡显示、华灯美化开关",
        listOf("dynamicColor", "themeId", "customThemes", "displaySetting", "huadengSettings", "browserSettings")
    ),
    SettingsGroup(
        "chat",
        "对话与模型",
        "各类用途模型、推理等级、标题/建议/压缩/翻译/OCR/生图提示词",
        listOf(
            "chatModelId", "fastModelId", "titleModelId", "fastModelReasoningLevel", "titleReasoningLevel",
            "memoryModelId", "memoryModelReasoningLevel", "imageGenerationModelId", "imageGenerationSize",
            "imageGenerationReasoningLevel", "enableAutoTitle", "titlePrompt", "translateModeId", "translatePrompt",
            "translateThinkingBudget", "translateTargetLanguage", "enableSuggestion", "suggestionPrompt",
            "ocrModelId", "ocrPrompt", "compressModelId", "compressPrompt", "favoriteModels",
            "proactiveMessageModelId"
        )
    ),
    SettingsGroup(
        "assistant",
        "助手与角色",
        "助手列表（含 systemPrompt/temperature/工具开关）、标签、Persona、快捷消息、群聊",
        listOf(
            "assistantId",
            "assistants",
            "assistantTags",
            "personas",
            "activePersonaId",
            "quickMessages",
            "groupChats"
        )
    ),
    SettingsGroup(
        "provider",
        "供应商与语音",
        "API 供应商（可增删改）、生图/TTS/ASR 供应商",
        listOf(
            "providers",
            "mediaGenerationProviders",
            "ttsProviders",
            "selectedTTSProviderId",
            "defaultTTSPlaybackSpeed",
            "asrProviders",
            "selectedASRProviderId"
        )
    ),
    SettingsGroup(
        "prompt",
        "提示词注入与世界书",
        "模式注入、世界书、全局正则组、世界书预算、作者注释、宏变量、向量检索",
        listOf(
            "modeInjections", "lorebooks", "regexGroups", "worldInfoBudget", "worldInfoBudgetCap",
            "worldInfoMinActivations", "worldInfoMinActivationsDepthMax", "worldInfoRecursive",
            "worldInfoMaxRecursionSteps", "worldInfoDepth", "worldInfoCharacterStrategy", "worldInfoOverflowAlert",
            "worldInfoUseGroupScoring", "vectorStorageEnabled", "vectorStorageModelId", "vectorStorageThreshold",
            "vectorStorageScanDepth", "authorNote", "authorNoteEnabled", "authorNotePosition", "authorNoteDepth",
            "authorNoteRole", "authorNoteInterval", "authorNoteEntries", "macroGlobalVariables", "macroChatVariables"
        )
    ),
    SettingsGroup(
        "search",
        "搜索与网络",
        "搜索服务、搜索通用参数、全局联网开关、网络 UA/代理/重试",
        listOf("searchServices", "searchCommonOptions", "searchServiceSelected", "enableWebSearch", "networkSetting")
    ),
    SettingsGroup(
        "mcp",
        "MCP 服务",
        "MCP 服务器列表",
        listOf("mcpServers")
    ),
    SettingsGroup(
        "storage",
        "备份与存储",
        "WebDAV / S3 备份、附件保留天数、GitHub token",
        listOf("webDavConfig", "s3Config", "uploadS3Config", "backupReminderConfig", "fileRetentionDays", "githubToken")
    ),
    SettingsGroup(
        "server",
        "内置 Web 服务",
        "Web 服务器开关、端口、鉴权",
        listOf(
            "webServerEnabled",
            "webServerPort",
            "webServerJwtEnabled",
            "webServerAccessPassword",
            "webServerLocalhostOnly"
        )
    ),
    SettingsGroup(
        "integration",
        "外部集成",
        "微信 Bot、QQ Bot、AI 主动发消息",
        listOf("wechatBotSetting", "qqBotSetting", "proactiveMessageSetting")
    ),
    SettingsGroup(
        "security",
        "安全与开发者",
        "工具审批策略、开发者模式",
        listOf("securitySetting", "developerMode")
    ),
)

fun buildSettingsControlTool(
    settingsStore: SettingsStore,
    patchRecorder: me.rerere.rikkahub.plugin.settings.PluginSettingsService? = null,
    onPatchCreated: (suspend () -> Unit)? = null,
): Tool = Tool(
    name = "settings_control",
    description = "读取或修改本应用自身的设置（主题、外观、模型、供应商、助手、提示词、搜索、MCP、备份等）。" +
        "懒加载用法：先 action=groups 看分组 → action=fields&group=<id> 看字段 → action=get/set 读写具体路径。" +
        "路径形如 themeId、displaySetting.fontSize、providers[0].name、assistants[id=xxx].temperature。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add(JsonPrimitive("groups"))
                            add(JsonPrimitive("fields"))
                            add(JsonPrimitive("get"))
                            add(JsonPrimitive("set"))
                            add(JsonPrimitive("add"))
                            add(JsonPrimitive("remove"))
                            add(JsonPrimitive("search"))
                        }
                    )
                    put("description", "groups=分组列表; fields=分组内字段; get=读; set=改; add=数组追加; remove=数组删除; search=按关键词搜索")
                })
                put("group", buildJsonObject {
                    put("type", "string")
                    put("description", "分组 id（action=fields 时用）")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "设置路径，如 themeId / displaySetting.fontSize / providers[0].baseUrl")
                })
                put("value", buildJsonObject {
                    put("type", "string")
                    put("description", "新值。字符串直接写；数字/布尔写字面量；对象或数组写 JSON 文本")
                })
                put("index", buildJsonObject {
                    put("type", "integer")
                    put("description", "action=remove 时的数组下标（可省略，改用 value 匹配 id/name）")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "action=search 的关键词")
                })
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val action = obj["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "groups"
        val outcome = runCatching { handleSettingsAction(action, obj, settingsStore, patchRecorder) }
        outcome.getOrNull()?.let { if (it.patchCreated) onPatchCreated?.invoke() }
        val text = outcome.fold({ it.json }, { error ->
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(false),
                    "error" to JsonPrimitive(error.message ?: error::class.simpleName ?: "error"),
                )
            ).toString()
        })
        listOf(UIMessagePart.Text(text))
    }
)

private class SettingsResult(val json: String, val patchCreated: Boolean = false)

private suspend fun handleSettingsAction(
    action: String,
    args: JsonObject,
    store: SettingsStore,
    recorder: me.rerere.rikkahub.plugin.settings.PluginSettingsService?,
): SettingsResult {
    val current = store.awaitLoaded()
    return when (action) {
        "groups" -> SettingsResult(groupsJson(current))
        "fields" -> SettingsResult(fieldsJson(current, args.str("group")))
        "get" -> SettingsResult(getJson(current, args.requireStr("path")))
        "set" -> setJson(store, recorder, args.requireStr("path"), args.requireStr("value"))
        "add" -> addJson(store, recorder, args.requireStr("path"), args.requireStr("value"))
        "remove" -> removeJson(store, recorder, args.requireStr("path"), args.str("value"), args.int("index"))
        "search" -> SettingsResult(searchJson(current, args.requireStr("query")))
        else -> error("unknown action: $action")
    }
}

private fun groupsJson(settings: Settings): String {
    val tree = encode(settings)
    val known = SETTINGS_GROUPS.flatMap { it.keys }.toSet()
    val other = tree.keys.filter { it !in known && it != "init" }
    return buildJsonObject {
        put("ok", true)
        put("totalFields", tree.size)
        put("groups", buildJsonArray {
            SETTINGS_GROUPS.forEach { g ->
                add(buildJsonObject {
                    put("id", g.id)
                    put("label", g.label)
                    put("hint", g.hint)
                    put("fieldCount", g.keys.count { it in tree })
                })
            }
            add(buildJsonObject {
                put("id", "other")
                put("label", "其他")
                put("hint", "未归类字段")
                put("fieldCount", other.size)
            })
        })
        put("next", "action=fields&group=<id>")
    }.toString()
}

private fun fieldsJson(settings: Settings, groupId: String?): String {
    val tree = encode(settings)
    val keys = if (groupId.isNullOrBlank() || groupId == "other") {
        val known = SETTINGS_GROUPS.flatMap { it.keys }.toSet()
        tree.keys.filter { it !in known && it != "init" }
    } else {
        SETTINGS_GROUPS.firstOrNull { it.id == groupId }?.keys ?: error("unknown group: $groupId")
    }
    return buildJsonObject {
        put("ok", true)
        put("group", groupId ?: "other")
        put("fields", buildJsonArray {
            keys.forEach { key ->
                val value = tree[key] ?: return@forEach
                add(buildJsonObject {
                    put("path", key)
                    put("type", typeName(value))
                    put("value", preview(value))
                    if (value is JsonObject) put("children", value.keys.joinToString(","))
                    if (value is JsonArray) {
                        put("size", value.size)
                        (value.firstOrNull() as? JsonObject)?.let { put("itemKeys", it.keys.joinToString(",")) }
                    }
                })
            }
        })
        put("next", "action=get&path=<path> 或 action=set&path=<path>&value=<new>")
    }.toString()
}

private fun getJson(settings: Settings, path: String): String {
    val value = SettingsPatcher.read(encode(settings), path)
    return buildJsonObject {
        put("ok", true)
        put("path", path)
        put("type", typeName(value))
        put("value", deepPreview(value, GET_CHARS))
    }.toString()
}

private suspend fun setJson(
    store: SettingsStore,
    recorder: me.rerere.rikkahub.plugin.settings.PluginSettingsService?,
    path: String,
    raw: String,
): SettingsResult {
    val tokens = SettingsPatcher.tokens(path)
    var applied: JsonElement = JsonNull
    store.update { current ->
        val tree = encode(current)
        val next = SettingsPatcher.write(
            tree,
            path,
            SettingsPatcher.coerce(SettingsPatcher.readTokens(tree, tokens), raw)
        ) as JsonObject
        applied = SettingsPatcher.readTokens(next, tokens)
        SettingsPatcher.decode(next)
    }
    val created = recorder?.recordIntent(path, applied) ?: false
    return SettingsResult(
        buildJsonObject {
            put("ok", true)
            put("path", path)
            put("applied", deepPreview(applied, GET_CHARS))
            put("patch", "system-tweaks")
        }.toString(),
        created
    )
}

private suspend fun addJson(
    store: SettingsStore,
    recorder: me.rerere.rikkahub.plugin.settings.PluginSettingsService?,
    path: String,
    raw: String,
): SettingsResult {
    val element = SettingsPatcher.coerce(null, raw)
    var size = 0
    var next: JsonArray = JsonArray(emptyList())
    store.update { current ->
        val tree = encode(current)
        val array = SettingsPatcher.read(tree, path) as? JsonArray ?: error("$path is not an array")
        next = JsonArray(array + element)
        size = next.size
        SettingsPatcher.decode(SettingsPatcher.write(tree, path, next) as JsonObject)
    }
    val created = recorder?.recordIntent(path, next) ?: false
    return SettingsResult(
        buildJsonObject {
            put("ok", true)
            put("path", path)
            put("size", size)
            put("added", deepPreview(element, GET_CHARS))
        }.toString(),
        created
    )
}

private suspend fun removeJson(
    store: SettingsStore,
    recorder: me.rerere.rikkahub.plugin.settings.PluginSettingsService?,
    path: String,
    match: String?,
    index: Int?,
): SettingsResult {
    var removed: JsonElement = JsonNull
    var next: JsonArray = JsonArray(emptyList())
    store.update { current ->
        val tree = encode(current)
        val array = SettingsPatcher.read(tree, path) as? JsonArray ?: error("$path is not an array")
        val idx = when {
            index != null -> index
            !match.isNullOrBlank() -> SettingsPatcher.indexOf(array, match)
            else -> error("index or value required")
        }
        if (idx !in array.indices) error("index out of range: $idx (size=${array.size})")
        removed = array[idx]
        next = JsonArray(array.filterIndexed { i, _ -> i != idx })
        SettingsPatcher.decode(SettingsPatcher.write(tree, path, next) as JsonObject)
    }
    val created = recorder?.recordIntent(path, next) ?: false
    return SettingsResult(
        buildJsonObject {
            put("ok", true)
            put("path", path)
            put("removed", preview(removed))
        }.toString(),
        created
    )
}

private fun searchJson(settings: Settings, query: String): String {
    val needle = query.lowercase()
    val hits = mutableListOf<JsonObject>()
    fun visit(element: JsonElement, path: String, depth: Int) {
        if (hits.size >= 40 || depth > 4) return
        when (element) {
            is JsonObject -> element.forEach { (k, v) -> visit(v, "$path.$k", depth + 1) }
            is JsonArray -> element.forEachIndexed { i, v -> visit(v, "$path[$i]", depth + 1) }
            is JsonPrimitive -> {
                val text = element.contentOrNull ?: return
                if (path.lowercase().contains(needle) || text.lowercase().contains(needle)) {
                    hits.add(buildJsonObject {
                        put("path", path.trimStart('.'))
                        put("value", text.take(PREVIEW_CHARS))
                    })
                }
            }
            else -> Unit
        }
    }
    visit(encode(settings), "", 0)
    return buildJsonObject {
        put("ok", true)
        put("query", query)
        put("hits", JsonArray(hits))
    }.toString()
}

private fun encode(settings: Settings): JsonObject =
    JsonInstant.encodeToJsonElement(Settings.serializer(), settings).jsonObject

private fun decode(tree: JsonObject): Settings =
    JsonInstant.decodeFromJsonElement(Settings.serializer(), tree)

private fun typeName(element: JsonElement): String = when (element) {
    is JsonNull -> "null"
    is JsonObject -> "object"
    is JsonArray -> "array"
    is JsonPrimitive -> if (element.isString) "string" else "primitive"
}

private fun preview(element: JsonElement): String = when (element) {
    is JsonNull -> "null"
    is JsonObject -> "{${element.keys.take(8).joinToString(",")}}"
    is JsonArray -> "[${element.size}]"
    is JsonPrimitive -> element.content.let { if (it.length > PREVIEW_CHARS) it.take(PREVIEW_CHARS) + "…" else it }
}

private fun deepPreview(element: JsonElement, limit: Int): JsonElement {
    val text = element.toString()
    return if (text.length <= limit) element else JsonPrimitive(text.take(limit) + "…(${text.length} chars)")
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.requireStr(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("$key required")

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
