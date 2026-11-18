package me.rerere.rikkahub.ui.pages.extensions

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryGroupsTest {
    @Test fun `legacy whitespace groups remain ungrouped without rewriting existing fields`() {
        val whitespace = PromptInjection.RegexInjection(name = "space", group = "  ", content = "preserve")
        val plain = whitespace.copy(id = Uuid.random(), name = "plain", group = "")
        val fresh = whitespace.copy(id = Uuid.random(), name = "fresh", group = "")
        val original = listOf(whitespace, plain)
        val inserted = insertLorebookEntry(original, fresh, LorebookEntryPlacement.groupEdge("", false))
        assertEquals(listOf(fresh, whitespace, plain), inserted)
        assertEquals("  ", inserted[1].group)
        val reordered = moveLorebookEntry(original, whitespace.id, LorebookEntryPlacement(plain.id, true, ""))
        assertEquals(listOf(plain, whitespace), reordered)
        assertEquals(original.associateBy { it.id }, reordered.associateBy { it.id })
    }
    private val entries = List(5) {
        PromptInjection.RegexInjection(name = "entry $it", content = "full $it", group = if (it % 2 == 0) "A" else "B",
            keywords = listOf("key$it"), enabled = false, priority = 77, injectDepth = 9, probability = 31,
            inclusionGroup = "competition", groupWeight = 43)
    }
    private val book = Lorebook(entries = entries, entryGroups = listOf("B", "Empty", "A"))

    @Test fun `legacy native json without group metadata keeps entries and derives original group order`() {
        val exported = Json.parseToJsonElement(LorebookSerializer.exportToJson(book)).jsonObject
        val legacy = JsonObject(exported + ("data" to JsonObject(exported.getValue("data").jsonObject - "entryGroups")))
        val restored = LorebookSerializer.importFromJson(legacy.toString(), "legacy")
        assertTrue(restored.entryGroups.isEmpty())
        assertEquals(listOf("A", "B"), lorebookEntryGroupNames(restored))
        assertEquals(entries, restored.entries.zip(entries).map { (a, b) -> a.copy(id = b.id) })
    }

    @Test fun `empty groups and group display order survive native export and import`() {
        val created = createLorebookEntryGroup(book, "  New  ")
        assertEquals(listOf("B", "Empty", "A", "New"), created.entryGroups)
        assertSame(entries, created.entries)
        val restored = LorebookSerializer.importFromJson(LorebookSerializer.exportToJson(created), "roundtrip")
        assertEquals(created.entryGroups, restored.entryGroups)
        assertEquals(entries, restored.entries.zip(entries).map { (a, b) -> a.copy(id = b.id) })
        assertTrue(runCatching { createLorebookEntryGroup(created, "New") }.isFailure)
        assertTrue(runCatching { createLorebookEntryGroup(created, " ") }.isFailure)
    }

    @Test fun `new entry can target empty group or precise position in another group`() {
        val fresh = entries[0].copy(id = Uuid.random(), group = "source")
        val empty = insertLorebookEntry(entries, fresh, LorebookEntryPlacement.groupEdge("Empty", false))
        assertEquals(fresh.copy(group = "Empty"), empty.last())
        val precise = insertLorebookEntry(entries, fresh, LorebookEntryPlacement(entries[1].id, false, "B"))
        assertEquals(fresh.copy(group = "B"), precise[1])
        assertEquals(entries, precise.filterNot { it.id == fresh.id })
    }

    @Test fun `single move changes only group and order retains id and leaves empty source group`() {
        val source = book.copy(entries = listOf(entries[0].copy(group = "Solo")) + entries.drop(1))
        val first = source.entries.first()
        val updated = applyLorebookBatch(listOf(source), LorebookBatchRequest(source, source,
            LorebookBatchAction.Move(setOf(first.id)), LorebookEntryPlacement(entries[1].id, true, "B"))).single()
        assertEquals(first.copy(group = "B"), updated.entries[1])
        assertEquals(source.entries.drop(1), updated.entries.filterNot { it.id == first.id })
        assertTrue("Solo" in updated.entryGroups)
        assertTrue(updated.entries.none { it.group == "Solo" })
    }

    @Test fun `mixed source batch keeps source order and other target members order`() {
        val selected = setOf(entries[0].id, entries[1].id, entries[3].id)
        val updated = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book,
            LorebookBatchAction.Move(selected), LorebookEntryPlacement(entries[2].id, true, "A"))).single()
        assertEquals(listOf(entries[2], entries[1].copy(group = "A"), entries[3].copy(group = "A"), entries[0], entries[4]), updated.entries)
        assertEquals(book.entryGroups, updated.entryGroups)
    }

    @Test fun `group edge means first or last member not whole book edge even with interleaved entries`() {
        val fresh = entries[0].copy(id = Uuid.random(), group = "source")
        val first = insertLorebookEntry(entries, fresh, LorebookEntryPlacement.groupEdge("B", false))
        val last = insertLorebookEntry(entries, fresh, LorebookEntryPlacement.groupEdge("B", true))
        assertEquals(fresh.copy(group = "B"), first[1])
        assertEquals(fresh.copy(group = "B"), last[4])
        assertEquals(entries, first.filterNot { it.id == fresh.id })
        assertEquals(entries, last.filterNot { it.id == fresh.id })
        assertEquals(fresh.copy(group = ""), insertLorebookEntry(emptyList(), fresh, LorebookEntryPlacement.groupEdge("", true)).single())
    }

    @Test fun `cross book mixed copy uses chosen group new ids and never changes source`() {
        val target = Lorebook(entryGroups = listOf("Empty"))
        val state = LorebookCopyState().apply { stage(book.id, entries.take(2)) }
        val task = checkNotNull(state.pending.value)
        val result = applyLorebookBatch(listOf(book, target), LorebookBatchRequest(target, target,
            LorebookBatchAction.Copy(task), LorebookEntryPlacement.groupEdge("Empty", true)))
        assertSame(book, result[0])
        assertEquals(entries.take(2).map { it.copy(group = "Empty") }, result[1].entries.zip(entries).map { (a, b) -> a.copy(id = b.id) })
        assertTrue(result[1].entries.none { a -> entries.any { it.id == a.id } })
        assertEquals(task, state.pending.value) // Pure planning never consumes clipboard state.
        val invalid = LorebookBatchRequest(book, book, LorebookBatchAction.Move(setOf(entries[0].id)),
            LorebookEntryPlacement(entries[1].id, true, "changed"))
        assertTrue(runCatching { applyLorebookBatch(listOf(book), invalid) }.isFailure)
    }

    @Test fun `group headers and foreign entries are valid drops but safety still clears targets`() {
        val safety = LorebookDragBounds(0f, 0f, 300f, 100f)
        val viewport = LorebookDragBounds(0f, 100f, 300f, 600f)
        fun feedback(y: Float) = evaluateLorebookEntryDrag(LorebookDragPoint(100f, y), safety, viewport,
            listOf(LorebookDropTarget(null, "Empty", 110f, 150f), LorebookDropTarget(entries[1].id, "B", 160f, 250f)),
            50f, 720f, true, true)
        assertEquals(LorebookEntryPlacement.groupEdge("Empty", true), feedback(130f).placement)
        assertEquals(LorebookEntryPlacement(entries[1].id, true, "B"), feedback(240f).placement)
        assertNull(feedback(50f).placement)
        assertEquals(0f, feedback(50f).scrollVelocity, 0f)
    }
}
