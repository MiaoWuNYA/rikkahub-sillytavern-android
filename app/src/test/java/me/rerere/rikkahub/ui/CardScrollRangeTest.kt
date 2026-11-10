package me.rerere.rikkahub.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 手势契约：面板容器不拦截触摸，滚动体只有 WebView/panel.html 自己。 */
class CardScrollRangeTest {

    private val src = File(
        "src/main/java/me/rerere/rikkahub/ui/components/richtext/HtmlWebViewBlock.kt"
    ).readText()

    @Test
    fun `panel container never intercepts the gesture`() {
        assertTrue(
            "容器必须放行所有手势，否则卡内按钮点不动",
            src.contains("override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false"),
        )
    }

    @Test
    fun `legacy scroll arbitration is gone`() {
        assertFalse("不得再用 scrollY 自己算区间", src.contains("scrollY"))
        assertFalse("不得再用 getContentHeight 混算像素", src.contains("getContentHeight"))
        assertFalse("不得再有 maxScroll 仲裁", src.contains("maxScroll"))
    }

    @Test
    fun `webview keeps native overscroll and stays out of the focus chain`() {
        assertTrue(src.contains("overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS"))
        assertTrue(src.contains("isVerticalScrollBarEnabled = false"))
        assertTrue(src.contains("isFocusableInTouchMode = false"))
    }

    @Test
    fun `panel page owns the scroll body`() {
        assertTrue(
            "唯一宿主页必须是 panel.html",
            src.contains("loadUrl(TavernRuntimeManager.BASE_URL + \"panel.html\")"),
        )
    }
}
