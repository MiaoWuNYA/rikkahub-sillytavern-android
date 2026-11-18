package me.rerere.rikkahub.ui.pages.extensions

import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.transformMessages
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class LorebookEntryUnifiedOrderTest {
    private fun fixture(): Lorebook {
        val categories = listOf(LorebookCategory(name = "One"), LorebookCategory(name = "Two"), LorebookCategory(name = "Empty"))
        return Lorebook(categories = categories, entries = (0..12).map { n ->
            PromptInjection.RegexInjection(name = "Entry $n", content = "[[MARK_$n]]",
                categoryId = when { n < 6 -> categories[0].id; n == 6 -> categories[1].id; else -> null },
                constantActive = true, useProbability = false)
        })
    }
    private fun text(book: Lorebook): String = transformMessages(listOf(UIMessage.user("hello")),
        Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book))
        .flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    @Test fun allThirteenCategoryEntriesAppearExactlyOnce() {
        val book = fixture(); val output = text(book)
        book.entries.forEach { assertEquals(it.content, 1, Regex(Regex.escape(it.content)).findAll(output).count()) }
    }
    @Test fun disabledEntriesAreExcludedWithoutLosingTheirNeighbours() {
        val book = fixture().let { it.copy(entries = it.entries.mapIndexed { n, e -> e.copy(enabled = n % 3 != 0) }) }
        val output = text(book)
        book.entries.forEach { assertEquals(it.enabled, output.contains(it.content)) }
    }
    @Test fun sameCategoryDoesNotDisableLegacyCompetition() {
        val book = fixture().let { it.copy(entries = it.entries.mapIndexed { n, e -> e.copy(group = if (n < 6) "exclusive" else "") }) }
        val output = text(book)
        assertEquals(8, book.entries.count { output.contains(it.content) })
        assertEquals(1, book.entries.take(6).count { output.contains(it.content) })
    }
    @Test fun mixedRootOrderPersistsAndControlsOrdinaryPromptOutput() {
        val book = fixture(); val singles = book.entries.drop(7)
        val order = listOf(LorebookOrderNode(entryId = singles[0].id), LorebookOrderNode(categoryId = book.categories[0].id),
            LorebookOrderNode(entryId = singles[1].id), LorebookOrderNode(categoryId = book.categories[2].id),
            LorebookOrderNode(categoryId = book.categories[1].id)) + singles.drop(2).map { LorebookOrderNode(entryId = it.id) }
        val mixed = book.copy(rootOrder = order)
        val reopened = JsonInstant.decodeFromString<Lorebook>(JsonInstant.encodeToString(mixed))
        assertEquals(order, reopened.orderedNodes())
        val output = text(reopened)
        val offsets = reopened.orderedOrdinaryEntries().map { output.indexOf(it.content) }
        assertEquals(offsets.sorted(), offsets)
    }
    @Test fun wholeCategoryMovesBetweenSinglesWithoutChangingChildren() {
        val book = fixture(); val target = book.entries.last()
        val moved = moveLorebookCategory(book, book.categories[0].id, LorebookEntryPlacement(target.id, false, "", categoryTarget = true))
        assertEquals(book.entries.take(6), moved.entries.filter { it.categoryId == book.categories[0].id })
        val nodes = moved.orderedNodes()
        assertEquals(LorebookOrderNode(categoryId = book.categories[0].id), nodes[nodes.indexOf(LorebookOrderNode(entryId = target.id)) - 1])
        assertEquals(book.categories, moved.categories)
    }
    @Test fun nativeImportRemapsCategoryAndEntryReferences() {
        val book = fixture().let { it.copy(rootOrder = listOf(LorebookOrderNode(entryId = it.entries.last().id), LorebookOrderNode(categoryId = it.categories[0].id))) }
        val envelope = me.rerere.rikkahub.data.export.LorebookSerializer.export(book)
        val json = me.rerere.rikkahub.data.export.ExportSerializer.DefaultJson.encodeToString(envelope)
        val imported = me.rerere.rikkahub.data.export.LorebookSerializer.importFromJson(json, "test")
        assertEquals(book.orderedEntries().map { it.content }, imported.orderedEntries().map { it.content })
        assertTrue(book.entries.map { it.id }.toSet().intersect(imported.entries.map { it.id }.toSet()).isEmpty())
        assertTrue(book.categories.map { it.id }.toSet().intersect(imported.categories.map { it.id }.toSet()).isEmpty())
        assertTrue(imported.entries.all { it.categoryId == null || imported.categories.any { c -> c.id == it.categoryId } })
    }
    @Test fun legacyBooksRemainUnclassifiedWithoutChangingGroup() {
        val book = Lorebook(entries = listOf(PromptInjection.RegexInjection(group = "old", groupWeight = 17)))
        assertTrue(book.categories.isEmpty()); assertNull(book.entries.single().categoryId)
        assertEquals(book.entries, book.orderedEntries())
    }
    @Test fun explicitReleaseToCategoryChangesMembershipButPreservesTriggerSettings() {
        val book = fixture(); val source = book.entries.last().copy(group = "old", inclusionGroup = "other", groupWeight = 17)
        val placement = LorebookEntryPlacement.groupEdge(book.categories[0].id.toString(), true).copy(categoryTarget = true)
        val moved = insertCategoryEntries(book.entries.dropLast(1), listOf(source), placement, null).single { it.id == source.id }
        assertEquals(source.copy(group = "", categoryId = book.categories[0].id), moved)
        val removed = insertCategoryEntries(emptyList(), listOf(moved), placement.copy(group = ""), null).single()
        assertEquals(source.copy(group = ""), removed)
    }
    @Test fun deletingCategoryPromotesChildrenInPlaceAndKeepsOtherGroups() {
        val book = fixture(); val expected = book.orderedEntries().map { if (it.categoryId == book.categories[0].id) it.copy(categoryId = null) else it }
        val deleted = deleteLorebookCategory(book, book.categories[0].id)
        assertEquals(expected, deleted.orderedEntries())
        assertTrue(deleted.categories.any { it.name == "Empty" })
    }
    @Test fun completeMessageJsonRoundTripContainsAllThirteenMarkersOnceInOrder() {
        val book = fixture().let { it.copy(rootOrder = listOf(LorebookOrderNode(entryId = it.entries.last().id)) + it.orderedNodes()) }
        val result = transformMessages(listOf(UIMessage.user("hello")), Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book))
        val json = JsonInstant.encodeToString(result)
        val reopened = JsonInstant.decodeFromString<List<UIMessage>>(json)
        assertEquals(result.map { it.role to it.parts }, reopened.map { it.role to it.parts })
        val output = reopened.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val offsets = book.orderedOrdinaryEntries().map { entry ->
            assertEquals(1, Regex(Regex.escape(entry.content)).findAll(json).count())
            output.indexOf(entry.content)
        }
        assertEquals(offsets.sorted(), offsets)
    }
    @Test fun movingCategoryReordersPromptWithoutRewritingStorage() {
        val book = fixture()
        val moved = moveLorebookCategory(book, book.categories[0].id, LorebookEntryPlacement(book.entries.last().id, true, "", categoryTarget = true))
        val reopened = JsonInstant.decodeFromString<Lorebook>(JsonInstant.encodeToString(moved))
        val output = text(reopened)
        assertEquals(book.entries, reopened.entries)
        val offsets = reopened.orderedOrdinaryEntries().map { output.indexOf(it.content) }
        assertEquals(offsets.sorted(), offsets)
    }
    @Test fun crossBookCopyAssignsOnlyTargetCategoryAndFreshEntryIdentity() {
        val source = fixture(); val category = LorebookCategory(name = "Target")
        val target = Lorebook(categories = listOf(category))
        val task = LorebookCopyTask(kotlin.uuid.Uuid.random(), source.id, source.entries.take(2))
        val p = LorebookEntryPlacement.groupEdge(category.id.toString(), true).copy(categoryTarget = true)
        val result = applyLorebookBatch(listOf(source, target), LorebookBatchRequest(target, target, LorebookBatchAction.Copy(task), p))
        assertEquals(source, result.first())
        val copies = result.last().entries
        assertEquals(2, copies.size)
        assertTrue(copies.all { it.categoryId == category.id && source.entries.none { e -> e.id == it.id } })
        assertEquals(source.entries.take(2), copies.mapIndexed { index, e -> e.copy(id = source.entries[index].id, categoryId = source.entries[index].categoryId) })
    }
    @Test fun unknownTargetCategoryFailsWithoutModifyingEitherBook() {
        val source = fixture(); val target = Lorebook()
        val before = listOf(source, target)
        val task = LorebookCopyTask(kotlin.uuid.Uuid.random(), source.id, source.entries.take(1))
        val p = LorebookEntryPlacement.groupEdge(kotlin.uuid.Uuid.random().toString(), true).copy(categoryTarget = true)
        assertTrue(runCatching { applyLorebookBatch(before, LorebookBatchRequest(target, target, LorebookBatchAction.Copy(task), p)) }.isFailure)
        assertEquals(listOf(source, target), before)
    }
    @Test fun emptyCategoryRetainsItsRootSlotAfterSingleInsertion() {
        val book = fixture().let { it.copy(rootOrder = listOf(LorebookOrderNode(categoryId = it.categories.last().id)) + it.orderedNodes()) }
        val entry = PromptInjection.RegexInjection(content = "new", group = "exclusive", groupWeight = 17)
        val p = LorebookEntryPlacement.groupEdge(book.categories.last().id.toString(), true).copy(categoryTarget = true)
        val inserted = insertLorebookEntry(book.orderedEntries(), entry, p)
        val updated = book.withPlacedEntries(inserted, setOf(entry.id), p)
        assertEquals(book.orderedNodes().first(), updated.orderedNodes().first())
        assertEquals(entry.copy(group = "", categoryId = book.categories.last().id), updated.entries.single { it.id == entry.id })
    }

}
