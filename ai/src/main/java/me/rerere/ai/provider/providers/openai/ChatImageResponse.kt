package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一部分中转站没有实现 OpenAI 的 `/images/generations` 与 `/images/edits`，
 * 而是把图像模型当成普通对话模型挂在 `/chat/completions` 上：发一条单轮消息，
 * 生成的图以 Markdown 图片链接的形式写在回复正文里。这类站点对图片端点一律回 404，
 * 用户看到的就是"404 page not found"，看着像模型不可用，其实能用。
 *
 * 这里放解析这类回复的纯函数，从 [OpenAIProvider] 里拆出来是为了能直接写单测。
 */
internal object ChatImageResponse {

    /**
     * 抠出正文里的 Markdown 图片链接。只锚定括号里的 http(s) 地址，不关心 alt 文本。
     * `[^)\s]+` 保证一行里多个链接各算各的，不会被当成一个长 URL 吞掉。
     */
    private val MARKDOWN_IMAGE_URL = Regex("""!\[[^\]]*]\((https?://[^)\s]+)\)""")

    /**
     * 从 chat 回复体里取出图片链接。取不到正文时返回空列表，由调用方决定报什么错。
     *
     * 正文允许是字符串或"内容分片数组"两种形态：中转站回字符串，而 OpenAI 自家的
     * 图像模型走 chat 通道时回 `[{"type":"text","text":"..."}]`，两种都接住。
     */
    fun parseImageUrls(body: JsonObject): List<String> {
        val message = body["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?: return emptyList()
        val content = message["content"] ?: return emptyList()

        val text = when (content) {
            is JsonPrimitive -> content.contentOrNull
            else -> content.jsonArray
                .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                .joinToString("\n")
        } ?: return emptyList()

        return MARKDOWN_IMAGE_URL.findAll(text).map { it.groupValues[1] }.toList()
    }

    /** 取回复正文原文，用于把"没找到图片"时的原始内容带进错误信息。 */
    fun extractText(body: JsonObject): String? {
        val message = body["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?: return null
        val content = message["content"] ?: return null
        return when (content) {
            is JsonPrimitive -> content.contentOrNull
            else -> content.jsonArray
                .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                .joinToString("\n")
        }
    }
}
