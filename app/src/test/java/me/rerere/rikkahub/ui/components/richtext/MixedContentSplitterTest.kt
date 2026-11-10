package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedContentSplitterTest {
    @Test
    fun plainMarkdown_isSingleBodyPiece() {
        val pieces = splitMixedMessagePieces("这是一段普通文字。\n\n- 列表项一\n- 列表项二")
        assertEquals(1, pieces.size)
        val body = pieces[0] as MixedContentPiece.MarkdownBody
        assertEquals(0, body.subIndex)
        assertTrue(body.text.contains("列表项一"))
    }

    @Test
    fun singlePanel_isOneHtmlPieceWithTitle() {
        val html = "<div class=\"status-card\"><style>.x{color:#fff}</style><h3>角色面板</h3><div>内容</div></div>"
        val pieces = splitMixedMessagePieces(html)
        assertEquals(1, pieces.size)
        val panel = pieces[0] as MixedContentPiece.HtmlPanel
        assertEquals("角色面板", panel.panelTitle)
        assertEquals(html, panel.html)
    }

    @Test
    fun mixedContent_splitsProseAndPanelInOrder() {
        val panel = "<div class=\"p\"><style>.p{color:#fff}</style><div>面板内容</div></div>"
        val pieces = splitMixedMessagePieces("正文一。\n\n" + panel + "\n\n正文二。")
        assertEquals(3, pieces.size)
        assertEquals("正文一。", (pieces[0] as MixedContentPiece.MarkdownBody).text)
        val html = pieces[1] as MixedContentPiece.HtmlPanel
        assertEquals(panel, html.html)
        assertEquals("预设渲染面板 #1", html.panelTitle)
        assertEquals("正文二。", (pieces[2] as MixedContentPiece.MarkdownBody).text)
        assertEquals(listOf(0, 1, 2), pieces.map { it.subIndex })
    }

    @Test
    fun panelTitle_derivationBranches() {
        assertEquals("天气面板", extractPanelDisplayName("<!-- panel: 天气面板 --><div>x</div>"))
        assertEquals("状态", extractPanelDisplayName("<div><title>状态</title></div>"))
        assertEquals("角色状态栏", extractPanelDisplayName("<div>stat_data</div>"))
        assertEquals("预设渲染面板 #3", extractPanelDisplayName("<div>x</div>", 2))
    }

    @Test
    fun nonInteractiveHtml_staysMarkdown() {
        val text = "参数占位 <div>短</div> 不是面板。"
        val pieces = splitMixedMessagePieces(text)
        assertEquals(1, pieces.size)
        assertEquals(text, (pieces[0] as MixedContentPiece.MarkdownBody).text)
        assertFalse(shouldSplitAsPanel("<div>短</div>"))
        assertFalse(looksLikeInteractiveHtml("<div>短</div>"))
    }

    @Test
    fun fencedCodeBlock_htmlInside_isNotSplit() {
        val text = "示例代码：\n\n```kotlin\nval s = \"<div>hi</div>\"\n```"
        val pieces = splitMixedMessagePieces(text)
        assertEquals(1, pieces.size)
        assertTrue((pieces[0] as MixedContentPiece.MarkdownBody).text.contains("kotlin"))
        assertFalse(shouldSplitAsPanel("val s = \"<div>hi</div>\""))
    }

    @Test
    fun fencedHtmlDocument_becomesPanelAfterProse() {
        val fence = "```"
        val text = "开场白正文\n\n" + fence + "html\n" +
            "<!DOCTYPE html>\n<html><body><div class=\"panel\"><h3>控制台</h3></div></body></html>\n" + fence
        val pieces = splitMixedMessagePieces(text)
        assertEquals(2, pieces.size)
        assertEquals("开场白正文", (pieces[0] as MixedContentPiece.MarkdownBody).text)
        val panel = pieces[1] as MixedContentPiece.HtmlPanel
        assertEquals("控制台", panel.panelTitle)
        assertTrue(panel.html.startsWith("<!DOCTYPE html>"))
        assertEquals(1, panel.subIndex)
    }

    @Test
    fun interactiveDetection_thresholdsBehave() {
        assertFalse(looksLikeInteractiveHtml("<div>x</div>"))
        assertTrue(looksLikeInteractiveHtml("<script>console.log('hi')</script>"))
        assertTrue(shouldRenderAsHtmlPanel("<div style=\"display:flex\"><span>a</span></div>"))
        assertFalse(looksLikeHtmlFragment("<div>hi</div>"))
    }

    @Test
    fun balancedTagEnd_handlesNestedSameTag() {
        val text = "<div><div>x</div></div>尾巴"
        val end = findBalancedTagEnd(text, 0, "div")
        assertEquals("<div><div>x</div></div>", text.substring(0, end))
    }
}
