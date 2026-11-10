package me.rerere.rikkahub.data.st.preset

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap

/**
 * SPreset 全字段兼容入口。
 *
 * 约束：
 * - `parse` / `parseActivePreset` 是纯 JSON 解析，不接触 QuickJS；
 * - 所有公开 suspend 函数内部 runCatching 降级，脚本异常不会穿透到生成流程；
 * - 所有 JS 脚本/参数通过 JsonPrimitive 序列化后注入，不拼接未转义文本。
 */
object SpresetCompat {
    private const val OUTPUT_FN = "__rikkahub_spreset_output"
    private const val FORMATTER_FN = "__rikkahub_spreset_formatter"
    private const val FINAL_REQUEST_FN = "__rikkahub_spreset_final_request"

    private val resolvedToolCache = ConcurrentHashMap<String, JsonObject>()

    /** 已支持的全部 SPreset 扩展私有键（用于导入告警过滤）。 */
    fun supportedKeys(): Set<String> = setOf(
        "ChatSquash",
        "RegexBinding",
        "MacroNest",
        "ToolBindings",
        "MessageInjections",
        "OutputPreprocessing",
        "FixedPresetName",
        "ForcedPostProcessing",
        "FinalRequestProcessing",
        "version",
        "enabled",
    )

    fun parse(spreset: JsonObject?): StSpresetConfig = SpresetParser.parse(spreset)

    fun parseActivePreset(
        tavernPresetsJson: String?,
        enabledGroups: Set<String>,
    ): StSpresetConfig = SpresetParser.parseActivePreset(tavernPresetsJson, enabledGroups)

    fun clearCaches() {
        resolvedToolCache.clear()
    }

    suspend fun executeToolAction(
        binding: StSpresetToolBinding,
        argsJson: String,
    ): String? = presetRunCatching(null) {
        if (binding.code.isBlank() && binding.actionBody.isBlank()) {
            return@presetRunCatching null
        }
        val safeArgsJson = if (parseJsonElementOrNull(argsJson) != null) argsJson else "{}"
        val script = buildToolActionScript(binding, safeArgsJson)
        withSpresetQuickJs {
            installSpresetConsole()
            evaluate<String?>(script)
        }
    }

    suspend fun resolveToolDefinition(binding: StSpresetToolBinding): JsonObject? =
        presetRunCatching(null) {
            val formDefinition = SpresetParser.toolDefinitionFromForm(binding)
            if (formDefinition != null) {
                // form 已同时提供 name + parameters，足够构造 Tool，无需执行 code。
                return@presetRunCatching formDefinition
            }
            if (binding.code.isBlank()) return@presetRunCatching null

            val cacheKey = bindingCacheKey(binding)
            resolvedToolCache[cacheKey]?.let { return@presetRunCatching it }

            val codeLiteral = JsonPrimitive(binding.code).toString()
            val resolvedRaw = withSpresetQuickJs {
                installSpresetConsole()
                evaluate<String?>(
                    """
                    (() => {
                      const tool = new Function($codeLiteral)();
                      if (!tool || typeof tool !== 'object') {
                        throw new Error('SPreset tool code did not return an object');
                      }
                      return JSON.stringify({
                        name: tool.name ?? null,
                        description: tool.description ?? null,
                        parameters: tool.parameters ?? null
                      });
                    })()
                    """.trimIndent()
                )
            }
            val resolved = resolvedRaw?.let { parseJsonElementOrNull(it)?.jsonObjectOrNull() }
                ?: return@presetRunCatching null

            val fallbackSchema = binding.parameters.toSpresetParameterSchema()
            val definition = buildJsonObject {
                put(
                    "name",
                    resolved.stringValue("name")?.takeIf(String::isNotBlank)
                        ?: binding.effectiveName,
                )
                put(
                    "description",
                    resolved.stringValue("description")
                        ?: binding.description,
                )
                val parameters = resolved.findPresetElement("parameters", "params")
                    ?: fallbackSchema?.let { schema ->
                        buildJsonObject {
                            put("type", "object")
                            put("properties", schema.properties)
                            if (schema.required.isNotEmpty()) {
                                put("required", JsonArray(schema.required.map { JsonPrimitive(it) }))
                            }
                        }
                    }
                if (parameters != null) put("parameters", parameters)
            }
            resolvedToolCache[cacheKey] = definition
            definition
        }

    suspend fun processOutput(
        p: StSpresetOutputPreprocessing,
        buffer: String,
        final: Boolean,
        stateJson: String,
    ): Processed? = presetRunCatching(null) {
        if (!p.enabled || p.script.isBlank()) return@presetRunCatching null
        val argsJson = buildJsonObject {
            put("buffer", buffer)
            put("final", final)
            put("state", parseJsonElementOrNull(stateJson) ?: JsonObject(emptyMap()))
        }.toString()
        val raw = withSpresetQuickJs {
            installSpresetConsole()
            installSpresetFunction(OUTPUT_FN, p.script)
            callSpresetFunction(OUTPUT_FN, argsJson)
        } ?: return@presetRunCatching null
        parseProcessedResult(raw, buffer, stateJson)
    }

    suspend fun formatToolCalls(
        p: StSpresetOutputPreprocessing,
        callsJson: String,
    ): String? = presetRunCatching(null) {
        val formatter = p.toolCallFormatter
        if (!formatter.enabled || formatter.script.isBlank()) return@presetRunCatching null
        val callsElement = parseJsonElementOrNull(callsJson) ?: JsonArray(emptyList())
        val argsJson = buildJsonObject {
            put("calls", callsElement)
        }.toString()
        val raw = withSpresetQuickJs {
            installSpresetConsole()
            installSpresetFunction(FORMATTER_FN, formatter.script)
            callSpresetFunction(FORMATTER_FN, argsJson)
        } ?: return@presetRunCatching null
        normalizeScriptStringResult(raw)
    }

    suspend fun processFinalRequest(
        p: StSpresetFinalRequestProcessing,
        requestJson: String,
    ): String? = presetRunCatching(null) {
        if (!p.enabled || p.script.isBlank()) return@presetRunCatching null
        val request = parseJsonElementOrNull(requestJson) ?: JsonObject(emptyMap())
        val argsJson = buildJsonObject {
            put("request", request)
        }.toString()
        withSpresetQuickJs {
            installSpresetConsole()
            installSpresetFunction(FINAL_REQUEST_FN, p.script)
            callSpresetFunction(FINAL_REQUEST_FN, argsJson)
        }
    }

    suspend fun createDynamicTools(
        config: StSpresetConfig,
        existingNames: Set<String>,
    ): List<Tool> = presetRunCatching(emptyList<Tool>()) {
        if (!config.enabled) return@presetRunCatching emptyList()
        val occupied = existingNames.map { it.lowercase() }.toMutableSet()
        val result = mutableListOf<Tool>()
        config.toolBindings.filter { it.enabled }.forEachIndexed { index, binding ->
            runCatching {
                val fallbackName = "spreset_tool_$index"
                val sanitized = sanitizeToolName(
                    binding.effectiveName.ifBlank { fallbackName }
                )
                if (!occupied.add(sanitized.lowercase())) return@runCatching

                var schema = binding.parameters.toSpresetParameterSchema()
                var description = binding.description
                if (schema == null || description.isBlank()) {
                    val resolved = resolveToolDefinition(binding)
                    if (schema == null) {
                        schema = resolved?.findPresetElement("parameters", "params")
                            .toSpresetParameterSchema()
                    }
                    if (description.isBlank()) {
                        description = resolved?.stringValue("description").orEmpty()
                    }
                }
                val properties = schema?.properties ?: JsonObject(emptyMap())
                val required = schema?.required.orEmpty()

                result += Tool(
                    name = sanitized,
                    description = description.ifBlank {
                        binding.displayName.ifBlank { sanitized }
                    },
                    parameters = {
                        InputSchema.Obj(
                            properties = properties,
                            required = required.takeIf { it.isNotEmpty() },
                        )
                    },
                    execute = { args ->
                        val output = executeToolAction(binding, args.toString())
                        listOf(UIMessagePart.Text(output ?: ""))
                    },
                )
            }
        }
        result
    }

    suspend fun applyChatSquash(
        messages: List<UIMessage>,
        cfg: StSpresetChatSquash,
        userName: String,
        charName: String,
    ): List<UIMessage> = SpresetChatSquashEngine.apply(messages, cfg, userName, charName)

    suspend fun applyMessageInjections(
        messages: List<UIMessage>,
        config: StSpresetConfig,
    ): List<UIMessage> = SpresetMessageInjectionEngine.apply(messages, config)

    suspend fun finalizeAssistantOutput(
        messages: List<UIMessage>,
        config: StSpresetConfig,
        toolCallsAlreadyFormatted: Boolean,
    ): List<UIMessage> = presetRunCatching(messages) {
        if (!config.enabled) return@presetRunCatching messages
        val index = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (index < 0) return@presetRunCatching messages

        val p = config.outputPreprocessing
        if (!p.enabled) return@presetRunCatching messages

        var message = messages[index]
        if (p.script.isNotBlank() && message.parts.any { it is UIMessagePart.Text }) {
            val processed = processOutput(
                p = p,
                buffer = message.toText(),
                final = true,
                stateJson = "{}",
            )
            if (processed != null) {
                message = message.copy(
                    parts = replaceTextParts(message.parts, processed.resolvedFinalOutput())
                )
            }
        }

        if (p.consumeToolCalls && !toolCallsAlreadyFormatted) {
            val tools = message.parts.filterIsInstance<UIMessagePart.Tool>()
            if (tools.isNotEmpty() && p.toolCallFormatter.enabled && p.toolCallFormatter.script.isNotBlank()) {
                val formatted = formatToolCalls(p, toolPartsToCallsJson(tools).toString())
                if (formatted != null) {
                    message = message.copy(parts = replaceToolParts(message.parts, formatted))
                }
            }
        }

        messages.toMutableList().also { it[index] = message }
    }

    suspend fun applyFinalRequestProcessing(
        messages: List<UIMessage>,
        tools: List<Tool>,
        p: StSpresetFinalRequestProcessing,
    ): Pair<List<UIMessage>, List<Tool>>? = presetRunCatching(null) {
        if (!p.enabled || p.script.isBlank()) return@presetRunCatching null

        val requestJson = buildJsonObject {
            put("messages", buildJsonArray {
                messages.forEach { add(it.toOpenAiStyleJson()) }
            })
            put("tools", buildJsonArray {
                tools.forEach { add(it.toOpenAiStyleJson()) }
            })
        }.toString()

        val resultRaw = processFinalRequest(p, requestJson) ?: return@presetRunCatching null
        val resultElement = parseJsonElementOrNull(resultRaw) ?: return@presetRunCatching null
        val resultObject = resultElement as? JsonObject
        val returnedMessages = when (val element = resultObject?.findPresetElement("messages") ?: resultElement) {
            is JsonArray -> element
            else -> return@presetRunCatching null
        }
        val parsedMessages = parseOpenAiStyleMessages(returnedMessages)
        if (parsedMessages.isEmpty()) return@presetRunCatching null

        val returnedTools = resultObject?.findPresetElement("tools") as? JsonArray
        val finalTools = if (returnedTools != null) {
            parseOpenAiStyleTools(returnedTools, tools)
        } else {
            tools
        }
        parsedMessages to finalTools
    }

    fun processStream(
        input: Flow<StreamChunk>,
        config: StSpresetConfig,
    ): Flow<StreamChunk> = SpresetStreamEngine.process(input, config)

    // ---- QuickJS 调用脚本构造 ----

    private fun buildToolActionScript(binding: StSpresetToolBinding, argsJson: String): String {
        val argsLiteral = JsonPrimitive(argsJson).toString()
        val resultHandling = """
            if (value === undefined || value === null) return null;
            if (typeof value === 'string') return value;
            return JSON.stringify(value);
        """.trimIndent()
        return if (binding.code.isNotBlank()) {
            val codeLiteral = JsonPrimitive(binding.code).toString()
            """
            (async () => {
              const args = JSON.parse($argsLiteral);
              const tool = new Function($codeLiteral)();
              if (!tool || typeof tool.action !== 'function') {
                throw new Error('SPreset tool action is not a function');
              }
              const value = await tool.action(args);
              $resultHandling
            })()
            """.trimIndent()
        } else {
            val bodyLiteral = JsonPrimitive(binding.actionBody).toString()
            """
            (async () => {
              const args = JSON.parse($argsLiteral);
              const fn = new Function('args', $bodyLiteral);
              const value = await fn(args);
              $resultHandling
            })()
            """.trimIndent()
        }
    }

    private fun parseProcessedResult(raw: String, buffer: String, stateJson: String): Processed {
        val element = parseJsonElementOrNull(raw)
        if (element is JsonObject) {
            val outputElement = element.findPresetElement("output", "text", "value", "content")
            val holdElement = element.findPresetElement("hold", "remainder", "pending")
            val stateElement = element.findPresetElement("state", "stateJson", "state_json")
            val output = when {
                outputElement != null -> outputElement.stringValue().orEmpty()
                stateElement != null || holdElement != null -> buffer
                else -> buffer
            }
            val hold = holdElement.stringValue().orEmpty()
            val newState = stateElement?.toString() ?: stateJson
            return Processed(output = output, hold = hold, stateJson = newState)
        }
        if (element is JsonPrimitive && element.isString) {
            return Processed(output = element.contentOrNull.orEmpty(), stateJson = stateJson)
        }
        if (element != null && element !is JsonNull) {
            return Processed(output = raw, stateJson = stateJson)
        }
        // 非 JSON 文本按普通字符串处理；显式空字符串代表脚本选择输出空内容。
        return Processed(output = raw, stateJson = stateJson)
    }

    private fun normalizeScriptStringResult(raw: String): String? {
        if (raw.isEmpty()) return ""
        val element = parseJsonElementOrNull(raw) ?: return raw
        return when (element) {
            is JsonPrimitive -> if (element.isString) element.contentOrNull.orEmpty() else element.toString()
            else -> raw
        }
    }

    private fun bindingCacheKey(binding: StSpresetToolBinding): String =
        "${binding.id}|${binding.code.hashCode()}|${binding.parameters?.hashCode() ?: 0}"

    // ---- Tool / message JSON 互转 ----

    private fun UIMessage.toOpenAiStyleJson(): JsonObject {
        val textParts = parts.filterIsInstance<UIMessagePart.Text>()
        val rich = parts.any {
            it is UIMessagePart.Image || it is UIMessagePart.Video ||
                it is UIMessagePart.Audio || it is UIMessagePart.Document
        }
        val content: JsonElement = if (rich) {
            buildJsonArray {
                textParts.forEach { part ->
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", part.text)
                    })
                }
                parts.forEach { part ->
                    when (part) {
                        is UIMessagePart.Image -> add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", part.url) })
                        })
                        is UIMessagePart.Document -> add(buildJsonObject {
                            put("type", "text")
                            put("text", part.fileName)
                        })
                        else -> Unit
                    }
                }
            }
        } else {
            JsonPrimitive(textParts.joinToString("\n") { it.text })
        }

        val reasoning = parts.filterIsInstance<UIMessagePart.Reasoning>()
            .joinToString("\n") { it.reasoning }
        val toolCalls = parts.filterIsInstance<UIMessagePart.Tool>()

        return buildJsonObject {
            put("role", role.name.lowercase())
            put("content", content)
            name?.takeIf(String::isNotBlank)?.let { put("name", it) }
            if (reasoning.isNotBlank()) put("reasoning_content", reasoning)
            if (toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    toolCalls.forEach { part ->
                        add(buildJsonObject {
                            put("id", part.toolCallId)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", part.toolName)
                                put("arguments", part.input.ifBlank { "{}" })
                            })
                        })
                    }
                })
            }
        }
    }

    private fun Tool.toOpenAiStyleJson(): JsonObject {
        val parametersJson = runCatching { parameters() }.getOrNull().toInputSchemaJson()
        return buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                if (parametersJson != null) {
                    put("parameters", parametersJson)
                } else {
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", JsonObject(emptyMap()))
                    })
                }
            })
        }
    }

    private fun InputSchema?.toInputSchemaJson(): JsonObject? = when (this) {
        is InputSchema.Obj -> buildJsonObject {
            put("type", "object")
            put("properties", properties)
            required?.takeIf { it.isNotEmpty() }?.let { requiredNames ->
                put("required", buildJsonArray { requiredNames.forEach { add(JsonPrimitive(it)) } })
            }
        }
        else -> null
    }

    private fun parseOpenAiStyleMessages(array: JsonArray): List<UIMessage> {
        val result = mutableListOf<UIMessage>()
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val role = obj.stringValue("role")
                ?.trim()
                ?.lowercase()
                ?.let { name ->
                    when (name) {
                        "model", "ai" -> MessageRole.ASSISTANT
                        "human" -> MessageRole.USER
                        "function" -> MessageRole.TOOL
                        else -> MessageRole.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    }
                }
                ?: return@forEach

            val parts = mutableListOf<UIMessagePart>()
            when (val content = obj.findPresetElement("content")) {
                is JsonPrimitive -> content.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { parts += UIMessagePart.Text(it) }
                is JsonArray -> content.forEach { partElement ->
                    partElement.toUiContentPart()?.let(parts::add)
                }
                is JsonObject -> content.toUiContentPart()?.let(parts::add)
                else -> Unit
            }

            obj.stringValue("reasoning_content", "reasoning", "thinking")
                ?.takeIf(String::isNotBlank)
                ?.let { parts += UIMessagePart.Reasoning(reasoning = it) }

            obj.findPresetElement("tool_calls", "toolCalls")
                ?.jsonArrayOrNull()
                ?.forEach { callElement ->
                    callElement.toUiToolPart()?.let(parts::add)
                }

            if (parts.isEmpty() && role != MessageRole.ASSISTANT) return@forEach
            result += UIMessage(
                role = role,
                parts = parts,
                name = obj.stringValue("name")?.takeIf(String::isNotBlank),
            )
        }
        return result
    }

    private fun JsonElement.toUiContentPart(): UIMessagePart? {
        if (this is JsonPrimitive) {
            return contentOrNull?.takeIf { it.isNotEmpty() }?.let { value -> UIMessagePart.Text(value) }
        }
        val obj = this as? JsonObject ?: return null
        val type = obj.stringValue("type")?.lowercase()
        return when (type) {
            "text", "input_text", "output_text" -> {
                val text = obj.stringValue("text", "content")
                text?.let { value -> UIMessagePart.Text(value) }
            }
            "image_url", "input_image", "image" -> {
                val image = obj.findPresetElement("image_url", "imageUrl", "image")
                val url = when (image) {
                    is JsonPrimitive -> image.contentOrNull
                    is JsonObject -> image.stringValue("url", "data")
                    else -> obj.stringValue("url", "data")
                }
                url?.takeIf(String::isNotBlank)?.let { value -> UIMessagePart.Image(value) }
            }
            else -> obj.stringValue("text", "content")
                ?.takeIf(String::isNotBlank)
                ?.let { value -> UIMessagePart.Text(value) }
        }
    }

    private fun JsonElement.toUiToolPart(): UIMessagePart.Tool? {
        val obj = this as? JsonObject ?: return null
        val function = obj.findPresetElement("function")?.jsonObjectOrNull()
        val id = obj.stringValue("id", "toolCallId", "tool_call_id", "callId", "call_id").orEmpty()
        val name = obj.stringValue("name", "toolName", "tool_name")
            ?: function?.stringValue("name")
            ?: return null
        val argumentsElement = obj.findPresetElement("arguments", "args", "input")
            ?: function?.findPresetElement("arguments", "args")
        val input = when (argumentsElement) {
            is JsonPrimitive -> argumentsElement.contentOrNull.orEmpty()
            is JsonObject, is JsonArray -> argumentsElement.toString()
            else -> "{}"
        }
        return UIMessagePart.Tool(
            toolCallId = id,
            toolName = name,
            input = input.ifBlank { "{}" },
        )
    }

    private fun parseOpenAiStyleTools(array: JsonArray, originalTools: List<Tool>): List<Tool> {
        val originalsByName = originalTools.associateBy { it.name }
        val result = mutableListOf<Tool>()
        val seen = mutableSetOf<String>()
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val function = obj.findPresetElement("function")?.jsonObjectOrNull() ?: obj
            val name = function.stringValue("name")?.trim()?.takeIf(String::isNotBlank)
                ?: return@forEach
            if (!seen.add(name)) return@forEach
            result += originalsByName[name] ?: buildNoopTool(name, function)
        }
        return result
    }

    private fun buildNoopTool(name: String, function: JsonObject): Tool {
        val schemaElement = function.findPresetElement("parameters", "params")
        val schema = schemaElement.toSpresetParameterSchema()
        return Tool(
            name = name,
            description = function.stringValue("description").orEmpty(),
            parameters = {
                InputSchema.Obj(
                    properties = schema?.properties ?: JsonObject(emptyMap()),
                    required = schema?.required?.takeIf { it.isNotEmpty() },
                )
            },
            execute = { emptyList() },
        )
    }

    private fun toolPartsToCallsJson(tools: List<UIMessagePart.Tool>): JsonArray = buildJsonArray {
        tools.forEach { part ->
            add(buildJsonObject {
                put("id", part.toolCallId)
                put("name", part.toolName)
                put("arguments", parseArgumentsElement(part.input))
            })
        }
    }

    private fun replaceTextParts(parts: List<UIMessagePart>, text: String): List<UIMessagePart> {
        val result = mutableListOf<UIMessagePart>()
        var inserted = false
        parts.forEach { part ->
            if (part is UIMessagePart.Text) {
                if (!inserted) {
                    result += UIMessagePart.Text(text)
                    inserted = true
                }
            } else {
                result += part
            }
        }
        if (!inserted) result += UIMessagePart.Text(text)
        return result
    }

    private fun replaceToolParts(parts: List<UIMessagePart>, formatted: String): List<UIMessagePart> {
        val result = mutableListOf<UIMessagePart>()
        var inserted = false
        parts.forEach { part ->
            if (part is UIMessagePart.Tool) {
                if (!inserted && formatted.isNotEmpty()) {
                    result += UIMessagePart.Text(formatted)
                    inserted = true
                }
            } else {
                result += part
            }
        }
        return result
    }

    private fun sanitizeToolName(raw: String): String {
        var name = raw.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
        name = name.replace(Regex("_+"), "_").trim('_')
        if (name.isEmpty()) name = "spreset_tool"
        if (name.firstOrNull()?.isDigit() == true) name = "spreset_$name"
        return name.take(64)
    }
}
