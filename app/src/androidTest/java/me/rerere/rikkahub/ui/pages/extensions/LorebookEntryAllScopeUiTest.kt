package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LorebookEntryAllScopeUiTest {
    @get:Rule val compose = createComposeRule()
    // Folding is now a category property; exclusive group membership remains independently present.
    private val categories = listOf("A", "B", "全部条目").map { LorebookCategory(name = "Folder $it") }
    private val initialEntries = listOf("A", "B", "", "全部条目").map { name ->
        PromptInjection.RegexInjection(name = "Entry $name", group = name, content = "body $name",
            categoryId = null)
    }
    private val original = Lorebook(entries = initialEntries, entryGroups = listOf("A", "B", "全部条目", "Target"),
        categories = categories, rootOrder = initialEntries.map { e -> e.categoryId?.let { LorebookOrderNode(categoryId = it) } ?: LorebookOrderNode(entryId = e.id) })
    private var draft by mutableStateOf(original)
    private var stored by mutableStateOf(original)
    private var staged = emptyList<PromptInjection.RegexInjection>()
    private fun begin(mode: String) {
        compose.setContent { MaterialTheme { LorebookEditSheet(draft, {}, {}, {}, stored,
            onStageCopy = { staged = it }, onCommitBatch = { request -> applyLorebookBatch(listOf(stored), request).single().also { stored = it } }, onEdit = { draft = it }) } }
        compose.onNodeWithText("批量管理条目").performClick()
        compose.onNodeWithText(mode).performClick()
    }
    private fun all(label: String) {
        compose.onNodeWithTag("lorebook-selection-scope").performClick()
        compose.onNode(hasText("全部条目") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("$label · 已选 0 条").assertIsDisplayed()
        compose.onNodeWithText("全选条目").performClick()
        compose.onNodeWithText("$label · 已选 4 条").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择条目 Entry A").assertDoesNotExist() // Still folded.
    }
    @Test fun copyIncludesFoldedGroupsAndHelpActuallyPrecedesScope() {
        begin("复制到其他世界书")
        val help = compose.onNodeWithText("ⓘ 复制说明").fetchSemanticsNode().boundsInRoot
        val scope = compose.onNodeWithTag("lorebook-selection-scope").fetchSemanticsNode().boundsInRoot
        val buttons = compose.onNodeWithText("全选该组").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("跨书复制 · 已选 0 条").fetchSemanticsNode().boundsInRoot
        assertTrue(help.left >= title.right)
        assertTrue(help.top < title.bottom && title.top < help.bottom)
        assertTrue(maxOf(help.bottom, title.bottom) <= scope.top)
        assertTrue(scope.bottom <= buttons.top)
        all("跨书复制")
        captureLorebookRepairEvidence("all-scope-copy-layout")
        compose.onNodeWithText("完成选择").performClick()
        compose.runOnIdle { assertEquals(original.orderedEntries(), staged);assertEquals(original, stored) }
    }
    @Test fun deleteAllIncludesFoldedGroupsAndKeepsGroupMetadata() {
        begin("删除条目");all("批量删除")
        compose.onNodeWithText("删除所选").performClick()
        compose.onNodeWithText("将删除 4 条", substring = true).assertIsDisplayed()
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle { assertTrue(stored.entries.isEmpty());assertEquals(original.entryGroups, stored.entryGroups) }
    }
    @Test fun settingsAllIncludesFoldedGroupsAndPreservesOtherFields() {
        begin("批量设置");all("批量设置")
        compose.onNodeWithText("设置所选").performClick()
        compose.onNodeWithTag("bulk-select-PRIORITY").performClick()
        compose.onNodeWithTag("bulk-priority").performTextReplacement("90")
        compose.onNodeWithText("应用到 4 个条目").performClick()
        compose.runOnIdle { assertEquals(original.entries.map { it.copy(priority = 90) }, stored.entries) }
    }
    @Test fun moveAllIncludesFoldedGroupsAndUpdatesEverySelectedEntry() {
        begin("移动到本书其他位置");all("批量移动")
        compose.onNodeWithTag("lorebook-target-group").performClick()
        compose.onNodeWithText("互斥组：Target").performClick()
        compose.onNodeWithText("移入互斥组").performClick()
        compose.onNodeWithText("将 4 条移动到", substring = true).assertIsDisplayed()
        compose.onNodeWithText("确认移动").performClick()
        compose.runOnIdle { assertEquals(original.entries.map { it.copy(group = "Target") }, stored.entries) }
    }
    @Test fun allScopeSwitchAndRestorePreserveManualSelectionUntilSelectAllIsPressed() {
        var selected by mutableStateOf(setOf(original.entries[1].id))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { LorebookSelectionControls(original.entries, selected, LorebookBatchMode.MOVE,
            { selected = it }, {}, {}, groups = original.entryGroups) } }
        fun choose(label: String) {
            compose.onNodeWithTag("lorebook-selection-scope").performClick()
            compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
        }
        choose("全部条目")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("全选范围：全部条目").assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf(original.entries[1].id), selected) }
        choose("互斥组：A")
        compose.runOnIdle { assertEquals(setOf(original.entries[1].id), selected) }
        choose("全部条目")
        compose.onNodeWithText("全选条目").performClick()
        compose.runOnIdle { assertEquals(original.entries.map { it.id }.toSet(), selected) }
        compose.onNodeWithText("取消全选").performClick()
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
    }

}
