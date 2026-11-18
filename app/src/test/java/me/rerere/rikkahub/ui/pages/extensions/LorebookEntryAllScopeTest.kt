package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryAllScopeTest {
    private val entries = listOf("A", "B", "", "全部条目", "  ").map { PromptInjection.RegexInjection(name = it, group = it, content = "body $it", keywords = listOf(it)) }
    private val book = Lorebook(entries = entries, entryGroups = listOf("A", "B", "全部条目", "Empty"))
    private val ids get() = lorebookScopeIds(book.entries, null)
    @Test fun allScopeIncludesEveryEntryAndIsDistinctFromAGroupWithTheSameLabel() {
        assertEquals(5, ids.size)
        assertEquals(setOf(entries[3].id), lorebookScopeIds(entries, "全部条目"))
        assertEquals(setOf(entries[2].id, entries[4].id), lorebookScopeIds(entries, ""))
        assertEquals(ids, toggleLorebookScope(entries, setOf(entries[0].id), null))
        assertTrue(toggleLorebookScope(entries, ids, null).isEmpty())
        assertEquals(ids, toggleLorebookScope(entries, ids, "Empty"))
    }
    @Test fun moveAllPreservesIdsConfigurationAndOrder() {
        val moved = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Move(ids), LorebookEntryPlacement.groupEdge("Target", true))).single()
        assertEquals(entries.map { it.copy(group = "Target") }, moved.entries)
        assertTrue(moved.entryGroups.contains("Empty"))
    }
    @Test fun deleteAllRetainsEmptyGroups() {
        val removed = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Delete(ids))).single()
        assertTrue(removed.entries.isEmpty());assertEquals(book.entryGroups, removed.entryGroups)
    }
    @Test fun settingsAllOnlyChangesRequestedField() {
        val changed = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Settings(ids, LorebookSettingsPatch(priority = 90)))).single()
        assertEquals(entries.map { it.copy(priority = 90) }, changed.entries)
    }
    @Test fun copyAllPreservesSourceAndAllocatesIndependentIds() {
        val target = Lorebook()
        val task = LorebookCopyTask(Uuid.random(), book.id, orderedLorebookSelection(book.entries, ids))
        val books = applyLorebookBatch(listOf(book, target), LorebookBatchRequest(target, target, LorebookBatchAction.Copy(task), LorebookEntryPlacement.groupEdge("Target", true)))
        assertSame(book, books[0])
        val copies = books[1].entries
        assertEquals(5, copies.size);assertEquals(5, copies.map { it.id }.distinct().size)
        copies.zip(entries).forEach { (copy, source) -> assertFalse(copy.id in ids);assertEquals(source.copy(id = copy.id, group = "Target"), copy) }
    }
}
