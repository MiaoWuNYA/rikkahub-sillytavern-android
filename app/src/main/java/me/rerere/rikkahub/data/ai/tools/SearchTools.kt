package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.LocalDate
import kotlin.time.Clock
import kotlin.uuid.Uuid

fun createSearchTools(settings: Settings): Set<Tool> {
    // 酒馆模式用精简描述：完整版里有两条指令会直接改变角色扮演的输出形态——
    // "Images: embed 2-4 relevant ones as ![](url) at the start of your reply"
    // 要求模型在回复开头插搜索结果图，
    // "Citations: ... add `[citation,domain](id)` after the sentence"
    // 要求句末挂角标。这两条在普通助手场景是合理的溯源要求，但在角色扮演里
    // 会让 AI 的回复带上出戏的插图与引用角标。角色扮演中联网只用来查事实，
    // 查到什么由模型自己自然地写进对话即可。
    //
    // 顺带去掉 "Today is <日期>"：它每天变化，而工具描述处于请求前缀区，
    // 跨天复用同一对话时会让前缀缓存从这处分叉；酒馆模式也不需要它。
    val tavernMode = settings.huadengSettings.enableTavernMode
    val searchWebDescription = if (tavernMode) {
        """
        Search the web for up-to-date or specific information (latest news, current facts, verification).
        Inspect each result's title, URL, and date before making a current claim; if sources conflict or a
        primary source is missing, run another focused search or use scrape_web on the best source.

        Results: items[].id, index, title, url, publishedDate/highlights (if supplied), text.
        retrievedAt is retrieval time, never a publication date.
        """.trimIndent()
    } else {
        """
        Search the web for up-to-date or specific information (latest news, current facts, verification).
        Inspect each result's title, URL, and date before making a current claim; if sources conflict or a
        primary source is missing, run another focused search or use scrape_web on the best source.
        Today is ${LocalDate.now().toLocalString(true)}.

        Results: items[].id, index, title, url, publishedDate/highlights (if supplied), text.
        retrievedAt is retrieval time, never a publication date.
        Citations: after using a result, add `[citation,domain](id)` after the sentence; omit if none cited.
        Images: embed 2-4 relevant ones as `![](url)` at the start of your reply, only urls from images[].
        """.trimIndent()
    }
    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = searchWebDescription,
                parameters = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    service.parameters(options)
                },
                execute = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    ).getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                    val results =
                        JsonInstantPretty.encodeToJsonElement(result).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["items"] =
                                JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                    JsonObject(item.jsonObject.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                    })
                                })
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT })
        val service = SearchService.getService(options)
        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page, when search snippets are insufficient,
                        or when a current claim needs verification against a specific source.
                        Avoid using it for common questions unless the user asks.
                        """.trimIndent(),
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        service.scrapingParameters(options)
                    },
                    execute = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        ).getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                        val payload = JsonInstantPretty.encodeToJsonElement(result).jsonObject
                        // scrape 返回整页正文，必须截断（历史每轮重复计费）
                        listOf(UIMessagePart.Text(payload.toString().truncateForToolResult()))
                    }
                ))
        }
    }
}
