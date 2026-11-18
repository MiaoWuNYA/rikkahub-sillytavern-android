package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntrySharedSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val a = PromptInjection.RegexInjection(name = "IndependentName", content = "IndependentBody", keywords = listOf("IndependentKeyword"), group = "G", priority = 50, constantActive = true)
    private val same = List(3) { a.copy(id = Uuid.random()) }
    private val mixed = (1..3).map { a.copy(id = Uuid.random(), priority = it, injectDepth = it) }
    private var applied = mutableListOf<LorebookSettingsPatch>()
    private var visible by mutableStateOf(true)
    private fun show(entries: List<PromptInjection.RegexInjection>) {
        compose.setContent { MaterialTheme { if (visible) LorebookBulkSettingsDialog(entries, { visible = false }, { applied.add(it) }) } }
    }
    private fun field(key: String) = compose.onNodeWithTag("bulk-field-$key")
    private fun select(key: String) { compose.onNodeWithTag("bulk-select-$key").performClick() }
    private fun toggles(key: String) = compose.onAllNodes(isToggleable() and hasAnyAncestor(hasTestTag("bulk-field-$key")))
    @Test fun commonValuesAndPermanentSwitchStayIndependentFromFieldSelection() {
        show(same)
        compose.onNodeWithText("应用到 3 个条目").assertIsNotEnabled()
        field("CONSTANT_ACTIVE").performScrollTo()
        toggles("CONSTANT_ACTIVE").onFirst().assertIsOff()
        toggles("CONSTANT_ACTIVE").onLast().assertIsOn()
        select("CONSTANT_ACTIVE")
        toggles("CONSTANT_ACTIVE").onLast().assertIsOn()
        select("CONSTANT_ACTIVE")
        toggles("CONSTANT_ACTIVE").onLast().assertIsOn()
        field("ENABLED").performScrollTo()
        toggles("ENABLED").onLast().performClick()
        compose.onNodeWithText("应用到 3 个条目").assertIsNotEnabled()
        select("ENABLED")
        compose.onNodeWithText("应用到 3 个条目").performClick()
        compose.runOnIdle { assertEquals(LorebookSettingsPatch(enabled = false), applied.single()) }
    }
    @Test fun mixedPriorityRequiresNewValueAndOnlyPriorityIsApplied() {
        show(mixed)
        field("PRIORITY").performScrollTo()
        compose.onNodeWithTag("bulk-priority").assertDoesNotExist()
        select("PRIORITY")
        compose.onNodeWithText("应用到 3 个条目").assertIsNotEnabled()
        compose.onNodeWithTag("bulk-edit-PRIORITY").performClick()
        compose.onNodeWithTag("bulk-priority").performTextReplacement("90")
        compose.onNodeWithText("应用到 3 个条目").performClick()
        compose.runOnIdle { assertEquals(LorebookSettingsPatch(priority = 90), applied.single());assertEquals(mixed.map { it.copy(priority = 90) }, mixed.map { applied.single().apply(it) }) }
        captureLorebookRepairEvidence("shared-bulk-mixed", compose.onNode(isDialog()).captureToImage().asAndroidBitmap())
    }
    @Test fun multipleFieldsThenDeselectingEditedFieldStopsItFromApplying() {
        show(same)
        select("ENABLED");toggles("ENABLED").onLast().performClick()
        select("PRIORITY")
        compose.onNodeWithTag("bulk-priority").performTextReplacement("90")
        compose.onNodeWithText("应用到 3 个条目").performClick()
        select("PRIORITY")
        compose.onNodeWithText("应用到 3 个条目").performClick()
        compose.runOnIdle {
            assertEquals(LorebookSettingsPatch(priority = 90, enabled = false), applied[0])
            assertEquals(LorebookSettingsPatch(enabled = false), applied[1])
        }
    }
    @Test fun cancellingSharedPageLeavesSourcesUntouchedAndExcludesContent() {
        show(same)
        compose.onNodeWithText(a.name).assertDoesNotExist()
        compose.onNodeWithText(a.content).assertDoesNotExist()
        compose.onNodeWithText(a.keywords.single()).assertDoesNotExist()
        select("PRIORITY");compose.onNodeWithTag("bulk-priority").performTextReplacement("90")
        compose.onNodeWithText("取消设置").performClick()
        compose.runOnIdle { assertFalse(visible);assertTrue(applied.isEmpty());assertTrue(same.all { it.priority == 50 }) }
    }
    @Test fun ordinaryEntryStillEditsPriorityAndKeepsIndependentFields() {
        var entry by mutableStateOf(a)
        var saved: PromptInjection.RegexInjection? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { RegexInjectionEditDialog(entry, {}, { saved = entry }, { entry = it }) } }
        compose.onNode(hasText(context.getString(R.string.prompt_page_priority_label)) and hasSetTextAction()).performTextReplacement("90")
        compose.onNodeWithText(context.getString(R.string.prompt_page_confirm)).performClick()
        compose.runOnIdle { assertEquals(a.copy(priority = 90), saved) }
        compose.onNodeWithTag("bulk-select-PRIORITY").assertDoesNotExist()
    }
    @Test fun originalGroupDialogRetainsItsExistingIndependentBehavior() {
        var saved: PromptInjection.RegexInjection? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { GroupSettingsDialog("G", same, {}, { _, value -> saved = value }) } }
        compose.onNode(hasText(context.getString(R.string.prompt_page_priority_label)) and hasSetTextAction()).performTextReplacement("90")
        compose.onNodeWithText(context.getString(R.string.prompt_page_apply_to_group, 3)).performClick()
        compose.runOnIdle { assertEquals(a.copy(id = same.first().id, priority = 90), saved) }
        compose.onNodeWithTag("bulk-select-PRIORITY").assertDoesNotExist()
    }
}
