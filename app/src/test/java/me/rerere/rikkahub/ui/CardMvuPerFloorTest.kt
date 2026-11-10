package me.rerere.rikkahub.ui

import me.rerere.rikkahub.ui.components.richtext.MvuStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 面板模式下的逐楼变量契约：变量由 runtime.js 的 TavernHelper 持有，宿主只保证 mvuData 字段与解析链路仍在。 */
class CardMvuPerFloorTest {

    private fun read(p: String) = File(p).readText()

    @Test
    fun `ui message keeps a per-floor mvu snapshot field`() {
        val src = read("../ai/src/main/java/me/rerere/ai/ui/Message.kt")
        assertTrue(
            "UIMessage 必须保留 mvuData 字段（逐楼快照的存储位置）",
            src.contains("val mvuData: String? = null"),
        )
    }

    @Test
    fun `host no longer injects a card-side mvu bridge`() {
        assertFalse(
            "CardHostBridge.kt 已随自建面板删除",
            File("src/main/java/me/rerere/rikkahub/ui/components/richtext/CardHostBridge.kt").exists(),
        )
        assertFalse(
            "ChatList 不再构造 CardHostContext",
            read("src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt").contains("CardHostContext"),
        )
    }

    @Test
    fun `panel host hands html over to the tavern runtime bridge`() {
        val src = read("src/main/java/me/rerere/rikkahub/ui/components/richtext/HtmlWebViewBlock.kt")
        assertTrue("面板桥 RikkaPanel 必须注册", src.contains("\"RikkaPanel\""))
        assertTrue("酒馆桥 RikkaBridge 必须注册", src.contains("\"RikkaBridge\""))
        assertTrue("卡片 HTML 只经 getPendingHtml 交给运行时", src.contains("getPendingHtml"))
        assertTrue("宿主页必须是 panel.html", src.contains("panel.html"))
    }

    @Test
    fun `status bar entry is recognised as an initvar source`() {
        // 该卡的世界书里初始化条目名为 `[initvar]变量初始化勿开`
        val stat = MvuStore.parseInitVar(
            File("/tmp/initvar_chara.txt").takeIf { it.exists() }?.readText() ?: return,
            "user",
        )
        assertNotNull(stat)
        var found = ""
        stat!!.forEach { (k, v) ->
            val o = v as? kotlinx.serialization.json.JsonObject ?: return@forEach
            if (o["identity"] is kotlinx.serialization.json.JsonObject && o.containsKey("opening")) {
                found = k
            }
        }
        assertEquals("user", found)
        val user = stat["user"] as kotlinx.serialization.json.JsonObject
        listOf("identity", "名声", "stats", "状态栏", "attitudes").forEach { field ->
            assertTrue("状态栏要求的字段缺失: " + field, user.containsKey(field))
        }
        val world = stat["world"] as kotlinx.serialization.json.JsonObject
        listOf("当前日期", "当前时间", "当前地点", "剧情线").forEach { field ->
            assertTrue("world 字段缺失: " + field, world.containsKey(field))
        }
    }
}
