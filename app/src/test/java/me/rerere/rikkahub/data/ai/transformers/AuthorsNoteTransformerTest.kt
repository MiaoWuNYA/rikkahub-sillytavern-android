package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AuthorNoteEntry
import me.rerere.rikkahub.data.model.AuthorNotePosition
import me.rerere.rikkahub.data.model.Persona
import me.rerere.rikkahub.data.model.PersonaInjectionPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AuthorsNoteTransformerTest {
    private val assistant = Assistant(id = Uuid.random(), name = "test")
    private val conversationId = Uuid.random()

    @Test
    fun `prompt and in-chat entries are both injected with independent roles`() {
        val messages = basicMessages()
        val settings = settingsWith(
            AuthorNoteEntry(
                id = "a",
                name = "A",
                content = "prompt-note",
                position = AuthorNotePosition.IN_PROMPT,
                role = MessageRole.SYSTEM,
            ),
            AuthorNoteEntry(
                id = "b",
                name = "B",
                content = "chat-note",
                position = AuthorNotePosition.IN_CHAT,
                depth = 1,
                role = MessageRole.USER,
            ),
        )

        val result = transform(settings, messages, userCount = 1, chatCount = 2)

        assertEquals(listOf("main", "prompt-note", "U1", "chat-note", "A1"), labels(result))
        assertEquals(MessageRole.SYSTEM, result[1].role)
        assertEquals(MessageRole.USER, result[3].role)
    }

    @Test
    fun `each entry applies its own interval`() {
        val settings = settingsWith(
            note("a", "every", interval = 1),
            note("b", "third", interval = 3),
        )

        for (count in 1..2) {
            val labels = labels(transform(settings, basicMessages(), userCount = count, chatCount = 2))
            assertTrue("interval 1 should inject at count $count", "every" in labels)
            assertFalse("interval 3 should not inject at count $count", "third" in labels)
        }
        val thirdTurn = labels(transform(settings, basicMessages(), userCount = 3, chatCount = 2))
        assertTrue("every" in thirdTurn)
        assertTrue("third" in thirdTurn)
    }

    @Test
    fun `disabled entry is skipped independently`() {
        val settings = settingsWith(
            note("a", "enabled", enabled = true),
            note("b", "disabled", enabled = false),
        )

        val labels = labels(transform(settings, basicMessages(), userCount = 1, chatCount = 2))

        assertTrue("enabled" in labels)
        assertFalse("disabled" in labels)
    }

    @Test
    fun `different depths use independent insertion points`() {
        val messages = listOf(
            UIMessage.system("main"),
            UIMessage.user("U1"),
            UIMessage.assistant("A1"),
            UIMessage.user("U2"),
            UIMessage.assistant("A2"),
            UIMessage.user("U3"),
            UIMessage.assistant("A3"),
        )
        val settings = settingsWith(
            note("near", "depth-1", depth = 1),
            note("far", "depth-4", depth = 4),
        )

        val labels = labels(transform(settings, messages, userCount = 3, chatCount = 6))

        assertTrue(labels.indexOf("depth-4") < labels.indexOf("U2"))
        assertTrue(labels.indexOf("depth-1") > labels.indexOf("U3"))
        assertTrue(labels.indexOf("depth-1") < labels.indexOf("A3"))
    }

    @Test
    fun `global switch disables every entry`() {
        val messages = basicMessages()
        val settings = settingsWith(note("a", "hidden"))
            .copy(authorNoteEnabled = false)

        assertEquals(messages, transform(settings, messages, userCount = 1, chatCount = 2))
    }

    @Test
    fun `legacy single note still injects with all legacy parameters`() {
        val messages = basicMessages()
        val settings = Settings(
            authorNote = "legacy-note",
            authorNoteEnabled = true,
            authorNotePosition = AuthorNotePosition.IN_PROMPT,
            authorNoteDepth = 9,
            authorNoteRole = MessageRole.USER,
            authorNoteInterval = 2,
            authorNoteEntries = null,
        )

        val skipped = transform(settings, messages, userCount = 1, chatCount = 2)
        assertFalse("legacy-note" in labels(skipped))

        val injected = transform(settings, messages, userCount = 2, chatCount = 2)
        assertEquals("legacy-note", labels(injected)[1])
        assertEquals(MessageRole.USER, injected[1].role)
    }

    @Test
    fun `different depths keep separate frozen anchors during tool loop`() {
        val messages = listOf(
            UIMessage.system("main"),
            UIMessage.user("U1"),
            UIMessage.assistant("A1"),
            UIMessage.user("U2"),
            UIMessage.assistant("A2"),
            UIMessage.user("U3"),
            UIMessage.assistant("A3"),
        )
        val settings = settingsWith(
            note("near", "depth-1", depth = 1),
            note("far", "depth-4", depth = 4),
        )
        val anchors = mutableMapOf<String, Pair<String, String>>()
        transformAuthorNotes(settings, assistant, conversationId, 3, 6, messages, anchors)

        val toolLoopMessages = messages + UIMessage.assistant("tool-step")
        val second = transformAuthorNotes(
            settings,
            assistant,
            conversationId,
            3,
            7,
            toolLoopMessages,
            anchors,
        )
        val secondLabels = labels(second)

        assertEquals("U2", secondLabels[secondLabels.indexOf("depth-4") + 1])
        assertEquals("A3", secondLabels[secondLabels.indexOf("depth-1") + 1])
    }

    @Test
    fun `top or bottom persona appears once with multiple notes`() {
        PersonaInjectionPosition.entries
            .filter { it == PersonaInjectionPosition.TOP_OF_CHAT || it == PersonaInjectionPosition.BOTTOM_OF_CHAT }
            .forEach { position ->
                val persona = Persona(
                    id = Uuid.random(),
                    name = "persona",
                    description = "persona-only-once",
                    position = position,
                )
                val settings = settingsWith(
                    note("a", "note-a"),
                    note("b", "note-b", depth = 4),
                ).copy(personas = listOf(persona), activePersonaId = persona.id)

                val result = transform(settings, basicMessages(), userCount = 1, chatCount = 2)

                assertEquals(
                    "$position must inject persona exactly once",
                    1,
                    labels(result).count { it == "persona-only-once" },
                )
        }
    }

    @Test
    fun `legacy note keeps persona merged into one injected message`() {
        PersonaInjectionPosition.entries
            .filter { it == PersonaInjectionPosition.TOP_OF_CHAT || it == PersonaInjectionPosition.BOTTOM_OF_CHAT }
            .forEach { position ->
                val persona = Persona(
                    id = Uuid.random(),
                    name = "persona",
                    description = "legacy-persona",
                    position = position,
                )
                val settings = Settings(
                    authorNote = "legacy-note",
                    authorNoteEnabled = true,
                    personas = listOf(persona),
                    activePersonaId = persona.id,
                )

                val result = transform(settings, basicMessages(), userCount = 1, chatCount = 2)
                val injected = result.filter { message ->
                    message.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("") { it.text }
                        .startsWith("[Author's Note]")
                }

                assertEquals(1, injected.size)
                val content = labels(injected).single()
                val expected = if (position == PersonaInjectionPosition.TOP_OF_CHAT) {
                    "legacy-persona\nlegacy-note"
                } else {
                    "legacy-note\nlegacy-persona"
                }
                assertEquals(expected, content)
            }
    }

    @Test
    fun `in-chat notes do not split user from assistant tool call`() {
        val user = UIMessage.user("tool-user")
        val toolAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "tool",
                    input = "{}",
                    output = emptyList(),
                )
            ),
        )
        val messages = listOf(UIMessage.system("main"), user, toolAssistant)
        val settings = settingsWith(
            note("safe-a", "safe-note-a", depth = 1),
            note("safe-b", "safe-note-b", depth = 1),
        )

        val result = transform(settings, messages, userCount = 1, chatCount = 2)
        val resultLabels = labels(result)
        val firstNoteIndex = resultLabels.indexOf("safe-note-a")
        val secondNoteIndex = resultLabels.indexOf("safe-note-b")
        val userIndex = result.indexOfFirst { it.id == user.id }
        val toolIndex = result.indexOfFirst { it.id == toolAssistant.id }

        assertTrue(firstNoteIndex < secondNoteIndex)
        assertTrue(secondNoteIndex < userIndex)
        assertEquals(userIndex + 1, toolIndex)
    }

    private fun settingsWith(vararg entries: AuthorNoteEntry) = Settings(
        authorNoteEnabled = true,
        authorNoteEntries = entries.toList(),
    )

    private fun note(
        id: String,
        content: String,
        enabled: Boolean = true,
        depth: Int = 1,
        interval: Int = 1,
    ) = AuthorNoteEntry(
        id = id,
        name = id,
        content = content,
        enabled = enabled,
        position = AuthorNotePosition.IN_CHAT,
        depth = depth,
        interval = interval,
    )

    private fun basicMessages() = listOf(
        UIMessage.system("main"),
        UIMessage.user("U1"),
        UIMessage.assistant("A1"),
    )

    private fun transform(
        settings: Settings,
        messages: List<UIMessage>,
        userCount: Int,
        chatCount: Int,
    ) = transformAuthorNotes(
        settings = settings,
        assistant = assistant,
        conversationId = conversationId,
        chatUserMessageCount = userCount,
        chatMessageCount = chatCount,
        messages = messages,
    )

    private fun labels(messages: List<UIMessage>): List<String> = messages.map { message ->
        message.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
            .removePrefix("[Author's Note]\n")
    }
}
