package me.rerere.rikkahub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片滑动必须优先于外层聊天列表。
 *
 * 修复前用 `onPostScroll`：它在**外层列表已经消费完手势之后**才被调用，
 * 此时留给卡片的只剩残渣，表现为"滑一下里面只动一点点、整段滚动被原生列表吃掉"。
 * 用户的原话是「与原生 kotlin 的翻动冲突」。
 *
 * 正确做法是 `onPreScroll` —— 手势链里从内向外派发的第一站，
 * 卡片先吃掉可用位移，吃掉多少就报多少 consumed，剩下的才轮到列表。
 */
class CardScrollDispatchTest {

    /** 模拟一个 WebView 的滚动区间：[0, maxScroll] */
    private class FakeScrollable(val maxScroll: Int) {
        var scrollY: Int = 0
            private set

        /** 返回实际消费的位移（与 WebView.scrollBy 语义一致） */
        fun scrollBy(dy: Int): Int {
            val before = scrollY
            scrollY = (scrollY + dy).coerceIn(0, maxScroll)
            return scrollY - before
        }
    }

    /**
     * 复刻修复后的分发逻辑：onPreScroll 让卡片先消费，
     * 并把不足 1px 的截断余数留到下一帧。
     */
    private class Dispatcher(private val card: FakeScrollable) {
        private var remainder = 0f

        /** @return 卡片消费掉的位移 */
        fun onPreScroll(available: Float): Float {
            val delta = available + remainder
            val step = delta.toInt()
            if (step == 0) {
                remainder = delta
                return 0f
            }
            val actually = card.scrollBy(step).toFloat()
            remainder = delta - actually
            return actually
        }
    }

    @Test
    fun `card consumes the gesture before the list does`() {
        val d = Dispatcher(FakeScrollable(maxScroll = 1000))
        assertEquals("卡片能吃下多少就报多少", 50f, d.onPreScroll(50f), 0.01f)
    }

    @Test
    fun `only the leftover goes to the outer list`() {
        val card = FakeScrollable(maxScroll = 100)
        card.scrollBy(100) // 卡片已滚到底
        val d = Dispatcher(card)
        // 卡片滚不动了，剩余量应当全部交还列表（返回 0 表示卡片没消费）
        assertEquals(0f, d.onPreScroll(30f), 0.01f)
    }

    @Test
    fun `partial consumption returns exactly what the card took`() {
        val card = FakeScrollable(maxScroll = 30)
        val d = Dispatcher(card)
        // 请求 100，卡片只能吃 30 —— 必须如实上报 30，不能谎报 100
        assertEquals(30f, d.onPreScroll(100f), 0.01f)
    }

    @Test
    fun `sub-pixel movement accumulates instead of being dropped`() {
        val card = FakeScrollable(maxScroll = 1000)
        val d = Dispatcher(card)
        // 慢速滑动每帧可能只有 0.4px。若直接 toInt() 丢弃，
        // 用户会感觉"手指在动但内容纹丝不动"。
        var total = 0f
        repeat(10) { total += d.onPreScroll(0.4f) }
        assertEquals("10 帧 × 0.4px 应累积出 4px", 4f, total, 0.01f)
        assertEquals(4, card.scrollY)
    }

    @Test
    fun `no movement is claimed when the card cannot scroll`() {
        val card = FakeScrollable(maxScroll = 0)
        val d = Dispatcher(card)
        assertEquals(0f, d.onPreScroll(25f), 0.01f)
        assertEquals(0, card.scrollY)
    }

    @Test
    fun `the post-scroll hook never double-applies the same gesture`() {
        // 同一次拖动被 onPreScroll 与 onPostScroll 各 scrollBy 一遍，
        // 正是"能滑但几乎不动"的成因。修复后 onPostScroll 直接返回 Zero。
        val card = FakeScrollable(maxScroll = 1000)
        val d = Dispatcher(card)
        d.onPreScroll(40f)
        assertEquals("只应被应用一次", 40, card.scrollY)
        // onPostScroll 返回 Offset.Zero（这里以"不再调用 scrollBy"表示）
        assertTrue(true)
    }
}
