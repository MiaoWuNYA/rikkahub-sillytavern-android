package me.rerere.rikkahub.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 风月 AI 角色卡的兼容。
 *
 * 它有一套自己的字段：ttl（名称）、desc（HTML 前端界面）、pre_pt
 * （前置提示词）、world_book（世界书）。没有 spec、没有 data，
 * 掉到 V1 分支会被当平铺卡读 name 而报错。
 *
 * 测试用一份真实的卡片文件断言「判定依据确实成立」——只断言源码里
 * 有某个字符串，证明不了它认得出真实文件。
 */
class FengyueImportTest {

    private val card: File? by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        var found: File? = null
        repeat(4) {
            if (found == null && dir != null) {
                val f = File(dir, "1122.json")
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
        assertTrue("测试用卡片应存在", card != null)
        val obj = Json.parseToJsonElement(card!!.readText()).let { it as JsonObject }

        // 判定依据：这三个字段同时存在
        assertTrue("有 ttl", obj["ttl"]?.jsonPrimitive?.content?.isNotBlank() == true)
        assertTrue("有 desc", obj["desc"]?.jsonPrimitive?.content?.isNotBlank() == true)
        assertTrue("有 pre_pt", obj["pre_pt"] != null)

        // 且没有酒馆卡的标志字段，否则会被前面的分支截走
        assertTrue("没有 spec", obj["spec"] == null)
        assertTrue("没有 data", obj["data"] == null)
    }

    @Test
    fun `field mapping matches the card's real data`() {
        val obj = Json.parseToJsonElement(card!!.readText()).let { it as JsonObject }

        // desc 是完整 HTML 网页——这是前端卡，必须原样保留
        val desc = obj["desc"]!!.jsonPrimitive.content
        assertTrue("desc 是 HTML", desc.contains("<!DOCTYPE html") || desc.contains("<html"))
        assertTrue("desc 长度可观", desc.length > 5000)

        // pre_pt 是提示词（角色设定与规则）
        val prePt = obj["pre_pt"]!!.jsonPrimitive.content
        assertTrue("pre_pt 有内容", prePt.isNotBlank())

        // world_book 是数组
        assertTrue("world_book 是数组", obj["world_book"] is JsonArray)
    }

    @Test
    fun `detection runs before the v1 fallback`() {
        // 顺序错了就会被 V1 分支截走，报「缺少 name 字段」。
        val fengyueIdx = importer.indexOf("looksLikeFengyueCard(json)")
        val v1Idx = importer.indexOf("\"chara_card_v1\", \"chara_card\" ->")
        assertTrue("要有判定函数", fengyueIdx > 0)
        assertTrue("判定要在 V1 分支之前", fengyueIdx < v1Idx)
    }

    @Test
    fun `html greeting is preserved`() {
        // 前端卡的界面就是 desc，剥掉标签等于把卡片毁了。
        val block = importer.substringAfter("private fun parseFengyueCard(")
            .substringBefore("private fun parseFengyueWorldBook(")
        assertTrue("开场白取 desc", block.contains("str(\"desc\")"))
        assertTrue("原文保留，不做清理", block.contains("UIMessage.assistant(prompt = greetingHtml)"))
        assertTrue("设定取 pre_pt", block.contains("str(\"pre_pt\")"))
    }

    @Test
    fun `world book entries are converted`() {
        val block = importer.substringAfter("private fun parseFengyueWorldBook(")
        assertTrue("要拆 _or_ 前缀", block.contains("removePrefix(\"_or_\")"))
        assertTrue("要拆 @wb@ 分隔符", block.contains("@wb@"))
        assertTrue("要读 enable", block.contains("\"enable\""))
        assertTrue("要读 probability", block.contains("\"probability\""))
        // 单条解析失败不该让整个导入失败
        assertTrue("用 mapNotNull 跳过坏条目", block.contains("mapNotNull"))
    }

    @Test
    fun `the world book actually parses`() {
        // 用真实数据确认条目格式与解析假设一致
        val obj = Json.parseToJsonElement(card!!.readText()).let { it as JsonObject }
        val wb = obj["world_book"] as JsonArray
        assertTrue("世界书非空", wb.isNotEmpty())

        val first = wb.first().let { it as JsonObject }
        assertTrue("有条目键 key", first["key"] != null)
        assertTrue("有条目值 value", first["value"]?.jsonPrimitive?.content?.isNotBlank() == true)
        // key 里确实带分隔符，解析逻辑才有意义
        val keys = wb.mapNotNull {
            (it as? JsonObject)?.get("key")?.jsonPrimitive?.content
        }
        assertTrue("至少有一条带 @wb@ 分隔符", keys.any { it.contains("@wb@") })
    }
}
