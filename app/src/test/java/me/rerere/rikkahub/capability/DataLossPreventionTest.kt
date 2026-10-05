package me.rerere.rikkahub.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 防止「聊天记录被清空」再次发生。
 *
 * 这是一次真实的数据丢失事故（P0）：主动消息的异常清理路径拿到一个由
 * getOrCreateSession 凭空创建的空会话（Conversation.ofId，零条 messageNodes），
 * 再经 saveConversation 写回数据库，把用户的几千条记录连同记忆关联一起清零。
 *
 * 触发条件是常态而非边缘情况：进程被系统回收后内存里本就没有 session，
 * 此时定时触发失败就走了那条路。所以这里逐层设防，任何一层都能独立拦住它。
 */
class DataLossPreventionTest {

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

    private fun stripComments(src: String): String =
        src.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.take(i) else line
            }

    private val chatService get() = read("src/main/java/me/rerere/rikkahub/service/ChatService.kt")
    private val trigger get() = read("src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageTriggerService.kt")

    @Test
    fun `new sessions load their content from the database`() {
        // 防线 1（源头）：getOrCreateSession 不能凭空造空会话
        val body = chatService.substringAfter("private fun getOrCreateSession(")
            .substringBefore("private fun removeSession(")
        assertTrue(
            "getOrCreateSession 应通过 loadInitialConversation 从数据库初始化",
            body.contains("loadInitialConversation("),
        )
        assertFalse(
            "不应再直接 Conversation.ofId(...) 当作初始内容——那是空会话，" +
                "任何「取状态再写回」的调用都会拿它覆盖真实历史",
            body.contains("initial = Conversation.ofId("),
        )
        assertTrue("应有 loadInitialConversation 实现",
            chatService.contains("private fun loadInitialConversation("))
        assertTrue("它应读数据库",
            chatService.substringAfter("private fun loadInitialConversation(")
                .substringBefore("private fun getOrCreateSession(")
                .contains("conversationRepo.getConversationById"))
    }

    @Test
    fun `saving refuses to wipe a conversation that has history`() {
        // 防线 2（落库）：saveConversation 是唯一的写入口，在这里兜底
        val body = chatService.substringAfter("suspend fun saveConversation(")
            .substringBefore("// ---- 翻译消息 ----")
        assertTrue(
            "saveConversation 应拒绝「已有历史 -> 变成零条」的保存",
            body.contains("conversation.messageNodes.isEmpty()") &&
                body.contains("Refusing to save conversation"),
        )
        // 必须真的查一次已有内容，否则判不出「已有历史」
        assertTrue("应读取库中现有节点数",
            body.contains("getConversationById") && body.contains("existingNodes"))
        assertTrue("拒绝后应直接返回，不落库", body.contains("return"))
    }

    @Test
    fun `proactive flow never saves from a possibly-empty session`() {
        val code = stripComments(trigger)
        // 防线 3（调用方）：主动消息的三条写回路径都要自检
        assertTrue("saveProactiveMessage 应拒绝空状态写回",
            trigger.contains("Refusing to save proactive message"))
        // 剔除注释后再判断：解释「为什么删掉这种写法」的注释里当然会有
        // 同样的字符串，直接 contains 会把自己的说明判成违规
        assertFalse(
            "不应存在「取当前状态 -> 无条件 saveConversation」的写法",
            code.contains("saveConversation(conversationId, chatService.getConversationFlow(conversationId).value)"),
        )
        // updateOrAppendAiMessage 里保留 saveConversation 是正确的：
        // 它写的是「已存在 node 就地更新」或「追加新 node」后的内容，
        // 永远带着至少一条消息，不存在写空的风险。要拦的只是那种
        // 「直接把 getConversationFlow(...).value 原样写回」的惰性写法。
        assertTrue("就地更新那条路径应保留",
            code.contains("chatService.updateConversationState(conversationId) { updated }"))
        // 出错后的清理路径不得再碰数据库
        assertTrue(
            "异常分支不应再保存对话",
            trigger.contains("never overwrite from a possibly-empty session"),
        )
    }

    @Test
    fun `every saveConversation caller is accounted for`() {
        // 这条防止将来有人新加一个调用点却绕过了防线 2 的前提假设：
        // 防线 2 依赖「写空内容 = 异常」，如果哪天有正常业务真的要把
        // 会话清空，必须走 deleteConversation 而不是 saveConversation。
        val callers = Regex("saveConversation\\(").findAll(chatService).count()
        assertTrue("ChatService 内应仍有 saveConversation 调用点（实际 $callers）", callers > 5)
        assertTrue(
            "会话删除应由 ConversationRepository.deleteConversation 负责——" +
                "不该用 saveConversation 传空内容来达到清空效果",
            read("src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt")
                .contains("fun deleteConversation("),
        )
    }
}
