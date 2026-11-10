package me.rerere.rikkahub.data.st.preset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 纯 JSON 解析器，不依赖 Android / QuickJS，方便 host 侧单测。
 *
 * 所有入口都做兜底：非法字段最多被忽略，不会让预设导入失败。
 */
internal object SpresetParser {

    fun parse(spreset: JsonObject?): StSpresetConfig {
        if (spreset == null) return StSpresetConfig()
        return runCatching { parseUnsafe(unwrap(spreset)) }
            .getOrElse { StSpresetConfig(raw = spreset) }
    }

    fun parseActivePreset(tavernPresetsJson: String?, enabledGroups: Set<String>): StSpresetConfig {
        if (tavernPresetsJson.isNullOrBlank()) return StSpresetConfig()
        return runCatching { parseActiveUnsafe(tavernPresetsJson, enabledGroups) }
            .getOrElse { StSpresetConfig() }
    }

    /** 若传入的是完整 preset root（含 extensions.SPreset），自动下钻。 */
    private fun unwrap(obj: JsonObject): JsonObject {
        val extensions = obj.findPresetElement("extensions")?.jsonObjectOrNull() ?: return obj
        return extensions.findPresetElement("SPreset", "spreset")?.jsonObjectOrNull() ?: obj
    }

    private fun parseUnsafe(sp: JsonObject): StSpresetConfig {
        val chatSquashElement = sp.findPresetElement("ChatSquash", "chat_squash")
        val chatSquash = parseChatSquash(chatSquashElement)

        val regexBinding = sp.findPresetElement("RegexBinding", "regex_binding")
            ?.jsonObjectOrNull()
            ?: JsonObject(emptyMap())
        val regexInfo = parseRegexBinding(regexBinding)

        val macroNestDepth = parseMacroNestDepth(sp.findPresetElement("MacroNest", "macro_nest"))

        val toolBindings = parseToolBindings(
            sp.findPresetElement("ToolBindings", "tool_bindings", "tools")
        )
        val messageInjections = parseMessageInjections(
            sp.findPresetElement("MessageInjections", "message_injections", "injections")
        )

        val outputPreprocessing = parseOutputPreprocessing(
            sp.findPresetElement("OutputPreprocessing", "output_preprocessing", "output")
        )
        val fixedPresetName = sp.findPresetElement("FixedPresetName", "fixed_preset_name")
            .stringValue().orEmpty()
        val forcedPostProcessing = parseForcedPostProcessing(
            sp.findPresetElement("ForcedPostProcessing", "forced_post_processing")
        )
        val finalRequestProcessing = parseFinalRequestProcessing(
            sp.findPresetElement("FinalRequestProcessing", "final_request_processing")
        )

        return StSpresetConfig(
            chatSquash = chatSquash,
            regexBinding = regexBinding,
            regexEnabledIds = regexInfo.enabledIds,
            regexDisabledIds = regexInfo.disabledIds,
            regexEnabledNames = regexInfo.enabledNames,
            regexDisabledNames = regexInfo.disabledNames,
            macroNestDepth = macroNestDepth,
            toolBindings = toolBindings,
            messageInjections = messageInjections,
            outputPreprocessing = outputPreprocessing,
            fixedPresetName = fixedPresetName,
            forcedPostProcessing = forcedPostProcessing,
            finalRequestProcessing = finalRequestProcessing,
            version = sp.findPresetElement("version")?.stringValue().orEmpty(),
            enabled = sp.booleanValue("enabled") ?: true,
            raw = sp,
        )
    }

    private fun parseChatSquash(element: JsonElement?): StSpresetChatSquash {
        val defaults = StSpresetChatSquash()
        if (element == null) return defaults
        val obj = element.jsonObjectOrNull()
        if (obj == null) {
            val enabled = element.booleanValue()
            return if (enabled != null) defaults.copy(enabled = enabled) else defaults
        }
        return StSpresetChatSquash(
            enabled = obj.booleanValue("enabled") ?: defaults.enabled,
            conditionalEnabled = obj.booleanValue("conditionalEnabled", "conditional_enabled")
                ?: defaults.conditionalEnabled,
            conditionalTag = obj.stringValue("conditionalTag", "conditional_tag") ?: defaults.conditionalTag,
            separateChatHistory = obj.booleanValue("separateChatHistory", "separate_chat_history")
                ?: defaults.separateChatHistory,
            parseClewd = obj.booleanValue("parseClewd", "parse_clewd") ?: defaults.parseClewd,
            userRoleSystem = obj.booleanValue("userRoleSystem", "user_role_system")
                ?: defaults.userRoleSystem,
            role = obj.stringValue("role")?.trim()?.lowercase()?.takeIf { it in VALID_SQUASH_ROLES }
                ?: defaults.role,
            enableStopString = obj.booleanValue("enableStopString", "enable_stop_string")
                ?: defaults.enableStopString,
            stopString = obj.stringValue("stopString", "stop_string") ?: defaults.stopString,
            userPrefix = obj.stringValue("userPrefix", "user_prefix") ?: defaults.userPrefix,
            userSuffix = obj.stringValue("userSuffix", "user_suffix") ?: defaults.userSuffix,
            charPrefix = obj.stringValue("charPrefix", "char_prefix") ?: defaults.charPrefix,
            charSuffix = obj.stringValue("charSuffix", "char_suffix") ?: defaults.charSuffix,
            prefixSystem = obj.stringValue("prefixSystem", "prefix_system") ?: defaults.prefixSystem,
            suffixSystem = obj.stringValue("suffixSystem", "suffix_system") ?: defaults.suffixSystem,
            enableSquashedSeparator = obj.booleanValue(
                "enableSquashedSeparator",
                "enable_squashed_separator",
            ) ?: defaults.enableSquashedSeparator,
            squashedSeparatorRegex = obj.booleanValue(
                "squashedSeparatorRegex",
                "squashed_separator_regex",
            ) ?: defaults.squashedSeparatorRegex,
            squashedSeparatorString = obj.stringValue(
                "squashedSeparatorString",
                "squashed_separator_string",
            ) ?: defaults.squashedSeparatorString,
            squashedPostScriptEnable = obj.booleanValue(
                "squashedPostScriptEnable",
                "squashed_post_script_enable",
            ) ?: defaults.squashedPostScriptEnable,
            squashedPostScript = obj.stringValue(
                "squashedPostScript",
                "squashed_post_script",
            ) ?: defaults.squashedPostScript,
            reSplit = obj.booleanValue("reSplit", "re_split") ?: defaults.reSplit,
        )
    }

    private data class RegexInfo(
        val enabledIds: Set<String>,
        val disabledIds: Set<String>,
        val enabledNames: Set<String>,
        val disabledNames: Set<String>,
    )

    private fun parseRegexBinding(obj: JsonObject): RegexInfo {
        val enabledIds = linkedSetOf<String>()
        val disabledIds = linkedSetOf<String>()
        val enabledNames = linkedSetOf<String>()
        val disabledNames = linkedSetOf<String>()

        fun markEnabled(id: String?, name: String?) {
            id?.trim()?.takeIf(String::isNotBlank)?.let(enabledIds::add)
            name?.trim()?.takeIf(String::isNotBlank)?.let(enabledNames::add)
        }

        fun markDisabled(id: String?, name: String?) {
            id?.trim()?.takeIf(String::isNotBlank)?.let(disabledIds::add)
            name?.trim()?.takeIf(String::isNotBlank)?.let(disabledNames::add)
        }

        fun collectStateList(element: JsonElement?, enabled: Boolean) {
            (element.jsonArrayOrNull() ?: return).forEach { item ->
                when (item) {
                    is JsonPrimitive -> {
                        val value = item.contentOrNull?.trim().orEmpty()
                        if (value.isNotEmpty()) {
                            if (enabled) markEnabled(value, value) else markDisabled(value, value)
                        }
                    }
                    is JsonObject -> {
                        val id = item.stringValue("id", "uuid", "regexId", "regex_id")
                        val name = item.stringValue("name", "scriptName", "script_name")
                        if (enabled) markEnabled(id, name) else markDisabled(id, name)
                    }
                    else -> Unit
                }
            }
        }

        collectStateList(obj.findPresetElement("enabled", "enabledRegexes", "enabled_regexes"), true)
        collectStateList(obj.findPresetElement("disabled", "disabledRegexes", "disabled_regexes"), false)

        obj.findPresetElement("bindings", "regexBindings", "regex_bindings")
            ?.jsonObjectOrNull()
            ?.forEach { (id, state) ->
                val enabled = when (state) {
                    is JsonPrimitive -> state.booleanValue()
                    is JsonObject -> state.booleanValue("enabled", "forceEnabled", "force_enabled")
                        ?: state.booleanValue("disabled", "forceDisabled", "force_disabled")?.let { !it }
                    else -> null
                }
                when (enabled) {
                    true -> markEnabled(id, null)
                    false -> markDisabled(id, null)
                    null -> Unit
                }
            }

        obj.findPresetElement("regexes", "regexScripts", "regex_scripts")
            ?.jsonArrayOrNull()
            ?.forEach { item ->
                val regex = item as? JsonObject ?: return@forEach
                val id = regex.stringValue("id", "uuid", "regexId", "regex_id")
                val name = regex.stringValue("scriptName", "script_name", "name")
                val disabled = regex.booleanValue("disabled")
                val enabled = regex.booleanValue("enabled")
                when {
                    disabled == true || enabled == false -> markDisabled(id, name)
                    disabled == false || enabled == true -> markEnabled(id, name)
                }
            }

        return RegexInfo(
            enabledIds = enabledIds,
            disabledIds = disabledIds,
            enabledNames = enabledNames,
            disabledNames = disabledNames,
        )
    }

    private fun parseMacroNestDepth(element: JsonElement?): Int {
        if (element == null) return 0
        element.intValue()?.let { return it.coerceIn(0, 5) }
        val obj = element.jsonObjectOrNull() ?: return 0
        val enabled = obj.booleanValue("enabled") ?: false
        if (!enabled) return 0
        val depth = obj.intValue("maxDepth", "max_depth", "depth", "macroNestDepth", "macro_nest_depth")
            ?: 2
        return depth.coerceIn(1, 5)
    }

    private fun parseToolBindings(element: JsonElement?): List<StSpresetToolBinding> {
        val entries = extractObjectEntries(
            element,
            singleObjectKeys = setOf("code", "form", "enabled", "uuid"),
        )
        return entries.mapNotNull { (fallbackId, value) ->
            parseToolBinding(fallbackId, value)
        }
    }

    private fun parseToolBinding(fallbackId: String, element: JsonElement): StSpresetToolBinding? {
        val obj = element.jsonObjectOrNull() ?: return null
        val form = obj.findPresetElement("form")?.jsonObjectOrNull()
        val functionObj = obj.findPresetElement("function")?.jsonObjectOrNull()

        val id = obj.stringValue("id", "uuid", "key") ?: fallbackId
        val uuid = obj.stringValue("uuid", "id") ?: id
        val name = form?.stringValue("name")
            ?: obj.stringValue("name", "resolvedName", "resolved_name", "toolName", "tool_name")
            ?: functionObj?.stringValue("name")
            ?: ""
        val displayName = form?.stringValue("displayName", "display_name")
            ?: obj.stringValue("displayName", "display_name")
            ?: ""
        val description = form?.stringValue("description", "desc")
            ?: obj.stringValue("description", "desc")
            ?: functionObj?.stringValue("description", "desc")
            ?: ""
        val parameters = form?.findPresetElement("parameters", "params", "parameterSchema", "parameter_schema")
            ?: obj.findPresetElement("parameters", "params", "parameterSchema", "parameter_schema")
            ?: functionObj?.findPresetElement("parameters", "params")
        val actionBody = form?.stringValue("actionBody", "action_body")
            ?: obj.stringValue("actionBody", "action_body")
            ?: ""
        val code = obj.stringValue("code", "script", "source").orEmpty()
        val stealth = form?.booleanValue("stealth") ?: obj.booleanValue("stealth") ?: false
        val editorMode = obj.stringValue("editorMode", "editor_mode")
            ?: if (code.isNotBlank()) "code" else "form"
        return StSpresetToolBinding(
            id = id,
            enabled = obj.booleanValue("enabled") ?: false,
            name = name,
            displayName = displayName,
            description = description,
            parameters = parameters,
            form = form,
            actionBody = actionBody,
            code = code,
            stealth = stealth,
            uuid = uuid,
            editorMode = editorMode,
            raw = obj,
        )
    }

    private fun parseMessageInjections(element: JsonElement?): List<StSpresetMessageInjection> {
        val entries = extractObjectEntries(
            element,
            singleObjectKeys = setOf("reasoning", "toolCalls", "tool_calls", "enabled", "editorMode"),
        )
        return entries.mapNotNull { (fallbackId, value) ->
            parseMessageInjection(fallbackId, value)
        }
    }

    private fun parseMessageInjection(
        fallbackId: String,
        element: JsonElement,
    ): StSpresetMessageInjection? {
        val obj = element.jsonObjectOrNull() ?: return null
        val id = obj.stringValue("id", "uuid", "key") ?: fallbackId
        val reasoning = obj.findPresetElement(
            "reasoning",
            "reasoningContent",
            "reasoning_content",
            "content",
        ).stringValue().orEmpty()
        val signature = obj.findPresetElement("signature", "reasoningSignature", "reasoning_signature")
            .stringValue().orEmpty()
        val toolCalls = parseInjectedToolCalls(
            obj.findPresetElement("toolCalls", "tool_calls", "calls")
        )
        val toolResults = parseInjectedToolResults(
            obj.findPresetElement("toolResults", "tool_results", "results")
        )
        return StSpresetMessageInjection(
            id = id,
            enabled = obj.booleanValue("enabled") ?: false,
            editorMode = obj.stringValue("editorMode", "editor_mode") ?: "form",
            reasoning = reasoning,
            signature = signature,
            toolCalls = toolCalls,
            toolResults = toolResults,
            raw = obj,
        )
    }

    private fun parseInjectedToolCalls(element: JsonElement?): List<StSpresetInjectedToolCall> {
        val items = extractObjectEntries(
            element,
            singleObjectKeys = setOf("name", "toolName", "tool_name", "function", "arguments", "args"),
        )
        return items.mapNotNull { (fallbackId, value) ->
            val obj = value.jsonObjectOrNull() ?: return@mapNotNull null
            val function = obj.findPresetElement("function")?.jsonObjectOrNull()
            val id = obj.stringValue("id", "toolCallId", "tool_call_id", "callId", "call_id", "uuid")
                ?: fallbackId
            val name = obj.stringValue("name", "toolName", "tool_name")
                ?: function?.stringValue("name")
                ?: ""
            val argumentsElement = obj.findPresetElement("arguments", "args", "input", "parameters")
                ?: function?.findPresetElement("arguments", "args")
            val arguments = when (argumentsElement) {
                is JsonPrimitive -> argumentsElement.contentOrNull?.takeIf { it.isNotBlank() } ?: "{}"
                is JsonObject, is JsonArray -> argumentsElement.toString()
                else -> "{}"
            }
            StSpresetInjectedToolCall(
                id = id,
                name = name,
                arguments = arguments,
                raw = obj,
            )
        }
    }

    private fun parseInjectedToolResults(element: JsonElement?): List<StSpresetInjectedToolResult> {
        val items = extractObjectEntries(
            element,
            singleObjectKeys = setOf("id", "toolCallId", "tool_call_id", "content", "output", "result"),
        )
        return items.mapNotNull { (fallbackId, value) ->
            val obj = value.jsonObjectOrNull() ?: return@mapNotNull null
            val id = obj.stringValue("id", "toolCallId", "tool_call_id", "callId", "call_id", "uuid")
                ?: fallbackId
            val name = obj.stringValue("name", "toolName", "tool_name") ?: ""
            val content = obj.findPresetElement("content", "output", "result", "response")
                ?: JsonNull
            StSpresetInjectedToolResult(
                id = id,
                name = name,
                content = content,
                raw = obj,
            )
        }
    }

    /**
     * ToolBindings / MessageInjections 既可能是 `{id: {...}}` 映射，也可能是数组。
     * 对数组用下标作为 fallback id；对单个对象则保留其 key。
     */
    private fun extractObjectEntries(
        element: JsonElement?,
        singleObjectKeys: Set<String>,
    ): List<Pair<String, JsonElement>> {
        return when (element) {
            null -> emptyList()
            is JsonArray -> element.mapIndexed { index, value -> index.toString() to value }
            is JsonObject -> {
                val looksSingle = element.keys.any { it in singleObjectKeys }
                if (looksSingle) {
                    listOf("" to element)
                } else {
                    element.entries.map { it.key to it.value }
                }
            }
            is JsonPrimitive -> {
                val parsed = runCatching { Json.parseToJsonElement(element.contentOrNull.orEmpty()) }.getOrNull()
                if (parsed == null || parsed is JsonPrimitive) emptyList() else extractObjectEntries(parsed, singleObjectKeys)
            }
        }
    }

    private fun parseOutputPreprocessing(element: JsonElement?): StSpresetOutputPreprocessing {
        val defaults = StSpresetOutputPreprocessing()
        if (element == null) return defaults
        val obj = element.jsonObjectOrNull()
        if (obj == null) {
            val script = element.stringValue().orEmpty()
            return StSpresetOutputPreprocessing(
                enabled = script.isNotBlank(),
                script = script,
            )
        }
        val script = obj.stringValue("script", "code", "outputScript", "output_script").orEmpty()
        val enabled = obj.booleanValue("enabled") ?: script.isNotBlank()
        val consume = obj.booleanValue("consumeToolCalls", "consume_tool_calls") ?: false
        val formatterElement = obj.findPresetElement(
            "toolCallFormatter",
            "tool_call_formatter",
            "formatter",
        )
        val formatterObj = formatterElement.jsonObjectOrNull()
        val formatterScript = formatterObj?.stringValue("script", "code").orEmpty()
            .ifBlank { formatterElement.stringValue().orEmpty() }
        val formatterEnabled = formatterObj?.booleanValue("enabled") ?: formatterScript.isNotBlank()
        return StSpresetOutputPreprocessing(
            enabled = enabled,
            script = script,
            consumeToolCalls = consume,
            toolCallFormatter = StSpresetOutputToolCallFormatter(
                enabled = formatterEnabled,
                script = formatterScript,
            ),
        )
    }

    private fun parseForcedPostProcessing(element: JsonElement?): StSpresetForcedPostProcessing {
        val defaults = StSpresetForcedPostProcessing()
        if (element == null) return defaults
        val obj = element.jsonObjectOrNull()
        if (obj == null) {
            val primitive = element as? JsonPrimitive
            val bool = primitive?.booleanValue()
            if (bool != null) return StSpresetForcedPostProcessing(enabled = bool)
            val mode = primitive?.contentOrNull.orEmpty()
            return StSpresetForcedPostProcessing(enabled = mode.isNotBlank(), mode = mode)
        }
        val mode = obj.stringValue("mode", "type", "strategy").orEmpty()
        return StSpresetForcedPostProcessing(
            enabled = obj.booleanValue("enabled") ?: mode.isNotBlank(),
            mode = mode,
        )
    }

    private fun parseFinalRequestProcessing(element: JsonElement?): StSpresetFinalRequestProcessing {
        val defaults = StSpresetFinalRequestProcessing()
        if (element == null) return defaults
        val obj = element.jsonObjectOrNull()
        if (obj == null) {
            val script = element.stringValue().orEmpty()
            return StSpresetFinalRequestProcessing(enabled = script.isNotBlank(), script = script)
        }
        val script = obj.stringValue("script", "code").orEmpty()
        return StSpresetFinalRequestProcessing(
            enabled = obj.booleanValue("enabled") ?: script.isNotBlank(),
            script = script,
        )
    }

    private fun parseActiveUnsafe(
        tavernPresetsJson: String,
        enabledGroups: Set<String>,
    ): StSpresetConfig {
        val mapObj = Json.parseToJsonElement(tavernPresetsJson) as? JsonObject
            ?: return StSpresetConfig()

        if (enabledGroups.isNotEmpty()) {
            for (group in enabledGroups) {
                val root = findPresetGroup(mapObj, group) ?: continue
                val spreset = extractSpresetObject(root)
                if (spreset != null) return parse(spreset)
            }
            return StSpresetConfig()
        }

        // enabledGroups 为空：按 FixedPresetName 回退。
        for ((_, value) in mapObj) {
            val root = decodePresetRoot(value) ?: continue
            val spreset = extractSpresetObject(root) ?: continue
            val fixedName = spreset.findPresetElement("FixedPresetName", "fixed_preset_name")
                .stringValue()?.trim().orEmpty()
            if (fixedName.isEmpty()) continue
            val fixedRoot = findPresetGroup(mapObj, fixedName)
            return if (fixedRoot != null) {
                parse(extractSpresetObject(fixedRoot) ?: fixedRoot)
            } else {
                parse(spreset)
            }
        }
        return StSpresetConfig()
    }

    private fun findPresetGroup(mapObj: JsonObject, group: String): JsonObject? {
        mapObj[group]?.let { return decodePresetRoot(it) }
        val normalized = normalizePresetKey(group)
        val matching = mapObj.entries.firstOrNull { normalizePresetKey(it.key) == normalized } ?: return null
        return decodePresetRoot(matching.value)
    }

    private fun decodePresetRoot(element: JsonElement?): JsonObject? {
        var current = element ?: return null
        repeat(3) {
            when (current) {
                is JsonObject -> return current
                is JsonPrimitive -> {
                    val text = current.contentOrNull?.trim().orEmpty()
                    if (!text.startsWith("{")) return null
                    current = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
                }
                else -> return null
            }
        }
        return current as? JsonObject
    }

    private fun extractSpresetObject(root: JsonObject): JsonObject? {
        val extensions = root.findPresetElement("extensions")?.jsonObjectOrNull()
        if (extensions != null) {
            extensions.findPresetElement("SPreset", "spreset")?.jsonObjectOrNull()?.let { return it }
        }
        // 兼容 Settings.tavernPresets 中直接存 SPreset 对象的非常规形态。
        return root.takeIf {
            it.findPresetElement("ChatSquash", "ToolBindings", "OutputPreprocessing") != null
        }
    }

    private val VALID_SQUASH_ROLES = setOf("system", "user", "assistant", "follow")

    /** 供 host 测试/调试使用：不经过 QuickJS 的轻量 schema 描述。 */
    fun toolDefinitionFromForm(binding: StSpresetToolBinding): JsonObject? {
        val schema = binding.parameters.toSpresetParameterSchema() ?: return null
        val name = binding.effectiveName.takeIf(String::isNotBlank) ?: return null
        return buildJsonObject {
            put("name", name)
            put("description", binding.description)
            put(
                "parameters",
                buildJsonObject {
                    put("type", "object")
                    put("properties", schema.properties)
                    if (schema.required.isNotEmpty()) {
                        put("required", JsonArray(schema.required.map { JsonPrimitive(it) }))
                    }
                },
            )
        }
    }
}
