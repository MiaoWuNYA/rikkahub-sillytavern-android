package me.rerere.rikkahub.data.st.preset

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * SPreset ChatSquash 全量配置。
 *
 * 字段名与 SPreset `ChatSquash` 的 snake_case 原始键一一对应；
 * 同时提供 Kotlin 惯用的 camelCase 属性与 snake_case 只读别名，便于不同调用方接入。
 */
data class StSpresetChatSquash(
    val enabled: Boolean = false,
    val conditionalEnabled: Boolean = false,
    val conditionalTag: String = "",
    val separateChatHistory: Boolean = false,
    val parseClewd: Boolean = true,
    val userRoleSystem: Boolean = false,
    val role: String = "assistant",
    val enableStopString: Boolean = false,
    val stopString: String = "User:",
    val userPrefix: String = "\n\nUser:",
    val userSuffix: String = "",
    val charPrefix: String = "\n\nAssistant:",
    val charSuffix: String = "",
    val prefixSystem: String = "",
    val suffixSystem: String = "",
    val enableSquashedSeparator: Boolean = false,
    val squashedSeparatorRegex: Boolean = false,
    val squashedSeparatorString: String = "",
    val squashedPostScriptEnable: Boolean = false,
    val squashedPostScript: String = "",
    val reSplit: Boolean = false,
) {
    val conditional_enabled: Boolean get() = conditionalEnabled
    val conditional_tag: String get() = conditionalTag
    val separate_chat_history: Boolean get() = separateChatHistory
    val parse_clewd: Boolean get() = parseClewd
    val user_role_system: Boolean get() = userRoleSystem
    val enable_stop_string: Boolean get() = enableStopString
    val stop_string: String get() = stopString
    val user_prefix: String get() = userPrefix
    val user_suffix: String get() = userSuffix
    val char_prefix: String get() = charPrefix
    val char_suffix: String get() = charSuffix
    val prefix_system: String get() = prefixSystem
    val suffix_system: String get() = suffixSystem
    val enable_squashed_separator: Boolean get() = enableSquashedSeparator
    val squashed_separator_regex: Boolean get() = squashedSeparatorRegex
    val squashed_separator_string: String get() = squashedSeparatorString
    val squashed_post_script_enable: Boolean get() = squashedPostScriptEnable
    val squashed_post_script: String get() = squashedPostScript
    val re_split: Boolean get() = reSplit
}

/**
 * `extensions.SPreset.ToolBindings` 中的单个工具绑定。
 *
 * - [parameters] 是 `form.parameters` 的原始 JSON（数组或 JSON Schema 对象），
 *   优先用于构造运行时 Tool 的 JSON Schema；
 * - 缺失 form 时保留 [code]，运行时通过 QuickJS `new Function(code)()` 解析工具定义与 action。
 */
data class StSpresetToolBinding(
    val id: String = "",
    val enabled: Boolean = false,
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    /** `form.parameters`（数组或 JSON Schema 对象） */
    val parameters: JsonElement? = null,
    /** 原始 form 对象，保留 formatMessageBody 等未建模字段 */
    val form: JsonObject? = null,
    val actionBody: String = "",
    val code: String = "",
    val stealth: Boolean = false,
    val uuid: String = "",
    val editorMode: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val effectiveName: String
        get() = name.ifBlank { displayName.ifBlank { uuid.ifBlank { id } } }

    val formParameters: JsonElement?
        get() = parameters

    fun parameterSchema(): JsonObject? = parameters.toSpresetParameterSchema()?.properties

    fun requiredParameters(): List<String> = parameters.toSpresetParameterSchema()?.required.orEmpty()
}

/** MessageInjections 中解析后的 toolCalls 条目。 */
data class StSpresetInjectedToolCall(
    val id: String = "",
    val name: String = "",
    /** 始终保存为 JSON 字符串，作为 UIMessagePart.Tool.input */
    val arguments: String = "{}",
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** MessageInjections 中解析后的 toolResults 条目。 */
data class StSpresetInjectedToolResult(
    val id: String = "",
    val name: String = "",
    val content: JsonElement = JsonNull,
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** `extensions.SPreset.MessageInjections` 中的单个注入项。 */
data class StSpresetMessageInjection(
    val id: String = "",
    val enabled: Boolean = false,
    val editorMode: String = "form",
    val reasoning: String = "",
    val signature: String = "",
    val toolCalls: List<StSpresetInjectedToolCall> = emptyList(),
    val toolResults: List<StSpresetInjectedToolResult> = emptyList(),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val editor_mode: String get() = editorMode
    val tool_calls: List<StSpresetInjectedToolCall> get() = toolCalls
    val tool_results: List<StSpresetInjectedToolResult> get() = toolResults
}

data class StSpresetOutputToolCallFormatter(
    val enabled: Boolean = false,
    val script: String = "",
)

data class StSpresetOutputPreprocessing(
    val enabled: Boolean = false,
    val script: String = "",
    val consumeToolCalls: Boolean = false,
    val toolCallFormatter: StSpresetOutputToolCallFormatter = StSpresetOutputToolCallFormatter(),
) {
    val consume_tool_calls: Boolean get() = consumeToolCalls
    val tool_call_formatter: StSpresetOutputToolCallFormatter get() = toolCallFormatter
}

data class StSpresetForcedPostProcessing(
    val enabled: Boolean = false,
    val mode: String = "",
)

data class StSpresetFinalRequestProcessing(
    val enabled: Boolean = false,
    val script: String = "",
)

/**
 * `OutputPreprocessing.script` 的单次执行结果。
 *
 * [output] 是该次调用可见的完整输出（不是增量）；流式调用方需要自行做前缀差量。
 * [hold] 是脚本要求暂缓输出的尾部；[stateJson] 是脚本返回的状态，需在后续调用原样回传。
 */
data class Processed(
    val output: String,
    val hold: String = "",
    val stateJson: String = "{}",
) {
    /** final=true 时把 hold 归并进最终文本，避免最后一段被吞。 */
    fun resolvedFinalOutput(): String {
        if (hold.isBlank()) return output
        if (output.endsWith(hold)) return output
        return output + hold
    }
}

/** `extensions.SPreset` 的完整兼容模型。 */
data class StSpresetConfig(
    val chatSquash: StSpresetChatSquash = StSpresetChatSquash(),
    /** 原样保留的 `RegexBinding` 对象 */
    val regexBinding: JsonObject = JsonObject(emptyMap()),
    /** 从 RegexBinding.enabled / bindings 提取的强制启用 ID */
    val regexEnabledIds: Set<String> = emptySet(),
    /** 从 RegexBinding.disabled / bindings 提取的强制禁用 ID */
    val regexDisabledIds: Set<String> = emptySet(),
    /** RegexBinding.regexes[].scriptName 等名称级强制启用项 */
    val regexEnabledNames: Set<String> = emptySet(),
    /** RegexBinding.regexes[].scriptName 等名称级强制禁用项 */
    val regexDisabledNames: Set<String> = emptySet(),
    val macroNestDepth: Int = 0,
    val toolBindings: List<StSpresetToolBinding> = emptyList(),
    val messageInjections: List<StSpresetMessageInjection> = emptyList(),
    val outputPreprocessing: StSpresetOutputPreprocessing = StSpresetOutputPreprocessing(),
    val fixedPresetName: String = "",
    val forcedPostProcessing: StSpresetForcedPostProcessing = StSpresetForcedPostProcessing(),
    val finalRequestProcessing: StSpresetFinalRequestProcessing = StSpresetFinalRequestProcessing(),
    val version: String = "",
    val enabled: Boolean = true,
    /** 原始 SPreset 对象，确保未知字段不丢失 */
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val chat_squash: StSpresetChatSquash get() = chatSquash
    val regex_binding: JsonObject get() = regexBinding
    val macro_nest_depth: Int get() = macroNestDepth
    val tool_bindings: List<StSpresetToolBinding> get() = toolBindings
    val message_injections: List<StSpresetMessageInjection> get() = messageInjections
    val output_preprocessing: StSpresetOutputPreprocessing get() = outputPreprocessing
    val fixed_preset_name: String get() = fixedPresetName
    val forced_post_processing: StSpresetForcedPostProcessing get() = forcedPostProcessing
    val final_request_processing: StSpresetFinalRequestProcessing get() = finalRequestProcessing
}

internal data class SpresetParameterSchema(
    val properties: JsonObject,
    val required: List<String>,
)

/**
 * 把 ToolBindings.form.parameters 的两种常见形态转换成 JSON Schema：
 * 1. `[{ "name": "content", "type": "string", "required": true }]`
 * 2. 已经是 `{ "type": "object", "properties": {...}, "required": [...] }`
 */
internal fun JsonElement?.toSpresetParameterSchema(): SpresetParameterSchema? {
    return when (this) {
        is JsonArray -> parseParameterArray(this)
        is JsonObject -> parseParameterObject(this)
        else -> null
    }
}

private fun parseParameterArray(items: JsonArray): SpresetParameterSchema {
    val properties = LinkedHashMap<String, JsonElement>()
    val required = mutableListOf<String>()
    items.forEach { element ->
        val item = element as? JsonObject ?: return@forEach
        val name = item.stringValue("name", "key", "parameter", "id")?.trim().orEmpty()
        if (name.isEmpty()) return@forEach
        val explicitSchema = item["schema"] as? JsonObject
        val propertySchema = explicitSchema ?: buildJsonObject {
            item.forEach { (key, value) ->
                if (key !in PARAMETER_META_KEYS) put(key, value)
            }
            if (item["type"] == null) put("type", "string")
        }
        properties[name] = propertySchema
        val requiredValue = item["required"] ?: item["isRequired"] ?: item["is_required"]
        if (requiredValue.booleanValue() == true || requiredValue.stringValue() == "true") {
            required += name
        }
    }
    return SpresetParameterSchema(JsonObject(properties), required)
}

private fun parseParameterObject(obj: JsonObject): SpresetParameterSchema {
    val rawProperties = obj["properties"] as? JsonObject
    if (rawProperties != null) {
        return SpresetParameterSchema(rawProperties, rawPropertiesRequired(obj["required"]))
    }
    // 少数预设把 form.parameters 直接存成 { "content": { "type": "string" } } 映射。
    val direct = obj.entries.filter { (key, value) ->
        key !in SCHEMA_META_KEYS && (value is JsonObject || value is JsonPrimitive)
    }
    if (direct.isNotEmpty()) {
        return SpresetParameterSchema(
            JsonObject(direct.associate { it.key to it.value }),
            rawPropertiesRequired(obj["required"]),
        )
    }
    return SpresetParameterSchema(JsonObject(emptyMap()), rawPropertiesRequired(obj["required"]))
}

private fun rawPropertiesRequired(element: JsonElement?): List<String> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
}

private val PARAMETER_META_KEYS = setOf("name", "key", "parameter", "id", "required", "isRequired", "is_required", "schema")
private val SCHEMA_META_KEYS = setOf("type", "properties", "required", "description", "title", "additionalProperties")

// ---- 宽松 JSON 访问辅助：同时兼容 camelCase / snake_case / PascalCase ----

internal fun normalizePresetKey(key: String): String =
    key.filter { it.isLetterOrDigit() }.lowercase()

internal fun JsonObject.findPresetElement(vararg keys: String): JsonElement? {
    keys.forEach { key ->
        this[key]?.let { return it }
    }
    val normalized = keys.map(::normalizePresetKey).toSet()
    return entries.firstOrNull { normalizePresetKey(it.key) in normalized }?.value
}

internal fun JsonElement?.jsonObjectOrNull(): JsonObject? {
    var current = this ?: return null
    repeat(3) {
        when (current) {
            is JsonObject -> return current
            is JsonPrimitive -> {
                val text = current.contentOrNull?.trim().orEmpty()
                if (!text.startsWith("{")) return null
                current = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(text)
                }.getOrNull() ?: return null
            }
            else -> return null
        }
    }
    return current as? JsonObject
}

internal fun JsonElement?.jsonArrayOrNull(): JsonArray? {
    var current = this ?: return null
    repeat(3) {
        when (current) {
            is JsonArray -> return current
            is JsonPrimitive -> {
                val text = current.contentOrNull?.trim().orEmpty()
                if (!text.startsWith("[")) return null
                current = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(text)
                }.getOrNull() ?: return null
            }
            else -> return null
        }
    }
    return current as? JsonArray
}

internal fun JsonElement?.stringValue(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject, is JsonArray -> toString()
    else -> null
}

internal fun JsonObject.stringValue(vararg keys: String): String? =
    findPresetElement(*keys).stringValue()

internal fun JsonObject.booleanValue(vararg keys: String): Boolean? =
    findPresetElement(*keys).booleanValue()

internal fun JsonObject.intValue(vararg keys: String): Int? =
    findPresetElement(*keys).intValue()

internal fun JsonElement?.booleanValue(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.contentOrNull?.trim()?.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }
}

internal fun JsonElement?.intValue(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    primitive.contentOrNull?.trim()?.toIntOrNull()?.let { return it }
    primitive.booleanOrNull?.let { return if (it) 1 else 0 }
    return null
}

internal fun JsonElement?.booleanOrStringEnabled(): Boolean? = when (this) {
    is JsonPrimitive -> booleanOrNull ?: contentOrNull?.takeIf { it.isNotBlank() }?.let { true }
    is JsonObject -> booleanValue("enabled")
    else -> null
}
