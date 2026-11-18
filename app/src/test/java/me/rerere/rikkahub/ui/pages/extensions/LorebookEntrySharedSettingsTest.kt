package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntrySharedSettingsTest {
    private val a = PromptInjection.RegexInjection(name = "unique", content = "body", keywords = listOf("key"), group = "G", priority = 1, constantActive = true)
    private val mixed = (1..3).map { n -> a.copy(id = Uuid.random(), priority = n, injectDepth = n, position = InjectionPosition.entries[n - 1]) }
    @Test fun identicalValuesAreShownButNoFieldsParticipateByDefault() {
        val state = LorebookBulkEditState(List(3) { a.copy(id = Uuid.random()) })
        assertTrue(state.selected.isEmpty());assertTrue(state.mixed.isEmpty());assertFalse(state.canApply)
        assertEquals(1, state.draft.priority);assertTrue(state.draft.constantActive)
    }
    @Test fun mixedValueCannotApplyFirstEntryWithoutAnExplicitNewValue() {
        val state = LorebookBulkEditState(mixed)
        assertTrue(EntrySettingKey.PRIORITY in state.mixed)
        assertEquals(0, state.draft.priority)
        state.select(EntrySettingKey.PRIORITY, true)
        assertFalse(state.canApply);assertTrue(runCatching { state.patch() }.isFailure)
        state.open(EntrySettingKey.PRIORITY);state.change(EntrySettingKey.PRIORITY, 90)
        assertTrue(state.canApply)
        assertEquals(mixed.map { it.copy(priority = 90) }, mixed.map { state.patch().apply(it) })
    }
    @Test fun multipleFieldsApplyAndDeselectingOnePreservesEachOriginalValue() {
        val state = LorebookBulkEditState(mixed)
        state.change(EntrySettingKey.PRIORITY, 90);state.change(EntrySettingKey.ENABLED, false)
        state.select(EntrySettingKey.PRIORITY, true);state.select(EntrySettingKey.ENABLED, true)
        assertEquals(mixed.map { it.copy(priority = 90, enabled = false) }, mixed.map { state.patch().apply(it) })
        state.select(EntrySettingKey.PRIORITY, false)
        assertEquals(mixed.map { it.copy(enabled = false) }, mixed.map { state.patch().apply(it) })
    }
    @Test fun actualBooleanAndFieldParticipationAreIndependent() {
        val state = LorebookBulkEditState(listOf(a, a.copy(id = Uuid.random())))
        state.change(EntrySettingKey.CONSTANT_ACTIVE, false)
        assertTrue(state.selected.isEmpty());assertFalse(state.canApply)
        state.select(EntrySettingKey.ENABLED, true)
        assertTrue(state.patch().apply(a).constantActive)
        state.select(EntrySettingKey.CONSTANT_ACTIVE, true)
        assertFalse(state.patch().apply(a).constantActive)
    }
    @Test fun nullableGlobalResetAndExtraCommonFieldsDoNotTouchIndependentContent() {
        val entries = mixed.mapIndexed { n, e -> e.copy(scanDepth = n + 1) }
        val state = LorebookBulkEditState(entries)
        state.open(EntrySettingKey.SCAN_DEPTH);state.change(EntrySettingKey.SCAN_DEPTH, null)
        state.select(EntrySettingKey.SCAN_DEPTH, true)
        state.change(EntrySettingKey.ROLE, MessageRole.ASSISTANT);state.select(EntrySettingKey.ROLE, true)
        assertEquals(entries.map { it.copy(scanDepth = null, role = MessageRole.ASSISTANT) }, entries.map { state.patch().apply(it) })
    }
    @Test fun cancelledDraftEditsNeverMutateOriginalEntriesAndAllowlistContainsNoContent() {
        val before = mixed.toList();val state = LorebookBulkEditState(mixed)
        state.change(EntrySettingKey.PRIORITY, 77);state.select(EntrySettingKey.PRIORITY, true)
        assertEquals(before, mixed)
        for (key in EntrySettingKey.entries) {
            val unchanged = key.write(a, key.read(a))
            assertEquals(a, unchanged)
        }
    }
    @Test fun untouchedLegacyValuesDoNotBlockOtherFieldsButSelectedValuesAreValidated() {
        val legacy = a.copy(probability = 150, delayUntilRecursion = -1)
        val state = LorebookBulkEditState(List(3) { legacy.copy(id = Uuid.random()) })
        state.change(EntrySettingKey.PRIORITY, 90);state.select(EntrySettingKey.PRIORITY, true)
        assertTrue(state.canApply)
        assertEquals(legacy.copy(priority = 90), state.patch().apply(legacy))
        state.select(EntrySettingKey.PROBABILITY, true)
        assertFalse(state.canApply)
        state.change(EntrySettingKey.PROBABILITY, 70)
        assertTrue(state.canApply)
        state.select(EntrySettingKey.DELAY_RECURSION, true)
        assertFalse(state.canApply)
        state.change(EntrySettingKey.DELAY_RECURSION, 2)
        assertEquals(legacy.copy(priority = 90, probability = 70, delayUntilRecursion = 2), state.patch().apply(legacy))
        for ((key, invalid) in listOf(EntrySettingKey.PROBABILITY to 150, EntrySettingKey.DELAY_RECURSION to -1)) {
            assertTrue(runCatching { LorebookSettingsPatch(overrides = mapOf(key to invalid)).apply(a) }.isFailure)
        }
    }
}
