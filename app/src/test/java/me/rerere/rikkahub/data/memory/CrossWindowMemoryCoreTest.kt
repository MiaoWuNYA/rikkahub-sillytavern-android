package me.rerere.rikkahub.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossWindowMemoryCoreTest {

    private var now = 1_000_000L
    private fun core() = CrossWindowMemoryCore(clock = { now })

    @Test
    fun `append stores entries with incremental ids`() {
        val c = core()
        c.append("a1", "conv1", "m1", "user", "你好")
        c.append("a1", "conv1", "m2", "assistant", "你好呀")
        assertEquals(2, c.peekRecent("a1").size)
        assertEquals("你好", c.peekRecent("a1").first().text)
    }

    @Test
    fun `append is idempotent on messageId and ignores blank input`() {
        val c = core()
        c.append("a1", "conv1", "m1", "user", "第一版")
        c.append("a1", "conv1", "m1", "user", "重试后不同内容")
        assertEquals(1, c.peekRecent("a1").size)
        assertEquals("第一版", c.peekRecent("a1").first().text)

        c.append("a1", "conv1", "m2", "user", "   ")
        c.append("", "conv1", "m3", "user", "无助手ID")
        assertEquals(1, c.peekRecent("a1").size)
    }

    @Test
    fun `append trims store to max entries`() {
        val c = core()
        repeat(1300) { i ->
            c.append("a1", "conv1", "m$i", "user", "msg $i")
        }
        val recent = c.peekRecent("a1", limit = 2000)
        assertEquals(1200, recent.size)
        assertEquals("msg 100", recent.first().text)
    }

    @Test
    fun `consumeForeignDelta only returns other conversations and advances cursor`() {
        val c = core()
        c.append("a1", "conv1", "m1", "user", "来自窗口1")
        c.append("a1", "conv2", "m2", "user", "来自窗口2")
        c.append("a1", "conv2", "m3", "user", "来自窗口2第二条")
        c.append("a2", "conv3", "m4", "user", "其他助手")

        val delta = c.consumeForeignDelta("a1", "conv2")
        assertTrue(delta.prompt.contains("来自窗口1"))
        assertFalse(delta.prompt.contains("来自窗口2"))
        assertFalse(delta.prompt.contains("其他助手"))
        assertEquals(1, delta.entryCount)

        // 游标推进后不再重复投递
        val again = c.consumeForeignDelta("a1", "conv2")
        assertEquals("", again.prompt)
    }

    @Test
    fun `consumeForeignDelta respects entry and char limits`() {
        val c = core()
        repeat(5) { i -> c.append("a1", "conv1", "m$i", "user", "内容 $i") }
        val limited = c.consumeForeignDelta("a1", "conv2", maxEntries = 2)
        assertEquals(2, limited.entryCount)

        val c2 = core()
        repeat(10) { i -> c2.append("a1", "conv1", "m$i", "user", "x".repeat(100)) }
        val charCapped = c2.consumeForeignDelta("a1", "conv2", maxEntries = 50, maxChars = 300)
        // 单条行宽 116 字符，300 上限只能装下 2 条
        assertEquals(2, charCapped.entryCount)
    }

    @Test
    fun `claimCompression keeps tail and previous summary untouched`() {
        val c = core()
        repeat(10) { i -> c.append("a1", "conv1", "m$i", "user", "内容".repeat(100) + i) }
        val work = c.claimCompression(assistantId = "a1", thresholdChars = 100, tailEntries = 3)
        assertNotNull(work)
        assertEquals(7, work!!.entries.size)
        // 前缀最后一条 = 总条数 - 尾部保留条数
        assertEquals(10 - 3, work.throughEntryId)

        // 租约期内不可重复认领
        now += 60_000
        assertNull(c.claimCompression(assistantId = "a1", thresholdChars = 100, tailEntries = 3))
    }

    @Test
    fun `claimCompression skips when below threshold`() {
        val c = core()
        repeat(3) { i -> c.append("a1", "conv1", "m$i", "user", "短内容 $i") }
        assertNull(c.claimCompression(assistantId = "a1", thresholdChars = 12000, tailEntries = 16))
    }

    @Test
    fun `completeCompression replaces prefix with summary`() {
        val c = core()
        repeat(6) { i -> c.append("a1", "conv1", "m$i", "user", "内容".repeat(50) + i) }
        val work = c.claimCompression(assistantId = "a1", thresholdChars = 100, tailEntries = 2)
        assertNotNull(work)
        val throughId = work!!.throughEntryId

        c.completeCompression(work, "早些的对话摘要")
        val entries = c.peekRecent("a1")
        assertTrue(entries.none { it.id <= throughId })
        assertEquals(2, entries.size)
        val summary = c.peekSummary("a1")
        assertNotNull(summary)
        assertEquals("早些的对话摘要", summary!!.text)
        assertEquals(throughId, summary.throughEntryId)
    }

    @Test
    fun `failCompression releases the lease`() {
        val c = core()
        repeat(6) { i -> c.append("a1", "conv1", "m$i", "user", "内容".repeat(50) + i) }
        val work = c.claimCompression(assistantId = "a1", thresholdChars = 100, tailEntries = 2)
        assertNotNull(work)
        c.failCompression(work!!)
        // 租约释放后可重新认领
        assertNotNull(c.claimCompression(assistantId = "a1", thresholdChars = 100, tailEntries = 2))
    }

    @Test
    fun `expired lease allows recompression`() {
        val c = core()
        repeat(6) { i -> c.append("a1", "conv1", "m$i", "user", "内容".repeat(50) + i) }
        c.claimCompression(assistantId = "a1", thresholdChars = 100, tailEntries = 2)
        now += 10 * 60 * 1000L + 1
        assertNotNull(c.claimCompression(assistantId = "a1", thresholdChars = 100, tailEntries = 2))
    }

    @Test
    fun `clearAssistant removes only that assistant`() {
        val c = core()
        c.append("a1", "conv1", "m1", "user", "助手1的内容")
        c.append("a2", "conv2", "m2", "user", "助手2的内容")
        c.consumeForeignDelta("a1", "conv2")
        c.clearAssistant("a1")
        assertTrue(c.peekRecent("a1").isEmpty())
        assertEquals(1, c.peekRecent("a2").size)
        assertNull(c.peekSummary("a1"))
    }
}
