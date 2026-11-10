package me.rerere.rikkahub.ui

import me.rerere.rikkahub.ui.components.richtext.computePanelHeightConstraint
import me.rerere.rikkahub.ui.components.richtext.gpuSafeMaxPanelHeightDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 卡片高度契约：panelResize 上报的 CSS px 直接当 dp 用，只受 48dp 下限与 GPU 纹理上限约束。 */
class CardHeightMathTest {

    private val src = File(
        "src/main/java/me/rerere/rikkahub/ui/components/richtext/HtmlWebViewBlock.kt"
    ).readText()

    @Test
    fun `small reported heights are used unchanged`() {
        for (c in listOf(48, 80, 400, 800, 1200)) {
            assertEquals("内容=$c 必须原样作为卡片高度", c to false, computePanelHeightConstraint(c, 3f))
        }
    }

    @Test
    fun `zero or negative heights fall back to the minimum`() {
        assertEquals(48 to false, computePanelHeightConstraint(0, 3f))
        assertEquals(48 to false, computePanelHeightConstraint(-1, 3f))
    }

    @Test
    fun `gpu texture cap clamps huge heights and says so`() {
        assertEquals(1365, gpuSafeMaxPanelHeightDp(3f))
        assertEquals(1600, gpuSafeMaxPanelHeightDp(1f))
        assertEquals(480, gpuSafeMaxPanelHeightDp(20f))
        assertEquals(1365 to true, computePanelHeightConstraint(5000, 3f))
        assertEquals(1365 to true, computePanelHeightConstraint(20000, 3f))
    }

    @Test
    fun `source keeps the reported height as the card height`() {
        assertTrue(
            "panelResize 上报的高度必须经 computePanelHeightConstraint 落地",
            src.contains("computePanelHeightConstraint(raw, density)"),
        )
        assertTrue(
            "高度必须写进 PanelHeightCache",
            src.contains("PanelHeightCache.put(panelKey, contentHash, bounded)"),
        )
        assertTrue("高度按 dp 直接生效", src.contains("Modifier.height(heightDp.dp)"))
        assertFalse("旧的软上限常量必须消失", src.contains("CARD_MAX_HEIGHT"))
        assertFalse("不得再依赖旧的 contentHeight 测量", src.contains("contentHeight.dp"))
    }
}
