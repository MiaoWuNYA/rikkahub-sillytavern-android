package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LorebookEntryCategoryManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val category = LorebookCategory(name = "Folder")
    private val single = PromptInjection.RegexInjection(name = "Outside", content = "unchanged")
    private val member = PromptInjection.RegexInjection(name = "Inside", categoryId = category.id)
    private var book by mutableStateOf(Lorebook(entries = listOf(single, member), categories = listOf(category),
        rootOrder = listOf(LorebookOrderNode(entryId = single.id), LorebookOrderNode(categoryId = category.id))))
    @Test fun shortcutsMoveCategoryAcrossSinglesWithoutMutatingEntries() {
        compose.setContent { MaterialTheme { LorebookCategoryManagement(book, { book = it }, {}) } }
        compose.onNodeWithText("置顶").performClick()
        compose.runOnIdle { assertEquals(category.id, book.orderedNodes().first().categoryId); assertEquals(listOf(single, member), book.entries) }
        compose.onNodeWithText("移至末尾").performClick()
        compose.runOnIdle { assertEquals(category.id, book.orderedNodes().last().categoryId); assertEquals(listOf(single, member), book.entries) }
        captureLorebookRepairEvidence("round14-category-root-shortcuts")
    }
    @Test fun categoryDeletionConfirmationKeepsItsEntriesAndCanBeCancelled() {
        compose.setContent { MaterialTheme { LorebookCategoryManagement(book, { book = it }, {}) } }
        val before = book
        compose.onNodeWithText("删除分类").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(before, book) }
        compose.onNodeWithText("删除分类").performClick()
        compose.onNodeWithText("确认删除分类").performClick()
        compose.runOnIdle { assertTrue(book.categories.isEmpty()); assertEquals(listOf(single, member.copy(categoryId = null)), book.entries) }
    }
    @Test fun typedRangeChangesNeverOverwriteManualSelectionAcrossBothGroupKinds() {
        val exclusive = single.copy(id = kotlin.uuid.Uuid.random(), name = "Competitive", group = "Legacy")
        book = book.copy(entries = book.entries + exclusive)
        var selected by mutableStateOf(setOf(exclusive.id))
        compose.setContent { MaterialTheme { LorebookSelectionControls(book.entries, selected, LorebookBatchMode.MOVE,
            { selected = it }, {}, {}, book = book) } }
        compose.onNodeWithTag("lorebook-selection-scope").performClick()
        compose.onNodeWithText("分类组：Folder").performClick()
        compose.runOnIdle { assertEquals(setOf(exclusive.id), selected) }
        compose.onNodeWithText("全选该组").performClick()
        compose.runOnIdle { assertEquals(setOf(exclusive.id, member.id), selected) }
        compose.onNodeWithTag("lorebook-selection-scope").performClick()
        compose.onNodeWithText("未分类条目").performClick()
        compose.onNodeWithText("全选该组").performClick()
        compose.runOnIdle { assertEquals(setOf(exclusive.id, member.id, single.id), selected) }
    }
}
