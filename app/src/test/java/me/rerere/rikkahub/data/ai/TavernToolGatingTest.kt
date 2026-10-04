package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.BuiltInTools
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.service.shouldUseExternalWebSearch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酒馆模式的工具面开关规则。
 *
 * 背景：酒馆模式的定位是「只发原版酒馆会发的东西」。工具面此前有三处漏网，
 * 这组测试把规则钉住，避免以后改动时又漏回去：
 *
 * 1. memory_tool 在酒馆模式下必须缺席——它不是原版酒馆的能力，
 *    开着会让助手自行读写记忆记录。
 * 2. 「保留工具」开关打开时，联网搜索必须可用——这正是该开关的语义。
 * 3. web_fetch 跟着 web_search 走——没有搜索时给出抓取工具，
 *    模型拿到一个无从获取 URL 的东西，只会误用。
 */
class TavernToolGatingTest {

    // ── 规则 1：记忆工具 ──────────────────────────────────────────────

    @Test
    fun `memory tool is registered in normal mode when memory is enabled`() {
        assertTrue(shouldRegisterMemoryTool(memoryEnabled = true, tavernMode = false))
    }

    @Test
    fun `memory tool is suppressed in tavern mode`() {
        assertFalse(
            "酒馆模式必须排除记忆工具，否则助手会自行读写记忆",
            shouldRegisterMemoryTool(memoryEnabled = true, tavernMode = true),
        )
    }

    @Test
    fun `memory tool stays absent when memory is off regardless of mode`() {
        assertFalse(shouldRegisterMemoryTool(memoryEnabled = false, tavernMode = false))
        assertFalse(shouldRegisterMemoryTool(memoryEnabled = false, tavernMode = true))
    }

    // ── 规则 2：联网搜索 ──────────────────────────────────────────────

    @Test
    fun `web search is offered when assistant enables it and model lacks built-in search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(modelId = "test", displayName = "test", tools = emptySet())
        assertTrue(
            "助手开了联网搜索、模型自身没有内置搜索时必须提供 App 的搜索工具",
            shouldUseExternalWebSearch(assistant, model),
        )
    }

    @Test
    fun `web search is skipped when assistant disables it`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(modelId = "test", displayName = "test", tools = emptySet())
        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `web search is skipped when model already has built-in search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(
            modelId = "test",
            displayName = "test",
            tools = setOf(BuiltInTools.Search),
        )
        assertFalse(
            "模型已内置搜索时不应重复注册，否则工具面出现两个搜索入口",
            shouldUseExternalWebSearch(assistant, model),
        )
    }
}
