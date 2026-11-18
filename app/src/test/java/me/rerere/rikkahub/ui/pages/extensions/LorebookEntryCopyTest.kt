package me.rerere.rikkahub.ui.pages.extensions

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryCopyTest {
    private val entries = List(3) {
        PromptInjection.RegexInjection(
            name = "entry $it", content = "body $it", group = "story", keywords = listOf("key $it"),
            secondaryKeys = listOf("other"), enabled = false, priority = 73, probability = 49,
            position = InjectionPosition.AT_DEPTH, injectDepth = 6, role = MessageRole.USER,
            selective = true, sticky = 2, cooldown = 3, displayIndex = 91,
            inclusionGroup = "shared", groupWeight = 37,
        )
    }
    private val source = Lorebook(name = "source", entries = entries)
    private val targetEntry = entries[0].copy(name = "existing") // Also exercise legacy cross-book ID collisions.
    private val target = Lorebook(name = "target", entries = listOf(targetEntry))
    private fun task(selected: List<PromptInjection.RegexInjection> = entries) = LorebookCopyTask(Uuid.random(), source.id, selected)

    @Test fun `copy uses new global unique ids retains fields source and target order`() {
        val request = LorebookBatchRequest(target, target, LorebookBatchAction.Copy(task()), LorebookEntryPlacement(targetEntry.id, false))
        val updated = applyLorebookBatch(listOf(source, target), request)
        assertSame(source, updated[0])
        assertSame(targetEntry, updated[1].entries.last())
        val copies = updated[1].entries.dropLast(1)
        assertEquals(entries, copies.zip(entries).map { (copy, original) -> copy.copy(id = original.id) })
        assertEquals(copies.size, copies.map { it.id }.distinct().size)
        assertTrue(copies.none { copy -> entries.any { it.id == copy.id } })
        assertEquals(listOf(targetEntry), target.entries)
    }

    @Test fun `empty first and last destinations preserve group configuration`() {
        for (end in listOf(false, true)) {
            val updated = applyLorebookBatch(listOf(source, target), LorebookBatchRequest(target, target, LorebookBatchAction.Copy(task()), atEnd = end))[1]
            val copies = if (end) updated.entries.drop(1) else updated.entries.dropLast(1)
            assertEquals(entries, copies.zip(entries).map { (copy, original) -> copy.copy(id = original.id) })
        }
        val empty = target.copy(entries = emptyList())
        val copied = applyLorebookBatch(listOf(source, empty), LorebookBatchRequest(empty, empty, LorebookBatchAction.Copy(task()), atEnd = true))[1]
        assertEquals(3, copied.entries.size)
    }

    @Test fun `same source missing source stale destination and wrong group are rejected`() {
        val requests = listOf(
            LorebookBatchRequest(source, source, LorebookBatchAction.Copy(task()), atEnd = true),
            LorebookBatchRequest(target, target, LorebookBatchAction.Copy(task().copy(sourceBookId = Uuid.random())), atEnd = true),
            LorebookBatchRequest(target, target.copy(description = "stale"), LorebookBatchAction.Copy(task()), atEnd = true),
            LorebookBatchRequest(target.copy(entries = listOf(targetEntry.copy(group = "other"))), target, LorebookBatchAction.Copy(task()), LorebookEntryPlacement(targetEntry.id, true)),
        )
        requests.forEach { assertTrue(runCatching { applyLorebookBatch(listOf(source, target), it) }.isFailure) }
        assertEquals(entries, source.entries)
    }

    @Test fun `copy state survives sheet lifetimes prevents replacement and removes last selection`() {
        val state = LorebookCopyState()
        state.stage(source.id, entries)
        val staged = state.pending.value
        assertTrue(runCatching { state.stage(target.id, listOf(targetEntry)) }.isFailure)
        assertEquals(staged, state.pending.value)
        state.remove(entries[1].id)
        assertEquals(listOf(entries[0], entries[2]), state.pending.value?.entries)
        state.remove(entries[0].id)
        state.remove(entries[2].id)
        assertNull(state.pending.value)
        state.stage(source.id, entries)
        state.cancel()
        assertNull(state.pending.value)
    }

    @Test fun `failed copy restores target retains task and retry inserts only once`() = runTest {
        val state = LorebookCopyState().apply { stage(source.id, entries) }
        val staged = checkNotNull(state.pending.value)
        var stored = listOf(source, target)
        var fail = true
        val writer = LorebookBatchWriter { transform ->
            stored = transform(stored)
            if (fail) { fail = false; throw IOException("disk failure") }
        }
        val request = LorebookBatchRequest(target, target, LorebookBatchAction.Copy(staged), atEnd = true)
        assertTrue(runCatching { state.commit(staged) { writer.commit(request) } }.isFailure)
        assertEquals(listOf(source, target), stored)
        assertEquals(staged, state.pending.value)
        assertFalse(state.busy.value)
        state.commit(staged) { writer.commit(request) }
        assertNull(state.pending.value)
        assertSame(source, stored[0])
        assertEquals(4, stored[1].entries.size)
        assertTrue(runCatching { state.commit(staged) { writer.commit(request) } }.isFailure)
        assertEquals(4, stored[1].entries.size)
    }

    @Test fun `saving freezes task and stale confirmation cannot consume a changed selection`() = runTest {
        val state = LorebookCopyState().apply { stage(source.id, entries) }
        val staged = checkNotNull(state.pending.value)
        state.remove(entries.last().id)
        assertTrue(runCatching { state.commit(staged) { Unit } }.isFailure)
        val current = checkNotNull(state.pending.value)
        val gate = CompletableDeferred<Unit>()
        val saving = async { state.commit(current) { gate.await() } }
        runCurrent()
        assertTrue(state.busy.value)
        state.cancel()
        state.remove(entries.first().id)
        assertEquals(current, state.pending.value)
        assertTrue(runCatching { state.stage(target.id, listOf(targetEntry)) }.isFailure)
        gate.complete(Unit)
        saving.await()
        assertNull(state.pending.value)
        assertFalse(state.busy.value)
    }

    @Test fun `rollback preserves unrelated concurrent book changes`() = runTest {
        val other = Lorebook(name = "other")
        var stored = listOf(source, target, other)
        var fail = true
        val changed = other.copy(description = "concurrent change")
        val writer = LorebookBatchWriter { update ->
            stored = update(stored)
            if (fail) {
                fail = false
                stored = stored.map { if (it.id == other.id) changed else it }
                throw IOException("disk failure")
            }
        }
        val request = LorebookBatchRequest(target, target, LorebookBatchAction.Copy(task()), atEnd = true)
        assertTrue(runCatching { writer.commit(request) }.isFailure)
        assertEquals(listOf(source, target, changed), stored)
    }

    @Test fun `json round trip retains copied order and semantic fields`() {
        val copied = applyLorebookBatch(listOf(source, target), LorebookBatchRequest(target, target, LorebookBatchAction.Copy(task()), atEnd = true))[1]
        val restored = LorebookSerializer.importFromJson(LorebookSerializer.exportToJson(copied), "test")
        assertEquals(copied.entries, copied.entries.zip(restored.entries).map { (before, after) -> after.copy(id = before.id) })
    }

    @Test fun `single copies consume only successful entry and remaining task can still commit together`() = runTest {
        val state = LorebookCopyState().apply { stage(source.id, entries) }
        var stored = listOf(source, target)
        val writer = LorebookBatchWriter { stored = it(stored) }
        val staged = checkNotNull(state.pending.value)
        val first = setOf(entries[1].id)
        val request = LorebookBatchRequest(target, target, LorebookBatchAction.Copy(staged, first), atEnd = true)
        state.commit(staged, first) { writer.commit(request) }
        assertEquals(listOf(entries[0], entries[2]), state.pending.value?.entries)
        assertEquals(staged.id, state.pending.value?.id)
        assertEquals(2, stored[1].entries.size)
        assertEquals(entries[1], stored[1].entries.last().copy(id = entries[1].id))
        assertNotEquals(entries[1].id, stored[1].entries.last().id)
        assertSame(source, stored[0])
        val remaining = checkNotNull(state.pending.value)
        val next = LorebookBatchRequest(stored[1], stored[1], LorebookBatchAction.Copy(remaining), atEnd = true)
        state.commit(remaining) { writer.commit(next) }
        assertNull(state.pending.value)
        assertEquals(listOf(targetEntry.name, entries[1].name, entries[0].name, entries[2].name), stored[1].entries.map { it.name })
        assertEquals(4, stored[1].entries.map { it.id }.distinct().size)
    }

    @Test fun `single copy failure invalid ids and stale confirmation preserve entire selection`() = runTest {
        val state = LorebookCopyState().apply { stage(source.id, entries) }
        val staged = checkNotNull(state.pending.value)
        var stored = listOf(source, target)
        var fail = true
        val writer = LorebookBatchWriter { update ->
            stored = update(stored)
            if (fail) { fail = false; throw IOException("disk failure") }
        }
        val ids = setOf(entries[0].id)
        val request = LorebookBatchRequest(target, target, LorebookBatchAction.Copy(staged, ids), atEnd = true)
        assertTrue(runCatching { state.commit(staged, ids) { writer.commit(request) } }.isFailure)
        assertEquals(staged, state.pending.value)
        assertEquals(listOf(source, target), stored)
        for (invalid in listOf(emptySet(), setOf(Uuid.random()))) {
            assertTrue(runCatching { applyLorebookBatch(stored, request.copy(action = LorebookBatchAction.Copy(staged, invalid))) }.isFailure)
            assertTrue(runCatching { state.commit(staged, invalid) { error("Must not write") } }.isFailure)
        }
        state.remove(entries[2].id)
        assertTrue(runCatching { state.commit(staged, ids) { error("Stale confirmation must not write") } }.isFailure)
        assertEquals(entries.take(2), state.pending.value?.entries)
    }
}
