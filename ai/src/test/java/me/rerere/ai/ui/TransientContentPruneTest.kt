package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientContentPruneTest {

    private fun userMessage(text: String, parts: List<UIMessagePart> = listOf(UIMessagePart.Text(text))) =
        UIMessage(role = MessageRole.USER, parts = parts)

    private fun assistantMessage(parts: List<UIMessagePart>) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts)

    @Test
    fun `messages within keep window are untouched`() {
        val messages = listOf(
            userMessage("u1"),
            assistantMessage(listOf(UIMessagePart.Image("file://a.png"))),
            userMessage("u2"),
        )
        assertEquals(messages, messages.pruneOldTransientContent())
    }

    @Test
    fun `old search tool output is replaced by placeholder`() {
        val messages = listOf(
            userMessage("old question"),
            assistantMessage(listOf(
                UIMessagePart.Tool(
                    toolCallId = "t1",
                    toolName = "search_web",
                    input = """{"query":"kotlin coroutines"}""",
                    output = listOf(UIMessagePart.Text("very long search result body")),
                ),
                UIMessagePart.Text("old answer"),
            )),
            userMessage("recent question 1"),
            assistantMessage(listOf(UIMessagePart.Text("recent answer 1"))),
            userMessage("recent question 2"),
        )
        val pruned = messages.pruneOldTransientContent()
        // 条数不变
        assertEquals(messages.size, pruned.size)
        // 保留窗口内的消息原样
        assertEquals(messages[2], pruned[2])
        assertEquals(messages[3], pruned[3])
        assertEquals(messages[4], pruned[4])
        // 旧搜索结果被替换为占位文本，包含消息 ID
        val toolPart = pruned[1].parts[0] as UIMessagePart.Tool
        assertEquals(1, toolPart.output.size)
        val placeholder = (toolPart.output[0] as UIMessagePart.Text).text
        assertTrue(placeholder.contains("read_history_message"))
        assertTrue(placeholder.contains(messages[1].id.toString()))
        assertTrue(placeholder.contains("kotlin coroutines"))
        // 旧回复文本保留
        assertEquals("old answer", (pruned[1].parts[1] as UIMessagePart.Text).text)
    }

    @Test
    fun `old image parts are pruned but tool structure kept`() {
        val messages = listOf(
            userMessage("look at this", parts = listOf(UIMessagePart.Image("file://old.png"))),
            assistantMessage(listOf(UIMessagePart.Text("nice"))),
            userMessage("recent 1"),
            assistantMessage(listOf(UIMessagePart.Text("ok"))),
            userMessage("recent 2"),
        )
        val pruned = messages.pruneOldTransientContent()
        val parts = pruned[0].parts
        assertEquals(1, parts.size)
        val text = (parts[0] as UIMessagePart.Text).text
        assertTrue(text.contains("read_history_message"))
    }

    @Test
    fun `non web tools are not pruned`() {
        val messages = listOf(
            userMessage("old"),
            assistantMessage(listOf(
                UIMessagePart.Tool(
                    toolCallId = "t1",
                    toolName = "calculator",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("42")),
                ),
            )),
            userMessage("recent"),
        )
        assertEquals(messages, messages.pruneOldTransientContent())
    }

    @Test
    fun `no user messages means no pruning`() {
        val messages = listOf(
            assistantMessage(listOf(UIMessagePart.Image("file://a.png"))),
        )
        assertEquals(messages, messages.pruneOldTransientContent())
    }
}
