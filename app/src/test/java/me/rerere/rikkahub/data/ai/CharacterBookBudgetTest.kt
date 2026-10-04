package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 角色卡内嵌世界书不参与预算裁剪。
 *
 * 背景：世界书预算默认是上下文的 25%（官方 world_info_budget）。中文角色卡
 * 的世界书动辄几万 token——实测一张 22 条的卡里 11 条 constant 合计 3.7 万字符，
 * 按 25% 预算会把后面的条目逐条丢弃。
 *
 * 而丢掉的偏偏是定义卡片格式的那几条：<生成格式> 教模型写
 * <mainbody>/<phone> 骨架，<Status>/<twitter>/<bilibili>/<choice> 各自
 * 定义一块内容的格式。它们一丢，模型不再输出标签，卡内 JS 拿不到数据，
 * 表现就是「卡片只剩一个 HTML 空壳」「直播弹幕/选项面板显示未激活」。
 *
 * 这也是默认值从 25 改成 100 的原因：卡是用户主动导入的，它的必需内容
 * 不该和文风/人物生动化这类可裁条目争额度。
 */
class CharacterBookBudgetTest {

    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformer.kt"),
            File("app/src/main/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformer.kt"),
        )
        candidates.firstOrNull { it.exists() }?.readText() ?: ""
    }

    @Test
    fun `character book entries are exempt from the budget cut`() {
        assertTrue("找不到 PromptInjectionTransformer.kt", source.isNotEmpty())
        assertTrue(
            "预算检查必须对卡内条目豁免，否则格式条目会被挤掉",
            source.contains("characterBookEntryIds") &&
                source.contains("budgetExempt = entry.ignoreBudget || entry.id in characterBookEntryIds"),
        )
    }

    @Test
    fun `overflow break also exempts character book entries`() {
        assertTrue(
            "溢出后的 break 也要放行卡内条目，一处溢出不该连带砍掉后面的格式条目",
            source.contains("exemptFromOverflow = entry.ignoreBudget || entry.id in characterBookEntryIds"),
        )
    }

    @Test
    fun `default budget is unlimited`() {
        val pref = listOf(
            File("src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt"),
            File("app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt"),
        ).firstOrNull { it.exists() }?.readText().orEmpty()
        assertTrue("找不到 PreferencesStore.kt", pref.isNotEmpty())
        assertTrue(
            "默认预算应为 100（不裁剪）；25 会让中文大卡的关键格式条目被丢弃",
            pref.contains("val worldInfoBudget: Int = 100"),
        )
        assertTrue(
            "读取兜底也必须是 100，否则老用户仍拿到 25",
            pref.contains("preferences[WORLD_INFO_BUDGET]?.coerceIn(0, 100) ?: 100"),
        )
    }

    @Test
    fun `real card constant entries are a large share of the old budget`() {
        val f = File("/tmp/card.json")
        if (!f.exists()) return
        val root = kotlinx.serialization.json.Json.parseToJsonElement(f.readText()) as JsonObject
        val data = root["data"] as JsonObject
        val book = data["character_book"] as JsonObject
        val entries = book["entries"] as JsonArray

        var cjkChars = 0
        var nonCjk = 0
        var constantCount = 0
        entries.filter {
            (it as JsonObject)["constant"]?.let { c -> (c as JsonPrimitive).booleanOrNull } == true
        }.forEach { e ->
            constantCount++
            val content = (e as JsonObject)["content"]
                ?.let { (it as JsonPrimitive).contentOrNull }.orEmpty()
            content.forEach { ch ->
                val isCjk = ch.code in 0x4E00..0x9FFF || ch.code in 0x3040..0x30FF
                if (isCjk) cjkChars++ else nonCjk++
            }
        }
        val tokens = cjkChars + (nonCjk + 3) / 4
        println("constant 条目 $constantCount 条，约 $tokens token（旧默认预算 65536×25% = 16384）")

        // 旧预算下，constant 条目本身已占掉大半额度，再叠加递归缓冲就会溢出。
        // 超过 60% 即足以说明「格式条目与可裁条目争额度」这个设计问题是真实存在的。
        assertTrue(
            "constant 条目 $tokens token 应占旧预算 16384 的 60% 以上，否则该设计问题不成立",
            tokens > 16384 * 0.6,
        )
    }
}
