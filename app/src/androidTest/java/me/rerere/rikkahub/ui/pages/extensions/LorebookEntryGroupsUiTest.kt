package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryGroupsUiTest {
    @get:Rule val compose = createComposeRule()
    private val alpha = PromptInjection.RegexInjection(name = "Alpha", group = "A", content = "full alpha", priority = 71)
    private val beta = alpha.copy(id = Uuid.random(), name = "Beta", group = "B")
    private val anchor = alpha.copy(id = Uuid.random(), name = "Anchor")
    private val original = Lorebook(entries = listOf(alpha, beta, anchor), entryGroups = listOf("A", "B", "Empty"))
    private var draft by mutableStateOf(original)
    private var stored by mutableStateOf(original)
    private var sheetVisible by mutableStateOf(true)

    @Test fun whitespaceGroupEntriesStayVisibleAndGroupFrontUsesTheirFirstSlot() {
        val spaced = alpha.copy(group = "  ")
        val plain = beta.copy(group = "")
        draft = original.copy(entries = listOf(spaced, plain, anchor))
        val fresh = alpha.copy(id = Uuid.random(), name = "Fresh", group = "")
        var pending by mutableStateOf<PromptInjection.RegexInjection?>(fresh)
        compose.setContent {
            MaterialTheme {
                LorebookEntryList(draft, pending, {}, { placement ->
                    draft = draft.withLorebookEntries(insertLorebookEntry(draft.entries, fresh, placement)); pending = null
                }, {}, modifier = Modifier.size(360.dp, 650.dp),
                    header = {}, onEntriesChange = {}, onEditEntry = {}, onAddEntry = {}, onGroupSettings = { _, _ -> })
            }
        }
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasText("Alpha"))
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("插入最前").performClick()
        compose.runOnIdle {
            assertEquals(listOf(fresh, spaced, plain, anchor), draft.entries)
            assertEquals("  ", draft.entries.single { it.id == spaced.id }.group)
        }
    }

    private fun chooseGroup(group: String) {
        compose.onNodeWithTag("lorebook-target-group").performClick()
        compose.onNode(hasText(if (group.isBlank()) "分类：未分类" else "互斥组：$group") and hasAnyAncestor(isPopup())).performClick()
    }

    private fun move(handle: SemanticsNodeInteraction, target: SemanticsNodeInteraction, held: Boolean) {
        // A shelf handle stays fixed. Center a measured destination before dragging so this
        // placement test cannot accidentally become an edge-autoscroll test after group expansion.
        val list = compose.onNodeWithTag("lorebook-entry-list")
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val initial = target.fetchSemanticsNode().boundsInRoot
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, initial.center.y - viewport.center.y) }
        compose.waitForIdle()
        val bounds = handle.fetchSemanticsNode().boundsInRoot
        val destination = target.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(Offset(width * 0.3f, height * 0.7f)) }
        if (held) compose.mainClock.advanceTimeBy(700)
        handle.performTouchInput { moveTo(Offset(bounds.width * 0.3f, destination.center.y - bounds.top + 2f)) }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("松手插入").assertIsDisplayed()
        if (!held) {
            val safety = compose.onNodeWithTag("lorebook-insertion-safety-area").fetchSemanticsNode().boundsInRoot
            val lifted = compose.onNodeWithTag("lorebook-lifted-batch").fetchSemanticsNode().boundsInRoot
            assertTrue("The new card must follow the first drag move out of safety", lifted.bottom > safety.bottom)
        }
        compose.mainClock.advanceTimeByFrame()
        captureLorebookEditorEvidence(if (held) "groups-batch-drag" else "groups-new-drag",
            compose.onNodeWithTag("groups-editor-root").captureToImage().asAndroidBitmap())
        handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun showSheet() {
        compose.setContent {
            MaterialTheme {
                if (!sheetVisible) androidx.compose.material3.TextButton(onClick = { draft = stored; sheetVisible = true }) {
                    androidx.compose.material3.Text("重开世界书")
                } else LorebookEditSheet(draft, {}, { stored = draft; sheetVisible = false }, { stored = it }, stored,
                    onCommitBatch = { request -> applyLorebookBatch(listOf(stored), request).single().also { stored = it } },
                    onEdit = { draft = it })
            }
        }
    }

    @Test fun createEmptyGroupWithoutEntryAndSaveItInTheBook() {
        showSheet()
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasText("管理互斥组"))
        compose.onNodeWithText("管理互斥组").performClick()
        compose.onNodeWithTag("lorebook-group-name").performTextInput("Created")
        compose.onNodeWithText("创建互斥组").performClick()
        compose.onNode(hasText("Created") and hasSetTextAction()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成互斥组管理").performClick()
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasTestTag("lorebook-group:Created"))
        compose.onNodeWithTag("lorebook-group:Created").assertIsDisplayed()
        captureLorebookEditorEvidence("groups-created-empty")
        compose.runOnIdle { assertEquals(original.entries, draft.entries); assertTrue("Created" in draft.entryGroups) }
        // Save through the existing sheet confirmation; no dummy entry is introduced.
        val label = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getString(me.rerere.rikkahub.R.string.prompt_page_confirm)
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle { assertEquals(draft, stored); assertTrue("Created" in stored.entryGroups) }
        compose.onNodeWithText("重开世界书").performClick()
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasTestTag("lorebook-group:Created"))
        compose.onNodeWithTag("lorebook-group:Created").assertIsDisplayed()
    }

    @Test fun newEntryCanDragIntoAnEmptyGroupHeaderWithOriginalId() {
        val fresh = alpha.copy(id = Uuid.random(), name = "New", group = "")
        var pending by mutableStateOf<PromptInjection.RegexInjection?>(fresh)
        compose.setContent {
            MaterialTheme {
                LorebookEntryList(draft, pending, { pending = null }, { placement ->
                    draft = draft.withLorebookEntries(insertLorebookEntry(draft.entries, fresh, placement)); pending = null
                }, {}, modifier = Modifier.size(360.dp, 600.dp).testTag("groups-editor-root"),
                    header = {}, onEntriesChange = {}, onEditEntry = {}, onAddEntry = {}, onGroupSettings = { _, _ -> })
            }
        }
        move(compose.onNodeWithContentDescription("拖动插入 New"), compose.onNodeWithTag("lorebook-group:Empty"), false)
        compose.runOnIdle {
            assertNull(pending)
            assertEquals(fresh.copy(group = "Empty"), draft.entries.single { it.id == fresh.id })
            assertEquals(original.entries, draft.entries.filterNot { it.id == fresh.id })
        }
    }

    @LorebookSelectionIntegration
    @Test fun scopedSelectionMovesToChosenGroupOnlyAfterConfirmationAndCanReturnToUngrouped() {
        showSheet()
        fun select() {
            compose.onNodeWithText("批量管理条目").performClick()
            compose.onNodeWithText("移动到本书其他位置").performClick()
            compose.onNode(hasText("A") and hasClickAction()).performClick()
            for (name in listOf("Alpha", "Anchor")) {
                compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasContentDescription("选择条目 $name"))
                compose.onNodeWithContentDescription("选择条目 $name").performClick()
            }
        }
        select()
        chooseGroup("Empty")
        compose.onNodeWithText("移入互斥组").performClick()
        compose.onNodeWithText("将 2 条移动到「Empty」", substring = true).assertIsDisplayed()
        compose.onNodeWithText("取消移动").performClick()
        compose.runOnIdle { assertEquals(original, stored) }
        compose.onNodeWithText("移入互斥组").performClick()
        compose.onNodeWithText("确认移动").performClick()
        compose.runOnIdle {
            assertEquals(listOf(alpha.copy(group = "Empty"), beta, anchor.copy(group = "Empty")), stored.entries)
            assertEquals(listOf("A", "B", "Empty"), stored.entryGroups)
        }
        compose.onNodeWithText("批量管理条目").performClick()
        compose.onNodeWithText("移动到本书其他位置").performClick()
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasText("Empty"))
        compose.onNodeWithText("Empty").performClick()
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasContentDescription("选择条目 Alpha"))
        compose.onNodeWithContentDescription("选择条目 Alpha").performClick()
        chooseGroup("")
        compose.onNodeWithText("移至目标末尾").performClick()
        compose.onNodeWithText("确认移动").performClick()
        compose.runOnIdle { assertEquals(alpha.copy(group = ""), stored.entries.single { it.id == alpha.id }) }
    }

    @Test fun singleAndRemainingBatchCanDropIntoDifferentGroupsWithoutChangingOtherFields() {
        var batch by mutableStateOf<LorebookBatchPreview?>(LorebookBatchPreview(Uuid.random(), LorebookBatchMode.MOVE, listOf(alpha, beta)))
        fun commit(ids: Set<Uuid>, placement: LorebookEntryPlacement?, end: Boolean?) {
            draft = applyLorebookBatch(listOf(draft), LorebookBatchRequest(draft, draft, LorebookBatchAction.Move(ids), placement, end)).single()
            batch = batch?.let { b -> b.copy(entries = b.entries.filterNot { it.id in ids }).takeIf { it.entries.isNotEmpty() } }
        }
        compose.setContent {
            MaterialTheme {
                LorebookEntryList(draft, null, { batch = null }, {}, {}, batch = batch,
                    modifier = Modifier.size(360.dp, 600.dp).testTag("groups-editor-root"),
                    onCommitSingle = { id, placement -> commit(setOf(id), placement, null) },
                    onCommitBatch = { placement, end -> commit(checkNotNull(batch).entries.map { it.id }.toSet(), placement, end) },
                    header = {}, onEntriesChange = {}, onEditEntry = {}, onAddEntry = {}, onGroupSettings = { _, _ -> })
            }
        }
        // Root entries are classification targets. Choose the explicit upper exclusive header
        // when the purpose of this test is to change competition membership.
        move(compose.onNodeWithTag("lorebook-thumbnail-${beta.id}"), compose.onNodeWithTag("lorebook-group:A"), true)
        compose.runOnIdle { assertEquals(listOf(alpha), batch?.entries); assertEquals(beta.copy(group = "A"), draft.entries.single { it.id == beta.id }) }
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasTestTag("lorebook-group:Empty"))
        move(compose.onNodeWithContentDescription("长按拖动 1 条"), compose.onNodeWithTag("lorebook-group:Empty"), true)
        compose.runOnIdle {
            assertNull(batch)
            assertEquals(alpha.copy(group = "Empty"), draft.entries.single { it.id == alpha.id })
            assertEquals(anchor, draft.entries.single { it.id == anchor.id })
        }
    }

    @Test fun crossBookCopyToEmptyGroupPreservesSourceAndCreatesIndependentIds() {
        val source = original
        val target = Lorebook(entryGroups = listOf("Destination"))
        val copies = LorebookCopyState().apply { stage(source.id, listOf(alpha, beta)) }
        var books by mutableStateOf(listOf(source, target))
        var targetDraft by mutableStateOf(target)
        val writer = LorebookBatchWriter { books = it(books) }
        compose.setContent {
            val task by copies.pending.collectAsState()
            val busy by copies.busy.collectAsState()
            MaterialTheme {
                LorebookEditSheet(targetDraft, {}, {}, {}, books[1], copyTask = task, copyBusy = busy,
                    onCommitBatch = { request ->
                        val action = request.action as LorebookBatchAction.Copy
                        copies.commit(action.task, action.ids) { writer.commit(request) }
                    }, onEdit = { targetDraft = it })
            }
        }
        chooseGroup("Destination")
        compose.onNodeWithText("移入互斥组").performClick()
        compose.onNodeWithText("确认跨书复制").assertIsDisplayed()
        compose.onNodeWithText("「Destination」组内最后", substring = true).assertIsDisplayed()
        compose.onNodeWithText("目标为「Destination」分组", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(source, target), books) }
        compose.onNodeWithText("确认复制").performClick()
        compose.runOnIdle {
            assertNull(copies.pending.value)
            assertEquals(source, books[0])
            assertEquals(listOf(alpha, beta).map { it.copy(group = "Destination") }, books[1].entries.zip(listOf(alpha, beta)).map { (a, b) -> a.copy(id = b.id) })
            assertTrue(books[1].entries.none { copy -> source.entries.any { it.id == copy.id } })
        }
    }
}
