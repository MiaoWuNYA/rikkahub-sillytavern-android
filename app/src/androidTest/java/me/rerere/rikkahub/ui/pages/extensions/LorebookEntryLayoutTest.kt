package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose measurement and pointer integration, in addition to the pure decision unit tests. */
@RunWith(AndroidJUnit4::class)
class LorebookEntryLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val pending = PromptInjection.RegexInjection(name = "Pending", content = "Pending content")
    private var draft by mutableStateOf(Lorebook(entries = List(30) {
        PromptInjection.RegexInjection(name = "Existing $it", content = "Content $it")
    }))
    private var staged by mutableStateOf<PromptInjection.RegexInjection?>(pending)

    private fun showEditor() {
        compose.setContent {
            MaterialTheme {
                LorebookEntryList(
                    book = draft,
                    pendingEntry = staged,
                    onCancelPending = { staged = null },
                    onInsertPending = { target ->
                        draft = draft.copy(entries = insertLorebookEntry(draft.entries, pending, target))
                        staged = null
                    },
                    onUpdatePending = { staged = it },
                    modifier = Modifier.size(width = 360.dp, height = 560.dp).testTag("editor"),
                    header = { Text("Book settings", Modifier.height(200.dp)) },
                    onEntriesChange = { draft = draft.copy(entries = it) },
                    onEditEntry = {},
                    onAddEntry = {},
                    onGroupSettings = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun narrowRailUsesCardHeightAndReleasesTitleWidthInBothModes() {
        var entry by mutableStateOf(pending)
        var collapsed by mutableStateOf(false)
        var edited = 0
        var deleted = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().testTag("card")) {
                    RegexInjectionEntryCard(
                        entry, collapsed,
                        dragHandle = { LorebookDragHandle("Handle") },
                        onEdit = { edited++ }, onDelete = { deleted++ }, onUpdate = { entry = it },
                    )
                }
            }
        }
        repeat(2) {
            val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
            val handle = compose.onNodeWithContentDescription("Handle").fetchSemanticsNode().boundsInRoot
            val delete = compose.onNodeWithContentDescription(context.getString(R.string.prompt_page_delete))
            val edit = compose.onNodeWithContentDescription(context.getString(R.string.prompt_page_edit))
            assertTrue("Compact card height", card.height <= 132f * density)
            val titleBounds = compose.onNodeWithText(entry.name).fetchSemanticsNode().boundsInRoot
            val toggleBounds = compose.onNode(isToggleable()).fetchSemanticsNode().boundsInRoot
            assertTrue(titleBounds.right <= toggleBounds.left)
            assertTrue(edit.fetchSemanticsNode().boundsInRoot.top >= toggleBounds.bottom - 1f)
            assertEquals(36f * density, handle.width, 1f)
            assertTrue(handle.height >= card.height - 8f * density - 1f)
            assertTrue(compose.onNodeWithText(entry.name).fetchSemanticsNode().boundsInRoot.width >= card.width - 124f * density)
            assertEquals(20f * density, compose.onNodeWithTag("lorebook-drag-icon").fetchSemanticsNode().boundsInRoot.width, 1f)
            assertTrue(card.right - compose.onNodeWithTag("lorebook-drag-icon").fetchSemanticsNode().boundsInRoot.right <= 8f * density + 1f)
            assertTrue(card.right - handle.right <= 4f * density + 1f)
            assertTrue(delete.fetchSemanticsNode().boundsInRoot.right <= handle.left)
            assertTrue(edit.fetchSemanticsNode().boundsInRoot.right <= delete.fetchSemanticsNode().boundsInRoot.left)
            compose.onNode(isToggleable()).performClick()
            compose.runOnIdle { assertEquals(it == 1, entry.enabled) }
            edit.performClick()
            delete.performClick()
            compose.runOnIdle { collapsed = true }
        }
        compose.runOnIdle { assertEquals(2, edited); assertEquals(2, deleted) }
    }

    @Test
    fun entryCardWrapsContentInsideBoundedParent() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.height(560.dp).fillMaxWidth().testTag("bounded-parent")) {
                    Box(Modifier.testTag("card")) {
                        RegexInjectionEntryCard(pending, onEdit = {}, onDelete = {})
                    }
                    Text("Below card")
                }
            }
        }
        val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        val parent = compose.onNodeWithTag("bounded-parent").fetchSemanticsNode().boundsInRoot
        assertTrue("Decoration must not consume the parent's remaining height", card.height < parent.height / 2f)
        compose.onNodeWithText("Below card").assertIsDisplayed()
    }

    @Test
    fun safetyWrapsContentLeavesListVisibleAndStaysFixedWhileListScrolls() {
        showEditor()
        val editor = compose.onNodeWithTag("editor").fetchSemanticsNode().boundsInRoot
        val safety = compose.onNodeWithTag("lorebook-insertion-safety-area").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode().boundsInRoot
        assertTrue(safety.height <= editor.height * 0.56f)
        assertTrue(list.height > editor.height * 0.40f)
        compose.onNodeWithText("Existing 0").assertIsDisplayed()
        compose.onNodeWithText("Book settings").assertDoesNotExist()
        compose.onNodeWithText("插入最前").assertIsDisplayed()
        compose.onNodeWithText("插入最后").assertIsDisplayed()
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasText("Existing 29"))
        compose.onNodeWithText("Existing 29").assertIsDisplayed()
        assertEquals(safety, compose.onNodeWithTag("lorebook-insertion-safety-area").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun shortcutsAndCancelWorkWithoutDragging() {
        val original = draft.entries
        showEditor()
        compose.onNodeWithText("取消待插入").performClick()
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasText("Book settings"))
        compose.onNodeWithText("Book settings").assertIsDisplayed()
        compose.runOnIdle { assertEquals(original, draft.entries); staged = pending }
        compose.onNodeWithText("插入最前").performClick()
        compose.runOnIdle {
            assertEquals(pending, draft.entries.first())
            assertEquals(original, draft.entries.drop(1))
            assertEquals(null, staged)
            draft = draft.copy(entries = original)
            staged = pending
        }
        compose.onNodeWithText("插入最后").performClick()
        compose.runOnIdle {
            assertEquals(pending, draft.entries.last())
            assertEquals(original, draft.entries.dropLast(1))
            assertEquals(null, staged)
        }
        compose.onNodeWithTag("lorebook-insertion-safety-area").assertDoesNotExist()
    }

    @Test
    fun realPointerPausesInSafetyResumesInListAndReleaseInSafetyDoesNotInsert() {
        showEditor()
        val handle = compose.onNodeWithContentDescription("拖动插入 Pending")
        val handleBounds = handle.fetchSemanticsNode().boundsInRoot
        val safety = compose.onNodeWithTag("lorebook-insertion-safety-area").fetchSemanticsNode().boundsInRoot
        val listNode = compose.onNodeWithTag("lorebook-entry-list")
        val list = listNode.fetchSemanticsNode().boundsInRoot
        fun scrollPosition() = listNode.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val initialScroll = scrollPosition()
        compose.mainClock.autoAdvance = false
        // Cross touch slop horizontally, entirely inside the safety boundary.
        handle.performTouchInput { down(center); moveBy(Offset(-50f, 0f)) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("已暂停").assertIsDisplayed()
        assertEquals(initialScroll, scrollPosition(), 0f)

        val inList = Offset(handleBounds.width / 2f, list.top + list.height * 0.5f - handleBounds.top)
        handle.performTouchInput { moveTo(inList) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("松手插入").assertIsDisplayed()

        val inSafety = Offset(handleBounds.width / 2f, safety.center.y - handleBounds.top)
        handle.performTouchInput { moveTo(inSafety) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("已暂停").assertIsDisplayed()
        val pausedScroll = scrollPosition()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(pausedScroll, scrollPosition(), 0f)

        handle.performTouchInput { moveTo(inList) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("松手插入").assertIsDisplayed()
        // No frame/recomposition between returning and UP: final coordinates must still win.
        handle.performTouchInput { moveTo(inSafety); up() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(pending, staged); assertEquals(30, draft.entries.size) }
    }

    @Test
    fun edgeScrollRunsOnlyInListAndStopsAfterReturningToSafety() {
        showEditor()
        val handle = compose.onNodeWithContentDescription("拖动插入 Pending")
        val bounds = handle.fetchSemanticsNode().boundsInRoot
        val safety = compose.onNodeWithTag("lorebook-insertion-safety-area").fetchSemanticsNode().boundsInRoot
        val listNode = compose.onNodeWithTag("lorebook-entry-list")
        val list = listNode.fetchSemanticsNode().boundsInRoot
        fun scrollPosition() = listNode.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(center); moveBy(Offset(-50f, 0f)) }
        compose.mainClock.advanceTimeBy(100)
        val before = scrollPosition()
        handle.performTouchInput { moveTo(Offset(bounds.width / 2f, list.bottom - 2f - bounds.top)) }
        compose.mainClock.advanceTimeBy(500)
        assertTrue(scrollPosition() > before)
        handle.performTouchInput { moveTo(Offset(bounds.width / 2f, safety.center.y - bounds.top)) }
        compose.mainClock.advanceTimeBy(32)
        val paused = scrollPosition()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(paused, scrollPosition(), 0f)
        handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(30, draft.entries.size); assertEquals(pending, staged) }
    }
}
