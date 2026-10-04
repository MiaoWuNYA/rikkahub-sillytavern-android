package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.HuaDengSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酒馆模式下联网搜索工具的 description 必须去掉两条会改变输出形态的指令。
 *
 * 完整版描述里有：
 *   Images: embed 2-4 relevant ones as `![](url)` at the start of your reply
 *   Citations: after using a result, add `[citation,domain](id)` after the sentence
 * 前者要求模型在回复开头插搜索结果图，后者要求句末挂引用角标。
 * 这在普通助手场景是合理的溯源要求，但在角色扮演里会让回复带上出戏的
 * 插图与角标——属于非酒馆原生的 payload。
 *
 * 另外 "Today is <日期>" 每天变化，而工具描述位于请求前缀区，
 * 跨天复用同一对话会让前缀缓存从这处分叉。
 */
class TavernSearchDescriptionTest {

    private fun descOf(tavern: Boolean): String {
        val settings = Settings(
            huadengSettings = HuaDengSettings(enableTavernMode = tavern),
        )
        return createSearchTools(settings)
            .first { it.name == "search_web" }
            .description
    }

    @Test
    fun `tavern mode drops the image embedding instruction`() {
        assertFalse(
            "酒馆模式不应要求模型在回复开头插图",
            descOf(tavern = true).contains("Images: embed"),
        )
    }

    @Test
    fun `tavern mode drops the citation instruction`() {
        assertFalse(
            "酒馆模式不应要求句末挂引用角标",
            descOf(tavern = true).contains("Citations: after using a result"),
        )
    }

    @Test
    fun `tavern mode drops the volatile date line`() {
        assertFalse(
            "日期每天变化会打断跨天前缀缓存，酒馆模式不发",
            descOf(tavern = true).contains("Today is"),
        )
    }

    @Test
    fun `tavern mode keeps the core search guidance`() {
        val desc = descOf(tavern = true)
        assertTrue("仍要说清这个工具做什么", desc.contains("Search the web for up-to-date"))
        assertTrue("仍要说明结果结构", desc.contains("Results: items[].id"))
        assertTrue("仍要说明 retrievedAt 语义", desc.contains("never a publication date"))
    }

    @Test
    fun `normal mode keeps the full description`() {
        val desc = descOf(tavern = false)
        assertTrue("普通模式保留插图指令", desc.contains("Images: embed"))
        assertTrue("普通模式保留引用指令", desc.contains("Citations: after using a result"))
        assertTrue("普通模式保留日期", desc.contains("Today is"))
    }

    @Test
    fun `tavern description is strictly shorter`() {
        val tavern = descOf(tavern = true)
        val normal = descOf(tavern = false)
        assertTrue(
            "酒馆版必须更短：$${tavern.length} vs ${normal.length}",
            tavern.length < normal.length,
        )
    }
}
