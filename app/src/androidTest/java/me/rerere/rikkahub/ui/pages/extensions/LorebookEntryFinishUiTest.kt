package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryFinishUiTest {
    @get:Rule val compose = createComposeRule()
    private val a = PromptInjection.RegexInjection(name = "Alpha", group = "A", content = "body", priority = 50, injectDepth = 1)
    private val b = a.copy(id = Uuid.random(), name = "Beta", priority = 80, injectDepth = 3)
    private val original = Lorebook(entries = listOf(a, b), entryGroups = listOf("A", "B", "Empty"))
    private var draft by mutableStateOf(original)
    private fun list() {
        compose.setContent { MaterialTheme { LorebookEntryList(draft, null, {}, {}, {}, Modifier.size(360.dp, 600.dp).testTag("finish-root"),
            header = {}, onEntriesChange = { draft = draft.withLorebookEntries(it) }, onGroupsChange = { draft = it },
            onEditEntry = {}, onAddEntry = {}, onGroupSettings = { _, _ -> }) } }
    }
    private fun dragGroup(source: String, target: String, after: Boolean, cancel: Boolean = false) {
        val handle = compose.onNodeWithContentDescription("拖动分组 $source")
        val from = handle.fetchSemanticsNode().boundsInRoot
        val to = compose.onNodeWithTag("lorebook-group:$target").fetchSemanticsNode().boundsInRoot
        val before = draft
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        handle.performTouchInput { moveTo(Offset(from.width / 2, (if (after) to.bottom - 2 else to.top + 2) - from.top)) }
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle { assertEquals(before, draft) }
        if (cancel) handle.performTouchInput { cancel() } else handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }
    @Test fun groupHeadersMoveBothWaysIncludingEmptyGroupWithoutChangingEntries() {
        list()
        dragGroup("A", "Empty", true)
        compose.runOnIdle { assertEquals(listOf("B", "Empty", "A"), draft.entryGroups); assertEquals(original.entries, draft.entries) }
        dragGroup("A", "B", false)
        compose.runOnIdle { assertEquals(original, draft) }
        captureLorebookRepairEvidence("finish-group-order")
    }
    @Test fun groupDragCancellationPreservesOrderAndShortTapStillExpands() {
        list()
        dragGroup("A", "Empty", true, cancel = true)
        compose.onNodeWithContentDescription("拖动分组 A").performClick()
        compose.onNodeWithContentDescription("拖动排序 Alpha").assertIsDisplayed()
        compose.runOnIdle { assertEquals(original, draft) }
    }
    @Test fun groupDragAutoScrollsAndCancelsWithoutMutation() {
        draft = original.copy(entries = emptyList(), entryGroups = List(40) { "Group $it" })
        val before = draft
        list()
        val root = compose.onNodeWithTag("finish-root")
        val bounds = root.fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithContentDescription("拖动分组 Group 0").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        root.performTouchInput { down(handle.center - bounds.topLeft) }
        compose.mainClock.advanceTimeBy(700)
        root.performTouchInput { moveTo(Offset(handle.center.x - bounds.left, viewport.bottom - bounds.top - 2)) }
        compose.mainClock.advanceTimeBy(1200)
        assertTrue(compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        root.performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(before, draft) }
    }
    @Test fun bulkPriorityOnlyPreservesMixedDepthAndCancelIsReadOnly() {
        var applied: LorebookSettingsPatch? = null
        var show by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (show) LorebookBulkSettingsDialog(listOf(a, b), { show = false }, { applied = it }) } }
        compose.onNodeWithText("应用到 2 个条目").assertIsNotEnabled()
        compose.onNodeWithTag("bulk-select-PRIORITY").performClick()
        compose.onNodeWithTag("bulk-edit-PRIORITY").performClick()
        compose.onNodeWithTag("bulk-priority").performTextReplacement("90")
        compose.onNodeWithText("应用到 2 个条目").performClick()
        compose.runOnIdle { assertEquals(LorebookSettingsPatch(priority = 90), applied) }
        captureLorebookRepairEvidence("finish-bulk-settings", compose.onNode(isDialog()).captureToImage().asAndroidBitmap())
        compose.onNodeWithText("取消设置").performClick()
        compose.runOnIdle { assertFalse(show); assertEquals(1, a.injectDepth); assertEquals(3, b.injectDepth) }
    }
    @Test fun copyExplanationIsCollapsedWithoutChangingSelectionScope() {
        compose.setContent { MaterialTheme { LorebookSelectionControls(listOf(a, b), emptySet(), LorebookBatchMode.COPY, {}, {}, {}) } }
        compose.onNodeWithText("全选范围：未分类条目").assertIsDisplayed()
        compose.onNodeWithText("完成后返回世界书列表。", substring = true).assertDoesNotExist()
        compose.onNodeWithText("ⓘ 复制说明").performClick()
        compose.onNodeWithText("完成后返回世界书列表。", substring = true).assertIsDisplayed()
    }
}
