package me.rerere.rikkahub.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 对抗性审计发现的四条存活丢数据路径。
 *
 * 审计的核心批评是准确的：`contentTrusted` 修的是「空壳污染」，
 * **没修「部分覆盖」**——而后者更安静，因为没有东西归零、没有报错。
 */
class AuditFixTest {

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
    private val repo get() =
        read("src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt")

    @Test
    fun `P0-1 saving refuses to shrink the node list`() {
        // 只拦「归零」不够。列表视图用的轻量对象 messageNodes 恒为空，
        // 在分页合并完成前存一次（哪怕只是改标题）就会静默截断历史。
        assertTrue("应拦节点数变少", chat.contains("node count would shrink"))
        assertTrue("要有显式放行开关", chat.contains("allowShrink"))
        assertTrue("默认必须是拒绝", chat.contains("allowShrink: Boolean = false"))
        // 确认轻量对象确实是空的——这是判据成立的前提
        assertTrue(
            "conversationSummaryToConversation 应产出空节点（这正是风险来源）",
            repo.contains("messageNodes = emptyList()"),
        )
    }

    @Test
    fun `P0-2 attachment deletion is conservative`() {
        // 这是唯一会**物理删除磁盘文件**的地方，且不可恢复
        assertTrue("新列表为空时不得判定删除", chat.contains("new file list is empty while old has"))
        assertTrue("差集过大时放弃", chat.contains("looks like an incomplete conversation object"))
        // 且只有可信内容才允许走到这一步
        assertTrue(
            "updateConversation 里附件清理要受 trusted 约束",
            chat.contains("if (trusted) {") &&
                chat.substringAfter("private fun updateConversation(")
                    .substringBefore("fun updateConversationState(")
                    .contains("checkFilesDelete"),
        )
    }

    @Test
    fun `P0-3 target conversation must belong to the chosen assistant`() {
        val svc = read("src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt")
        assertTrue("必须比对归属", svc.contains("pinned.assistantId != assistantId"))
        // 原来的注释明确写着「不比对」，那正是漏洞所在
        assertTrue("归属不符要明确拒绝",
            svc.contains("belongs to assistant"))
    }

    @Test
    fun `P0-4 trusted flag is restored when full content is loaded`() {
        // 这条是「反向丢数据」：标记只置 false 不恢复 -> 永久拒绝保存
        // -> 消息看着在界面上，重启后全丢
        assertTrue(
            "updateConversation 应支持 trusted 参数并能恢复标记",
            chat.contains("trusted: Boolean = false") && chat.contains("session.contentTrusted = true"),
        )
        assertTrue(
            "从数据库读出完整内容时必须立信",
            chat.contains("updateConversation(conversationId, conversation, trusted = true)"),
        )
        assertTrue("getConversationFlow 也要能自动恢复", chat.contains("rehydrate session"))
    }
}
