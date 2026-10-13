package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationRegenerateVersionTest {
    private fun userMsg(text: String): UIMessage = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistantMsg(text: String): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun node(vararg messages: UIMessage) = MessageNode(
        messages = messages.toList(),
    )

    private fun textOf(message: UIMessage) =
        message.parts.filterIsInstance<UIMessagePart.Text>().first().text

    @Test
    fun `regenerate last assistant appends version`() {
        val m0 = userMsg("你好")
        val a1 = assistantMsg("回复1")
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = listOf(node(m0), node(a1)),
        )

        // 真实 regenerate 语义：messageRange=0..<1，chunk = 前缀 + 新回复
        val newAssistant = assistantMsg("回复2")
        val updated = conversation.updateCurrentMessages(listOf(m0, newAssistant))

        val targetNode = updated.messageNodes[1]
        assertEquals(2, targetNode.messages.size)
        assertEquals(1, targetNode.selectIndex)
        assertEquals("回复1", textOf(targetNode.messages[0]))
        assertEquals("回复2", textOf(targetNode.messages[1]))
        assertEquals("回复2", textOf(updated.currentMessages[1]))
    }

    @Test
    fun `regenerate middle assistant keeps trailing nodes and appends version`() {
        val m0 = userMsg("问1")
        val a1 = assistantMsg("答1")
        val m2 = userMsg("问2")
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = listOf(node(m0), node(a1), node(m2)),
        )

        val newAssistant = assistantMsg("答1-v2")
        val updated = conversation.updateCurrentMessages(listOf(m0, newAssistant))

        assertEquals(3, updated.messageNodes.size)
        assertEquals(2, updated.messageNodes[1].messages.size)
        assertEquals(1, updated.messageNodes[1].selectIndex)
        assertEquals("问2", textOf(updated.messageNodes[2].messages[0]))
    }

    @Test
    fun `streaming updates replace same id without adding versions`() {
        val m0 = userMsg("你好")
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = listOf(node(m0)),
        )
        val streaming = assistantMsg("半截")
        val step1 = conversation.updateCurrentMessages(listOf(m0, streaming))
        val step2 = step1.updateCurrentMessages(
            listOf(m0, streaming.copy(parts = listOf(UIMessagePart.Text("半截回复"))))
        )
        assertEquals(1, step2.messageNodes[1].messages.size)
        assertEquals(0, step2.messageNodes[1].selectIndex)
    }
}
