package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryRepairUiTest {
    @get:Rule val compose = createComposeRule()
    private val a = PromptInjection.RegexInjection(name = "Alpha", group = "A", content = "body Alpha", priority = 71)
    private val b = a.copy(id = Uuid.random(), name = "Beta")
    private val anchor = a.copy(id = Uuid.random(), name = "Anchor", group = "B")
    private val ungrouped = a.copy(id = Uuid.random(), name = "Loose", group = "")
    private val original = Lorebook(entries = listOf(a, b, anchor, ungrouped), entryGroups = listOf("A", "B", "Empty"))
    private var draft by mutableStateOf(original)
    private var stored by mutableStateOf(original)
    private var visible by mutableStateOf(true)
    private var writes = 0

    private fun showSheet() {
        compose.setContent {
            MaterialTheme {
                if (!visible) TextButton(onClick = { draft = stored; visible = true }) { Text("Reopen") }
                else LorebookEditSheet(draft, { visible = false }, { stored = draft; visible = false }, { stored = it }, stored,
                    onCommitBatch = { request -> applyLorebookBatch(listOf(stored), request).single().also { stored = it } },
                    onEdit = { draft = it; writes++ })
            }
        }
    }

    private fun expand(group: String) {
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasText(group) and hasClickAction())
        compose.onNode(hasText(group) and hasClickAction()).performClick()
    }

    private fun begin(mode: String) {
        compose.onNodeWithText("批量管理条目").performClick()
        compose.onNodeWithText(mode).performClick()
    }

    private val expandedForSelection = mutableSetOf<String>()
    private fun select(name: String) {
        val group = draft.entries.first { it.name == name }.group
        if (group.isNotBlank() && expandedForSelection.add(group)) expand(group)
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasContentDescription("选择条目 $name"))
        compose.onNodeWithContentDescription("选择条目 $name").performClick()
    }

    private fun dragTo(handle: SemanticsNodeInteraction, target: SemanticsNodeInteraction, cancel: Boolean = false) {
        val origin = handle.fetchSemanticsNode().boundsInRoot
        val destination = target.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        handle.performTouchInput { moveTo(Offset(origin.width / 2, destination.center.y - origin.top)) }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { assertEquals("No hover may mutate the draft", 0, writes) }
        if (cancel) handle.performTouchInput { cancel() } else handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun chooseScope(group: String) {
        compose.onNodeWithTag("lorebook-selection-scope").performClick()
        compose.onNodeWithText("${if (group.isBlank()) "未分类条目" else "互斥组：$group"}").performClick()
    }

    @LorebookSelectionIntegration
    @Test fun explicitScopeStaysIndependentAcrossManualCrossGroupSelectionAndScopedToggles() {
        showSheet()
        begin("移动到本书其他位置")
        fun scope(group: String) = compose.onNodeWithText("全选范围：${if (group == "未分组") "未分类条目" else "互斥组 · $group"}").assertIsDisplayed()
        fun count(n: Int) = compose.onNodeWithText("批量移动 · 已选 $n 条").assertIsDisplayed()
        scope("未分组")
        select("Alpha")
        select("Beta")
        scope("未分组"); count(2)
        select("Anchor")
        scope("未分组"); count(3)
        select("Beta")
        count(2)
        compose.onNodeWithText("全选该组").performClick()
        count(3) // Adds only Loose; keeps Alpha and Anchor.
        compose.onNodeWithText("取消全选").performClick()
        count(2)
        chooseScope("A")
        scope("A"); count(2)
        compose.onNodeWithText("全选该组").performClick()
        count(3)
        select("Alpha")
        scope("A"); count(2)
        compose.onNodeWithText("全选该组").performClick()
        compose.onNodeWithText("取消全选").performClick()
        count(1) // Removes A only; Anchor in B remains selected.
        select("Anchor")
        scope("A"); count(0)
        chooseScope("B")
        select("Alpha")
        scope("B"); count(1)
        select("Anchor")
        scope("B"); count(2)
        compose.onNodeWithText("完成选择").assertIsEnabled()
        compose.runOnIdle { assertEquals(original, stored); assertEquals(original, draft) }
        captureLorebookRepairEvidence("independent-selection-scope")
        compose.onNodeWithText("完成选择").performClick()
        compose.onNodeWithText("批量移动 · 已选 2 条").assertIsDisplayed()
        compose.onNodeWithText("取消待插入").performClick()
        compose.runOnIdle { assertEquals(original, stored); assertEquals(original, draft) }
    }

    @Test fun partialDeleteRequiresConfirmationAndCancelLeavesEveryEntryUntouched() {
        showSheet()
        begin("删除条目")
        select("Alpha")
        compose.onNodeWithText("删除所选").performClick()
        compose.onNodeWithText("将删除 1 条", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(original, draft); assertEquals(original, stored) }
        compose.onNodeWithText("取消删除").performClick()
        compose.runOnIdle { assertEquals(original, stored) }
        compose.onNodeWithText("删除所选").performClick()
        captureLorebookRepairEvidence("delete-confirmation",
            compose.onNode(isDialog() and hasAnyDescendant(hasText("确认删除所选"))).captureToImage().asAndroidBitmap())
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle {
            assertEquals(original.entries.drop(1), stored.entries)
            assertEquals(original.entryGroups, stored.entryGroups)
        }
        compose.onNodeWithTag("lorebook-insertion-safety-area").assertDoesNotExist()
    }

    @LorebookSelectionIntegration
    @Test fun deletingWholeSelectedGroupKeepsEmptyGroupAndSurvivesReopen() {
        showSheet()
        begin("删除条目")
        chooseScope("A")
        compose.onNodeWithText("全选该组").performClick()
        compose.onNodeWithText("批量删除 · 已选 2 条").assertIsDisplayed()
        compose.onNodeWithText("删除所选").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle {
            assertEquals(listOf(anchor, ungrouped), stored.entries)
            assertEquals(original.entryGroups, stored.entryGroups)
        }
        val cancel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.prompt_page_cancel)
        compose.onNodeWithText(cancel).performClick()
        compose.onNodeWithText("Reopen").performClick()
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasTestTag("lorebook-group:A"))
        compose.onNodeWithTag("lorebook-group:A").assertIsDisplayed()
        captureLorebookRepairEvidence("empty-group-after-delete")
    }

    @Test fun ordinaryCardDragAppendsToExistingGroupOnlyOnReleaseThenSavesAndReopens() {
        // Keep source and target simultaneously visible in the real sheet viewport.
        val before = original.copy(entries = listOf(a, anchor, ungrouped))
        draft = before
        stored = before
        showSheet()
        expand("A")
        // The exclusive region is now above the root entries. Bring the actual source rail
        // fully into view; the nearby B header must remain visible in the same viewport.
        val list = compose.onNodeWithTag("lorebook-entry-list")
        list.performScrollToNode(hasContentDescription("拖动排序 Alpha"))
        // In the restored single-view layout B is below the member in A.
        // Keep the source near the viewport top so the following target header stays visible.
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val source = compose.onNodeWithContentDescription("拖动排序 Alpha").fetchSemanticsNode().boundsInRoot
        val inset = viewport.height * 0.1f
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, source.top - viewport.top - inset) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("拖动排序 Alpha").assertIsDisplayed()
        compose.onNodeWithTag("lorebook-group:B").assertIsDisplayed()
        captureLorebookRepairEvidence("ordinary-before-drag")
        dragTo(compose.onNodeWithContentDescription("拖动排序 Alpha"), compose.onNodeWithTag("lorebook-group:B"))
        compose.runOnIdle {
            assertEquals(1, writes)
            assertEquals(listOf(a.copy(group = "B"), anchor, ungrouped), draft.entries)
            assertEquals(before, stored)
        }
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasContentDescription("拖动排序 Alpha"))
        captureLorebookRepairEvidence("ordinary-group-drop")
        val confirm = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.prompt_page_confirm)
        compose.onNodeWithText(confirm).performClick()
        compose.onNodeWithText("Reopen").performClick()
        compose.runOnIdle { assertEquals(draft, stored); assertEquals("B", stored.entries.single { it.id == a.id }.group) }
    }

    @Test fun ordinaryDragCancellationAndEmptyGroupThenUngroupedDropsPreserveEveryOtherField() {
        compose.setContent {
            MaterialTheme {
                LorebookEntryList(draft, null, {}, {}, {}, modifier = Modifier.size(360.dp, 700.dp),
                    header = {}, onEntriesChange = { draft = draft.withLorebookEntries(it); writes++ },
                    onEditEntry = {}, onAddEntry = {}, onGroupSettings = { _, _ -> })
            }
        }
        compose.onNodeWithText("A").performClick()
        val handle = compose.onNodeWithContentDescription("拖动排序 Alpha")
        dragTo(handle, compose.onNodeWithTag("lorebook-group:B"), cancel = true)
        compose.runOnIdle { assertEquals(original, draft); assertEquals(0, writes) }
        dragTo(handle, compose.onNodeWithTag("lorebook-group:Empty"))
        compose.runOnIdle { assertEquals(a.copy(group = "Empty"), draft.entries.single { it.id == a.id }) }
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasContentDescription("拖动排序 Alpha"))
        compose.runOnIdle { writes = 0 }
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasContentDescription("拖动排序 Alpha"))
        // Ordinary entry Loose is the actual target for release out of competition.
        dragTo(handle, compose.onNodeWithContentDescription("拖动排序 Loose"))
        compose.runOnIdle {
            assertEquals(a.copy(group = ""), draft.entries.single { it.id == a.id })
            assertEquals(original.entries.drop(1), draft.entries.filterNot { it.id == a.id })
            assertEquals(original.entryGroups, draft.entryGroups)
        }
    }

    @Test fun heldHandleDoesNotScrollUntilMovedAndPinnedSourceSurvivesLongEdgeScrollAndCancel() {
        val originalLong = Lorebook(entries = List(45) { a.copy(id = Uuid.random(), name = "Long $it", group = "") })
        draft = originalLong
        compose.setContent {
            MaterialTheme {
                LorebookEntryList(draft, null, {}, {}, {}, modifier = Modifier.size(360.dp, 600.dp).testTag("repair-root"),
                    header = {}, onEntriesChange = { draft = draft.copy(entries = it); writes++ },
                    onEditEntry = {}, onAddEntry = {}, onGroupSettings = { _, _ -> })
            }
        }
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasContentDescription("拖动排序 Long 4"))
        val root = compose.onNodeWithTag("repair-root")
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithContentDescription("拖动排序 Long 4").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode().boundsInRoot
        val start = Offset(handle.center.x, handle.bottom - 4f) - rootBounds.topLeft
        fun scroll() = compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = scroll()
        compose.mainClock.autoAdvance = false
        root.performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(750)
        assertEquals(before, scroll(), 0f)
        root.performTouchInput { moveTo(Offset(start.x, viewport.center.y - rootBounds.top)) }
        root.performTouchInput { moveTo(Offset(start.x, viewport.bottom - rootBounds.top - 2f)) }
        compose.mainClock.advanceTimeBy(600)
        val first = scroll()
        compose.mainClock.advanceTimeBy(1200)
        assertTrue("Scrolling must continue after the source passes out of view", scroll() > first)
        val bottom = scroll()
        root.performTouchInput { moveTo(Offset(start.x, viewport.top - rootBounds.top + 2f)) }
        compose.mainClock.advanceTimeBy(350)
        assertTrue(scroll() < bottom)
        root.performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, writes); assertEquals(originalLong, draft) }
    }
}
