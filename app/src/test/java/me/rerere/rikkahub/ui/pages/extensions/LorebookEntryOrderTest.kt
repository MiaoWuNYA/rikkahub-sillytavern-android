package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryOrderTest {
    private fun entry(name: String, group: String = "") = PromptInjection.RegexInjection(
        name = name,
        group = group,
        content = "content $name",
        keywords = listOf(name),
        secondaryKeys = listOf("secondary"),
        priority = name.length * 7,
        position = InjectionPosition.AT_DEPTH,
        injectDepth = 6,
        role = MessageRole.SYSTEM,
        probability = 63,
        displayIndex = 99,
    )

    private fun move(entries: List<PromptInjection.RegexInjection>, from: Uuid, to: Uuid): List<PromptInjection.RegexInjection> {
        val target = entries.firstOrNull { it.id == to } ?: return entries
        return moveLorebookEntry(entries, from, LorebookEntryPlacement(to,
            entries.indexOfFirst { it.id == from } < entries.indexOf(target), target.group.ifBlank { "" }))
    }

    @Test
    fun `a single drop preserves unselected relative order and every entry field`() {
        val a = entry("a", "group")
        val b = entry("other", "other")
        val c = entry("c", "group")
        val d = entry("d", "group")
        val entries = listOf(a, b, c, d)
        val moved = move(entries, a.id, d.id)
        assertEquals(listOf(b, c, d, a), moved)
        entries.forEach { original -> assertSame(original, moved.single { it.id == original.id }) }
        assertEquals(listOf(b, a, c, d), move(moved, a.id, c.id))
    }

    @Test
    fun `cross group drops update membership while stale and identical targets are no ops`() {
        val a = entry("a", "A")
        val b = entry("b", "B")
        val entries = listOf(a, b)
        assertEquals(listOf(b, a.copy(group = "B")), move(entries, a.id, b.id))
        assertSame(entries, move(entries, a.id, Uuid.random()))
        assertSame(entries, move(entries, Uuid.random(), a.id))
        assertSame(entries, move(entries, a.id, a.id))
    }

    @Test
    fun `pending inserts before and after an existing entry without changing its group`() {
        val a = entry("a", "A")
        val b = entry("b", "B")
        val c = entry("c", "A")
        val pending = entry("pending", "A")
        val entries = listOf(a, b, c)
        assertEquals(listOf(a, b, pending, c), insertLorebookEntry(entries, pending, LorebookEntryPlacement(c.id, false)))
        assertEquals(listOf(a, pending, b, c), insertLorebookEntry(entries, pending, LorebookEntryPlacement(a.id, true)))
        assertEquals(listOf(a, b, c), entries)
    }

    @Test
    fun `pending without a valid drop is never appended to an existing group`() {
        val a = entry("a", "A")
        val b = entry("b", "B")
        val pending = entry("pending", "A")
        val entries = listOf(a, b)
        assertSame(entries, insertLorebookEntry(entries, pending, null))
        assertSame(entries, insertLorebookEntry(entries, pending, LorebookEntryPlacement(b.id, true)))
        assertSame(entries, insertLorebookEntry(entries, pending, LorebookEntryPlacement(Uuid.random(), false)))
    }

    @Test
    fun `first entry can be inserted into empty book or empty group`() {
        val pending = entry("pending", "new")
        assertEquals(listOf(pending), insertLorebookEntry(emptyList(), pending, null))
        val existing = entry("existing", "other")
        assertEquals(listOf(existing, pending), insertLorebookEntry(listOf(existing), pending, null))
    }

    @Test
    fun `drop resolves against latest entries and never duplicates an id`() {
        val a = entry("a")
        val pending = entry("pending")
        val placement = LorebookEntryPlacement(a.id, true)
        val edited = a.copy(content = "edited during draft")
        val inserted = insertLorebookEntry(listOf(edited), pending, placement)
        assertEquals(listOf(edited, pending), inserted)
        assertSame(inserted, insertLorebookEntry(inserted, pending, placement))
        assertEquals(emptyList<PromptInjection.RegexInjection>(), insertLorebookEntry(emptyList(), pending, placement))
    }

    @Test
    fun `native export import retains reordered entries and semantic fields`() {
        val entries = listOf(entry("a", "A"), entry("b", "B"), entry("c", "A"))
        val reordered = move(entries, entries[0].id, entries[2].id)
        val book = Lorebook(name = "test", entries = reordered)
        val json = LorebookSerializer.exportToJson(book)
        val imported = LorebookSerializer.importFromJson(json, "test")
        // Native import deliberately allocates fresh book/entry IDs to avoid collisions.
        assertEquals(book, imported.copy(
            id = book.id,
            entries = imported.entries.mapIndexed { index, entry -> entry.copy(id = book.entries[index].id) },
        ))
    }

    @Test
    fun `long interleaved lists tolerate repeated moves and stale ids`() {
        var entries = List(1000) { entry("entry $it", "group ${it % 3}") }
        val originals = entries.associateBy { it.id }
        repeat(100) {
            val group = entries.filter { it.group == "group 0" }
            entries = move(entries, group.first().id, group.last().id)
            entries = move(entries, Uuid.random(), group.last().id)
        }
        assertEquals(originals, entries.associateBy { it.id })
        assertEquals(1000, entries.map { it.id }.distinct().size)
    }

    @Test
    fun `group shortcuts preserve group and every configuration field`() {
        val entries = listOf(entry("a", "A"), entry("b", "B"))
        val pending = entry("pending", "B")
        val front = insertLorebookEntry(entries, pending, LorebookEntryPlacement.groupEdge(pending.group, false))
        val back = insertLorebookEntry(entries, pending, LorebookEntryPlacement.groupEdge(pending.group, true))
        assertEquals(listOf(entries[0], pending, entries[1]), front)
        assertEquals(entries + pending, back)
        assertSame(pending, front[1])
        assertSame(pending, back.last())
        assertEquals(2, entries.size)
    }

    @Test
    fun `shortcuts handle empty single entry and duplicate ids`() {
        val pending = entry("pending", "new group")
        listOf(false, true).forEach { atEnd ->
            assertEquals(listOf(pending), insertLorebookEntry(emptyList(), pending, LorebookEntryPlacement.groupEdge(pending.group, atEnd)))
            val existing = entry("existing", "other group")
            val entries = listOf(existing)
            val inserted = insertLorebookEntry(entries, pending, LorebookEntryPlacement.groupEdge(pending.group, atEnd))
            assertEquals(listOf(existing, pending), inserted)
            assertSame(inserted, insertLorebookEntry(inserted, pending, LorebookEntryPlacement.groupEdge(pending.group, atEnd)))
        }
    }
}
