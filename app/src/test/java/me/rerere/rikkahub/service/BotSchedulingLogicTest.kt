package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.qq.isQqAccessTokenExpired
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.data.weixin.isWeixinTokenExpiredError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Bot 与主动消息移植部分的纯逻辑单元测试：
 * 调度间隔计算、QQ token 过期判断、微信 token 过期错误识别。
 */
class BotSchedulingLogicTest {

    // ---- 主动消息：随机间隔计算 ----

    @Test
    fun `computeDelayMinutes stays within min and max`() {
        val random = Random(42)
        repeat(200) {
            val delay = ProactiveMessageService.computeDelayMinutes(30, 90, random)
            assertTrue(delay in 30..90)
        }
    }

    @Test
    fun `computeDelayMinutes coerces invalid bounds`() {
        // min < 1 时抬到 1，结果落在 [1, max]
        repeat(50) {
            val delay = ProactiveMessageService.computeDelayMinutes(0, 5, Random(it))
            assertTrue(delay in 1..5)
        }
        // max < min 时抬到与 min 相同，只可能返回 min
        repeat(50) {
            assertEquals(45, ProactiveMessageService.computeDelayMinutes(45, 10, Random(it)))
        }
    }

    @Test
    fun `computeDelayMinutes with equal bounds returns that value`() {
        repeat(20) {
            assertEquals(60, ProactiveMessageService.computeDelayMinutes(60, 60, Random(it)))
        }
    }

    // ---- QQ Bot：access_token 过期判断 ----

    @Test
    fun `qq token is expired when blank`() {
        val now = 1_000_000L
        assertTrue(isQqAccessTokenExpired("", now + 600_000, now))
    }

    @Test
    fun `qq token is valid before expiry with margin`() {
        // 过期时间减去 60s 余量仍大于当前时间 → 有效
        val now = 1_000_000L
        assertFalse(isQqAccessTokenExpired("token", now + 600_000, now))
    }

    @Test
    fun `qq token is expired within margin`() {
        // 距过期只剩 30s（< 60s 余量）→ 视为过期，提前刷新
        val now = 1_000_000L
        assertTrue(isQqAccessTokenExpired("token", now + 30_000, now))
    }

    @Test
    fun `qq token is expired after expiry time`() {
        val now = 2_000_000L
        assertTrue(isQqAccessTokenExpired("token", now - 1, now))
    }

    // ---- 微信 Bot：token 过期错误识别 ----

    @Test
    fun `weixin session timeout error is detected`() {
        assertTrue(isWeixinTokenExpiredError("Weixin API 200: session timeout"))
        assertTrue(isWeixinTokenExpiredError("ret=-14"))
        assertTrue(isWeixinTokenExpiredError("Weixin API 401: unauthorized"))
    }

    @Test
    fun `weixin transient errors are not treated as token expiry`() {
        assertFalse(isWeixinTokenExpiredError("connect timed out"))
        assertFalse(isWeixinTokenExpiredError("Weixin API 500: internal error"))
        assertFalse(isWeixinTokenExpiredError(""))
    }
}
