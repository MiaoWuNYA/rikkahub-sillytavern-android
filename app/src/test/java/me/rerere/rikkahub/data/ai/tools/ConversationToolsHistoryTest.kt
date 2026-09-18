package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationToolsHistoryTest {

    private fun imageMessage(vararg urls: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text("看这张图")) + urls.map { UIMessagePart.Image(it) },
    )

    @Test
    fun `images are returned as real image parts so the model can see them`() {
        val message = imageMessage("file:///data/upload/a.png", "file:///data/upload/b.png")

        val result = buildHistoryMessageResult(
            messageId = message.id.toString(),
            message = message,
            imageParts = message.parts.filterIsInstance<UIMessagePart.Image>(),
        )

        // 第一部件是元数据 JSON，其后每个图片都是真实的 Image 部件
        assertTrue(result.first() is UIMessagePart.Text)
        assertEquals(
            listOf("file:///data/upload/a.png", "file:///data/upload/b.png"),
            result.drop(1).filterIsInstance<UIMessagePart.Image>().map { it.url },
        )
    }

    @Test
    fun `inline base64 images are not dumped into the metadata json`() {
        val base64 = "data:image/png;base64," + "A".repeat(10_000)
        val message = imageMessage(base64)

        val result = buildHistoryMessageResult(
            messageId = message.id.toString(),
            message = message,
            imageParts = emptyList(),
        )

        val text = (result.single() as UIMessagePart.Text).text
        // 元数据里只留标记，不再塞整段 base64（否则裁剪省下的 token 全被吃回去）
        assertTrue(text.contains("[inline base64 image]"))
        assertTrue("base64 payload leaked into tool result", !text.contains("A".repeat(100)))
    }

    @Test
    fun `non-vision models receive ocr text instead of images`() {
        val message = imageMessage("file:///data/upload/a.png")

        val result = buildHistoryMessageResult(
            messageId = message.id.toString(),
            message = message,
            // 调用方对非视觉模型预先跑 OCR，这里模拟其结果
            imageParts = listOf(UIMessagePart.Text("<image_file_ocr>一只猫</image_file_ocr>")),
        )

        assertEquals(2, result.size)
        assertTrue(result.none { it is UIMessagePart.Image })
        assertTrue((result[1] as UIMessagePart.Text).text.contains("一只猫"))
    }

    @Test
    fun `metadata json carries role and message id`() {
        val message = imageMessage("file:///data/upload/a.png")

        val result = buildHistoryMessageResult(
            messageId = message.id.toString(),
            message = message,
            imageParts = emptyList(),
        )

        val payload = kotlinx.serialization.json.Json
            .parseToJsonElement((result.single() as UIMessagePart.Text).text)
            .jsonObject
        assertEquals(message.id.toString(), payload["message_id"]!!.jsonPrimitive.content)
        assertEquals("USER", payload["role"]!!.jsonPrimitive.content)
    }
}
