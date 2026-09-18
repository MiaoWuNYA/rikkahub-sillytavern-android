package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageResponseTest {

    private fun body(contentJson: String): JsonObject = Json.parseToJsonElement(
        """{"choices":[{"message":{"role":"assistant","content":$contentJson}}]}"""
    ).jsonObject

    private fun textBody(text: String): JsonObject =
        body(Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text)))

    @Test
    fun `extracts single markdown image url`() {
        val json = textBody("![image_1](https://cdn.example.com/a.png)")
        assertEquals(
            listOf("https://cdn.example.com/a.png"),
            ChatImageResponse.parseImageUrls(json),
        )
    }

    @Test
    fun `extracts multiple urls without swallowing each other`() {
        val json = textBody(
            "![image_1](https://cdn.example.com/a.png)\n![image_2](https://cdn.example.com/b.png)"
        )
        assertEquals(
            listOf("https://cdn.example.com/a.png", "https://cdn.example.com/b.png"),
            ChatImageResponse.parseImageUrls(json),
        )
    }

    @Test
    fun `surrounding prose does not break extraction`() {
        val json = textBody("这是你要的图：\n\n![image_1](https://cdn.example.com/a.png)\n\n还需要别的吗？")
        assertEquals(
            listOf("https://cdn.example.com/a.png"),
            ChatImageResponse.parseImageUrls(json),
        )
    }

    @Test
    fun `plain text without image yields no urls`() {
        assertTrue(ChatImageResponse.parseImageUrls(textBody("抱歉，我无法生成这张图片。")).isEmpty())
    }

    @Test
    fun `non http url is ignored`() {
        assertTrue(
            ChatImageResponse.parseImageUrls(textBody("![image_1](data:image/png;base64,AAAA)")).isEmpty()
        )
    }

    @Test
    fun `missing choices yields no urls instead of throwing`() {
        val json = Json.parseToJsonElement("""{"error":{"message":"boom"}}""").jsonObject
        assertTrue(ChatImageResponse.parseImageUrls(json).isEmpty())
        assertNull(ChatImageResponse.extractText(json))
    }

    @Test
    fun `content array form is supported`() {
        val json = body("""[{"type":"text","text":"![image_1](https://cdn.example.com/a.png)"}]""")
        assertEquals(
            listOf("https://cdn.example.com/a.png"),
            ChatImageResponse.parseImageUrls(json),
        )
    }

    @Test
    fun `null content does not throw`() {
        val json = body("null")
        assertTrue(ChatImageResponse.parseImageUrls(json).isEmpty())
        assertNull(ChatImageResponse.extractText(json))
    }

    @Test
    fun `extractText returns raw content for error reporting`() {
        assertEquals("抱歉，我无法生成这张图片。", ChatImageResponse.extractText(textBody("抱歉，我无法生成这张图片。")))
    }
}
