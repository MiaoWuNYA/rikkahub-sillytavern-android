package me.rerere.rikkahub.ui

import me.rerere.rikkahub.ui.components.richtext.parseCardDocument
import me.rerere.rikkahub.ui.components.richtext.scopeCardCss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片 CSS 作用域隔离测试——对齐官方 `decodeStyleTags` 的语义：
 * 卡片样式必须被限制在卡片内部，不能污染外层聊天页面。
 */
class HtmlCardCssTest {
    private val root = "#rikka-card-root"

    @Test
    fun `plain selector gets root prefix`() {
        val out = scopeCardCss(".status { color: red; }")
        assertEquals("$root .status{ color: red; }", out)
    }

    @Test
    fun `body and html map to isolated root`() {
        val out = scopeCardCss("body { background: #000; }")
        assertTrue(out.startsWith(root))
        assertFalse(out.contains("body {"))
    }

    @Test
    fun `selector list is scoped per element`() {
        val out = scopeCardCss("h1, h2, .title { margin: 0; }")
        assertTrue(out.contains("$root h1"))
        assertTrue(out.contains("$root h2"))
        assertTrue(out.contains("$root .title"))
    }

    @Test
    fun `universal selector scopes to root children`() {
        val out = scopeCardCss("* { box-sizing: border-box; }")
        assertTrue(out.contains(root))
    }

    @Test
    fun `media query keeps at-rule and scopes inner rules`() {
        val out = scopeCardCss("@media (max-width: 600px) { .a { color: red; } }")
        assertTrue(out.startsWith("@media (max-width: 600px)"))
        assertTrue(out.contains("$root .a"))
    }

    @Test
    fun `import statement is stripped`() {
        val out = scopeCardCss("@import url('http://evil/x.css'); .a { color: red; }")
        assertFalse(out.contains("@import"))
        assertFalse(out.contains("evil"))
    }

    @Test
    fun `comments are removed`() {
        val out = scopeCardCss("/* note */ .a { color: red; }")
        assertFalse(out.contains("note"))
        assertTrue(out.contains("$root .a"))
    }

    @Test
    fun `nested at-rule recursion preserves declarations`() {
        val out = scopeCardCss("@supports (display: grid) { .grid { display: grid; } }")
        assertTrue(out.contains("@supports"))
        assertTrue(out.contains("display: grid"))
        assertTrue(out.contains("$root .grid"))
    }

    @Test
    fun `pseudo class with commas is not split`() {
        val out = scopeCardCss(":is(.a, .b) { color: red; }")
        // 顶层逗号才切分，:is(...) 内部的逗号保留
        assertEquals(1, Regex("$root").findAll(out).count())
    }

    @Test
    fun `parse card document extracts styles and body`() {
        val doc = """
            <!DOCTYPE html>
            <html>
            <head><style>.a{color:red}</style><meta name="x" content="y"></head>
            <body><div class="a">hi</div></body>
            </html>
        """.trimIndent()
        val parsed = parseCardDocument(doc)
        assertTrue(parsed.styleCss.contains(".a{color:red}"))
        assertTrue(parsed.bodyHtml.contains("""<div class="a">hi</div>"""))
        // style 已从 head 里剥掉，避免重复注入
        assertFalse(parsed.headHtml.contains("<style"))
    }

    @Test
    fun `document without body falls back to whole content`() {
        val doc = "<!DOCTYPE html><html><head></head><div>only div</div></html>"
        val parsed = parseCardDocument(doc)
        assertTrue(parsed.bodyHtml.contains("only div"))
    }



}
