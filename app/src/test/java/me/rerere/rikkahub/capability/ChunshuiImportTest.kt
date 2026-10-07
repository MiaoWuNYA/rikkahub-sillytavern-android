package me.rerere.rikkahub.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 春水 AI 对话导出的兼容。
 *
 * 它的结构与酒馆角色卡完全不同：没有 spec、没有 data，而是
 * 「一段已经聊过的对话」——人物设定写在第一条 user 消息里，
 * 首条 assistant 是开场白。
 */
class ChunshuiImportTest {

    private val card: File? by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir: File? = cwd
        var found: File? = null
        // 向上找几层：Gradle 单测的工作目录可能是 app/，也可能是仓库根
        repeat(4) {
            if (found == null && dir != null) {
                val f = File(dir, "对话.json")
                if (f.exists()) found = f
                dir = dir.parentFile
            }
        }
        found
    }

    private fun read(rel: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (root in listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile).filterNotNull()) {
            for (c in listOf(File(root, rel), File(root, "app/$rel"))) {
                if (c.exists()) return c.readText()
            }
        }
        return ""
    }

    private val importer get() =
        read("src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantImporter.kt")

    @Test
    fun `the real card has the expected shape`() {
        // 用真实文件确认判定依据成立：没有 spec/data，有 messages。
        assertTrue("测试用角色卡应存在", card != null)
        val obj = Json.parseToJsonElement(card!!.readText()).let { it as JsonObject }
        assertTrue("没有 spec", obj["spec"] == null)
        assertTrue("没有 data", obj["data"] == null)
        assertTrue("有 messages", obj["messages"] is JsonArray)
        assertTrue(
            "带 character_name 或 conversation_id",
            obj["character_name"] != null || obj["conversation_id"] != null,
        )
    }

    @Test
    fun `the format is recognised before the v1 fallback`() {
        // 顺序很关键：不先认它，就会掉进 V1 分支去读顶层 name，
        // 读不到就报错——用户手上明明有一份完好的设定却导不进来。
        val detectIdx = importer.indexOf("looksLikeConversationExport(json)")
        val v1Idx = importer.indexOf("\"chara_card_v1\", \"chara_card\" ->")
        assertTrue("要有识别函数", detectIdx > 0)
        assertTrue("识别要在 V1 分支之前", detectIdx < v1Idx)
    }

    @Test
    fun `user message becomes the character description`() {
        // 首条 user 是用户贴进去的人物设定，该进 systemPrompt，
        // **不该**进 presetMessages——否则每开新对话都会重复一遍这段长文。
        val block = importer.substringAfter("private fun parseConversationExport(")
            .substringBefore("internal fun cleanImportedText(")
        assertTrue("要取第一条 user 作设定", block.contains("firstIsUser"))
        assertTrue("设定要进 systemPrompt", block.contains("systemPrompt = setting"))
        assertFalse(
            "设定不该进开场白",
            block.contains("presetMessages = listOf(UIMessage.user"),
        )
    }

    @Test
    fun `assistant message becomes the greeting`() {
        val block = importer.substringAfter("private fun parseConversationExport(")
            .substringBefore("internal fun cleanImportedText(")
        assertTrue("要取首条 assistant", block.contains("greetingIndex"))
        assertTrue("要作为开场白", block.contains("UIMessage.assistant(prompt = greeting)"))
    }

    @Test
    fun `html markup is preserved not stripped`() {
        // 这类导出常常是「前端卡」——开场白本身就是一段 HTML，
        // 由应用内的卡片渲染器画成界面。
        //
        // 我第一版写了个 cleanImportedText 把标签全剥掉，那是错的：
        // 等于把卡片毁了，只剩一堆散落的文字。原文必须原样保留。
        assertFalse(
            "不该有剥离 HTML 的清理函数",
            importer.contains("fun cleanImportedText"),
        )
        assertFalse(
            "不该用正则删标签",
            importer.contains("Regex(\"<[^>]+>\")"),
        )
        // 开场白直接取原文
        val block = importer.substringAfter("private fun parseConversationExport(")
            .substringBefore("// ==================== V2 Parser")
        assertTrue(
            "开场白要原文保留",
            block.contains("?.let { textOf(it) }"),
        )
    }

    @Test
    fun `name falls back to the title`() {
        // 这份文件的 character_name 是空的，只能退回 title。
        val obj = Json.parseToJsonElement(card!!.readText()).let { it as JsonObject }
        val charName = obj["character_name"]?.jsonPrimitive?.content.orEmpty()
        assertEquals("这份导出里 character_name 确实是空的", "", charName)
        assertTrue("所以要退回 title", importer.contains("json[\"title\"]"))
    }
}
