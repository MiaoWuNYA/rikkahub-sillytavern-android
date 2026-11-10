package me.rerere.rikkahub.data.st.preset

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * MessageInjections 执行器：把 enabled 注入项展开为 assistant 消息，插到末尾真实 user 之前。
 * 解析失败/无可注入内容时跳过，绝不影响原有消息。
 */
internal object SpresetMessageInjectionEngine {
    suspend fun apply(
        messages: List<UIMessage>,
        config: StSpresetConfig,
    ): List<UIMessage> = presetRunCatching(messages) {
        if (!config.enabled) return@presetRunCatching messages
        val injections = config.messageInjections.filter { it.enabled }
        if (injections.isEmpty()) return@presetRunCatching messages

        val insertAt = messages.indexOfLast { it.role == MessageRole.USER }
            .let { if (it >= 0) it else messages.size }
        val generated = injections.mapNotNull { buildInjectionMessage(it) }
        if (generated.isEmpty()) return@presetRunCatching messages

        buildList {
            addAll(messages.take(insertAt))
            addAll(generated)
            addAll(messages.drop(insertAt))
        }
    }

    private fun buildInjectionMessage(injection: StSpresetMessageInjection): UIMessage? {
        val parts = mutableListOf<UIMessagePart>()

        if (injection.reasoning.isNotBlank()) {
            val metadata = if (injection.signature.isNotBlank()) {
                buildJsonObject { put("signature", injection.signature) }
            } else {
                null
            }
            parts += UIMessagePart.Reasoning(
                reasoning = injection.reasoning,
                metadata = metadata,
            )
        }

        injection.toolCalls.forEachIndexed { index, call ->
            if (call.name.isBlank()) return@forEachIndexed
            val toolCallId = call.id.ifBlank { "spreset_${injection.id}_$index" }
            parts += UIMessagePart.Tool(
                toolCallId = toolCallId,
                toolName = call.name,
                input = call.arguments.ifBlank { "{}" },
            )
        }

        injection.toolResults.forEach { result ->
            if (result.id.isBlank()) return@forEach
            val index = parts.indexOfFirst { part ->
                part is UIMessagePart.Tool && part.toolCallId == result.id
            }
            if (index < 0) return@forEach
            val toolPart = parts[index] as UIMessagePart.Tool
            val outputParts = toolResultContentToParts(result.content)
            parts[index] = toolPart.copy(output = toolPart.output + outputParts)
        }

        if (parts.isEmpty()) return null
        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts,
        )
    }

    private fun toolResultContentToParts(content: JsonElement): List<UIMessagePart> {
        return when (content) {
            is JsonPrimitive -> listOf(UIMessagePart.Text(content.contentOrNull.orEmpty()))
            is JsonArray -> content.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { UIMessagePart.Text(it) }
                    is JsonObject -> jsonPartToUiPart(element)
                    else -> null
                }
            }
            is JsonObject -> listOfNotNull(jsonPartToUiPart(content))
        }
    }

    private fun jsonPartToUiPart(obj: JsonObject): UIMessagePart? {
        val type = obj.stringValue("type")?.lowercase()
        return when (type) {
            "text", "input_text", "output_text" -> {
                obj.stringValue("text", "content")?.let { UIMessagePart.Text(it) }
            }
            "image_url", "image", "input_image" -> {
                val image = obj.findPresetElement("image_url", "imageUrl", "image", "url")
                val url = when (image) {
                    is JsonPrimitive -> image.contentOrNull
                    is JsonObject -> image.stringValue("url", "data")
                    else -> obj.stringValue("url", "data")
                }
                url?.takeIf(String::isNotBlank)?.let { UIMessagePart.Image(it) }
            }
            else -> obj.stringValue("text", "content")
                ?.takeIf(String::isNotBlank)
                ?.let { UIMessagePart.Text(it) }
        }
    }
}
