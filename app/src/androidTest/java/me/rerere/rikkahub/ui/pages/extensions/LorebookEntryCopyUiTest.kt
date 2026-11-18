package me.rerere.rikkahub.ui.pages.extensions

import java.io.IOException
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
class LorebookEntryCopyUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun copySurvivesSourceAndTargetSheetsAndFailedSaveUntilConfirmedSuccessful() {
        val source = Lorebook(name = "Source", entries = listOf("One", "Two").map {
            PromptInjection.RegexInjection(name = it, content = "content $it", probability = 47)
        })
        val target = Lorebook(name = "Target", entries = listOf(PromptInjection.RegexInjection(name = "Existing")))
        val copies = LorebookCopyState()
        var stored by mutableStateOf(listOf(source, target))
        var openId by mutableStateOf<Uuid?>(source.id)
        var failNext = false
        val writer = LorebookBatchWriter { update ->
            stored = update(stored)
            if (failNext) { failNext = false; throw IOException("simulated disk failure") }
        }
        val cancelLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.prompt_page_cancel)
        compose.setContent {
            val task by copies.pending.collectAsState()
            val busy by copies.busy.collectAsState()
            MaterialTheme {
                if (openId == null) {
                    Column {
                        task?.let { LorebookCopyShelf(it, busy, copies::remove, copies::cancel) }
                        TextButton(onClick = { openId = source.id }) { Text("Open source") }
                        TextButton(onClick = { openId = target.id }) { Text("Open target") }
                    }
                } else {
                    val original = stored.first { it.id == openId }
                    var draft by remember(original.id) { mutableStateOf(original) }
                    LorebookEditSheet(
                        book = draft, onDismiss = { openId = null }, onConfirm = {}, onSave = {},
                        persistedBook = original, copyTask = task, copyBusy = busy,
                        onStageCopy = { selected -> copies.stage(draft.id, selected); openId = null },
                        onRemoveCopyEntry = copies::remove, onCancelCopy = copies::cancel,
                        onCommitBatch = { request ->
                            val action = request.action as LorebookBatchAction.Copy
                            copies.commit(action.task) { writer.commit(request) }
                        },
                        onEdit = { draft = it },
                    )
                }
            }
        }
        compose.onNodeWithText("批量管理条目").performClick()
        compose.onNodeWithText("复制到其他世界书").performClick()
        for (name in listOf("Two", "One")) {
            compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasContentDescription("选择条目 $name"))
            compose.onNodeWithContentDescription("选择条目 $name").performClick()
        }
        compose.onNodeWithText("完成选择").performClick()
        compose.onNodeWithTag("lorebook-copy-shelf").assertIsDisplayed()
        compose.runOnIdle { assertEquals(source.entries, copies.pending.value?.entries); assertEquals(listOf(source, target), stored) }

        compose.onNodeWithText("Open source").performClick()
        compose.onNodeWithText("插入最前").assertIsNotEnabled()
        compose.onNodeWithContentDescription("插入操作说明").performClick()
        compose.onNodeWithText("这是来源世界书", substring = true).assertIsDisplayed()
        compose.onNodeWithText("关闭说明").performClick()
        compose.onNodeWithText(cancelLabel).performClick()
        compose.onNodeWithText("Open target").performClick()
        compose.runOnIdle { assertEquals(listOf(source, target), stored) }
        compose.onNodeWithText(cancelLabel).performClick()
        compose.onNodeWithTag("lorebook-copy-shelf").assertIsDisplayed()
        compose.onNodeWithText("Open target").performClick()
        compose.onNodeWithText("插入最后").performClick()
        compose.onNodeWithText("确认跨书复制").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(source, target), stored) }
        compose.onNodeWithText("返回选择落点").performClick()
        compose.runOnIdle { assertNotNull(copies.pending.value); assertEquals(listOf(source, target), stored) }

        compose.onNodeWithText("插入最后").performClick()
        compose.runOnIdle { failNext = true }
        compose.onNodeWithText("确认复制").performClick()
        compose.onNodeWithText("保存失败", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(source, target), stored); assertNotNull(copies.pending.value) }
        compose.onNodeWithText("插入最后").performClick()
        compose.onNodeWithText("确认复制").performClick()
        compose.runOnIdle {
            assertNull(copies.pending.value)
            assertEquals(source, stored[0])
            assertEquals(listOf("Existing", "One", "Two"), stored[1].entries.map { it.name })
            source.entries.zip(stored[1].entries.drop(1)).forEach { (before, after) ->
                assertNotEquals(before.id, after.id)
                assertEquals(before, after.copy(id = before.id))
            }
        }
        compose.onNodeWithTag("lorebook-insertion-safety-area").assertDoesNotExist()
        compose.onNodeWithText(cancelLabel).performClick()
        compose.onNodeWithText("Open target").performClick()
        compose.onNodeWithTag("lorebook-entry-list").performScrollToNode(hasText("One"))
        compose.onNodeWithText("One").assertIsDisplayed()
        compose.runOnIdle { assertEquals(source, stored[0]); assertEquals(3, stored[1].entries.size) }
    }

    @Test fun lastThumbnailRemovalAndCancelClearShelfWithoutWritingAnyBook() {
        val entry = PromptInjection.RegexInjection(name = "One")
        val source = Lorebook(entries = listOf(entry))
        val copies = LorebookCopyState().apply { stage(source.id, source.entries) }
        compose.setContent {
            val task by copies.pending.collectAsState()
            MaterialTheme { task?.let { LorebookCopyShelf(it, false, copies::remove, copies::cancel) } }
        }
        compose.onNodeWithContentDescription("移除选择 One").performClick()
        compose.onNodeWithTag("lorebook-copy-shelf").assertDoesNotExist()
        compose.runOnIdle { assertNull(copies.pending.value); assertEquals(listOf(entry), source.entries); copies.stage(source.id, source.entries) }
        compose.onNodeWithText("取消待插入").performClick()
        compose.onNodeWithTag("lorebook-copy-shelf").assertDoesNotExist()
        compose.runOnIdle { assertNull(copies.pending.value); assertEquals(listOf(entry), source.entries) }
    }
}
