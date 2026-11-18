package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class LorebookEntryBatchUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thumbnailUsesWholeGraphemeIncludingEmojiAndCombiningMarks() {
        assertEquals("?", lorebookThumbnailCharacter("  "))
        assertEquals("中", lorebookThumbnailCharacter("中文"))
        assertEquals("😀", lorebookThumbnailCharacter("😀 test"))
        assertEquals("a\u0301", lorebookThumbnailCharacter("a\u0301bc"))
        assertEquals("👨‍👩‍👧‍👦", lorebookThumbnailCharacter("👨‍👩‍👧‍👦 family"))
    }

    @LorebookSelectionIntegration
    @Test fun selectInReverseOrderCancelAndMoveWithoutChangingDataUntilCommit() {
        val original = Lorebook(entries = listOf("One", "Two", "Three").map {
            PromptInjection.RegexInjection(name = it, content = it)
        })
        var draft by mutableStateOf(original)
        var stored by mutableStateOf(original)
        compose.setContent {
            MaterialTheme {
                LorebookEditSheet(
                    book = draft, onDismiss = {}, onConfirm = {}, onSave = {},
                    persistedBook = stored,
                    onCommitBatch = { request ->
                        applyLorebookBatch(listOf(stored), request).single().also { stored = it }
                    },
                    onEdit = { draft = it },
                )
            }
        }
        fun begin() {
            compose.onNodeWithText("批量管理条目").performClick()
            compose.onNodeWithText("移动到本书其他位置").performClick()
        }
        fun select(name: String) {
            compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasContentDescription("选择条目 $name"))
            compose.onNodeWithContentDescription("选择条目 $name").performClick()
        }
        begin()
        select("Two")
        select("One")
        compose.onNodeWithText("完成选择").performClick()
        compose.runOnIdle { assertEquals(original, stored); assertEquals(original, draft) }
        compose.onNodeWithText("取消待插入").performClick()
        compose.runOnIdle { assertEquals(original, stored); assertEquals(original, draft) }
        begin()
        compose.onNodeWithText("全选该组").performClick()
        compose.onNodeWithText("取消全选").performClick()
        select("Two")
        select("One")
        compose.onNodeWithText("完成选择").performClick()
        compose.onNodeWithText("插入最后").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Three", "One", "Two"), stored.entries.map { it.name })
            original.entries.forEach { before -> assertEquals(before, stored.entries.single { it.id == before.id }) }
        }
        compose.onNodeWithTag("lorebook-insertion-safety-area").assertDoesNotExist()
    }

    @Test fun horizontalThumbnailsDoNotStartGroupDragAndLongPressHandleCommitsTheWholeGroup() {
        val original = Lorebook(entries = List(20) { PromptInjection.RegexInjection(name = "Item $it", content = "body") })
        val selected = original.entries.take(8)
        var batch by mutableStateOf<LorebookBatchPreview?>(LorebookBatchPreview(Uuid.random(), LorebookBatchMode.MOVE, selected))
        var draft by mutableStateOf(original)
        var committed = false
        compose.setContent {
            MaterialTheme {
                LorebookEntryList(
                    book = draft, pendingEntry = null, onCancelPending = { batch = null },
                    onInsertPending = {}, onUpdatePending = {},
                    batch = batch,
                    onRemoveBatchEntry = { id -> batch = batch?.let { it.copy(entries = it.entries.filterNot { e -> e.id == id }) } },
                    onCommitBatch = { target, edge ->
                        val action = LorebookBatchAction.Move(selected.map { it.id }.toSet())
                        draft = applyLorebookBatch(listOf(draft), LorebookBatchRequest(draft, draft, action, target, edge)).single()
                        committed = true
                        batch = null
                    },
                    modifier = Modifier.size(360.dp, 560.dp), header = {}, onEntriesChange = {},
                    onEditEntry = {}, onAddEntry = {}, onGroupSettings = { _, _ -> },
                )
            }
        }
        val area = compose.onNodeWithTag("lorebook-insertion-safety-area").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("lorebook-batch-thumbnails").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertFalse(committed); assertEquals(original, draft) }
        assertEquals(area, compose.onNodeWithTag("lorebook-insertion-safety-area").fetchSemanticsNode().boundsInRoot)
        val handle = compose.onNodeWithContentDescription("长按拖动 8 条")
        val bounds = handle.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("已暂停").assertIsDisplayed()
        handle.performTouchInput { moveTo(Offset(bounds.width / 2f, viewport.center.y - bounds.top)) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("松手插入").assertIsDisplayed()
        val listNode = compose.onNodeWithTag("lorebook-entry-list")
        fun scrollPosition() = listNode.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val beforeEdge = scrollPosition()
        handle.performTouchInput { moveTo(Offset(bounds.width / 2f, viewport.bottom - bounds.top - 2f)) }
        compose.mainClock.advanceTimeBy(250)
        assertTrue(scrollPosition() > beforeEdge)
        handle.performTouchInput { moveTo(Offset(bounds.width / 2f, area.center.y - bounds.top)) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("已暂停").assertIsDisplayed()
        val paused = scrollPosition()
        compose.mainClock.advanceTimeBy(250)
        assertEquals(paused, scrollPosition(), 0f)
        compose.runOnIdle { assertFalse(committed); assertEquals(original, draft) }
        handle.performTouchInput { moveTo(Offset(bounds.width / 2f, viewport.center.y - bounds.top)) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("松手插入").assertIsDisplayed()
        handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle {
            assertTrue(committed)
            assertEquals(original.entries.map { it.id }.toSet(), draft.entries.map { it.id }.toSet())
            assertEquals(selected, draft.entries.filter { it.id in selected.map { e -> e.id } })
        }
    }
}
