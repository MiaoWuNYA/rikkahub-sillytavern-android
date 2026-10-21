package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

// 新会话在初始化前被外部改动时，初始化必须仍然生效
class ConversationSessionDraftTest {

    @Test
    fun `draft update before initialize does not swallow initialization`() = runBlocking {
        val id = Uuid.random()
        val assistantId = Uuid.random()
        val folderId = Uuid.random()
        val session = ConversationSession(
            id = id,
            initial = Conversation.ofId(id),
            scope = this,
            onIdle = {},
        )

        session.updateDraftState { it.copy(folderId = folderId) }
        assertEquals("草稿改动必须先落到内存", folderId, session.state.value.folderId)

        session.initialize {
            Conversation.ofId(id, assistantId = assistantId, newConversation = true)
        }

        assertEquals("初始化不能被草稿改动吞掉", assistantId, session.state.value.assistantId)
        assertTrue("新会话标记必须保住，多开场白选择器靠它", session.state.value.newConversation)
    }

    @Test
    fun `draft update after initialize applies to memory`() = runBlocking {
        val id = Uuid.random()
        val folderId = Uuid.random()
        val session = ConversationSession(
            id = id,
            initial = Conversation.ofId(id),
            scope = this,
            onIdle = {},
        )

        session.initialize { Conversation.ofId(id, newConversation = true) }
        session.updateDraftState { it.copy(folderId = folderId) }

        assertEquals(folderId, session.state.value.folderId)
        assertTrue(session.state.value.newConversation)
        assertFalse(session.state.value.messageNodes.isNotEmpty())
    }
}
