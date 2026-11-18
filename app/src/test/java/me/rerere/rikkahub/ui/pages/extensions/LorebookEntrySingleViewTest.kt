package me.rerere.rikkahub.ui.pages.extensions

import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntrySingleViewTest {
    private val category = LorebookCategory(name = "Folder")
    private val empty = LorebookCategory(name = "Empty")
    private val a = PromptInjection.RegexInjection(name = "A", content = "A", keywords = listOf("key"), priority = 71)
    private val b = a.copy(id = Uuid.random(), name = "B", categoryId = category.id)
    private val x = a.copy(id = Uuid.random(), name = "X", group = "Exclusive", categoryId = category.id, groupWeight = 17, inclusionGroup = "Other")
    private val z = a.copy(id = Uuid.random(), name = "Z")
    private val book = Lorebook(entries = listOf(a, b, x, z), categories = listOf(category, empty),
        rootOrder = listOf(LorebookOrderNode(entryId = a.id), LorebookOrderNode(categoryId = category.id), LorebookOrderNode(entryId = z.id), LorebookOrderNode(categoryId = empty.id)))
    private fun ordinary(group: Uuid? = null, end: Boolean = true) = LorebookEntryPlacement.groupEdge(group?.toString().orEmpty(), end).copy(categoryTarget = true)
    @Test fun exclusiveEntriesNeverAppearInOrdinaryRootsEvenWithPreviousDualMetadata() {
        assertEquals(listOf(a, b, z), book.orderedOrdinaryEntries())
        assertEquals(4, book.orderedEntries().size)
        assertEquals(4, book.orderedEntries().map { it.id }.toSet().size)
    }
    @Test fun categoryShortcutsCrossOrdinarySinglesAndKeepStorageUnchanged() {
        val top = moveLorebookCategoryToEdge(book, empty.id, false)
        val bottom = moveLorebookCategoryToEdge(book, category.id, true)
        assertEquals(empty.id, top.orderedNodes().first().categoryId)
        assertEquals(category.id, bottom.orderedNodes().last().categoryId)
        assertEquals(book.entries, top.entries); assertEquals(book.entries, bottom.entries)
    }
    @Test fun exclusiveHeaderMovePreservesStableIdsPhysicalOrderAndAllIndependentFields() {
        val moved = moveLorebookEntry(book.entries, b.id, LorebookEntryPlacement.groupEdge("Exclusive", true))
        assertEquals(book.entries.map { it.id }, moved.map { it.id })
        assertEquals(b.copy(group = "Exclusive", categoryId = null), moved[1])
        assertEquals(book.entries.filterNot { it.id == b.id }, moved.filterNot { it.id == b.id })
    }
    @Test fun batchMoveIntoExclusiveChangesMembershipInPlaceWithoutSortingMembers() {
        val result = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book,
            LorebookBatchAction.Move(setOf(a.id, b.id)), LorebookEntryPlacement.groupEdge("Exclusive", true))).single()
        assertEquals(book.entries.map { it.id }, result.entries.map { it.id })
        assertEquals(listOf(a.copy(group = "Exclusive"), b.copy(group = "Exclusive", categoryId = null), x, z), result.entries)
        assertEquals(listOf(z), result.orderedOrdinaryEntries())
    }
    @Test fun releaseToCategoryRetainsContentAndCompetitionConfigurationExceptMembership() {
        val p = ordinary(category.id)
        val result = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Move(setOf(x.id)), p)).single()
        assertEquals(x.copy(group = "", categoryId = category.id), result.entries.single { it.id == x.id })
        assertEquals(listOf(b, x.copy(group = "", categoryId = category.id)), result.orderedOrdinaryEntries().filter { it.categoryId == category.id })
    }
    @Test fun releaseToSpecificUnclassifiedRootKeepsOtherMixedNodesInPlace() {
        val p = LorebookEntryPlacement(z.id, false, "", categoryTarget = true)
        val result = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Move(setOf(x.id)), p)).single()
        assertEquals(listOf(a, b, x.copy(group = "", categoryId = null), z), result.orderedOrdinaryEntries())
        assertEquals(empty.id, result.orderedNodes().last().categoryId)
    }
    @Test fun emptyCategorySlotSurvivesInsertDeleteAndRoundTrip() {
        val p = ordinary(empty.id)
        val result = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Move(setOf(x.id)), p)).single()
        assertEquals(empty.id, result.orderedNodes().last().categoryId)
        val deleted = result.withOrderedEntries(result.entries.filterNot { it.id == x.id })
        val reopened = JsonInstant.decodeFromString<Lorebook>(JsonInstant.encodeToString(deleted))
        assertEquals(empty.id, reopened.orderedNodes().last().categoryId)
        assertTrue(reopened.categories.contains(empty))
    }
    @Test fun dissolveExclusiveGroupNeverDeletesItsMembersOrSettings() {
        val result = dissolveLorebookExclusiveGroup(book, "Exclusive")
        assertEquals(book.entries.size, result.entries.size)
        assertEquals(x.copy(group = "", categoryId = null), result.entries.single { it.id == x.id })
        assertFalse(lorebookEntryGroupNames(result).contains("Exclusive"))
    }
    @Test fun renameExclusiveDoesNotUseFirstEntryAsTemplate() {
        val result = renameLorebookExclusiveGroup(book, "Exclusive", "Renamed")
        assertEquals(book.entries.map { if (it.id == x.id) it.copy(group = "Renamed") else it }, result.entries)
    }
    @Test fun deletingCategoryPromotesOnlyItsChildrenWithoutTouchingExclusiveMembership() {
        val result = deleteLorebookCategory(book, category.id)
        assertEquals(book.entries.size, result.entries.size)
        assertEquals(listOf(a, b.copy(categoryId = null), z), result.orderedOrdinaryEntries())
        assertEquals("Exclusive", result.entries.single { it.id == x.id }.group)
    }
    @Test fun scopesIncludeHiddenMembersButNeverCoupleCategoryAndExclusive() {
        assertEquals(setOf(a.id, b.id, x.id, z.id), lorebookScopeIds(book.entries, LorebookSelectionScope.All))
        assertEquals(setOf(a.id, z.id), lorebookScopeIds(book.entries, LorebookSelectionScope.Unclassified))
        assertEquals(setOf(b.id), lorebookScopeIds(book.entries, LorebookSelectionScope.Category(category.id)))
        assertEquals(setOf(x.id), lorebookScopeIds(book.entries, LorebookSelectionScope.Exclusive("Exclusive")))
    }
    @Test fun scopedToggleRetainsManualSelectionOutsideCurrentRange() {
        val scope = LorebookSelectionScope.Category(category.id)
        val checked = toggleLorebookScope(book.entries, setOf(x.id), scope)
        assertEquals(setOf(x.id, b.id), checked)
        assertEquals(setOf(x.id), toggleLorebookScope(book.entries, checked, scope))
    }
    @Test fun copyToExplicitOrdinaryTargetUsesFreshIdsAndDoesNotChangeSource() {
        val target = Lorebook(categories = listOf(empty))
        val task = LorebookCopyTask(Uuid.random(), book.id, listOf(x))
        val result = applyLorebookBatch(listOf(book, target), LorebookBatchRequest(target, target, LorebookBatchAction.Copy(task), ordinary(empty.id)))
        assertEquals(book, result.first())
        val copied = result.last().entries.single()
        assertNotEquals(x.id, copied.id)
        assertEquals(x.copy(id = copied.id, group = "", categoryId = empty.id), copied)
    }
    @Test fun copyToExclusivePreservesSettingsAndNeverImportsSourceCategoryId() {
        val target = Lorebook()
        val task = LorebookCopyTask(Uuid.random(), book.id, listOf(b))
        val result = applyLorebookBatch(listOf(book, target), LorebookBatchRequest(target, target, LorebookBatchAction.Copy(task), LorebookEntryPlacement.groupEdge("Destination", true)))
        val copied = result.last().entries.single()
        assertEquals(b.copy(id = copied.id, group = "Destination", categoryId = null), copied)
        assertEquals(book, result.first())
    }
    @Test fun unrelatedBulkSettingsNeverCleanOrRewriteOldHiddenMetadata() {
        val result = applyLorebookBatch(listOf(book), LorebookBatchRequest(book, book, LorebookBatchAction.Settings(setOf(x.id), LorebookSettingsPatch(priority = 90)))).single()
        assertEquals(x.copy(priority = 90), result.entries.single { it.id == x.id })
    }
    @Test fun twoHundredEntriesAndEmptyCategoriesSurviveMixedOrderingAndFreshImport() {
        val large = book.copy(entries = List(240) { n -> a.copy(id = Uuid.random(), name = "Entry $n", group = if (n % 7 == 0) "Exclusive" else "", categoryId = if (n % 3 == 0) category.id else null) })
        val imported = large.withFreshIds()
        assertEquals(240, imported.orderedEntries().map { it.id }.toSet().size)
        assertEquals(large.entries.map { it.content }, imported.entries.map { it.content })
        assertTrue(imported.entries.all { it.categoryId == null || imported.categories.any { c -> c.id == it.categoryId } })
    }
}
