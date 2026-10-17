package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant

// 酒馆助手预设 Preset <-> RikkaHub Assistant。字段对齐 @types/function/preset.d.ts。
// 预设内容本体以原始 JSON 落盘，保证 get/set 无损；'in_use' 由当前助手参数合成。

private val PLACEHOLDER_PROMPT_IDS = setOf(
    "worldInfoBefore", "personaDescription", "charDescription", "charPersonality",
    "scenario", "worldInfoAfter", "dialogueExamples", "chatHistory",
)

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content

private fun JsonObject.num(key: String): Double? =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

// 当前助手 -> Preset（'in_use'）
fun assistantToPresetJson(assistant: Assistant): JsonObject = buildJsonObject {
    put(
        "settings",
        buildJsonObject {
            put("max_context", JsonPrimitive(assistant.contextMessageLimit.takeIf { it > 0 } ?: 2000000))
            put("max_completion_tokens", JsonPrimitive(assistant.maxTokens ?: 300))
            put("reply_count", JsonPrimitive(1))
            put("should_stream", JsonPrimitive(assistant.streamOutput))
            put("temperature", JsonPrimitive(assistant.temperature?.toDouble() ?: 1.0))
            put("frequency_penalty", JsonPrimitive(0))
            put("presence_penalty", JsonPrimitive(0))
            put("repetition_penalty", JsonPrimitive(1))
            put("top_p", JsonPrimitive(assistant.topP?.toDouble() ?: 1.0))
            put("min_p", JsonPrimitive(0))
            put("top_k", JsonPrimitive(assistant.topK ?: 0))
            put("top_a", JsonPrimitive(0))
            put("seed", JsonPrimitive(-1))
            put("squash_system_messages", JsonPrimitive(false))
            put("reasoning_effort", JsonPrimitive("auto"))
            put("request_thoughts", JsonPrimitive(assistant.reasoningLevel.isEnabled))
            put("request_images", JsonPrimitive(false))
            put("enable_function_calling", JsonPrimitive(assistant.localTools.isNotEmpty()))
            put("enable_web_search", JsonPrimitive(assistant.enableWebSearch))
            put("allow_sending_images", JsonPrimitive("disabled"))
            put("allow_sending_videos", JsonPrimitive(false))
            put("character_name_prefix", JsonPrimitive("none"))
            put("wrap_user_messages_in_quotes", JsonPrimitive(false))
        },
    )
    val prompts = buildJsonArray {
        assistant.presetMessages.forEachIndexed { index, message ->
            val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            add(
                buildJsonObject {
                    put("id", JsonPrimitive("preset_$index"))
                    put("name", JsonPrimitive(""))
                    put("enabled", JsonPrimitive(true))
                    put("position", JsonPrimitive("relative"))
                    put("role", JsonPrimitive(if (message.role == MessageRole.ASSISTANT) "assistant" else "user"))
                    put("content", JsonPrimitive(text))
                },
            )
        }
    }
    put("prompts", prompts)
    put("prompts_unused", buildJsonArray { })
    put("extensions", buildJsonObject { })
}

// Preset -> Assistant：把预设生成参数与提示词写回助手，使其真正影响生成。
fun applyPresetToAssistant(assistant: Assistant, preset: JsonObject): Assistant {
    val settingsObj = preset["settings"] as? JsonObject
    val prompts = (preset["prompts"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val contentPrompts = prompts.filter { prompt ->
        val id = prompt.str("id").orEmpty()
        val hasContent = !prompt.str("content").isNullOrEmpty()
        val position = prompt["position"]
        val relative = position is JsonPrimitive && position.content == "relative"
        id !in PLACEHOLDER_PROMPT_IDS && hasContent && relative && prompt.bool("enabled") != false
    }
    val messages = contentPrompts.map { prompt ->
        val content = prompt.str("content").orEmpty()
        val role = when (prompt.str("role")) {
            "assistant" -> MessageRole.ASSISTANT
            "system" -> MessageRole.SYSTEM
            else -> MessageRole.USER
        }
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(content)))
    }
    return assistant.copy(
        temperature = settingsObj?.num("temperature")?.toFloat() ?: assistant.temperature,
        topP = settingsObj?.num("top_p")?.toFloat() ?: assistant.topP,
        topK = settingsObj?.int("top_k") ?: assistant.topK,
        maxTokens = settingsObj?.int("max_completion_tokens") ?: assistant.maxTokens,
        streamOutput = settingsObj?.bool("should_stream") ?: assistant.streamOutput,
        enableWebSearch = settingsObj?.bool("enable_web_search") ?: assistant.enableWebSearch,
        presetMessages = if (contentPrompts.isNotEmpty()) messages else assistant.presetMessages,
    )
}
