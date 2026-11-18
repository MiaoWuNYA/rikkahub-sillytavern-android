package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.CustomThoughtRule
import me.rerere.rikkahub.data.model.CustomThoughtSettings
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.setting.CustomThoughtSettingsEditor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CustomThoughtUiTest {
    @get:Rule val compose = createComposeRule()
    private val rule = CustomThoughtRule(startTag = "<rainchain>", endTag = "</rainchain>")
    private val config = CustomThoughtSettings(enabled = true, rules = listOf(rule))

    @Test fun separateCardFoldsWithoutChangingOriginal() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalSettings provides Settings()) { CustomThoughtCard("PRIVATE_MARKER") }
        } }
        compose.onNodeWithText("PRIVATE_MARKER").assertDoesNotExist()
        compose.onNodeWithText("自定义思考链").performClick()
        compose.onNodeWithText("PRIVATE_MARKER").assertIsDisplayed()
        compose.onNodeWithText("自定义思考链").performClick()
        compose.onNodeWithText("PRIVATE_MARKER").assertDoesNotExist()
    }

    @Test fun liveHideAndParsingSwitchesKeepBodyAndIndependentWindow() {
        val display = mutableStateOf(DisplaySetting(customThought = config))
        val parts = listOf(UIMessagePart.Reasoning("NATIVE_MARKER"), UIMessagePart.Text("BEFORE<rainchain>CUSTOM_MARKER</rainchain>AFTER"))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides Settings(displaySetting = display.value)) {
                    Column(Modifier.size(360.dp, 640.dp).verticalScroll(rememberScrollState())) {
                        MessagePartsBlock(assistant = null, messageDepth = 0, role = MessageRole.ASSISTANT,
                            model = null, parts = parts, annotations = emptyList(), loading = false, renderCards = false)
                    }
                }
            }
        }
        compose.onNodeWithText("BEFORE").assertIsDisplayed()
        compose.onNodeWithText("AFTER").assertIsDisplayed()
        compose.onNodeWithText("自定义思考链").performClick()
        compose.onNodeWithText("CUSTOM_MARKER").assertIsDisplayed()
        compose.runOnIdle { display.value = display.value.copy(hideNativeReasoning = true) }
        compose.onNodeWithText("CUSTOM_MARKER").assertIsDisplayed()
        compose.runOnIdle { display.value = display.value.copy(customThought = config.copy(enabled = false)) }
        compose.onNodeWithText("自定义思考链").assertDoesNotExist()
        compose.onNodeWithText("BEFORE<rainchain>CUSTOM_MARKER</rainchain>AFTER").assertIsDisplayed()
        assertEquals("BEFORE<rainchain>CUSTOM_MARKER</rainchain>AFTER", (parts.last() as UIMessagePart.Text).text)
    }

    @Test fun settingsRuleEditingAndThreeSwitchesAreIndependent() {
        val display = mutableStateOf(DisplaySetting())
        compose.setContent { MaterialTheme {
            Column(Modifier.size(360.dp, 700.dp).verticalScroll(rememberScrollState())) {
                CustomThoughtSettingsEditor(display.value) { update -> display.value = update(display.value) }
            }
        } }
        compose.onNodeWithTag("hide-native-reasoning").performClick()
        compose.runOnIdle {
            assertTrue(display.value.hideNativeReasoning)
            assertFalse(display.value.customThought.enabled)
            assertFalse(display.value.customThought.excludeFromRequests)
        }
        compose.onNodeWithTag("enable-custom-thought").performClick()
        compose.onNodeWithTag("exclude-custom-thought").performClick()
        compose.onNodeWithText("新增规则").performScrollTo().performClick()
        compose.onNodeWithText("开始标签").performScrollTo().performTextInput("<rainchain>")
        compose.onNodeWithText("结束标签").performScrollTo().performTextInput("</rainchain>")
        compose.runOnIdle {
            assertTrue(display.value.customThought.enabled)
            assertTrue(display.value.customThought.excludeFromRequests)
            assertEquals("<rainchain>", display.value.customThought.rules.single().startTag)
        }
        compose.onNodeWithText("删除规则").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(display.value.customThought.rules.isEmpty()) }
    }

    @Test fun inputPipelineFiltersRequestCopyButOutputPipelineDoesNot() = runBlocking {
        val raw = "BEFORE<rainchain>PRIVATE</rainchain>AFTER"
        val native = UIMessagePart.Reasoning("NATIVE")
        val message = UIMessage.assistant(raw).copy(parts = listOf(native, UIMessagePart.Text(raw)))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings(displaySetting = DisplaySetting(customThought = config.copy(excludeFromRequests = true)))
        val sent = listOf(message).transforms(listOf(object : InputMessageTransformer {}), context, Model(), Assistant(), settings)
        assertEquals("BEFOREAFTER", (sent.single().parts.last() as UIMessagePart.Text).text)
        assertSame(native, sent.single().parts.first())
        assertEquals(raw, (message.parts.last() as UIMessagePart.Text).text)
        val output = listOf(message).transforms(listOf(object : OutputMessageTransformer {}), context, Model(), Assistant(), settings)
        assertEquals(listOf(message), output)
    }
}
