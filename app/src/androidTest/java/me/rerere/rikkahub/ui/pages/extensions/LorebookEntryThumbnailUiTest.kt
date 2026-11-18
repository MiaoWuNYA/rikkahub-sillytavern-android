package me.rerere.rikkahub.ui.pages.extensions

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class LorebookEntryThumbnailUiTest {
    @get:Rule val compose = createComposeRule()
    private val entries = listOf("One", "Two", "Three", "Four", "Five", "Six").mapIndexed { index, name ->
        PromptInjection.RegexInjection(name = name, content = "Complete body $name", priority = index, probability = 41)
    }
    private val original = Lorebook(entries = entries)
    private var draft by mutableStateOf(original)
    private var stored by mutableStateOf(original)
    private var batch by mutableStateOf<LorebookBatchPreview?>(LorebookBatchPreview(Uuid.random(), LorebookBatchMode.MOVE, entries.take(3)))
    private var singleCommits = 0
    private fun tile(entry: PromptInjection.RegexInjection) = compose.onNodeWithTag("lorebook-thumbnail-${entry.id}")

    private fun screenshot(name: String) {
        // Semantics can be current before SurfaceFlinger presents the frame, especially with
        // the test clock paused during a held pointer. Let the native capture catch up too.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val dir = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "round6-layout").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun showList() {
        compose.setContent {
            MaterialTheme {
                LorebookEntryList(
                    draft, null, { batch = null }, {}, {},
                    modifier = Modifier.size(360.dp, 560.dp).testTag("thumbnail-editor"), batch = batch,
                    onRemoveBatchEntry = { error("The tile body must never trigger its corner remove button") },
                    onCommitSingle = { id, placement ->
                        singleCommits++
                        draft = applyLorebookBatch(listOf(draft), LorebookBatchRequest(draft, draft,
                            LorebookBatchAction.Move(setOf(id)), placement)).single()
                        batch = batch?.let { b -> b.copy(entries = b.entries.filterNot { it.id == id }).takeIf { it.entries.isNotEmpty() } }
                    },
                    onCommitBatch = { placement, end ->
                        val ids = checkNotNull(batch).entries.map { it.id }.toSet()
                        draft = applyLorebookBatch(listOf(draft), LorebookBatchRequest(draft, draft,
                            LorebookBatchAction.Move(ids), placement, end)).single()
                        batch = null
                    },
                    header = {}, onEntriesChange = {}, onEditEntry = {}, onAddEntry = {}, onGroupSettings = { _, _ -> },
                )
            }
        }
    }

    private fun showMoveSheet() {
        compose.setContent {
            MaterialTheme {
                LorebookEditSheet(draft, {}, {}, {}, stored,
                    onCommitBatch = { request -> applyLorebookBatch(listOf(stored), request).single().also { stored = it } },
                    onEdit = { draft = it })
            }
        }
        compose.onNodeWithText("批量管理条目").performClick()
        compose.onNodeWithText("移动到本书其他位置").performClick()
        entries.take(3).reversed().forEach { entry ->
            compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasContentDescription("选择条目 ${entry.name}"))
            compose.onNodeWithContentDescription("选择条目 ${entry.name}").performClick()
        }
        compose.onNodeWithText("完成选择").performClick()
    }

    private fun dragAfter(handle: SemanticsNodeInteraction, targetName: String, capture: String? = null) {
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasContentDescription("拖动排序 $targetName"))
        val target = compose.onNodeWithContentDescription("拖动排序 $targetName").fetchSemanticsNode().boundsInRoot
        val bounds = handle.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        // Lower-left part of the tile, clearly outside its independent top-right remove button.
        handle.performTouchInput { down(Offset(width * 0.3f, height * 0.7f)) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("lorebook-entry-preview").assertDoesNotExist()
        val liftedBefore = compose.onNodeWithTag("lorebook-lifted-batch").fetchSemanticsNode().boundsInRoot
        handle.performTouchInput { moveTo(Offset(bounds.width * 0.3f, target.center.y + 4f - bounds.top)) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("松手插入").assertIsDisplayed()
        val liftedAfter = compose.onNodeWithTag("lorebook-lifted-batch").fetchSemanticsNode().boundsInRoot
        val expectedDelta = target.center.y + 4f - bounds.top - bounds.height * 0.7f
        assertEquals("The lifted object must follow the actual pointer, for both single and group drags",
            expectedDelta, liftedAfter.top - liftedBefore.top, 1f)
        if (capture != null) screenshot(capture)
        handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun tapShowsCompleteScrollablePreviewOutsideTapClosesWithoutChangingViewportOrSelection() {
        val long = entries[0].copy(name = "A complete long entry title", content = (1..80).joinToString("\n") { "Preview line $it" })
        batch = batch?.copy(entries = listOf(long) + entries.drop(1).take(2))
        showList()
        val area = compose.onNodeWithTag("lorebook-insertion-safety-area").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode().boundsInRoot
        val before = batch
        tile(long).performTouchInput { click(Offset(width * 0.3f, height * 0.7f)) }
        compose.onNodeWithTag("lorebook-entry-preview").assertIsDisplayed()
        compose.onNodeWithText(long.name).assertIsDisplayed()
        screenshot("preview")
        compose.onNodeWithTag("lorebook-entry-preview").performTouchInput { swipeUp() }
        assertEquals(viewport, compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode().boundsInRoot)
        assertEquals(area, compose.onNodeWithTag("lorebook-insertion-safety-area").fetchSemanticsNode().boundsInRoot)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time + action * 30L, action, 4f, metrics.heightPixels * 0.8f, 0)
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lorebook-entry-preview").assertDoesNotExist()
        tile(long).performTouchInput { click() }
        compose.onNodeWithTag("lorebook-entry-preview").assertIsDisplayed()
        compose.onNodeWithText("关闭预览").performClick()
        compose.onNodeWithTag("lorebook-entry-preview").assertDoesNotExist()
        compose.runOnIdle { assertEquals(before, batch); assertEquals(original, draft); assertEquals(0, singleCommits) }
    }

    @Test fun singleDragReturnsToSafetyAndCancellationKeepsEveryEntryInOriginalOrder() {
        showList()
        val handle = tile(entries[1])
        val bounds = handle.fetchSemanticsNode().boundsInRoot
        val other = tile(entries[0]).fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(Offset(width * 0.3f, height * 0.7f)) }
        compose.mainClock.advanceTimeBy(700)
        val liftedStart = compose.onNodeWithTag("lorebook-lifted-batch").fetchSemanticsNode().boundsInRoot
        handle.performTouchInput { moveTo(Offset(bounds.width * 0.3f, viewport.center.y - bounds.top)) }
        compose.mainClock.advanceTimeBy(64)
        assertNotEquals(liftedStart.top, compose.onNodeWithTag("lorebook-lifted-batch").fetchSemanticsNode().boundsInRoot.top)
        assertEquals(other, tile(entries[0]).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("lorebook-entry-preview").assertDoesNotExist()
        screenshot("single-drag")
        handle.performTouchInput { moveTo(Offset(bounds.width * 0.3f, bounds.height * 0.7f)) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("已暂停").assertIsDisplayed()
        handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("lorebook-lifted-batch").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(Offset(width * 0.3f, height * 0.7f)) }
        compose.mainClock.advanceTimeBy(700)
        handle.performTouchInput { moveTo(Offset(-100f, viewport.center.y - bounds.top)); cancel() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, singleCommits); assertEquals(entries.take(3), batch?.entries); assertEquals(original, draft) }
    }

    @Test fun successiveSingleMovesPersistWithOriginalIdsAndRemainingTaskSurvives() {
        showMoveSheet()
        dragAfter(tile(entries[0]), "Four")
        compose.onNodeWithText("批量移动 · 已选 2 条").assertIsDisplayed()
        tile(entries[0]).assertDoesNotExist()
        tile(entries[1]).assertIsDisplayed()
        dragAfter(tile(entries[1]), "One")
        compose.onNodeWithText("批量移动 · 已选 1 条").assertIsDisplayed()
        compose.onNodeWithText("插入最后").performClick()
        compose.runOnIdle {
            assertEquals(listOf(3, 0, 1, 4, 5, 2).map { entries[it] }, stored.entries)
            assertEquals(stored, draft)
        }
        compose.onNodeWithTag("lorebook-insertion-safety-area").assertDoesNotExist()
    }

    @Test fun remainingItemsStillDragTogetherAfterOneSingleInsertion() {
        showList()
        dragAfter(tile(entries[0]), "Four")
        val handle = compose.onNodeWithContentDescription("长按拖动 2 条")
        dragAfter(handle, "One", "group-drag")
        compose.runOnIdle {
            assertNull(batch)
            assertEquals(listOf(3, 0, 1, 2, 4, 5).map { entries[it] }, draft.entries)
            assertEquals(1, singleCommits)
        }
    }

    @Test fun horizontalSwipeNeverOpensPreviewOrStartsSingleOrGroupDrag() {
        batch = batch?.copy(entries = List(16) { PromptInjection.RegexInjection(name = "Wide $it") })
        showList()
        compose.onNodeWithTag("lorebook-batch-thumbnails").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("lorebook-entry-preview").assertDoesNotExist()
        compose.onNodeWithTag("lorebook-lifted-batch").assertDoesNotExist()
        compose.runOnIdle { assertEquals(16, batch?.entries?.size); assertEquals(0, singleCommits); assertEquals(original, draft) }
    }

    @Test fun failedSingleCopyKeepsAllItemsAndSuccessUsesNewIdThenRemainderCanCommitTogether() {
        val source = original.copy(name = "Source", entries = entries.take(3))
        val target = Lorebook(name = "Target", entries = entries.drop(3))
        val state = LorebookCopyState().apply { stage(source.id, source.entries) }
        var books by mutableStateOf(listOf(source, target))
        var targetDraft by mutableStateOf(target)
        var fail = true
        val writer = LorebookBatchWriter { update ->
            books = update(books)
            if (fail) { fail = false; throw IOException("simulated disk failure") }
        }
        compose.setContent {
            val task by state.pending.collectAsState()
            val busy by state.busy.collectAsState()
            MaterialTheme {
                LorebookEditSheet(targetDraft, {}, {}, {}, books[1], copyTask = task, copyBusy = busy,
                    onRemoveCopyEntry = state::remove, onCancelCopy = state::cancel,
                    onCommitBatch = { request ->
                        val action = request.action as LorebookBatchAction.Copy
                        state.commit(action.task, action.ids) { writer.commit(request) }
                    }, onEdit = { targetDraft = it })
            }
        }
        dragAfter(tile(entries[1]), "Four")
        compose.onNodeWithText("将 1 条复制到", substring = true).assertIsDisplayed()
        compose.onNodeWithText("确认复制").performClick()
        compose.onNodeWithText("保存失败", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(source, target), books); assertEquals(source.entries, state.pending.value?.entries) }
        dragAfter(tile(entries[1]), "Four")
        compose.onNodeWithText("确认复制").performClick()
        compose.runOnIdle {
            assertEquals(source, books[0])
            assertEquals(listOf(entries[0], entries[2]), state.pending.value?.entries)
            val copy = books[1].entries.single { it.name == "Two" }
            assertNotEquals(entries[1].id, copy.id)
            assertEquals(entries[1], copy.copy(id = entries[1].id))
        }
        compose.onNodeWithText("插入最后").performClick()
        compose.onNodeWithText("将 2 条复制到", substring = true).assertIsDisplayed()
        compose.onNodeWithText("确认复制").performClick()
        compose.runOnIdle {
            assertNull(state.pending.value)
            assertEquals(source, books[0])
            assertEquals(listOf("Four", "Two", "Five", "Six", "One", "Three"), books[1].entries.map { it.name })
            assertEquals(6, books[1].entries.map { it.id }.distinct().size)
        }
    }
}
