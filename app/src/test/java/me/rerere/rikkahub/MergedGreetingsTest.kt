package me.rerere.rikkahub

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.toMergedMessageNode
import org.junit.Assert.assertEquals
import org.junit.Test

class MergedGreetingsTest {
    @Test
    fun `合并开场白是同一条消息的多个 swipe`() {
        val greetings = listOf(
            UIMessage.assistant("第一条"),
            UIMessage.assistant("第二条"),
            UIMessage.assistant("第三条"),
        )
        val node = greetings.toMergedMessageNode()
        assertEquals(3, node.messages.size)
        assertEquals(0, node.selectIndex)
        assertEquals("第一条", node.currentMessage.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().first().text)
    }
}
