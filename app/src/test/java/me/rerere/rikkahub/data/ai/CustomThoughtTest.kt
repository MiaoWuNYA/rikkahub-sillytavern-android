package me.rerere.rikkahub.data.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.CustomThoughtRule
import me.rerere.rikkahub.data.model.CustomThoughtSettings
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant

class CustomThoughtTest {
    private fun rule(tag: String) = CustomThoughtRule(startTag = "<$tag>", endTag = "</$tag>")
    private val config = CustomThoughtSettings(enabled = true, rules = listOf(rule("rainchain"), rule("analysis"), rule("thinking")))
    private val raw = "before<rainchain>private thought</rainchain>after"
    private val now = Instant.parse("2026-11-18T00:00:00Z")

    @Test fun defaultsAreOptInAndOldDisplaySettingsLoad() {
        val old = Json.decodeFromString<DisplaySetting>("{}")
        assertFalse(old.hideNativeReasoning)
        assertFalse(old.customThought.enabled)
        assertFalse(old.customThought.excludeFromRequests)
        assertNull(findCustomThought(raw, old.customThought))
    }

    @Test fun persistentRulesAndAllSwitchesRoundTrip() {
        val settings = DisplaySetting(hideNativeReasoning = true, customThought = config.copy(excludeFromRequests = true))
        assertEquals(settings, Json.decodeFromString<DisplaySetting>(Json.encodeToString(settings)))
    }

    @Test fun extractsFirstInTextRatherThanRuleOrder() {
        val text = "x<analysis>first</analysis>y<rainchain>second</rainchain>z"
        val match = findCustomThought(text, config)!!
        assertEquals("first", match.content)
        assertEquals("xy<rainchain>second</rainchain>z", match.removeFrom(text))
    }

    @Test fun disabledAndEmptyRulesCannotMatch() {
        val settings = config.copy(rules = listOf(rule("rainchain").copy(enabled = false), CustomThoughtRule(),
            CustomThoughtRule(startTag = " ", endTag = "x")))
        assertNull(findCustomThought(raw, settings))
    }

    @Test fun emptyPairIsSkippedAndLaterNonblankPairFound() {
        val text = "<rainchain> \n </rainchain>foo<rainchain>valid</rainchain>"
        assertEquals("valid", findCustomThought(text, config)!!.content)
    }

    @Test fun unclosedAndMismatchedPairsRemainText() {
        listOf("<rainchain>unfinished", "<rainchain>wrong</analysis>", "</rainchain>nothing").forEach {
            assertNull(findCustomThought(it, config))
            assertEquals(listOf(MessagePartBlock.ContentBlock(UIMessagePart.Text(it), 0)),
                listOf(UIMessagePart.Text(it)).groupMessageParts(customThought = config))
        }
    }

    @Test fun literalTagsAreNotRegexOrCaseInsensitive() {
        val literal = config.copy(rules = listOf(CustomThoughtRule(startTag = "[x.*]", endTag = "[/x.*]")))
        assertEquals("ok", findCustomThought("[x.*]ok[/x.*]", literal)!!.content)
        assertNull(findCustomThought("<RAINCHAIN>no</RAINCHAIN>", config))
    }

    @Test fun middleBlockKeepsDisplayOrderAndRawText() {
        val parts = listOf(UIMessagePart.Text(raw))
        val blocks = parts.groupMessageParts(customThought = config)
        assertEquals("before", ((blocks[0] as MessagePartBlock.ContentBlock).part as UIMessagePart.Text).text)
        assertEquals("private thought", (blocks[1] as MessagePartBlock.CustomThoughtBlock).text)
        assertEquals("after", ((blocks[2] as MessagePartBlock.ContentBlock).part as UIMessagePart.Text).text)
        assertEquals(raw, parts.single().text)
    }

    @Test fun nativeReasoningDoesNotBlockCustomAndHideKeepsTools() {
        val reasoning = UIMessagePart.Reasoning("native", finishedAt = now)
        val tool = UIMessagePart.Tool(toolCallId = "tool", toolName = "search", input = "{}")
        val parts = listOf(reasoning, tool, UIMessagePart.Text(raw))
        val visible = parts.groupMessageParts(customThought = config)
        assertTrue((visible.first() as MessagePartBlock.ThinkingBlock).steps.any { it is ThinkingStep.ReasoningStep })
        val hidden = parts.groupMessageParts(hideNativeReasoning = true, customThought = config)
        assertEquals(listOf(ThinkingStep.ToolStep(tool)), (hidden.first() as MessagePartBlock.ThinkingBlock).steps)
        assertEquals(1, hidden.filterIsInstance<MessagePartBlock.CustomThoughtBlock>().size)
        assertSame(reasoning, parts.first())
    }

    @Test fun nativeOnlyDoesNotCreateCustomWindow() {
        val parts = listOf(UIMessagePart.Reasoning("API", finishedAt = now))
        assertEquals(1, parts.groupMessageParts(customThought = config).size)
        assertTrue(parts.groupMessageParts(true, config).isEmpty())
    }

    @Test fun tttfExtractedThinkingIsNotExtractedAgain() {
        val transformed = listOf(UIMessage.assistant("<thinking>native fallback</thinking>answer"))
            .transformThinkTags(now, generationFinished = true).single()
        assertEquals(1, transformed.parts.filterIsInstance<UIMessagePart.Reasoning>().size)
        assertTrue(transformed.parts.groupMessageParts(customThought = config)
            .none { it is MessagePartBlock.CustomThoughtBlock })
    }

    @Test fun tttfThenCustomParsesOnlyResidualText() {
        val transformed = listOf(UIMessage.assistant("<think>native</think>$raw"))
            .transformThinkTags(now, generationFinished = true).single()
        val blocks = transformed.parts.groupMessageParts(customThought = config)
        assertEquals(1, blocks.filterIsInstance<MessagePartBlock.ThinkingBlock>().size)
        assertEquals(1, blocks.filterIsInstance<MessagePartBlock.CustomThoughtBlock>().size)
    }

    @Test fun streamingChangesOnlyDisplayAfterClosingTag() {
        val prefixes = listOf("<rain", "<rainchain>thought", "<rainchain>thought</rainchain", "<rainchain>thought</rainchain>")
        prefixes.forEachIndexed { index, text ->
            val blocks = listOf(UIMessagePart.Text(text)).groupMessageParts(customThought = config)
            assertEquals(if (index == 3) 1 else 0, blocks.filterIsInstance<MessagePartBlock.CustomThoughtBlock>().size)
        }
    }

    @Test fun changedRulesAndDisabledParsingRedisplayOriginal() {
        val parts = listOf(UIMessagePart.Text(raw))
        assertEquals(1, parts.groupMessageParts(customThought = config).filterIsInstance<MessagePartBlock.CustomThoughtBlock>().size)
        assertEquals(listOf(MessagePartBlock.ContentBlock(parts.single(), 0)), parts.groupMessageParts(customThought = config.copy(enabled = false)))
        assertEquals(listOf(MessagePartBlock.ContentBlock(parts.single(), 0)), parts.groupMessageParts(customThought = config.copy(rules = listOf(rule("other")))))
    }

    @Test fun onlyFirstPairAcrossTextPartsIsDisplayedAndFiltered() {
        val message = UIMessage.assistant(raw).copy(parts = listOf(UIMessagePart.Text(raw), UIMessagePart.Text(raw)))
        assertEquals(1, message.parts.groupMessageParts(customThought = config).filterIsInstance<MessagePartBlock.CustomThoughtBlock>().size)
        val sent = listOf(message).withoutCustomThoughtForRequest(config.copy(excludeFromRequests = true)).single()
        assertEquals(listOf("beforeafter", raw), sent.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
    }

    @Test fun allSwitchCombinationsPreserveStoredTextAndNativeReasoning() {
        val reasoning = UIMessagePart.Reasoning("signed native", finishedAt = now)
        val message = UIMessage.assistant(raw).copy(parts = listOf(reasoning, UIMessagePart.Text(raw)))
        listOf(false, true).forEach { hide -> listOf(false, true).forEach { enabled -> listOf(false, true).forEach { exclude ->
            val settings = config.copy(enabled = enabled, excludeFromRequests = exclude)
            val blocks = message.parts.groupMessageParts(hide, settings)
            assertEquals(if (hide) 0 else 1, blocks.filterIsInstance<MessagePartBlock.ThinkingBlock>().size)
            assertEquals(if (enabled) 1 else 0, blocks.filterIsInstance<MessagePartBlock.CustomThoughtBlock>().size)
            val sent = listOf(message).withoutCustomThoughtForRequest(settings).single()
            assertEquals(if (enabled && exclude) "beforeafter" else raw, sent.parts.filterIsInstance<UIMessagePart.Text>().single().text)
            assertSame(reasoning, sent.parts.first())
            assertEquals(raw, message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        } } }
    }

    @Test fun requestDoesNotFilterUsersSystemToolsOrOtherPairs() {
        val messages = listOf(UIMessage.user(raw), UIMessage.system(raw), UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text(raw))),
            UIMessage.assistant(raw + "<analysis>keep</analysis>"))
        val sent = messages.withoutCustomThoughtForRequest(config.copy(excludeFromRequests = true))
        assertEquals(messages.take(3), sent.take(3))
        assertEquals("beforeafter<analysis>keep</analysis>", sent.last().parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test fun rawArchiveSerializationRemainsUnchangedAfterDisplayAndRequest() {
        val message = UIMessage.assistant(raw)
        val before = Json.encodeToString(message)
        message.parts.groupMessageParts(customThought = config)
        listOf(message).withoutCustomThoughtForRequest(config.copy(excludeFromRequests = true))
        assertEquals(before, Json.encodeToString(message))
    }

    @Test fun markdownEscapesOnlyThoughtTagsAndPreservesCardsAndCode() {
        assertEquals("before\\<rainchain\\>private thought\\</rainchain\\>after", visibleThoughtTags(raw, config))
        val code = "`<rainchain>`\n```xml\n<rainchain>code</rainchain>\n```\n<div>card</div>"
        assertEquals(code, visibleThoughtTags(code, config))
        assertEquals("\\<rainchain>", visibleThoughtTags("\\<rainchain>", config))
        assertEquals("\\<thinking\\>raw\\</thinking\\>", visibleThoughtTags("<thinking>raw</thinking>", CustomThoughtSettings()))
    }
}
