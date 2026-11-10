package me.rerere.rikkahub.ui

import me.rerere.rikkahub.ui.components.richtext.PanelMountState
import me.rerere.rikkahub.ui.components.richtext.PanelUpdateAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 面板更新决策与 48ms 合并：判据必须与参考实现逐条一致。 */
class PanelMountStateTest {

    @Test
    fun `first mount always reloads the panel document`() {
        val st = PanelMountState()
        assertEquals(PanelUpdateAction.RELOAD_DOCUMENT, st.decideUpdateAction("<div>a</div>"))
        assertEquals(1, st.reloadCount)
        assertEquals("<div>a</div>", st.mountedHtml)
    }

    @Test
    fun `identical content is a no-op`() {
        val st = PanelMountState()
        st.decideUpdateAction("<div>a</div>")
        assertEquals(PanelUpdateAction.NO_OP, st.decideUpdateAction("<div>a</div>"))
        assertEquals(1, st.reloadCount)
    }

    @Test
    fun `any script on either side forces a full reload`() {
        val st = PanelMountState()
        assertEquals(PanelUpdateAction.RELOAD_DOCUMENT, st.decideUpdateAction("<div>a<script>1</script></div>"))
        assertEquals(PanelUpdateAction.RELOAD_DOCUMENT, st.decideUpdateAction("<div>b</div>"))
        assertEquals(2, st.reloadCount)

        val fresh = PanelMountState()
        assertEquals(PanelUpdateAction.RELOAD_DOCUMENT, fresh.decideUpdateAction("<div>a</div>"))
        assertEquals(
            PanelUpdateAction.RELOAD_DOCUMENT,
            fresh.decideUpdateAction("<div>b<script src=\"x.js\"></script></div>"),
        )
        assertEquals(0, fresh.inPlaceRemountCount)
    }

    @Test
    fun `scriptless content remounts in place`() {
        val st = PanelMountState()
        st.decideUpdateAction("<div>a</div>")
        assertEquals(PanelUpdateAction.REMOUNT_IN_PLACE, st.decideUpdateAction("<div>b</div>"))
        assertEquals(1, st.inPlaceRemountCount)
        assertEquals(1, st.reloadCount)
        assertEquals("<div>b</div>", st.mountedHtml)
    }

    @Test
    fun `reload generation bump rebuilds the page`() {
        val st = PanelMountState()
        st.decideUpdateAction("<div>a</div>", reloadGen = 7)
        assertEquals(PanelUpdateAction.NO_OP, st.decideUpdateAction("<div>a</div>", reloadGen = 7))
        assertEquals(PanelUpdateAction.RELOAD_DOCUMENT, st.decideUpdateAction("<div>a</div>", reloadGen = 8))
        assertEquals(2, st.reloadCount)
    }

    @Test
    fun `streaming updates inside the 48ms window are coalesced`() {
        val st = PanelMountState()
        var now = 1_000L
        st.clockMsProvider = { now }
        st.simulateMountForTest("<div>a</div>")

        st.submit("<div>b</div>", "#000000", "panel#1")
        assertFalse(st.hasPendingCoalescedRemount)

        now = 1_020L
        st.submit("<div>c</div>", "#000000", "panel#1")
        assertTrue("48ms 内的连续更新必须合并成一次重挂", st.hasPendingCoalescedRemount)

        now = 1_200L
        st.submit("<div>d</div>", "#000000", "panel#1")
        assertFalse("窗口过期后必须立刻挂载", st.hasPendingCoalescedRemount)
    }

    @Test
    fun `identical submit while mounted short-circuits`() {
        val st = PanelMountState()
        var now = 1_000L
        st.clockMsProvider = { now }
        st.simulateMountForTest("<div>a</div>")

        st.submit("<div>a</div>", "#000000", "panel#1")
        now = 1_010L
        st.submit("<div>a</div>", "#000000", "panel#1")

        assertFalse("内容与前景色都没变时不得排重挂", st.hasPendingCoalescedRemount)
        assertEquals("<div>a</div>", st.currentPendingHtml())
        assertEquals("panel#1", st.currentPendingKey())
    }

    @Test
    fun `release disposes the state machine`() {
        val st = PanelMountState()
        var now = 1_000L
        st.clockMsProvider = { now }
        st.simulateMountForTest("<div>a</div>")
        st.release()
        assertTrue(st.isDisposed)
        now = 1_010L
        st.submit("<div>b</div>", "#000000", "panel#1")
        assertFalse("释放后不得再排任何挂载", st.hasPendingCoalescedRemount)
        assertEquals("", st.currentPendingHtml())
    }

    @Test
    fun `constants and script matcher match the reference implementation`() {
        assertEquals(3, PanelMountState.MAX_PAGE_LOAD_RETRIES)
        assertEquals(48L, PanelMountState.STREAMING_REMOUNT_COALESCE_MS)
        assertTrue(PanelMountState.SCRIPT_TAG_RE.containsMatchIn("<SCRIPT src=x>"))
        assertFalse(PanelMountState.SCRIPT_TAG_RE.containsMatchIn("<div>a</div>"))
    }
}
