package me.rerere.rikkahub.data.st.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [v229 A1] `/api/backends/chat-completions/` 两个端点（status / generate）的兼容层（**纯函数**部分，可单测）。
 *
 * 背景：JS-Slash-Runner 的 `generate()` / `generateRaw()` 全部落在两个 ST 宿主端点：
 *  - `src/function/generate/index.ts:46`  ->  POST `/api/backends/chat-completions/status`（只探测可用模型）
 *  - `src/function/generate/responseGenerator.ts:343 / :438` -> POST `/api/backends/chat-completions/generate`
 *    （前者流式 SSE，后者要 `response.json()` 一个 OpenAI 形状的 body）
 *
 * 宿主以前对 `/api/` **零命中** -> 一律 404 -> 卡的 `TavernHelper.generateRaw()` 必失败。
 * 本文件只做「线上协议」的解析与拼装；真正的生成走宿主已配置的 Provider 管线（见 TavernRuntimeManager）。
 *
 * 设计红线：不引入新的网络栈、不复刻 ST 的全部分支。上游 provider 差异由 `Provider.streamText` 归一化，
 * 这里只负责 OpenAI 兼容的线格式（JSR 的 `getStreamingReply` 只认这个形状）。
 */
internal object TavernChatCompletionsApi {

    const val PREFIX = "/api/backends/chat-completions/"
    const val STATUS = PREFIX + "status"
    const val GENERATE = PREFIX + "generate"

    /** 路由判定：命中则返回端点类型，未命中返回 null（调用方继续走原有资产链路）。 */
    fun routeOf(path: String?): Endpoint? = when (path) {
        STATUS -> Endpoint.STATUS
        GENERATE -> Endpoint.GENERATE
        else -> null
    }

    enum class Endpoint { STATUS, GENERATE }

    // ------------------------------------------------------------------ 请求解析

    /**
     * `/generate` 的请求体解析结果。
     *
     * @param modelId 卡片请求的模型名（JSR `getChatCompletionModel` 的产物；可能为空 -> 调用方回退默认模型）
     * @param stream  请求体 `stream:true` 即为 SSE；JSR 流式路径固定为 true（responseGenerator.ts:325）
     * @param messages OpenAI 形状的消息数组，**原样透传**给宿主 Provider（role/content）
     */
    data class GenerateRequest(
        val modelId: String,
        val stream: Boolean,
        val temperature: Double?,
        val topP: Double?,
        val maxTokens: Int?,
        val messages: List<ChatMessage>,
    )

    /** OpenAI 线格式消息的最小子集（`content` 支持字符串；非字符串内容取 text 部分）。 */
    data class ChatMessage(val role: String, val content: String)

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析 `/generate` 请求体；非法 JSON / 缺 messages 时返回 null（调用方回 400 JSON 错误体）。 */
    fun parseGenerateRequest(body: String?): GenerateRequest? {
        if (body.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val messages = runCatching {
            root["messages"]?.jsonArray?.mapNotNull { el ->
                val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                val role = o["role"]?.jsonPrimitive?.contentOrNull ?: "user"
                ChatMessage(role, contentToText(o["content"]))
            }
        }.getOrNull() ?: return null
        if (messages.isEmpty()) return null
        return GenerateRequest(
            modelId = root["model"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            stream = root["stream"]?.jsonPrimitive?.booleanOrNull ?: false,
            temperature = root["temperature"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            topP = root["top_p"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            maxTokens = root["max_tokens"]?.jsonPrimitive?.intOrNull,
            messages = messages,
        )
    }

    /** `content` 既可能是字符串，也可能是 [{"type":"text","text":".."}]（多模态）。 */
    private fun contentToText(el: kotlinx.serialization.json.JsonElement?): String {
        if (el == null) return ""
        val prim = runCatching { el.jsonPrimitive }.getOrNull()
        if (prim != null) return prim.contentOrNull.orEmpty()
        val arr = runCatching { el.jsonArray }.getOrNull() ?: return ""
        return arr.joinToString("\n") { part ->
            val o = runCatching { part.jsonObject }.getOrNull() ?: return@joinToString ""
            o["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.trim()
    }

    // ------------------------------------------------------------------ /status 响应

    /**
     * `/status` 响应体。JSR `getModelList` 只读 `data[].id`（index.ts:59），
     * 别的字段是给 ST 原生 UI 的，这里给最小可用面 + `id`。
     */
    fun statusResponse(
        models: List<String>,
        currentModel: String?,
        apiUrl: String? = null,
    ): String = buildJsonObject {
        put("object", "list")
        putJsonArray("data") {
            models.forEach { m ->
                add(buildJsonObject {
                    put("id", m)
                    put("name", m)
                    put("object", "model")
                })
            }
        }
        put("model", currentModel.orEmpty())
        if (!apiUrl.isNullOrBlank()) put("api_url", apiUrl)
    }.toString()

    // ------------------------------------------------------------------ /generate 响应

    /** 单个流式分片（OpenAI `chat.completion.chunk` 形状）。JSR 读 choices[0].delta.content。 */
    fun streamChunk(
        id: String,
        model: String,
        deltaText: String?,
        finishReason: String? = null,
    ): String = buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", 0)
        put("model", model)
        putJsonArray("choices") {
            add(buildJsonObject {
                put("index", 0)
                putJsonObject("delta") {
                    if (deltaText != null) put("content", deltaText)
                }
                if (finishReason != null) put("finish_reason", finishReason)
            })
        }
    }.toString()

    /** 非流式整包响应（JSR 走 `response.json()`，见 responseGenerator.ts:451）。 */
    fun fullCompletion(id: String, model: String, text: String, finishReason: String = "stop"): String =
        buildJsonObject {
            put("id", id)
            put("object", "chat.completion")
            put("created", 0)
            put("model", model)
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", text)
                    }
                    put("finish_reason", finishReason)
                })
            }
        }.toString()

    /** SSE 行拼装：`data: <json>\n\n`。 */
    fun sseData(payload: String): String = "data: $payload\n\n"

    const val SSE_DONE = "data: [DONE]\n\n"

    /** 错误体（**绝不返回 500 空响应**；JSR 会读 `error.message`）。 */
    fun errorBody(message: String, type: String = "invalid_request_error"): String = buildJsonObject {
        putJsonObject("error") {
            put("message", message)
            put("type", type)
        }
    }.toString()

    /** 从宿主模型列表里挑最佳匹配：先精确 modelId，再忽略大小写，最后回退第一个。 */
    fun pickModel(requested: String, available: List<String>): String? {
        if (available.isEmpty()) return null
        val want = requested.trim()
        if (want.isEmpty()) return available.first()
        available.firstOrNull { it == want }?.let { return it }
        available.firstOrNull { it.equals(want, ignoreCase = true) }?.let { return it }
        return available.first()
    }

    /** 把 [ChatMessage] 映射到宿主 role 字符串（未知角色一律 user，避免 Provider 侧 throw）。 */
    fun normalizeRole(role: String): String = when (role.trim().lowercase()) {
        "system" -> "system"
        "assistant" -> "assistant"
        "tool" -> "user"
        else -> "user"
    }
}
