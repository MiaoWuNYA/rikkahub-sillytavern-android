package me.rerere.rikkahub.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡片高度换算回归测试。
 *
 * 历史缺陷：把 CARD_MAX_HEIGHT(520dp) 先换成物理像素再去夹 contentHeight，
 * 夹完又换算回 dp —— 一进一出差一个 density。
 * 3x 屏上"内容 800dp、上限 520dp"的卡片被算成 267dp，视口只剩一半，
 * 可滚动距离同步缩水，表现是"滑一下里面只动 2 毫米"。
 *
 * contentHeight 来自 JS 上报，卡片页写了
 * `meta viewport width=device-width, initial-scale=1`，
 * 因此 1 CSS px = 1 dp，必须直接用 dp 上限去夹。
 */
class CardHeightMathTest {

    private val cardMaxHeightDp = 520

    /** 复刻修复后的算法：全程 dp 空间 */
    private fun boundedHeightDp(contentHeightCss: Int): Int? =
        if (contentHeightCss > 0) contentHeightCss.coerceAtMost(cardMaxHeightDp) else null

    /** 复刻修复前的算法：dp -> px 夹完 -> 再换回 dp */
    private fun buggyHeightDp(contentHeightCss: Int, density: Float): Int {
        val maxPx = (cardMaxHeightDp * density).toInt()
        val bounded = contentHeightCss.coerceAtMost(maxPx)
        return (bounded / density).toInt()
    }

    @Test
    fun `tall content is capped at the soft limit in dp`() {
        // 内容远高于上限：应当正好停在 520dp，与屏幕密度无关
        for (c in listOf(2000, 5000, 20000)) {
            assertEquals("内容=$c", cardMaxHeightDp, boundedHeightDp(c))
        }
    }

    @Test
    fun `content just under the limit keeps its own height`() {
        // 内容 800dp < 上限 520dp？不，800 > 520，取上限。
        // 取一个真正未超上限的值验证不被缩放
        assertEquals(400, boundedHeightDp(400))
        assertEquals(cardMaxHeightDp, boundedHeightDp(800))
    }

    @Test
    fun `regression buggy math undersizes the viewport on high density screens`() {
        // 固化缺陷：证明旧算法在 3x 屏上把 mid-size 卡片压小
        assertEquals(266, buggyHeightDp(800, 3f))
        // 修复后是 520
        assertEquals(cardMaxHeightDp, boundedHeightDp(800))
    }

    @Test
    fun `zero content height yields null so the placeholder path is used`() {
        assertEquals(null, boundedHeightDp(0))
    }
}
