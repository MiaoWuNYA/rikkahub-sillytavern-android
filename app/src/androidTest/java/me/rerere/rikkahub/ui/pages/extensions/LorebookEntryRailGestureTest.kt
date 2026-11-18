package me.rerere.rikkahub.ui.pages.extensions

import android.graphics.Bitmap
import java.io.File

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class LorebookEntryRailGestureTest {
    @get:Rule val compose = createComposeRule()
    private val original = Lorebook(entries = List(24) {
        PromptInjection.RegexInjection(name = "Entry $it", content = "Preview $it", priority = it, probability = 67)
    })
    private var draft by mutableStateOf(original)
    private var reorders = 0
    private val pxPerDp get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    private fun showList() {
        compose.setContent {
            MaterialTheme {
                LorebookEntryList(
                    book = draft, pendingEntry = null, onCancelPending = {}, onInsertPending = {},
                    onUpdatePending = {},
                    modifier = Modifier.size(360.dp, 560.dp).testTag("rail-editor"), header = {},
                    onEntriesChange = { draft = draft.copy(entries = it); reorders++ },
                    onEditEntry = {}, onAddEntry = {}, onGroupSettings = { _, _ -> },
                )
            }
        }
    }

    private fun showSecondRail() = compose.onNodeWithTag("lorebook-entry-list")
        .performScrollToNode(hasContentDescription("拖动排序 Entry 1"))

    private fun scrollPosition() = compose.onNodeWithTag("lorebook-entry-list")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun ordinarySwipesAndHeldTouchesJustOutsideRailNeverReorder() {
        showList()
        val root = compose.onNodeWithTag("rail-editor")
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        showSecondRail()
        val preview = compose.onNodeWithText("Preview 1").fetchSemanticsNode().boundsInRoot
        val before = scrollPosition()
        root.performTouchInput {
            val start = preview.center - rootBounds.topLeft
            swipe(start, start - Offset(0f, 100f * pxPerDp), 200)
        }
        compose.runOnIdle { assertEquals(0, reorders); assertEquals(original, draft) }
        assertTrue(scrollPosition() > before)

        showSecondRail()
        val rail = compose.onNodeWithContentDescription("拖动排序 Entry 1").fetchSemanticsNode().boundsInRoot
        val nearLeft = Offset(rail.left - 2f * pxPerDp, rail.center.y) - rootBounds.topLeft
        val beforeHeld = scrollPosition()
        compose.mainClock.autoAdvance = false
        root.performTouchInput { down(nearLeft) }
        compose.mainClock.advanceTimeBy(700)
        root.performTouchInput { moveTo(nearLeft - Offset(0f, 100f * pxPerDp)); up() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(0, reorders); assertEquals(original, draft) }
        assertTrue("No transparent handle expansion into the content's scrolling area", scrollPosition() > beforeHeld)
    }

    @Test fun railNeedsLongPressWhileQuickSwipeStillScrolls() {
        showList()
        showSecondRail()
        val handle = compose.onNodeWithContentDescription("拖动排序 Entry 1")
        val before = scrollPosition()
        handle.performTouchInput { swipe(center, center - Offset(0f, 100f * pxPerDp), 200) }
        compose.runOnIdle { assertEquals(0, reorders); assertEquals(original, draft) }
        assertTrue(scrollPosition() > before)

        showSecondRail()
        val bounds = handle.fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithContentDescription("拖动排序 Entry 2").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertEquals(0, reorders) }
        handle.performTouchInput { moveTo(Offset(bounds.width / 2f, third.bottom - bounds.top - 8f * pxPerDp)) }
        compose.mainClock.advanceTimeBy(150)
        handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle {
            assertTrue("A deliberate held rail drag must reorder", reorders > 0)
            assertEquals(original.entries.map { it.id }.toSet(), draft.entries.map { it.id }.toSet())
            original.entries.forEach { entry -> assertEquals(entry, draft.entries.single { it.id == entry.id }) }
        }
    }

    @Test fun threeItemBatchSitsImmediatelyBelowTitleAndLeavesSeveralEntriesVisible() {
        val selected = original.entries.take(3)
        val task = LorebookCopyTask(Uuid.random(), Uuid.random(), selected)
        val title = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.prompt_page_edit_lorebook)
        compose.setContent {
            MaterialTheme {
                LorebookEditSheet(
                    book = original, persistedBook = original, copyTask = task,
                    onDismiss = {}, onConfirm = {}, onSave = {}, onEdit = {},
                    onCommitBatch = { error("Measurement must not commit data") },
                )
            }
        }
        val titleBounds = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val safety = compose.onNodeWithTag("lorebook-insertion-safety-area").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode().boundsInRoot
        val folder = File(checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "round5-layout")
        folder.mkdirs()
        File(folder, "metrics.txt").writeText(
            "safetyHeightDp=${safety.height / pxPerDp}\n" +
                "titleGapDp=${(safety.top - titleBounds.bottom) / pxPerDp}\n" +
                "listHeightDp=${list.height / pxPerDp}\n",
        )
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(folder, "editor.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // SurfaceFlinger may still show a blank frame although Compose measurement is ready.
        // Capture the measured sheet directly as a second, deterministic visual artifact.
        val content = compose.onNodeWithTag("lorebook-editor-sheet").captureToImage().asAndroidBitmap()
        File(folder, "editor-content.png").outputStream().use { content.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("Compact batch height: ${safety.height / pxPerDp}dp", safety.height / pxPerDp in 120f..150.5f)
        assertTrue("No disabled-entry or warning placeholders above the safety area", safety.top - titleBounds.bottom <= 12f * pxPerDp)
        assertTrue("List viewport: ${list.height / pxPerDp}dp", list.height > safety.height * 2f)
        compose.onNodeWithText("批量管理条目").assertDoesNotExist()
        compose.onNodeWithText("请先完成或取消当前待插入任务，再开始新任务").assertDoesNotExist()
        selected.forEach { entry ->
            val square = compose.onNodeWithTag("lorebook-thumbnail-${entry.id}").fetchSemanticsNode().boundsInRoot
            assertEquals(48f * pxPerDp, square.width, 1f)
            assertEquals(square.width, square.height, 0f)
        }
        compose.onNodeWithText("Entry 1").assertIsDisplayed()
        compose.onNodeWithText("插入最前").assertIsDisplayed()
        compose.onNodeWithText("插入最后").assertIsDisplayed()
        compose.onNodeWithText("取消待插入").assertIsDisplayed()
    }
}
