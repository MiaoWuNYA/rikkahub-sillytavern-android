package me.rerere.rikkahub.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 失败重试节流。
 *
 * 真实观测到的故障：助手没配模型时，日志里每 60 秒刷一条
 * 「该助手没有可用的模型」，连续 20 分钟。
 *
 * 成因是两条规则叠加：
 *   · 失败路径刻意**不**提交 last_triggered_time（对，否则一次失败会把
 *     之后整个 minInterval 窗口里的真实触发全判掉）
 *   · 但 finally 里无论如何都会 scheduleNext，于是失败后立刻又触发
 *
 * 结果是一个自我维持的循环：失败 -> 重排 -> 触发 -> 失败。
 * 去重检查因为时间戳没写而永远拦不住它。
 */
class ProactiveBackoffTest {

    private val roots: List<File> by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile)
            .filterNotNull().distinct()
    }

    private fun read(rel: String): String {
        for (root in roots) {
            for (c in listOf(File(root, rel), File(root, "app/$rel"))) {
                if (c.exists()) return c.readText()
            }
        }
        return ""
    }

    private val svc get() = read("src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt")
    private val runner get() =
        read("src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageTriggerService.kt")

    /** 复刻节流判定，与 run() 中的实现一一对应。 */
    private fun shouldSkip(
        lastAttemptMs: Long,
        nowMs: Long,
        backoffMinutes: Long,
    ): Boolean {
        if (lastAttemptMs <= 0) return false
        val sinceMin = (nowMs - lastAttemptMs) / 60_000L
        return sinceMin < backoffMinutes
    }

    @Test
    fun `failure backoff blocks rapid retries`() {
        val now = 1_000_000_000_000L
        // 刚失败过 -> 挡住
        assertTrue(shouldSkip(now - 30_000, now, 5))
        assertTrue(shouldSkip(now - 4 * 60_000, now, 5))
        // 超过冷静期 -> 放行
        assertFalse(shouldSkip(now - 6 * 60_000, now, 5))
        // 从未失败过 -> 放行
        assertFalse(shouldSkip(0, now, 5))
    }

    @Test
    fun `attempt stamp is separate from the success stamp`() {
        // 两个戳必须分开，否则要么失败被去重挡住（该重试的不重试），
        // 要么失败被当成成功（不该重试的狂重试）
        assertTrue("应有独立的尝试戳", svc.contains("KEY_LAST_ATTEMPT_TIME"))
        assertTrue("成功戳仍要保留", svc.contains("KEY_LAST_TRIGGERED_TIME"))
        assertTrue("应有冷静期常量", svc.contains("FAILURE_BACKOFF_MINUTES"))
    }

    @Test
    fun `all failure branches mark the attempt`() {
        // 每个 return false 的失败分支都要留痕，漏一个就是一个新的死循环
        val marks = Regex("markAttempt\\(").findAll(runner).count()
        assertTrue("失败分支应至少 3 处调用 markAttempt，实际 $marks", marks >= 3)
    }

    @Test
    fun `success clears the failure backoff`() {
        assertTrue(
            "成功应清掉尝试戳，让下一轮该跑就跑",
            runner.substringAfter("private fun commitTriggerStamp")
                .substringBefore("private fun markAttempt")
                .contains("KEY_LAST_ATTEMPT_TIME"),
        )
    }

    @Test
    fun `cancelling the feature clears all stamps`() {
        val cancelBody = svc.substringAfter("fun cancel(context: Context)")
            .substringBefore("fun resolveTargetConversation")
        assertTrue("取消时应清掉尝试戳", cancelBody.contains("KEY_LAST_ATTEMPT_TIME"))
        assertTrue("取消时应清掉成功戳", cancelBody.contains("KEY_LAST_TRIGGERED_TIME"))
    }

    @Test
    fun `cancellation log does not fabricate a user action`() {
        // 原文案断言「你开始发新消息」，但 CancellationException 也可能
        // 来自系统回收后台任务。用户明明没发消息却被告知发了，
        // 只会把排查引向错误方向。
        // 剔除注释后再断言：解释「为什么改掉旧文案」的注释里当然会引用
        // 旧文案，不剔会把自己的说明判成违规。
        val code = runner
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.take(i) else line
            }
        assertFalse(
            "不得把取消断言成用户行为",
            code.contains("生成过程中你开始发新消息"),
        )
        assertTrue("应如实说明原因未知", runner.contains("生成被打断"))
    }
}
