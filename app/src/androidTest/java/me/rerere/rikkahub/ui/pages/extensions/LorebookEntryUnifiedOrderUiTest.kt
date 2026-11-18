package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LorebookEntryUnifiedOrderUiTest {
    @get:Rule val compose = createComposeRule()
    private val a = PromptInjection.RegexInjection(name = "Single A", content = "a")
    private val category = LorebookCategory(name = "Folder")
    private val empty = LorebookCategory(name = "Empty")
    private val b = PromptInjection.RegexInjection(name = "Child B", categoryId = category.id, content = "b")
    private val c = PromptInjection.RegexInjection(name = "Single C", content = "c")
    private var book by mutableStateOf(Lorebook(entries = listOf(a, b, c), categories = listOf(category, empty),
        rootOrder = listOf(LorebookOrderNode(entryId = a.id), LorebookOrderNode(categoryId = category.id), LorebookOrderNode(entryId = c.id), LorebookOrderNode(categoryId = empty.id))))
    private fun show() { compose.setContent { MaterialTheme { LorebookEntryList(book, null, {}, {}, {}, Modifier.size(360.dp, 640.dp),
        header = {}, onEntriesChange = { book = book.withLorebookEntries(it) }, onGroupsChange = { book = it },
        onEditEntry = {}, onAddEntry = {}, onGroupSettings = { _, _ -> }) } } }
    @Test fun mixedNodesAreVisibleInOrderAndCollapseNeverChangesData() {
        show()
        val before = book
        val aY = compose.onNodeWithText("Single A").fetchSemanticsNode().boundsInRoot.top
        val groupY = compose.onNodeWithTag("lorebook-category:${category.id}").fetchSemanticsNode().boundsInRoot.top
        val cY = compose.onNodeWithText("Single C").fetchSemanticsNode().boundsInRoot.top
        assertTrue(aY < groupY && groupY < cY)
        compose.onNodeWithContentDescription("拖动分类 Folder").performClick()
        compose.onNodeWithText("Child B").assertIsDisplayed()
        compose.onNodeWithContentDescription("拖动分类 Folder").performClick()
        compose.onNodeWithText("Child B").assertDoesNotExist()
        compose.runOnIdle { assertEquals(before, book) }
        captureLorebookRepairEvidence("unified-mixed-order")
    }
    @Test fun longPressMovesWholeGroupAcrossSingleEntry() {
        show()
        val handle = compose.onNodeWithContentDescription("拖动分类 Folder")
        val from = handle.fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithContentDescription("拖动排序 Single A").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        handle.performTouchInput { moveTo(Offset(from.width / 2, target.top + 2 - from.top)) }
        compose.mainClock.advanceTimeBy(50)
        handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(LorebookOrderNode(categoryId = category.id), book.orderedNodes().first())
            assertEquals(listOf(b, a, c), book.orderedEntries())
        }
        captureLorebookRepairEvidence("unified-group-across-single")
    }
    @Test fun categoryManagementDoesNotConvertLegacyGroups() {
        book = book.copy(entries = listOf(b.copy(group = "exclusive")))
        compose.setContent { MaterialTheme { LorebookCategoryManagement(book, { book = it }, {}) } }
        compose.onNodeWithText("管理分类组").assertIsDisplayed()
        compose.onNodeWithText("转为分类组").assertDoesNotExist()
        compose.runOnIdle { assertEquals("exclusive", book.entries.single().group); assertEquals(category.id, book.entries.single().categoryId) }
    }
}
