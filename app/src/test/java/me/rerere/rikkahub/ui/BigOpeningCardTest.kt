package me.rerere.rikkahub.ui

import me.rerere.rikkahub.ui.components.richtext.findFencedHtmlDocument
import me.rerere.rikkahub.ui.components.richtext.splitCardSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大型前端角色卡（`v0.8.9.19__R6.4_.png` 这类）的开场白渲染。
 *
 * 真实数据：first_mes 长 93915 字符，整体是一个 ```html 围栏包裹的完整文档，
 * 标题「玄胤 · 入局簿」，内含 <style>/<script> 与交互路由，
 * 卡内会自行生成开场白界面。这种卡必须走"整条消息即一张卡"的路径。
 */
class BigOpeningCardTest {

    /** 按真实卡片的骨架构造：围栏 + 完整文档 + 内嵌脚本路由 */
    private fun bigCard(bodyRepeat: Int = 40): String = buildString {
        append("```html\n")
        append("<!DOCTYPE html>\n")
        append("<html lang=\"zh-Hans\"><head><meta charset=\"UTF-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">")
        append("<title>玄胤 · 入局簿</title>")
        append("<style id=\"opening-mobile-baseline\">:root{--night:#0c1015;--gold:#cdb27c}</style>")
        append("</head><body>")
        repeat(bodyRepeat) { i -> append("<div class=\"hero\" id=\"hero$i\">入局簿 $i</div>") }
        append("<script>var selectRoute;(function(){selectRoute=function(r){return r;};})();</script>")
        append("</body></html>\n")
        append("```")
    }

    @Test
    fun `fenced full document becomes a single card with no prose`() {
        val (prose, doc) = findFencedHtmlDocument(bigCard())!!
        assertTrue("围栏外没有正文", prose.isEmpty())
        assertTrue("文档以 DOCTYPE 开头", doc.startsWith("<!DOCTYPE html>"))
        assertTrue("文档完整闭合", doc.trimEnd().endsWith("</html>"))
    }

    @Test
    fun `card keeps its title and interactive script`() {
        val (_, doc) = findFencedHtmlDocument(bigCard())!!
        assertTrue("标题保留", doc.contains("玄胤 · 入局簿"))
        assertTrue("交互脚本保留（卡内据此生成开场白）", doc.contains("selectRoute"))
    }

    @Test
    fun `card content is not truncated`() {
        val (_, doc) = findFencedHtmlDocument(bigCard())!!
        // 40 个 hero 块一个不能少
        for (i in 0 until 40) {
            assertTrue("缺少 hero$i", doc.contains("id=\"hero$i\""))
        }
    }

    @Test
    fun `a very large opening is parsed without loss`() {
        // 真实卡片 first_mes 长 93915 字符；这里取一个同量级甚至更大的样例，
        // 确认超长文档不会被截断（曾担心正则回溯或长度上限）。
        val fm = bigCard(bodyRepeat = 5000)
        assertTrue("样例应当超过真实卡片量级", fm.length > 100_000)
        val (_, doc) = findFencedHtmlDocument(fm)!!
        assertTrue("末尾内容仍在", doc.contains("id=\"hero4999\""))
        assertTrue("开头内容仍在", doc.contains("id=\"hero0\""))
    }

    @Test
    fun `the fenced card is not additionally split into segments`() {
        // 围栏完整文档交给 findFencedHtmlDocument 处理，
        // 不应再走 splitCardSegments —— 否则会被拆成大量碎片。
        val fm = bigCard()
        assertNotNull(findFencedHtmlDocument(fm))
        val segs = splitCardSegments(fm)
        assertEquals("整条消息应作为单一卡片", 1, segs.count { it.first })
    }
}
