package me.rerere.rikkahub.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 值为 JSON null 的字段。
 *
 * 用户实际遇到的报错：
 *
 *     Element class kotlinx.serialization.json.JsonNull is not a JsonObject
 *
 * 根因是 `element?.jsonObject` —— 安全调用只挡住了「字段不存在」，
 * **挡不住「字段存在但值是 JSON null」**。后者会执行
 * `JsonNull.jsonObject` 并抛异常。
 *
 * 这在角色卡里是常态：chub 导出的卡 `character_book` 就是显式的
 * JSON null（不是缺字段），整份卡因此导入失败，而卡本身完全正常。
 */
class JsonNullCardTest {

    private val chubCard: File? by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        var found: File? = null
        repeat(4) {
            if (found == null && dir != null) {
                val f = File(dir, "111.json")
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
    fun `the reported failure is reproducible with plain jsonObject`() {
        // 复现：JsonNull 走 .jsonObject 会抛
        val element = Json.parseToJsonElement("""{"character_book": null}""").jsonObject
        val raw = element["character_book"]

        // 注意：raw 不是 null（字段存在），但它是 JsonNull
        assertTrue("字段存在", raw != null)
        assertTrue("但值是 JsonNull", raw is JsonNull)

        var threw = false
        try {
            raw!!.jsonObject
        } catch (e: Exception) {
            threw = true
            assertTrue(
                "异常信息应当就是用户看到的那条：${e.message}",
                e.message?.contains("JsonNull") == true ||
                    e.message?.contains("JsonObject") == true,
            )
        }
        assertTrue("这是崩溃的确切来源", threw)
    }

    @Test
    fun `jsonObjectSafe turns both cases into null`() {
        // 修复后的行为：字段不存在、字段是 null —— 都收敛成 null
        val obj = Json.parseToJsonElement(
            """{"missing_is_absent": 1, "explicit_null": null}"""
        ).jsonObject

        // 用反射调 private 扩展函数不方便，这里直接验语义：
        // (element as? JsonObject) 对 JsonNull 返回 null
        assertNull("JsonNull 强转应当得到 null", obj["explicit_null"] as? JsonObject)
        assertNull("不存在的字段是 null", obj["nope"])
        // 真正的对象仍然取得出来
        assertTrue(
            Json.parseToJsonElement("""{"a":{}}""").jsonObject["a"] as? JsonObject != null,
        )
    }

    @Test
    fun `the importer uses the safe accessor everywhere`() {
        // 所有取对象的调用点都必须走安全版本，
        // 漏一处就是一个「这张卡导入失败」的 bug。
        // 去掉注释再查：解释「为什么不能用 .jsonObject」的注释里当然会写到它
        val code = importer
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

        val unsafe = Regex("""\?\.jsonObject\b(?!Safe|OrNull)""")
            .findAll(code)
            .count()
        assertEquals("不该有裸的 ?.jsonObject", 0, unsafe)
        assertTrue("要有安全访问器", importer.contains("fun JsonElement?.jsonObjectSafe()"))
    }

    @Test
    fun `the chub card has an explicit null character_book`() {
        // 用真实文件确认这个场景确实存在（不是假想）
        assertTrue("测试用卡应存在", chubCard != null)
        val obj = Json.parseToJsonElement(chubCard!!.readText()).jsonObject
        val data = obj["data"]!!.jsonObject
        val book = data["character_book"]

        assertTrue("character_book 字段存在", book != null)
        assertTrue("值是 JSON null", book is JsonNull)
    }

    @Test
    fun `a card with an explicit null still parses its required fields`() {
        // 修复的意义：这份卡除 character_book 外一切都是好的
        val obj = Json.parseToJsonElement(chubCard!!.readText()).jsonObject
        val data = obj["data"]!!.jsonObject
        assertEquals("chara_card_v2", obj["spec"]?.jsonPrimitive?.content)
        assertTrue(data["name"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(data["description"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(data["first_mes"]!!.jsonPrimitive.content.isNotBlank())
    }
}
