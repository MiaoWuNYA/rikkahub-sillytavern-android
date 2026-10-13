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
 * 背景与规则（记忆工具的门控已内联进 GenerationLoop 的酒馆剔除逻辑，
 * 由 TavernPathParityTest 源码断言钉住）：
 *
 * 1. 联网搜索必须可用——「保留工具」开关的语义。
 * 2. web_fetch 跟着 web_search 走——没有搜索时给出抓取工具，
 *    模型拿到一个无从获取 URL 的东西，只会误用。
 */
class TavernToolGatingTest {

    // ── 规则 1：联网搜索 ──────────────────────────────────────────────

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
