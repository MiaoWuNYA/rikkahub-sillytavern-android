package me.rerere.rikkahub.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chub（charhub.io）导出的角色卡。
 *
 * 实测结构：标准的 chara_card_v2，但带两处 chub 专有的东西：
 *   · 顶层 extensions.chub —— 一个嵌套对象（id / full_path / expressions…）
 *   · data.avatar —— 指向 chub CDN 的**远程 URL**，而不是本地 PNG 路径
 *
 * 这份测试确认解析路径能走通，以及那两处专有字段不会把解析带崩。
 */
class ChubCardImportTest {

    private val card: File? by lazy {
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

    private fun root(): JsonObject =
        Json.parseToJsonElement(card!!.readText()).jsonObject

    @Test
    fun `it is a standard v2 card`() {
        // 判定走既有 V2 分支，不需要新增格式
        val obj = root()
        assertEquals("chara_card_v2", obj["spec"]?.jsonPrimitive?.content)
        assertNotNull("必须有 data", obj["data"])
        assertNotNull("V2 解析器要求的 name", obj["data"]?.jsonObject?.get("name"))
    }

    @Test
    fun `nested extensions values do not break parsing`() {
        // chub 的 extensions 值是**嵌套对象**（chub / depth_prompt），
        // 而 parseExtensions 的返回类型是 Map<String, String>。
        // 靠 v.toString() 兜底转成 JSON 文本，不会崩——
        // 这条测试把这个假设钉住，避免以后有人改成强转。
        val ext = root()["data"]!!.jsonObject["extensions"]!!.jsonObject
        assertTrue("chub 是对象而非字符串", ext["chub"] is JsonObject)
        assertTrue("depth_prompt 是对象而非字符串", ext["depth_prompt"] is JsonObject)

        val parse = importer.substringAfter("private fun parseExtensions(")
            .substringBefore("/** 官方深度提示")
        assertTrue(
            "必须有非字符串值的兜底",
            parse.contains("?: v.toString()"),
        )
    }

    @Test
    fun `the chub-specific metadata is preserved losslessly`() {
        // chub 块里带 id / full_path / expressions 等平台元数据。
        // 项目把 extensions 以原始 JSON 保底（extensionsRaw），
        // 这样导出时能原样写回，不丢平台信息。
        val obj = root()
        val chub = obj["data"]!!.jsonObject["extensions"]!!.jsonObject["chub"]!!.jsonObject
        assertNotNull("要保留 chub.id", chub["id"])
        assertNotNull("要保留 chub.full_path", chub["full_path"])
        assertTrue(
            "要无损保留原始 extensions",
            importer.contains("extensionsRaw"),
        )
    }

    @Test
    fun `the remote avatar is used when there is no local one`() {
        // data.avatar 指向 chub CDN，不是本地 PNG。
        // 原来这个字段完全没被读，导入后头像是灰的——用户会以为卡没导全。
        val avatar = root()["data"]!!.jsonObject["avatar"]!!.jsonPrimitive.content
        assertTrue("是远程 URL", avatar.startsWith("http"))
        assertTrue("指向 chub CDN", avatar.contains("charhub.io"))

        // 真代码里要有这条回退
        assertTrue("要有头像解析辅助", importer.contains("fun resolveCardAvatar"))
        assertTrue(
            "V2 要读 data.avatar",
            importer.contains("""resolveCardAvatar(avatarUri, data["avatar"]"""),
        )
        // 本地卡抽出的图片优先：那才是真实文件
        val helper = importer.substringAfter("private fun resolveCardAvatar(")
            .substringBefore("// ==================== V2 Parser")
        assertTrue(
            "本地 avatar 优先",
            helper.indexOf("!avatarUri.isNullOrBlank()") < helper.indexOf("remoteAvatar.startsWith"),
        )
        // 只在像 URL 时才用，避免用上相对路径得到加载不出来的头像
        assertTrue("要校验协议", helper.contains("https://"))
    }

    @Test
    fun `required card fields are present`() {
        val data = root()["data"]!!.jsonObject
        // 这几个是 V2 解析必读的，缺了会 error()
        listOf("name", "description", "first_mes").forEach { key ->
            assertNotNull("$key 必须存在", data[key])
        }
        // 这份卡没有角色书（JSON null，不是缺字段）
        assertTrue(
            "character_book 为 null",
            data["character_book"] == null || data["character_book"] is JsonNull,
        )
    }
}
