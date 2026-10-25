package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationCheckpointTest {
    @Test
    fun `compressed history is replaced by the summary`() {
        val nodes = List(4) { UIMessage.user("message $it").toMessageNode() }
        val source = Conversation(assistantId = Uuid.random(), messageNodes = nodes)

        val result = source.replacePrefixWithSummaries(throughNodeId = nodes[1].id, summaries = listOf("summary"))!!

        // 早期版本的行为：摘要成为第一条消息，被压缩的历史整段消失
        assertEquals(3, result.messageNodes.size)
        assertEquals("summary", result.messageNodes.first().currentMessage.toText())
        assertTrue(result.messageNodes.first().currentMessage.isContextCheckpoint)
        assertEquals(nodes.subList(2, 4), result.messageNodes.subList(1, 3))
    }

    @Test
    fun `compression is rejected when the anchor node is gone`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(UIMessage.user("message").toMessageNode()),
        )

        assertNull(source.replacePrefixWithSummaries(throughNodeId = Uuid.random(), summaries = listOf("summary")))
    }
}
