package me.rerere.rikkahub.ui.pages.extensions

import java.io.IOException
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryRepairTest {
    private val a = PromptInjection.RegexInjection(name = "a", group = "A", content = "full", enabled = false,
        priority = 79, probability = 31, injectDepth = 9, keywords = listOf("key"), inclusionGroup = "competition")
    private val b = a.copy(id = Uuid.random(), name = "b")
    private val c = a.copy(id = Uuid.random(), name = "c", group = "B")
    private val u = a.copy(id = Uuid.random(), name = "u", group = "  ")
    private val v = a.copy(id = Uuid.random(), name = "v", group = "")
    private val book = Lorebook(entries = listOf(a, b, c, u, v), entryGroups = listOf("A", "B", "Empty"))

    @Test fun `explicit ungrouped scope includes legacy blanks and preserves manual choices`() {
        val manual = setOf(a.id, c.id)
        assertEquals(manual + setOf(u.id, v.id), toggleLorebookScope(book.entries, manual, ""))
        assertEquals(manual, toggleLorebookScope(book.entries, manual + setOf(u.id, v.id), ""))
        assertEquals(setOf(u.id, v.id), toggleLorebookScope(book.entries, emptySet(), ""))
    }

    @Test fun `explicit scope ignores first checked group and toggles only its own ids`() {
        assertEquals(setOf(a.id, b.id, c.id), toggleLorebookScope(book.entries, setOf(c.id), "A"))
        assertEquals(setOf(c.id), toggleLorebookScope(book.entries, setOf(a.id, b.id, c.id), "A"))
        assertEquals(setOf(a.id, c.id), toggleLorebookScope(book.entries, setOf(a.id), "B"))
    }

    @Test fun `empty or missing scope cannot clear any manual selection`() {
        for (group in listOf("Empty", "Missing")) {
            assertEquals(setOf(a.id, c.id), toggleLorebookScope(book.entries, setOf(a.id, c.id), group))
        }
        assertEquals(emptySet<Uuid>(), toggleLorebookScope(emptyList(), emptySet(), ""))
    }

    @Test fun `partial deletion preserves unselected objects order and all group metadata`() {
        val result = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Delete(setOf(b.id, v.id)))).single()
        assertEquals(listOf(a, c, u), result.entries)
        result.entries.forEach { assertSame(book.entries.single { e -> e.id == it.id }, it) }
        assertEquals(book.entryGroups, result.entryGroups)
        assertEquals(5, book.entries.size)
    }

    @Test fun `deleting a whole group or whole book retains independent empty groups`() {
        val group = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Delete(setOf(a.id, b.id)))).single()
        assertEquals(listOf(c, u, v), group.entries)
        assertEquals(book.entryGroups, group.entryGroups)
        val empty = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Delete(book.entries.map { it.id }.toSet()))).single()
        assertTrue(empty.entries.isEmpty())
        assertEquals(book.entryGroups, empty.entryGroups)
    }

    @Test fun `failed deletion restores data and retry removes only intended entries`() = runTest {
        val unrelated = Lorebook(name = "unrelated")
        var stored = listOf(book, unrelated)
        var fail = true
        val writer = LorebookBatchWriter { update ->
            stored = update(stored)
            if (fail) { fail = false; throw IOException("disk failure") }
        }
        val request = LorebookBatchRequest(book, book, LorebookBatchAction.Delete(setOf(a.id)))
        assertTrue(runCatching { writer.commit(request) }.isFailure)
        assertEquals(listOf(book, unrelated), stored)
        writer.commit(request)
        assertEquals(book.entries.drop(1), stored[0].entries)
        assertSame(unrelated, stored[1])
    }

    @Test fun `empty stale and concurrently changed deletion requests are rejected`() {
        for (ids in listOf(emptySet(), setOf(Uuid.random()), setOf(a.id, Uuid.random()))) {
            assertTrue(runCatching { applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Delete(ids))) }.isFailure)
        }
        assertTrue(runCatching { applyLorebookBatch(listOf(book.copy(name = "changed")),
            LorebookBatchRequest(book, book, LorebookBatchAction.Delete(setOf(a.id)))) }.isFailure)
    }

    @Test fun `exclusive header drops change membership in place while ungrouped drops retain ids`() {
        for (group in listOf("B", "Empty", "")) {
            val moved = moveLorebookEntry(book.entries, a.id, LorebookEntryPlacement.groupEdge(group, true))
            assertEquals(a.copy(group = group), moved.single { it.id == a.id })
            assertEquals(book.entries.drop(1), moved.filterNot { it.id == a.id })
            if (group.isNotBlank()) assertEquals(book.entries.map { it.id }, moved.map { it.id }) // Competitive members have no manual sort requirement.
        }
    }

    @Test fun `ordinary precise drops preserve source fields and invalid drops are no ops`() {
        val moved = moveLorebookEntry(book.entries, b.id, LorebookEntryPlacement(a.id, false, "A"))
        assertEquals(listOf(b, a, c, u, v), moved)
        assertSame(book.entries, moveLorebookEntry(book.entries, b.id, LorebookEntryPlacement(Uuid.random(), true, "A")))
        assertSame(book.entries, moveLorebookEntry(book.entries, b.id, LorebookEntryPlacement(b.id, true, "A")))
    }
}
