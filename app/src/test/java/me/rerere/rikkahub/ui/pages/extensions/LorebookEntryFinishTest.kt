package me.rerere.rikkahub.ui.pages.extensions

import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryFinishTest {
    private val a = PromptInjection.RegexInjection(name = "a", group = "A", content = "body", keywords = listOf("key"), priority = 50, injectDepth = 1)
    private val b = a.copy(id = Uuid.random(), name = "b", group = "B", priority = 80, injectDepth = 3, position = InjectionPosition.entries.last())
    private val book = Lorebook(entries = listOf(a, b), entryGroups = listOf("A", "Empty", "B"))
    @Test fun groupOrderOnlyChangesMetadataAndSurvivesJson() {
        val moved = moveLorebookGroup(book, "A", "B", true)
        assertEquals(listOf("Empty", "B", "A"), moved.entryGroups)
        assertSame(book.entries, moved.entries)
        assertEquals(moved, JsonInstant.decodeFromString<Lorebook>(JsonInstant.encodeToString(moved)))
    }
    @Test fun invalidGroupAndUngroupedCannotChangeOrder() {
        assertSame(book, moveLorebookGroup(book, "", "A", false))
        assertSame(book, moveLorebookGroup(book, "A", "missing", true))
        assertSame(book, moveLorebookGroup(book, "A", "A", false))
    }
    @Test fun priorityOnlyPreservesAllMixedAndIndependentFields() {
        val patch = LorebookSettingsPatch(priority = 90)
        val result = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Settings(setOf(a.id, b.id), patch))).single()
        assertEquals(listOf(a.copy(priority = 90), b.copy(priority = 90)), result.entries)
        assertEquals(book.entryGroups, result.entryGroups)
        assertEquals(50, a.priority)
    }
    @Test fun zeroAndFalseAreExplicitChangesAndUntouchedPatchIsIdentityByValue() {
        assertEquals(a, LorebookSettingsPatch().apply(a))
        assertEquals(a.copy(enabled = false, probability = 0, injectDepth = 0),
            LorebookSettingsPatch(enabled = false, probability = 0, depth = 0).apply(a))
    }
    @Test fun invalidOrStaleSettingsNeverPartiallyApply() {
        for (action in listOf(LorebookBatchAction.Settings(emptySet(), LorebookSettingsPatch(priority = 9)),
            LorebookBatchAction.Settings(setOf(a.id, Uuid.random()), LorebookSettingsPatch(priority = 9)),
            LorebookBatchAction.Settings(setOf(a.id), LorebookSettingsPatch(probability = 101)),
            LorebookBatchAction.Settings(setOf(a.id), LorebookSettingsPatch()))) {
            assertTrue(runCatching { applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, action)) }.isFailure)
        }
        assertEquals(listOf(a, b), book.entries)
    }
}
