package me.rerere.rikkahub

import me.rerere.rikkahub.ui.components.richtext.parseCardDocument
import me.rerere.rikkahub.ui.components.richtext.splitCardSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前端卡文档拆解：head 只许留下能生效的标签，title/meta 不得漏进正文。
 *
 * 真机反馈「页面上出现 Peach iPhone UI Popup」即 `<title>` 被当正文渲染，
 * 输入取自 酒馆500+美化/1.png 的 chara.first_mes（位置 146）。
 */
class CardDocumentTest {

    private val peachTitle = "Peach iPhone UI Popup"

    @Test
    fun `head 只保留 script 与 link，title 与 meta 不得进正文`() {
        val doc = normalize(
            """
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>$peachTitle</title>
              <link href="https://fonts.example/css" rel="stylesheet">
              <script>var a = 1 < 2;</script>
              <style>body { background: #fff9e5; }</style>
            </head>
            <body><mainbody>开场白正文</mainbody></body>
            </html>
            """.trimIndent(),
        )

        val parsed = parseCardDocument(doc)
        assertFalse("title 文本不得进 head", parsed.headHtml.contains(peachTitle))
        assertFalse("title 文本不得进正文", parsed.bodyHtml.contains(peachTitle))
        assertFalse("head 里不得留 style（样式单列）", parsed.headHtml.contains("<style"))
        assertFalse("head 里不得留 meta", parsed.headHtml.contains("<meta"))
        assertTrue("link 必须保留", parsed.headHtml.contains("<link"))
        assertTrue("script 必须保留", parsed.headHtml.contains("<script"))
        assertTrue("自带样式必须抽到 styleCss", parsed.styleCss.contains("background: #fff9e5"))
        assertTrue("正文保留", parsed.bodyHtml.contains("开场白正文"))
    }

    @Test
    fun `无 body 外壳时也要剥掉 head，正文不含 title`() {
        val doc = normalize(
            """
            <html lang="en">
            <head>
              <title>$peachTitle</title>
              <style>.a { color: red; }</style>
            </head>
            <mainbody>只有自定义标签的正文</mainbody>
            </html>
            """.trimIndent(),
        )

        val parsed = parseCardDocument(doc)
        assertFalse("正文不得含 title", parsed.bodyHtml.contains(peachTitle))
        assertFalse("正文不得含 head 壳", parsed.bodyHtml.contains("<head"))
        assertTrue("正文保留", parsed.bodyHtml.contains("只有自定义标签的正文"))
    }

    @Test
    fun `head 没写闭合标签且没有 body 时 title 同样不得漏进正文`() {
        val doc = normalize(
            """
            <html lang="en">
            <head>
              <title>$peachTitle</title>
              <style>.a { color: red; }</style>
            <mainbody>正文</mainbody>
            </html>
            """.trimIndent(),
        )

        val parsed = parseCardDocument(doc)
        assertFalse("正文不得含 title", parsed.bodyHtml.contains(peachTitle))
        assertTrue("正文保留", parsed.bodyHtml.contains("正文"))
    }

    @Test
    fun `卡片段拆分：围栏整份文档被切成单个卡片段`() {
        val text = "开场白：\n```html\n<html><head><title>$peachTitle</title></head>" +
            "<body><mainbody>x</mainbody></body></html>\n```\n"
        val segments = splitCardSegments(normalize(text))
        val cards = segments.filter { it.first }
        assertEquals("围栏文档只应产出 1 个卡片段", 1, cards.size)
        assertTrue("卡片段含正文", cards.first().second.contains("mainbody"))
        assertFalse("标题不得落进散文段", segments.filterNot { it.first }.any { it.second.contains(peachTitle) })
    }

    private fun normalize(text: String) = text.replace("\r\n", "\n").replace("\r", "\n")
}
