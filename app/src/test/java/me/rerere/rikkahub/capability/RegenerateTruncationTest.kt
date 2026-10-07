package me.rerere.rikkahub.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #7 的**行为**复现与验证。
 *
 * 上一个测试是字符串断言（源码里有没有 `allowShrink = true`）。那种测法
 * 证明不了逻辑对不对——参数写在那儿、位置也可能写错。这里把
 * regenerateAtMessage 对用户消息那条分支的判断**原样复刻**成纯函数，
 * 直接喂场景验结果。
 *
 * 场景来自用户报告：
 *   在一条历史用户消息上点「重新生成」→ 弹窗说下面的消息会被清除
 *   实际：后续消息还在，新回复追加到末尾
 */
class RegenerateTruncationTest {

    /** 一条消息节点。只需要条数，不需要内容。 */
    private data class Node(val id: Int)

    /**
     * 复刻 regenerateAtMessage 里 `message.role == USER` 那条分支。
     *
     * 与 ChatService.kt 的实现一一对应：
     *   1. 找到目标消息所在节点
     *   2. subList(0, indexAt + 1) 截断
     *   3. saveConversation(..., allowShrink = true)
     */
    private sealed interface Outcome {
        /** 保存成功，结果是截断后的列表。 */
        data class Truncated(val nodes: List<Node>) : Outcome

        /** 被防线拒绝，什么都没变——这就是用户看到的现象。 */
        data class Rejected(val kept: List<Node>, val attempted: List<Node>) : Outcome

        /** 目标消息根本不在这个会话里。 */
        data object MessageNotFound : Outcome
    }

    /**
     * @param allowShrink 传 true = 修复后；false = 修复前
     * @param targetInConversation 目标消息是否真的在这个会话里
     */
    private fun regenerateUserMessage(
        nodes: List<Node>,
        targetId: Int,
        existingInDbCount: Int,
        allowShrink: Boolean,
        targetInConversation: Boolean = true,
    ): Outcome {
        // 第 1 步：定位
        if (!targetInConversation) return Outcome.MessageNotFound
        val indexAt = nodes.indexOfFirst { it.id == targetId }
        if (indexAt < 0) return Outcome.MessageNotFound

        // 第 2 步：截断
        val attempted = nodes.subList(0, indexAt + 1)

        // 第 3 步：保存。复刻 saveConversation 的判定顺序：
        //   防「节点数倒退」—— exists && !allowShrink && 库里有历史 && 新列表更短 => 拒绝
        val exists = true
        val wouldShrink = attempted.size < existingInDbCount
        if (exists && !allowShrink && existingInDbCount > 0 && wouldShrink) {
            return Outcome.Rejected(kept = nodes, attempted = attempted)
        }
        return Outcome.Truncated(attempted)
    }

    @Test
    fun `before the fix the truncation was silently rejected`() {
        // 复现用户报告的现象：10 条消息，在第 3 条用户消息上重新生成。
        val nodes = (1..10).map { Node(it) }
        val result = regenerateUserMessage(
            nodes = nodes,
            targetId = 3,
            existingInDbCount = 10,
            allowShrink = false, // 修复前
        )
        assertTrue("应当是「被拒绝」", result is Outcome.Rejected)
        val rejected = result as Outcome.Rejected
        assertEquals("被拒绝时一条都不该删", 10, rejected.kept.size)
        assertEquals("原本想截断到 3 条", 3, rejected.attempted.size)
    }

    @Test
    fun `after the fix the truncation takes effect`() {
        val nodes = (1..10).map { Node(it) }
        val result = regenerateUserMessage(
            nodes = nodes,
            targetId = 3,
            existingInDbCount = 10,
            allowShrink = true, // 修复后
        )
        assertTrue("应当成功截断", result is Outcome.Truncated)
        val truncated = (result as Outcome.Truncated).nodes
        assertEquals("应只剩到第 3 条", 3, truncated.size)
        assertEquals("保留的是前 3 条", listOf(1, 2, 3), truncated.map { it.id })
    }

    @Test
    fun `regenerating at the last user message keeps everything up to it`() {
        // 边界：在最后一条用户消息上重新生成，前面的都要留着。
        val nodes = (1..6).map { Node(it) }
        val result = regenerateUserMessage(nodes, targetId = 6, existingInDbCount = 6, allowShrink = true)
        assertEquals(6, (result as Outcome.Truncated).nodes.size)
    }

    @Test
    fun `regenerating at the first message keeps exactly one`() {
        // 边界：第一条就是用户消息，截断后应只剩它自己。
        val nodes = (1..5).map { Node(it) }
        val result = regenerateUserMessage(nodes, targetId = 1, existingInDbCount = 5, allowShrink = true)
        assertEquals(1, (result as Outcome.Truncated).nodes.size)
    }

    @Test
    fun `missing target is detected instead of silently emptying the conversation`() {
        // ⚠️ 这是我在核实 issue 时发现的**第二个隐患**。
        //
        // conversation.getMessageNodeByMessage(message) 返回的是 MessageNode? ，
        // 而 UIMessage 是 data class（equals 按内容比）。只要传进来的对象
        // 与节点里的那份有任一字段不同（拷贝过、反序列化过），就取不到节点，
        // 返回 null。
        //
        // 此时 `conversation.messageNodes.indexOf(null)` 得到 **-1**，
        // `subList(0, -1 + 1)` = `subList(0, 0)` = **空列表**。
        //
        // 也就是说「找不到目标消息」会被当成「截断到第 0 条」，
        // 而 allowShrink=true 恰好又放行了它 —— 整个对话被清空。
        //
        // 修复前 allowShrink=false 时这个 bug 不可见（因为任何缩减都被拒），
        // 修好截断之后它才真正暴露出来。这种「修一个 bug 露出另一个」的
        // 情况必须显式处理。
        val nodes = (1..10).map { Node(it) }
        val result = regenerateUserMessage(
            nodes = nodes,
            targetId = 999, // 不在会话里的消息
            existingInDbCount = 10,
            allowShrink = true,
            targetInConversation = false,
        )
        assertTrue(
            "找不到目标消息时必须是 MessageNotFound，而不是截断成空",
            result is Outcome.MessageNotFound,
        )
    }

    // ── 源码层面的接线检查 ──

    private fun chatSource(): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (root in listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile).filterNotNull()) {
            for (c in listOf(
                File(root, "src/main/java/me/rerere/rikkahub/service/ChatService.kt"),
                File(root, "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt"),
            )) {
                if (c.exists()) return c.readText()
            }
        }
        return ""
    }

    @Test
    fun `the real code guards against a missing node`() {
        // 上面的隐患必须在真实代码里被挡住：
        // 取到 null 之后不能直接拿去算 index。
        val chat = chatSource()
        val block = chat.substringAfter("if (message.role == MessageRole.USER) {").take(2200)
        assertTrue("这条分支应存在", block.contains("subList(0, indexAt + 1)"))
        assertTrue(
            "必须先判空再算下标，否则找不到消息会清空整个对话",
            block.contains("indexOf(node)") && block.contains("== -1"),
        )
    }
}
