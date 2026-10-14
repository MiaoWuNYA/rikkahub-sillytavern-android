package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.export.SillyTavernRegexImporter
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 前端卡的 HTML 挂在 markdownOnly 正则的 replaceString 上，只有显示层会用它。
 * 提示词层那条链的产物会被写回会话（ChatService 消费 GenerationChunk.Messages），
 * 带上它就会把整份卡片 HTML 灌进消息正文，所以两层必须是两个 transformer。
 */
class RegexDisplayLayerTest {

    private val cardFile = File("/tmp/card.json")

    private fun cardRegexes(): List<me.rerere.rikkahub.data.model.AssistantRegex>? {
        if (!cardFile.exists()) return null
        val root = kotlinx.serialization.json.Json.parseToJsonElement(cardFile.readText())
            as kotlinx.serialization.json.JsonObject
        val data = root["data"] as kotlinx.serialization.json.JsonObject
        val ext = data["extensions"] as kotlinx.serialization.json.JsonObject
        return SillyTavernRegexImporter.parse(ext["regex_scripts"].toString())
    }

    @Test
    fun `markdownOnly card regex only fires on the display layer`() {
        val regexes = cardRegexes() ?: return
        val display = regexes.filter { it.visualOnly && it.enabled }
        assertTrue("真卡应含 markdownOnly 正则", display.isNotEmpty())
        val assistant = Assistant(name = "card", regexes = regexes)
        val greeting = kotlinx.serialization.json.Json.parseToJsonElement(
            File("/tmp/card.json").readText()
        ).let { it as kotlinx.serialization.json.JsonObject }["data"]!!
            .let { it as kotlinx.serialization.json.JsonObject }["alternate_greetings"]!!
            .let { it as kotlinx.serialization.json.JsonArray }[0]
            .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        val messages = listOf(UIMessage.assistant(greeting))

        val forPrompt = messages.replaceRegexesForDisplay(assistant, null, visual = false)
        assertEquals(
            "提示词层不得展开卡片 HTML（产物会被写回会话）",
            greeting,
            forPrompt[0].toText(),
        )

        val forDisplay = messages.replaceRegexesForDisplay(assistant, null, visual = true)
        assertTrue(
            "显示层必须展开成完整 HTML 文档",
            forDisplay[0].toText().contains("<html") && forDisplay[0].toText().contains("<style"),
        )
    }

    @Test
    fun `a rule that is not idempotent runs once inside each layer`() {
        val regex = me.rerere.rikkahub.data.model.AssistantRegex(
            id = kotlin.uuid.Uuid.random(),
            name = "dup",
            findRegex = "x",
            replaceString = "xx",
            affectingScope = setOf(AssistantAffectScope.ASSISTANT),
        )
        val assistant = Assistant(name = "a", regexes = listOf(regex))
        val messages = listOf(UIMessage.assistant("x"))
        assertEquals("xx", messages.replaceRegexesForDisplay(assistant, null, visual = false)[0].toText())
        assertEquals("xx", messages.replaceRegexesForDisplay(assistant, null, visual = true)[0].toText())
    }

    @Test
    fun `the webview display chain carries only the display-layer transformer`() {
        val page = File(
            "/home/miaowu/Documents/rikka/rikkahub-plus/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"
        )
        if (!page.exists()) return
        val text = page.readText()
        val chain = text.substring(text.indexOf("conversation.currentMessages.visualTransforms"))
            .take(600)
        assertTrue("展示链必须用显示层 transformer", chain.contains("RegexVisualTransformer"))
        assertTrue(
            "展示链不得同时带上提示词层 transformer（非幂等规则会被跑两次）",
            !chain.contains("RegexOutputTransformer"),
        )
    }

    @Test
    fun `display layer keeps user messages untouched`() {
        val regex = me.rerere.rikkahub.data.model.AssistantRegex(
            id = kotlin.uuid.Uuid.random(),
            name = "only-assistant",
            findRegex = "hi",
            replaceString = "bye",
            affectingScope = setOf(AssistantAffectScope.ASSISTANT),
        )
        val assistant = Assistant(name = "a", regexes = listOf(regex))
        val messages = listOf(UIMessage.user("hi"))
        assertEquals("hi", messages.replaceRegexesForDisplay(assistant, null, visual = true)[0].toText())
    }
}
