package me.rerere.rikkahub.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 卡片高度契约回归测试。
 *
 * 两代历史缺陷，都出在「上限」上：
 * 1) 最早把 520dp 先换物理像素再去夹 contentHeight（dp）—— 差一个 density，
 *    3x 屏上 800dp 内容被算成 267dp，"滑一下里面只动 2 毫米"；
 * 2) 修好换算后又留了个 520dp 软上限 —— 卡内 WebView 与外层列表成了
 *    两个滚动体，LazyColumn 走指针消费、不走原生拦截链，
 *    requestDisallowInterceptTouchEvent 仲裁不可靠，
 *    "弹一下翻一点、然后又翻不动"由此而来。
 *
 * 现在的契约：卡片高度 = 内容全高（dp），**没有上限**，
 * 内嵌滚动不存在，唯一滚动体是外层列表 —— 与官方酒馆一致。
 */
class CardHeightMathTest {

    /** 现行算法：全高内联，内容多少 dp 就多高 */
    private fun cardHeightDp(contentHeightCss: Int): Int? =
        if (contentHeightCss > 0) contentHeightCss else null

    @Test
    fun `card height always equals full content height - no cap`() {
        for (c in listOf(80, 400, 800, 2000, 5000, 20000)) {
            assertEquals("内容=$c 必须原样作为卡片高度", c, cardHeightDp(c)!!)
        }
    }

    @Test
    fun `zero or unset content keeps the min-height placeholder`() {
        assertEquals(null, cardHeightDp(0))
        assertEquals(null, cardHeightDp(-1))
    }

    @Test
    fun `source no longer contains the soft cap`() {
        val src = File(
            "src/main/java/me/rerere/rikkahub/ui/components/richtext/HtmlWebViewBlock.kt"
        ).readText()
        org.junit.Assert.assertFalse(
            "不得再对卡片高度做任何夹取（内嵌滚动正是滑动冲突的根源）",
            src.contains("coerceAtMost(CARD_MAX_HEIGHT"),
        )
        org.junit.Assert.assertFalse(
            "CARD_MAX_HEIGHT 常量应已删除",
            src.contains("CARD_MAX_HEIGHT"),
        )
        org.junit.Assert.assertTrue(
            "卡片高度必须直接使用内容高度",
            src.contains("contentHeight.dp"),
        )
    }
}
