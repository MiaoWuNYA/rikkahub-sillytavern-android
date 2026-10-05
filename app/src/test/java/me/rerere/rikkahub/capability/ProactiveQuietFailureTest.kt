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
    fun `proactive no longer borrows the chat session machinery`() {
        // 这是重构的核心断言。
        //
        // 原实现借用 ChatService 的 session（引用计数、生成权、保存锁），
        // 而 session 的 isInUse 包含 _generationJob != null——不显式释放就会
        // 让 session 永远「正在生成」，后续每次触发都被跳过。为它加一对
        // try/release 只是打补丁；真正的解法是**根本不借**。
        //
        // 本功能只需要读、生成、写三件事，直接做，不引入任何中间状态。
        // 这样就没有「忘记释放」的可能——因为压根没有东西需要释放。
        // 剔除块注释和行注释后再判断：解释「为什么不再用它」的注释里
        // 当然会出现那些方法名，不剔的话会把自己的说明判成违规
        val body = runner
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.take(i) else line
            }
        listOf(
            "acquireSessionForBackground",
            "tryClaimGeneration",
            "releaseGenerationClaim",
            "addConversationReference",
            "removeConversationReference",
            "updateConversationState",
            "getConversationFlow",
            "saveConversation",
        ).forEach { banned ->
            assertFalse(
                "主动消息不应再调用 chatService.$banned —— 借用会话机制正是数据丢失的根源",
                body.contains("chatService.$banned"),
            )
        }
        // 只允许一个只读的并发判断
        assertTrue("只保留只读的生成状态查询",
            runner.contains("chatService.isGenerating("))
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
        val body = chat.substringAfter("private fun loadInitialConversationTrusted(")
            .substringBefore("private fun getOrCreateSession(")
        assertTrue("应重试", body.contains("repeat(3)"))
        assertTrue("应优先读数据库", body.contains("conversationRepo.getConversationById"))
        // 放弃时要有明确告警，方便以后从日志里定位
        assertTrue("放弃要留下醒目日志", body.contains("marked UNTRUSTED"))
    }

    @Test
    fun `untrusted session content blocks every save`() {
        // 这是最关键的一条。
        //
        // 前两道基于内容的检查有个共同盲区：空壳会话在 append 了一条
        // AI 消息之后就不再是空的，assistantId 也可能恰好相同，两道
        // 全过——然后用这 1 条覆盖掉库里的几千条历史。
        //
        // 所以必须有「内容从哪来」这一层判据。
        assertTrue("会话要带可信标记", read("src/main/java/me/rerere/rikkahub/service/ConversationSession.kt")
            .contains("var contentTrusted"))
        assertTrue("saveConversation 必须在最前面检查它",
            chat.contains("session.contentTrusted"))
        assertTrue("拒绝时要留下醒目日志",
            chat.contains("session content is UNTRUSTED"))
        // 加载失败必须标记为不可信，而不是静默给个空壳
        assertTrue("读取失败要标记不可信",
            chat.contains("to false") && chat.contains("marked UNTRUSTED"))
        // 读到了 null 属于成功查询，内容可信
        assertTrue("查到 null 应视为新会话（可信）",
            chat.contains("这是\\u4e00\\u6b21") || chat.contains("to true"))
    }

    @Test
    fun `untrusted session recovers automatically`() {
        // 只拒绝不恢复会让改动永久存不上，那是另一种坏体验
        assertTrue("应提供 rehydrate", read("src/main/java/me/rerere/rikkahub/service/ConversationSession.kt")
            .contains("fun rehydrate("))
        assertTrue("getConversationFlow 应尝试恢复",
            chat.contains("rehydrate session"))
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
