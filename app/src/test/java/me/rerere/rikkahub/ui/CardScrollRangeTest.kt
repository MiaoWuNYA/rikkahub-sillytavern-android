package me.rerere.rikkahub.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 卡片可滚区间的量纲契约。
 *
 * 这里曾出过一个只有在特定内容高度下才暴露的 bug：判定用了外层上报的
 * CSS 高度（dp 语义）去和 WebView 的物理像素高度比较。3x 屏上内容
 * 600dp 的卡片被判成「没有可滚区间」，于是按下时不抢手势，外层列表
 * 把手势整段截走 —— 表现就是「弹一下翻一点、然后又翻不动」。
 *
 * 因为一旦退回混算就会重新引入这个现象，这里用源码断言把契约钉住。
 */
class CardScrollRangeTest {

    private val src = File(
        "src/main/java/me/rerere/rikkahub/ui/components/richtext/HtmlWebViewBlock.kt"
    ).readText()

    @Test
    fun `scrollable range uses the webview's own pixel metrics`() {
        assertTrue(
            "可滚区间必须用 getContentHeight()（与 getHeight/scrollY 同为物理像素）",
            src.contains("(contentHeight - height).coerceAtLeast(0)"),
        )
    }

    @Test
    fun `boundary comparison never mixes reported css height with pixels`() {
        // 这条就是历史 bug 本身：contentHeight 是 dp、height 是 px，
        // 直接相减再和 scrollY 比会得到量纲错误的边界
        assertFalse(
            "不得再用上报的 CSS 高度与物理像素混算边界",
            src.contains("scrollY >= contentHeight - height"),
        )
    }

    @Test
    fun `gesture arbitration always delegates to the platform webview`() {
        // 绝不自己实现滚动：拖动/惯性/边界吸附都是 WebView 原生 OverScroller 的活，
        // 覆写只是为了让祖先在合适的时候重新获得拦截权
        assertTrue(
            "必须无条件交给 super.onTouchEvent",
            src.contains("return super.onTouchEvent(event)"),
        )
    }

    @Test
    fun `short cards never block the message list`() {
        // 没有可滚区间时必须完全放行，否则短卡片上滑不动列表
        assertTrue(
            "无滚区间时应放行祖先拦截",
            src.contains("if (maxScroll <= 0) {"),
        )
    }
}
