package me.rerere.rikkahub.ui

import me.rerere.rikkahub.ui.components.richtext.PanelHeightCache
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 高度缓存：键是 "面板键#内容哈希"，失效按面板键前缀清，容量 64 条。 */
class PanelHeightCacheTest {

    @Before
    fun clearCache() {
        PanelHeightCache.clear()
    }

    @Test
    fun `height is stored per panel key and content hash`() {
        PanelHeightCache.put("panel#1", 11, 800)
        assertEquals(800, PanelHeightCache.get("panel#1", 11))
        assertEquals(0, PanelHeightCache.get("panel#1", 12))
        assertEquals(0, PanelHeightCache.get("panel#2", 11))
    }

    @Test
    fun `default content hash reads back the stored height`() {
        PanelHeightCache.put("panel#1", 0, 120)
        assertEquals(120, PanelHeightCache.get("panel#1"))
    }

    @Test
    fun `non positive heights are ignored`() {
        PanelHeightCache.put("panel#1", 11, 0)
        PanelHeightCache.put("panel#1", 11, -5)
        assertEquals(0, PanelHeightCache.get("panel#1", 11))
    }

    @Test
    fun `invalidateForMessage drops every hash of one key only`() {
        PanelHeightCache.put("panel#1", 0, 100)
        PanelHeightCache.put("panel#1", 7, 200)
        PanelHeightCache.put("panel#10", 0, 300)

        PanelHeightCache.invalidateForMessage("panel#1")

        assertEquals(0, PanelHeightCache.get("panel#1", 0))
        assertEquals(0, PanelHeightCache.get("panel#1", 7))
        assertEquals(300, PanelHeightCache.get("panel#10", 0))
    }

    @Test
    fun `cache is bounded to sixty four entries`() {
        repeat(PanelHeightCache.MAX_ENTRIES + 6) { i ->
            PanelHeightCache.put("panel#" + i, 0, i + 1)
        }
        assertEquals(64, PanelHeightCache.size())
    }
}
