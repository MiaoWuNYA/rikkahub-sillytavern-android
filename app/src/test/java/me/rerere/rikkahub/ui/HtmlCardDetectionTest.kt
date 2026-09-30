package me.rerere.rikkahub.ui

import me.rerere.rikkahub.ui.components.richtext.findFencedHtmlDocument
import me.rerere.rikkahub.ui.components.richtext.findHtmlCard
import me.rerere.rikkahub.ui.components.richtext.isFullHtmlDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlCardDetectionTest {
    // 模拟酒馆前端卡 first_mes：```html 围栏 + \r\n 行尾 + 完整 HTML 文档，
    // 文档内部嵌 ```yaml 围栏（状态面板常见），末尾围栏后还有散落文字
    private val frontendCard = "``` html\r\n<!DOCTYPE html>\r\n<html>\r\n<head>\r\n<style>.a{}</style>\r\n</head>\r\n<body>\r\n<div class=\"status\">\r\n```yaml\r\n状态: ok\r\n```\r\n</div>\r\n<script>console.log(1)</script>\r\n</body>\r\n</html>\r\n``` \r\n"

    @Test
    fun `fenced card extracts full document without fence and prose`() {
        val (prose, doc) = findFencedHtmlDocument(frontendCard)!!
        assertTrue(prose.isEmpty())
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.endsWith("</html>"))
        // 内嵌 yaml 围栏不能截断文档
        assertTrue(doc.contains("console.log(1)"))
    }

    @Test
    fun `fenced card doc has no crlf`() {
        val (_, doc) = findFencedHtmlDocument(frontendCard)!!
        assertTrue(!doc.contains("\r"))
    }

    @Test
    fun `isFullHtmlDocument matches doctype and html tag`() {
        assertTrue(isFullHtmlDocument("<!DOCTYPE html><html></html>"))
        assertTrue(isFullHtmlDocument("\n<html><body></body></html>"))
        assertTrue(isFullHtmlDocument("<HTML>"))
    }

    @Test
    fun `plain markdown or fragment is not full document`() {
        assertNull(findFencedHtmlDocument("正常文本 ```html 代码演示\n<div>x</div>"))
        assertNull(findFencedHtmlDocument("<div>状态面板</div>")) // 片段不是完整文档
    }

    @Test
    fun `mixed prose plus real card fragment splits at first block tag`() {
        // 开场白 + 一张真卡（多块级标签 + class），应拆出散文并渲染卡片
        val text = """
            开场白散文

            <div class="status-panel">
              <div class="row"><span class="k">时间</span><span class="v">黄昏</span></div>
              <div class="row"><span class="k">地点</span><span class="v">港口</span></div>
            </div>
        """.trimIndent()
        val (start, html) = findHtmlCard(text)!!
        assertEquals("开场白散文", text.substring(0, start).trim())
        assertTrue(html.startsWith("<div"))
    }

    @Test
    fun `a lone div in prose is not promoted to a card`() {
        // 旧实现只要行首是标签就渲染成网页，导致正常回复整条变成 WebView
        val text = "开场白散文\n\n<div class=\"status\">面板</div>"
        assertNull(findHtmlCard(text))
    }

    @Test
    fun `self-invented placeholders are not cards`() {
        // 注入类插件要求模型「缺失细节自造占位符」，模型会写出 <TARGET> 这类角标，
        // 旧实现会把它当成卡片起点，整条回复变成网页
        assertNull(findHtmlCard("连接 <TARGET> 的 <PORT> 端口即可。"))
        assertNull(findHtmlCard("第一步\n<TARGET>\n第二步"))
    }

    @Test
    fun `fenced html inside explanatory prose is not a card`() {
        // 说明性代码块只有片段，不是完整文档，不应渲染成网页
        val text = "这是说明：\n\n```html\n<div>小片段</div>\n```"
        assertNull(findFencedHtmlDocument(text))
    }

    @Test
    fun `fenced card without closing fence still renders as document`() {
        // 真实卡常因截断/流式输出缺少闭合围栏；此时退化为"到文本末尾"，
        // 而不是整段退回 Markdown 把源码显示出来
        val (_, doc) = findFencedHtmlDocument("```html\n<!DOCTYPE html><html></html>")!!
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.endsWith("</html>"))
    }

    @Test
    fun `prose after closing fence is excluded from document`() {
        val text = "```html\n<!DOCTYPE html><html><body>x</body></html>\n```\n补充说明文字"
        val (prose, doc) = findFencedHtmlDocument(text)!!
        assertEquals("", prose)
        assertEquals("<!DOCTYPE html><html><body>x</body></html>", doc)
    }

    @Test
    fun `non-html fenced block is not treated as card`() {
        val text = "```html\nprintln(\"hello\")\n```\n说明"
        assertNull(findFencedHtmlDocument(text))
    }

    @Test
    fun `prose before fenced card is preserved`() {
        val text = "前面的话\n```html\n<!DOCTYPE html><html></html>\n```"
        val (prose, _) = findFencedHtmlDocument(text)!!
        assertEquals("前面的话", prose)
    }
}
