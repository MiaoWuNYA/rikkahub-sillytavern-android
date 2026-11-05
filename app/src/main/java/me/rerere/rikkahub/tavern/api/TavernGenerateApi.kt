package me.rerere.rikkahub.tavern.api

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings

/** ST 的 /api/backends/chat-completions/generate，转给 App 现有 provider 生成管线。 */
class TavernGenerateApi(
    private val settingsProvider: () -> Settings,
    private val generate: suspend (List<UIMessage>, Model) -> String,
    private val resolveModel: (Settings) -> Model?,
) : TavernApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun handle(request: TavernRequest): TavernReply? {
        if (request.path != PATH) return null
        return try {
            handleGenerate(request.bodyText())
        } catch (e: Exception) {
            Log.w(TAG, "generate failed", e)
            TavernReply.Text(500, e.message ?: e::class.java.simpleName)
        }
    }

    private suspend fun handleGenerate(bodyText: String): TavernReply {
        val root = json.parseToJsonElement(bodyText.ifBlank { "{}" }) as? JsonObject
            ?: return TavernReply.Text(500, "invalid body")
        val messages = parseMessages(root["messages"])
        if (messages.isEmpty()) return TavernReply.Text(500, "empty messages")
        val settings = settingsProvider()
        val model = resolveModel(settings) ?: return TavernReply.Text(500, "no chat model")
        val requestedModel = root["model"].strValue().orEmpty()
        val source = root["chat_completion_source"].strValue()
        val maxTokens = root["max_tokens"].strValue()
        val temperature = root["temperature"].strValue()
        val stream = root["stream"]?.jsonPrimitive?.booleanOrNull ?: false
        Log.d(TAG, "generate model=$requestedModel source=$source stream=$stream max_tokens=$maxTokens temperature=$temperature")
        val text = generate(messages, model)
        val responseModel = model.modelId
        return if (stream) {
            TavernReply.Json(200, buildSse(text, responseModel))
        } else {
            TavernReply.Json(200, buildCompletion(text, responseModel))
        }
    }

    private fun parseMessages(element: JsonElement?): List<UIMessage> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val content = contentText(obj["content"]) ?: return@mapNotNull null
            when (obj["role"].strValue()?.lowercase()) {
                "system" -> UIMessage.system(content)
                "assistant" -> UIMessage.assistant(content)
                else -> UIMessage.user(content)
            }
        }
    }

    private fun contentText(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonArray -> element.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            if (obj["type"].strValue() == "text") obj["text"].strValue() else null
        }.joinToString("\n").takeIf { it.isNotBlank() }

        is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun buildCompletion(text: String, model: String): String = buildJsonObject {
        put("id", "chatcmpl-tavern")
        put("object", "chat.completion")
        put("model", model)
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("message", buildJsonObject {
                    put("role", "assistant")
                    put("content", text)
                })
                put("finish_reason", "stop")
            })
        })
    }.toString()

    private fun buildSse(text: String, model: String): String = buildString {
        text.chunked(CHUNK_SIZE).forEach { chunk ->
            append("data: ")
            append(buildJsonObject {
                put("id", "chatcmpl-tavern")
                put("object", "chat.completion.chunk")
                put("model", model)
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject { put("content", chunk) })
                    })
                })
            }.toString())
            append("\n\n")
        }
        append("data: [DONE]\n\n")
    }

    private fun JsonElement?.strValue(): String? =
        (this as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "TavernGenerateApi"
        const val PATH = "api/backends/chat-completions/generate"
        const val CHUNK_SIZE = 24
    }
}
