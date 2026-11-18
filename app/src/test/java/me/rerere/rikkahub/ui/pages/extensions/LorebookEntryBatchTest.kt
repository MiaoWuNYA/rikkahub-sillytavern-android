package me.rerere.rikkahub.ui.pages.extensions

import java.io.IOException
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryBatchTest {
    @Test fun `successive single moves retain every field and remaining entries can move together`() {
        val entries = List(6) { PromptInjection.RegexInjection(name = "entry $it", content = "body $it", group = "same", priority = it, enabled = it % 2 == 0) }
        val book = Lorebook(entries = entries)
        val first = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book,
            LorebookBatchAction.Move(setOf(entries[0].id)), LorebookEntryPlacement(entries[3].id, true))).single()
        assertEquals(listOf(1, 2, 3, 0, 4, 5).map { entries[it] }, first.entries)
        val second = applyLorebookBatch(listOf(first), LorebookBatchRequest(first, first,
            LorebookBatchAction.Move(setOf(entries[1].id)), LorebookEntryPlacement(entries[0].id, true))).single()
        assertEquals(listOf(2, 3, 0, 1, 4, 5).map { entries[it] }, second.entries)
        val final = applyLorebookBatch(listOf(second), LorebookBatchRequest(second, second,
            LorebookBatchAction.Move(setOf(entries[2].id)), atEnd = true)).single()
        assertEquals(listOf(3, 0, 1, 4, 5, 2).map { entries[it] }, final.entries)
    }
    private fun entry(name: String, group: String = "") = PromptInjection.RegexInjection(
        name = name, group = group, content = "body $name", keywords = listOf(name),
        priority = 63, injectDepth = 7, probability = 37, enabled = false,
    )
    private val a = entry("A")
    private val b = entry("B")
    private val c = entry("C")
    private val d = entry("D")
    private val book = Lorebook(name = "book", entries = listOf(a, b, c, d))

    @Test fun `selection follows source order and staging never changes the book`() {
        assertEquals(listOf(a, c), orderedLorebookSelection(book.entries, linkedSetOf(c.id, a.id)))
        assertEquals(listOf(a, b, c, d), book.entries)
    }

    @Test fun `move resolves remaining target by id and preserves every entry instance`() {
        val request = LorebookBatchRequest(book, book, LorebookBatchAction.Move(setOf(c.id, a.id)), LorebookEntryPlacement(d.id, true))
        val moved = applyLorebookBatch(listOf(book), request).single()
        assertEquals(listOf(b, d, a, c), moved.entries)
        book.entries.forEach { assertSame(it, moved.entries.single { e -> e.id == it.id }) }
        assertEquals(listOf(a, b, c, d), book.entries)
    }

    @Test fun `first last and all entries have unambiguous positions`() {
        val action = LorebookBatchAction.Move(setOf(b.id, d.id))
        assertEquals(listOf(b, d, a, c), applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, action, atEnd = false)).single().entries)
        assertEquals(listOf(a, c, b, d), applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, action, atEnd = true)).single().entries)
        val all = LorebookBatchAction.Move(book.entries.map { it.id }.toSet())
        assertEquals(book, applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, all)).single())
    }

    @Test fun `missing selected entry selected target and mixed groups cannot mutate data`() {
        val bad = listOf(
            LorebookBatchRequest(book, book, LorebookBatchAction.Move(setOf(Uuid.random())), atEnd = true),
            LorebookBatchRequest(book, book, LorebookBatchAction.Move(setOf(a.id)), LorebookEntryPlacement(a.id, true)),
            LorebookBatchRequest(book, book, LorebookBatchAction.Move(emptySet()), atEnd = true),
        )
        bad.forEach { assertTrue(runCatching { applyLorebookBatch(listOf(book), it) }.isFailure) }
        assertTrue(runCatching { insertLorebookBatch(listOf(c), listOf(a, b.copy(group = "other")), null, true) }.isFailure)
        assertTrue(runCatching { insertLorebookBatch(listOf(c.copy(group = "other")), listOf(a), LorebookEntryPlacement(c.id, false), null) }.isFailure)
    }

    @Test fun `draft fields are saved while other books and groups are preserved`() {
        val other = book.copy(id = Uuid.random(), entries = listOf(entry("other", "other")))
        val draft = book.copy(description = "edited description", entries = book.entries.map { if (it.id == b.id) it.copy(content = "edited") else it })
        val saved = applyLorebookBatch(listOf(book, other), LorebookBatchRequest(draft, book, LorebookBatchAction.Move(setOf(b.id)), atEnd = false))
        assertSame(other, saved[1])
        assertEquals("edited description", saved[0].description)
        assertEquals("edited", saved[0].entries.first().content)
    }

    @Test fun `concurrent target changes are rejected`() {
        val changed = book.copy(name = "changed elsewhere")
        assertTrue(runCatching {
            applyLorebookBatch(listOf(changed), LorebookBatchRequest(book, book, LorebookBatchAction.Move(setOf(a.id)), atEnd = true))
        }.isFailure)
    }

    @Test fun `persistence failure restores target and retry does not duplicate entries`() = runTest {
        val other = book.copy(id = Uuid.random(), name = "other")
        var stored = listOf(book, other)
        var fail = true
        val writer = LorebookBatchWriter { update ->
            stored = update(stored)
            if (fail) { fail = false; throw IOException("disk failed") }
        }
        val request = LorebookBatchRequest(book, book, LorebookBatchAction.Move(setOf(a.id)), atEnd = true)
        assertTrue(runCatching { writer.commit(request) }.isFailure)
        assertEquals(listOf(book, other), stored)
        writer.commit(request)
        assertEquals(listOf(b, c, d, a), stored[0].entries)
        assertSame(other, stored[1])
    }

    @Test fun `export import preserves moved order and configuration`() {
        val moved = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Move(setOf(a.id, c.id)), atEnd = true)).single()
        val restored = LorebookSerializer.importFromJson(LorebookSerializer.exportToJson(moved), "test")
        assertEquals(moved.entries.map { it.name }, restored.entries.map { it.name })
        moved.entries.zip(restored.entries).forEach { (before, after) -> assertEquals(before, after.copy(id = before.id)) }
    }
}
