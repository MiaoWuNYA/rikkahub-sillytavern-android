package me.rerere.rikkahub.ui

import me.rerere.rikkahub.ui.components.richtext.MvuStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MVU 变量「逐楼快照」契约。
 *
 * 卡的 `O00｜MVU状态栏桥·前端逐楼快照` 条目写得很明确：
 *   状态栏由前端直接读取**每一楼自己的** MVU stat_data 快照渲染；
 *   旧楼显示旧状态、新楼显示新状态；不得把最新 MVU 反向覆盖所有历史楼。
 *
 * 所以变量必须挂在消息上、可持久化。只放 WebView 内存里的话，
 * 卡片一旦被 LazyColumn 回收重组，状态栏就会空白或串楼。
 */
class CardMvuPerFloorTest {

    private fun read(p: String) = File(p).readText()

    @Test
    fun `ui message carries a per-floor mvu snapshot`() {
        val src = read("../ai/src/main/java/me/rerere/ai/ui/Message.kt")
        assertTrue(
            "UIMessage 必须有 mvuData 字段（逐楼快照的存储位置）",
            src.contains("val mvuData: String? = null"),
        )
    }

    @Test
    fun `host persistence writes the snapshot back onto the message`() {
        val src = read("src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt")
        assertTrue(
            "卡写回变量时要持久化到该楼层",
            src.contains("persistMvu = onCardPersistMvu"),
        )
        assertTrue(
            "持久化必须落到消息的 mvuData 上",
            src.contains("m.copy(mvuData = dataJson)"),
        )
    }

    @Test
    fun `each card gets its own floor index and snapshot`() {
        val src = read("src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt")
        // 所有卡片共用一个 base context，但楼层序号和快照必须逐条注入，
        // 否则所有楼都会读到同一个状态，逐楼历史就失效了
        assertTrue("必须注入楼层序号", src.contains("nodeIndex = index,"))
        assertTrue(
            "必须注入该楼层自己的快照",
            src.contains("existingMvu = node.currentMessage.mvuData,"),
        )
    }

    @Test
    fun `existing snapshot wins over the world book initial value`() {
        val src = read(
            "src/main/java/me/rerere/rikkahub/ui/components/richtext/CardHostBridge.kt"
        )
        // 已保存的快照优先级高于 initvar：否则每次重组都会把玩家进度重置回开局
        assertTrue(
            "seedMvuData 应优先使用已保存快照",
            src.contains("existing?.takeIf { it.isNotBlank() }"),
        )
        assertTrue(
            "没有快照才退回世界书初值",
            src.contains("?: fallback?.takeIf { it.isNotBlank() }"),
        )
    }

    @Test
    fun `status bar entry is recognised as an initvar source`() {
        // 该卡的世界书里初始化条目名为 `[initvar]变量初始化勿开`
        val stat = MvuStore.parseInitVar(
            File("/tmp/initvar_chara.txt").takeIf { it.exists() }?.readText() ?: return,
            "user",
        )
        assertNotNull(stat)
        // findUserKey：有 identity 且有 opening 的那个键就是玩家根节点
        var found = ""
        stat!!.forEach { (k, v) ->
            val o = v as? kotlinx.serialization.json.JsonObject ?: return@forEach
            if (o["identity"] is kotlinx.serialization.json.JsonObject && o.containsKey("opening")) {
                found = k
            }
        }
        assertEquals("user", found)
        // 状态栏条目点名要展示这些字段，它们必须在变量树里真实存在
        val user = stat["user"] as kotlinx.serialization.json.JsonObject
        listOf("identity", "名声", "stats", "状态栏", "attitudes").forEach { field ->
            assertTrue("状态栏要求的字段缺失: $field", user.containsKey(field))
        }
        val world = stat["world"] as kotlinx.serialization.json.JsonObject
        listOf("当前日期", "当前时间", "当前地点", "剧情线").forEach { field ->
            assertTrue("world 字段缺失: $field", world.containsKey(field))
        }
    }
}
