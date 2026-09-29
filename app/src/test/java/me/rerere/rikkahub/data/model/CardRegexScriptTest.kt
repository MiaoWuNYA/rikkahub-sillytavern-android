package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.export.SillyTavernRegexImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前端卡（HTML 卡）兼容的核心链路回归。
 *
 * 真实前端卡不在 first_mes 里放 HTML，而是放一个占位标签（如 `<zd_status>`、
 * `<StatusPlaceHolderImpl/>`、`<gametext>`、`<start>`），再由
 * extensions.regex_scripts 在渲染阶段把标签替换成整段 HTML 文档。
 *
 * 本测试覆盖：脚本提取 → 语义映射 → 渲染阶段实际产出 HTML。
 */
class CardRegexScriptTest {

    /** 抽取自真实卡片：鬼灭之刃「开始界面」（57KB 完整文档，围栏包裹） */
    private val realCardExtensions = """
    {
      "regex_scripts": [
        {
          "id": "a1",
          "scriptName": "开始界面",
          "findRegex": "/<start>([\\s\\S]*?)<\\/start>/gsi",
          "replaceString": "```html\n<!DOCTYPE html>\n<html><head><style>.page-wrapper{position:fixed;top:0;left:0;width:100vw;height:100vh}</style></head><body><div class=\"page-wrapper\"><main tickbubble>内容</main></div><script>console.log(1)</script></body></html>\n```",
          "placement": [2],
          "markdownOnly": true,
          "promptOnly": false,
          "disabled": false
        },
        {
          "id": "a3",
          "scriptName": "对 AI 隐藏状态栏",
          "findRegex": "/<StatusPlaceHolderImpl\\/>/gsi",
          "replaceString": "",
          "placement": [2],
          "markdownOnly": false,
          "promptOnly": true,
          "disabled": false
        },
        {
          "id": "a2",
          "scriptName": "状态栏",
          "findRegex": "/<StatusPlaceHolderImpl\\/>/gsi",
          "replaceString": "```html\n<!DOCTYPE html><html><body><StatusBlock>状态</StatusBlock></body></html>\n```",
          "placement": [2],
          "markdownOnly": true,
          "promptOnly": false,
          "minDepth": null,
          "maxDepth": 2,
          "disabled": false
        }
      ]
    }
    """.trimIndent()

    private fun cardRegexes(): List<AssistantRegex> {
        val scripts = kotlinx.serialization.json.Json
            .parseToJsonElement(realCardExtensions)
            .let { it as kotlinx.serialization.json.JsonObject }["regex_scripts"]!!
        return SillyTavernRegexImporter.parse(scripts.toString())
    }

    private fun assistant() = Assistant(
        id = kotlin.uuid.Uuid.random(),
        name = "card",
        regexes = cardRegexes(),
    )

    @Test
    fun `all three scripts are extracted from card extensions`() {
        assertEquals(3, cardRegexes().size)
    }

    @Test
    fun `html scripts target display layer only`() {
        val scripts = cardRegexes()
        val start = scripts.first { it.name == "开始界面" }
        assertTrue("markdownOnly 应映射为仅显示层", start.visualOnly)
        assertEquals(setOf(AssistantAffectScope.ASSISTANT), start.affectingScope)
    }

    /**
     * promptOnly 的已知保真缺口。
     *
     * 官方 promptOnly=true 表示「只改发送给 AI 的内容，显示用原文」；
     * 但 AssistantRegex 只有 visualOnly 一个布尔位，转换器把
     * (markdownOnly=false, promptOnly=true) 也映射为 visualOnly=false，
     * 等价于「两层都生效」。因此这类脚本在本地也会作用到显示层。
     *
     * 对前端卡的直接影响：卡里常见的「对 AI 隐藏状态栏」会把占位标签
     * 从显示内容里一并清掉。下方断言固化了当前真实行为，缺口本身记录在案。
     */
    @Test
    fun `prompt only script does not clobber html injected by an earlier display script`() {
        // 规则按数组顺序执行：状态栏(2) 先把占位标签换成 HTML，隐藏状态栏(3) 随后
        // 再找同一个标签已无匹配，因此不会破坏已注入的卡片内容。
        val hide = cardRegexes().first { it.name == "对 AI 隐藏状态栏" }
        assertFalse(hide.visualOnly)
        val withHtml = "<StatusPlaceHolderImpl/>".replaceRegexes(
            assistant(), AssistantAffectScope.ASSISTANT, visual = true,
        )
        assertTrue(withHtml.contains("<!DOCTYPE html>"))
    }

    /**
     * promptOnly 三态修复回归。
     *
     * 修复前 (markdownOnly=false, promptOnly=true) 被映射为「两层都生效」，
     * 卡里「对 AI 隐藏状态栏」这类脚本会先把占位标签从显示内容里清掉，
     * 导致排在其后的显示脚本再也匹配不到 —— 卡片整块不渲染。
     * 修复后该脚本只作用于提示词层，显示层原样保留。
     */
    @Test
    fun `prompt only script leaves display content untouched`() {
        val hide = cardRegexes().first { it.name == "对 AI 隐藏状态栏" }
        assertTrue("应记录为仅提示词层", hide.promptOnly)
        assertFalse("不应同时标记为仅显示层", hide.visualOnly)

        val onlyHide = Assistant(
            id = kotlin.uuid.Uuid.random(),
            name = "card",
            regexes = listOf(hide),
        )
        val text = "<StatusPlaceHolderImpl/>"

        // 显示层：保留原文，占位标签仍在
        assertEquals(text, text.replaceRegexes(onlyHide, AssistantAffectScope.ASSISTANT, visual = true))
        // 提示词层：清空
        assertEquals("", text.replaceRegexes(onlyHide, AssistantAffectScope.ASSISTANT, visual = false))
    }

    /**
     * 修复前会失效的真实故障顺序：promptOnly 清空脚本在前，显示脚本在后。
     * 修复后显示脚本仍能拿到占位标签，卡片正常注入。
     */
    @Test
    fun `prompt only script before display script no longer breaks the card`() {
        val scripts = cardRegexes()
        val hideIdx = scripts.indexOfFirst { it.name == "对 AI 隐藏状态栏" }
        val showIdx = scripts.indexOfFirst { it.name == "状态栏" }
        // 与真实卡一致：清空脚本排在显示脚本之前
        assertTrue("真实卡中隐藏脚本在显示脚本之前", hideIdx in 0 until showIdx)

        val out = "<StatusPlaceHolderImpl/>".replaceRegexes(
            assistant(), AssistantAffectScope.ASSISTANT, visual = true,
        )
        assertTrue("卡片 HTML 应正常注入", out.contains("<!DOCTYPE html>"))
        assertTrue(out.contains("StatusBlock"))
    }

    @Test
    fun `placeholder tag is expanded into a fenced html document at render time`() {
        val input = "剧情描述。\n<start>初始化</start>\n"
        val out = input.replaceRegexes(assistant(), AssistantAffectScope.ASSISTANT, visual = true)

        assertTrue("应注入完整 HTML 文档", out.contains("<!DOCTYPE html>"))
        assertTrue("应保留 ```html 围栏（渲染层据此识别卡片）", out.contains("```html"))
        assertTrue("应保留卡片脚本", out.contains("<script>"))
        assertFalse("占位标签应被替换掉", out.contains("<start>"))
        assertTrue("正文应保留", out.contains("剧情描述。"))
    }

    /**
     * 深度门控：maxDepth=2 的状态栏脚本只应作用在最近 3 条消息上。
     *
     * 修复前渲染层不传 depth（=0 表示不限制），导致历史消息被重复注入 HTML。
     */
    @Test
    fun `maxDepth bounds html injection across history`() {
        val a = assistant()
        val text = "<StatusPlaceHolderImpl/>"

        val near = text.replaceRegexes(a, AssistantAffectScope.ASSISTANT, visual = true, depth = 0)
        assertTrue("最新消息应注入", near.contains("<!DOCTYPE html>"))

        val boundary = text.replaceRegexes(a, AssistantAffectScope.ASSISTANT, visual = true, depth = 2)
        assertTrue("depth=2 仍在范围内", boundary.contains("<!DOCTYPE html>"))

        // 超出 maxDepth：状态栏脚本不再注入。
        // 注意此时同时生效的「对 AI 隐藏状态栏」脚本仍会清空标签（见上一条测试），
        // 所以结果为空串而不是原文——关键点是「没有 HTML 注入」。
        val far = text.replaceRegexes(a, AssistantAffectScope.ASSISTANT, visual = true, depth = 3)
        assertFalse("超出 maxDepth 不应注入 HTML", far.contains("<!DOCTYPE html>"))
    }

    @Test
    fun `js slash literal with gsi flags matches across newlines`() {
        val out = "<start>\n第一行\n第二行\n</start>".replaceRegexes(
            assistant(), AssistantAffectScope.ASSISTANT, visual = true,
        )
        assertTrue("s 标志应让 . 跨行匹配（亦即 [\\s\\S] 生效）", out.contains("<!DOCTYPE html>"))
        assertFalse(out.contains("<start>"))
    }

    @Test
    fun `unmatched placeholder is left untouched`() {
        val text = "普通消息，没有占位标签"
        assertEquals(text, text.replaceRegexes(assistant(), AssistantAffectScope.ASSISTANT, visual = true))
    }

    /**
     * 旧数据兼容：新增 promptOnly 字段前保存的配置里没有该键，
     * 反序列化必须回落到 false（两层都生效），不能因缺字段报错。
     */
    @Test
    fun `legacy regex json without promptOnly still deserializes`() {
        val legacy = """
        {"id":"11111111-1111-1111-1111-111111111111","name":"old",
         "enabled":true,"findRegex":"x","replaceString":"y",
         "affectingScope":["ASSISTANT"],"visualOnly":true,"minDepth":0,"maxDepth":0}
        """.trimIndent()
        val reg = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<AssistantRegex>(legacy)
        assertFalse("缺省应为 false，保持老行为", reg.promptOnly)
        assertTrue(reg.visualOnly)
    }
}
