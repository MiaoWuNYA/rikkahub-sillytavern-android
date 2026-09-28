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
    fun `mixed prose plus html fragment splits at first block tag`() {
        val text = "开场白散文\n\n<div class=\"status\">面板</div>"
        val (start, html) = findHtmlCard(text)!!
        assertEquals("开场白散文", text.substring(0, start).trim())
        assertTrue(html.startsWith("<div"))
    }

    @Test
    fun `fenced card without closing fence falls back to null`() {
        assertNull(findFencedHtmlDocument("```html\n<!DOCTYPE html><html></html>"))
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
