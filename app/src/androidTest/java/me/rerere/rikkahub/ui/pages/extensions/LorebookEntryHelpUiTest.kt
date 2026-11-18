package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryHelpUiTest {
    @get:Rule val compose = createComposeRule()
    private val entry = PromptInjection.RegexInjection(name = "Pending", content = "Complete content")
    private val book = Lorebook(entries = List(6) { entry.copy(id = Uuid.random(), name = "Entry $it") })

    private fun show(mode: LorebookBatchMode?) {
        compose.setContent {
            MaterialTheme {
                LorebookEntryList(book, if (mode == null) entry else null, {}, {}, {},
                    modifier = Modifier.size(360.dp, 560.dp),
                    batch = mode?.let { LorebookBatchPreview(Uuid.random(), it, listOf(entry)) },
                    header = {}, onEntriesChange = {}, onEditEntry = {}, onAddEntry = {}, onGroupSettings = { _, _ -> })
            }
        }
    }

    @Test fun copyHelpDefaultsClosedAndTogglesWithoutChangingViewport() {
        show(LorebookBatchMode.COPY)
        val viewport = compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("lorebook-insertion-help").assertDoesNotExist()
        compose.onNodeWithContentDescription("插入操作说明").performClick()
        compose.onNodeWithText("复制不会删除来源条目", substring = true).assertIsDisplayed()
        compose.onNodeWithText("短按方块", substring = true).assertIsDisplayed()
        captureLorebookEditorEvidence("modules-1-3-help")
        assertEquals(viewport, compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("插入操作说明").performClick()
        compose.onNodeWithTag("lorebook-insertion-help").assertDoesNotExist()
        assertEquals(viewport, compose.onNodeWithTag("lorebook-entry-list").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun newEntryHelpHasNoBatchInstructionsAndPreviewRemainsFullCard() {
        show(null)
        compose.onAllNodesWithText("Complete content").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("lorebook-insertion-help").assertDoesNotExist()
        captureLorebookEditorEvidence("modules-1-3-new-card")
        compose.onNodeWithContentDescription("插入操作说明").performClick()
        compose.onNodeWithText("拿起新增条目", substring = true).assertIsDisplayed()
        compose.onNodeWithText("长按方块", substring = true).assertDoesNotExist()
        compose.onNodeWithText("关闭说明").performClick()
    }
}
