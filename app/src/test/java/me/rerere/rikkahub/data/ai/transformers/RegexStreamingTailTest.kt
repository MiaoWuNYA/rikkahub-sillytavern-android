package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 流式期间正则只应用在正在生成的最后一条消息上：
 * 历史消息在 step 结束时已被写回正则结果，逐 chunk 全量重跑既卡顿又会二次替换。
 */
class RegexStreamingTailTest {

    private fun toolRegex() = AssistantRegex(
        id = Uuid.random(),
        name = "tool-tag",
        findRegex = "<tool>[\\s\\S]*?</tool>",
        replaceString = "🔧 工具调用中...",
        affectingScope = setOf(AssistantAffectScope.ASSISTANT),
    )

    @Test
    fun `streaming tail applies regex to the generating message`() {
        val assistant = Assistant(name = "a", regexes = listOf(toolRegex()))
        val streaming = listOf(
            UIMessage.user("hi"),
            UIMessage.assistant("done"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("let me check <tool>search_web</tool>")),
            ),
        )
        val applied = streaming.applyStreamingRegexTail(assistant, null)
        assertEquals("let me check 🔧 工具调用中...", applied.last().toText())
        assertEquals("hi", applied[0].toText())
        assertEquals("done", applied[1].toText())
    }

    @Test
    fun `streaming tail keeps already replaced history intact`() {
        val assistant = Assistant(name = "a", regexes = listOf(toolRegex()))
        val streaming = listOf(
            UIMessage.user("hi"),
            UIMessage.assistant("already 🔧 工具调用中... done"),
            UIMessage.assistant("<tool>run</tool>"),
        )
        val applied = streaming.applyStreamingRegexTail(assistant, null)
        assertEquals("already 🔧 工具调用中... done", applied[1].toText())
        assertEquals("🔧 工具调用中...", applied.last().toText())
    }

    @Test
    fun `streaming tail skips when the last message is not assistant`() {
        val assistant = Assistant(name = "a", regexes = listOf(toolRegex()))
        val messages = listOf(
            UIMessage.assistant("earlier <tool>x</tool>"),
            UIMessage.user("ask <tool>y</tool>"),
        )
        assertEquals(messages, messages.applyStreamingRegexTail(assistant, null))
    }
}
