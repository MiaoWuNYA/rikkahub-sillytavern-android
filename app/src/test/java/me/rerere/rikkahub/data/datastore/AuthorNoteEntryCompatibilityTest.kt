package me.rerere.rikkahub.data.datastore

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.model.AuthorNotePosition
import me.rerere.rikkahub.data.model.LEGACY_AUTHOR_NOTE_ENTRY_ID
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorNoteEntryCompatibilityTest {
    @Test
    fun `legacy settings become one lossless compatibility entry`() {
        val legacyJson = """
            {
              "authorNote": "legacy content",
              "authorNoteEnabled": true,
              "authorNotePosition": "BEFORE_PROMPT",
              "authorNoteDepth": 7,
              "authorNoteRole": "user",
              "authorNoteInterval": 3
            }
            """.trimIndent()
        val legacy = JsonInstant.decodeFromString<Settings>(SettingsJsonMigrator.migrate(legacyJson))

        assertNull(legacy.authorNoteEntries)
        val entry = legacy.authorNoteEntriesOrLegacy().single()
        assertEquals(LEGACY_AUTHOR_NOTE_ENTRY_ID, entry.id)
        assertEquals("legacy content", entry.content)
        assertTrue(entry.enabled)
        assertEquals(AuthorNotePosition.BEFORE_PROMPT, entry.position)
        assertEquals(7, entry.depth)
        assertEquals(MessageRole.USER, entry.role)
        assertEquals(3, entry.interval)
        assertTrue(legacy.authorNoteEnabled)
    }

    @Test
    fun `explicit empty entry list never resurrects legacy content`() {
        val settings = Settings(
            authorNote = "legacy content",
            authorNoteEnabled = true,
            authorNoteEntries = emptyList(),
        )
        val restored = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(settings))

        assertTrue(restored.authorNoteEntries?.isEmpty() == true)
        assertTrue(restored.authorNoteEntriesOrLegacy().isEmpty())
    }
}
