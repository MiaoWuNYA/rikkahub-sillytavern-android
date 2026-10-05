package me.rerere.rikkahub.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「定时到了却什么都不做，还谎报原因」这一类故障。
 *
 * 真实事故（2026-10-05）：主动消息每 60 秒触发一次，日志上全写着
 * 「该对话正在进行生成」和「生成过程中你开始发新消息，主动消息被取消」，
 * 但用户根本没在聊天。同时所有助手的对话归属被打乱、消息被清空。
 *
 * 两个根因：
 *   1. tryClaimGeneration 注册的 job 从不释放 -> session 永久「正在生成」
 *   2. 新建 session 用「当前助手」当默认归属 -> 整行覆盖时改写 assistant_id
 */
class ProactiveQuietFailureTest {

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

    private val chat get() = read("src/main/java/me/rerere/rikkahub/service/ChatService.kt")
    private val runner get() =
        read("src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageTriggerService.kt")

    @Test
    fun `generation claim is always released`() {
        // 漏掉释放 = session 永远「正在生成」= 主动消息永久静默
        assertTrue("应有 releaseGenerationClaim", chat.contains("fun releaseGenerationClaim("))
        assertTrue(
            "主动消息必须在 finally 里释放生成权（try 与 release 成对）",
            runner.contains("releaseGenerationClaim"),
        )
        // 且必须是「谁 claim 谁 release」，用同一个 job 比对
        assertTrue(
            "释放时要确认是自己的那个 job，不能误伤用户后来的生成",
            chat.contains("session.getJob() === job"),
        )
    }

    @Test
    fun `release happens even when the run throws or is cancelled`() {
        // 定位 run() 的那个 finally：用「重新排程」这段独有代码做锚点
        val idx = runner.indexOf("Failed to reschedule after completion/error/cancellation")
        assertTrue("找不到 run() 的 finally 块", idx > 0)
        val tail = runner.substring(idx).take(900)
        assertTrue("finally 里应释放生成权", tail.contains("releaseGenerationClaim"))
        assertTrue("finally 里也应释放会话引用", tail.contains("removeConversationReference"))
    }

    @Test
    fun `saving never reassigns a conversation to another assistant`() {
        // 「所有助手的聊天都不见了」的真身：归属被整行覆盖改掉
        assertTrue(
            "saveConversation 必须拒绝改 assistantId",
            chat.contains("assistantId would change"),
        )
        val body = chat.substringAfter("suspend fun saveConversation(")
            .substringBefore("// ---- 翻译消息 ----")
        assertTrue("要在写之前比对库里的归属",
            body.contains("existing.assistantId != conversation.assistantId"))
    }

    @Test
    fun `new sessions load real content and retry instead of going empty`() {
        // 空会话是这一切的起点，必须尽力避免
        val body = chat.substringAfter("private fun loadInitialConversation(")
            .substringBefore("private fun getOrCreateSession(")
        assertTrue("应重试", body.contains("repeat(3)"))
        assertTrue("应优先读数据库", body.contains("conversationRepo.getConversationById"))
        // 放弃时要有明确告警，方便以后从日志里定位
        assertTrue("放弃要留下醒目日志", body.contains("returning an EMPTY session"))
    }

    @Test
    fun `empty-session writes are refused at every layer`() {
        assertTrue("落库层拒绝清空", chat.contains("Refusing to save conversation"))
        assertTrue("主动消息落库前自检", runner.contains("Refusing to save proactive message"))
        assertFalse(
            "不应存在「取当前状态 -> 无条件写回」的惰性写法",
            runner.lines().filterNot { it.trimStart().startsWith("//") }
                .any { it.contains("saveConversation(conversationId, chatService.getConversationFlow(conversationId).value)") },
        )
    }
}
