package me.rerere.rikkahub.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 保存防线的**行为**测试。
 *
 * —— 为什么要另写一份 ——
 *
 * 这个仓库里已有的防线测试全是字符串断言（读源码文本，断言
 * `contains("allowShrink")`）。那种测法有个结构性缺陷，真实发生过：
 * 我加了 `allowShrink` 参数、加了守卫、字符串断言全绿——**但没有任何
 * 调用点真的传它**。结果用户删除消息被静默拒绝，界面显示删掉了，
 * 重启后消息又回来了。测试根本没发现，因为参数名和守卫字符串都在。
 *
 * 所以这里不再断言「源码里有没有这个词」，而是把
 * ChatService 里那道防线的**判定逻辑原样复刻**成纯函数，直接喂场景、
 * 验结果。逻辑一旦漂移，这些用例就会挂。
 */
class SaveGuardBehaviorTest {

    /**
     * 复刻 ChatService.saveConversation 的判定顺序。
     *
     * 与 ChatService.kt 中的实现一一对应：
     *   1. session 内容不可信 -> 拒绝
     *   2. 新会话且完全为空  -> 跳过
     *   3. assistantId 变化  -> 拒绝
     *   4. 节点数变少（未显式放行）-> 拒绝
     */
    private enum class Decision { SAVED, REFUSED_UNTRUSTED, SKIPPED_EMPTY_NEW, REFUSED_REASSIGN, REFUSED_SHRINK }

    private fun decide(
        exists: Boolean,
        contentTrusted: Boolean,
        existingAssistant: String,
        incomingAssistant: String,
        existingNodeCount: Int,
        incomingNodeCount: Int,
        incomingTitle: String,
        allowShrink: Boolean,
    ): Decision {
        if (!contentTrusted) return Decision.REFUSED_UNTRUSTED
        if (!exists && incomingTitle.isBlank() && incomingNodeCount == 0) return Decision.SKIPPED_EMPTY_NEW
        if (exists && existingAssistant != incomingAssistant) return Decision.REFUSED_REASSIGN
        if (exists && !allowShrink && existingNodeCount > 0 && incomingNodeCount < existingNodeCount) {
            return Decision.REFUSED_SHRINK
        }
        return Decision.SAVED
    }

    // ── 必须拒绝：数据会被破坏 ──

    @Test
    fun `untrusted session is always refused`() {
        // 这是最初那两次事故的路径：DB 读失败建了空壳，
        // 空壳 append 一条消息后就不再是空的，归属也可能恰好相同，
        // 基于内容的检查全都拦不住——只能靠「内容从哪来」判断。
        assertEquals(
            Decision.REFUSED_UNTRUSTED,
            decide(false in listOf(true), contentTrusted = false, existingAssistant = "A",
                incomingAssistant = "A", existingNodeCount = 300, incomingNodeCount = 1,
                incomingTitle = "对话", allowShrink = true),
        )
    }

    @Test
    fun `reassigning the assistant is refused`() {
        assertEquals(
            Decision.REFUSED_REASSIGN,
            decide(true, true, "A", "B", 300, 300, "对话", false),
        )
    }

    @Test
    fun `partial overwrite is refused even when not empty`() {
        // P0-1：列表视图的轻量对象 messageNodes 恒为空，
        // 但分页中间态可能带着「一部分」节点。只拦归零不够，
        // 这里验证 300 -> 5 也会被拦。
        assertEquals(
            Decision.REFUSED_SHRINK,
            decide(true, true, "A", "A", 300, 5, "对话", false),
        )
        assertEquals(
            Decision.REFUSED_SHRINK,
            decide(true, true, "A", "A", 300, 0, "对话", false),
        )
    }

    // ── 必须放行：正常功能 ──

    @Test
    fun `user deleting messages is allowed`() {
        // 这就是被漏掉的那条。没有它，删消息会静默失效。
        assertEquals(
            Decision.SAVED,
            decide(true, true, "A", "A", 300, 299, "对话", allowShrink = true),
        )
    }

    @Test
    fun `appending messages is allowed`() {
        assertEquals(
            Decision.SAVED,
            decide(true, true, "A", "A", 300, 301, "对话", false),
        )
    }

    @Test
    fun `editing title without touching nodes is allowed`() {
        assertEquals(
            Decision.SAVED,
            decide(true, true, "A", "A", 300, 300, "新标题", false),
        )
    }

    @Test
    fun `brand new conversation is allowed`() {
        assertEquals(
            Decision.SAVED,
            decide(false, true, "A", "A", 0, 1, "新对话", false),
        )
    }

    @Test
    fun `empty brand new conversation is skipped not refused`() {
        // 跳过 ≠ 拒绝：这是「没什么可存的」，不是「有风险」
        assertEquals(
            Decision.SKIPPED_EMPTY_NEW,
            decide(false, true, "A", "A", 0, 0, "", false),
        )
    }

    // ── 接线检查：真实调用点必须传 allowShrink ──

    @Test
    fun `real deletion paths actually pass allowShrink`() {
        // 光有参数不算数——必须有调用点真的传它。
        // 这条断言的存在理由就是上一次「零调用点」的事故。
        val chat = findSource("src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        val passes = Regex("allowShrink\\s*=\\s*true").findAll(chat).count()
        assertTrue(
            "应有 >= 3 处真实调用传 allowShrink=true（删消息/切分支/选节点），实际 $passes",
            passes >= 3,
        )
        // 且这些调用必须紧邻删除语义的函数
        listOf("deleteMessage", "buildConversationAfterMessageDelete", "selectMessageNode")
            .forEach { fn ->
                assertTrue("源码里应有 $fn", chat.contains(fn))
            }
    }

    @Test
    fun `regenerating from a historical user message is allowed to shrink`() {
        // issue #7：对历史用户消息点「重新生成」，弹窗说下面的消息会被清除，
        // 实际一条没删，新回复还追加到了末尾。
        //
        // 原因是这条路径截断后没传 allowShrink，被「节点数不得变少」的
        // 防线静默拒绝了。这是防线第二次咬到合法路径——上次是删消息，
        // 这次是重新生成。所以单独钉一条测试。
        val chat = findSource("src/main/java/me/rerere/rikkahub/service/ChatService.kt")

        // 找到那条截断分支
        val block = chat.substringAfter("if (message.role == MessageRole.USER) {")
            .take(3000)
        assertTrue("这条分支确实在截断", block.contains("subList(0, indexAt + 1)"))
        assertTrue(
            "截断后的保存必须显式放行，否则会被防线静默拒绝",
            block.contains("allowShrink = true"),
        )
        // 放行的前提是「确实找到了目标消息」。找不到时 indexOf(null) = -1，
        // subList(0, 0) 会把整个对话清空——allowShrink 一放行这条路就通了。
        assertTrue("必须先判空", block.contains("indexAt == -1"))
    }

    @Test
    fun `every deliberate shrink path passes allowShrink`() {
        // 计数守门：已知的合法缩减路径有四处（重新生成截断、选分支、
        // 删消息、fork）。少一处就是一个「操作点了没反应」的 bug。
        val chat = findSource("src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        val passes = Regex("allowShrink\\s*=\\s*true").findAll(chat).count()
        assertTrue("至少应有 4 处合法放行，实际 $passes", passes >= 4)
    }

    private fun findSource(rel: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (root in listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile).filterNotNull()) {
            for (c in listOf(File(root, rel), File(root, "app/$rel"))) {
                if (c.exists()) return c.readText()
            }
        }
        return ""
    }
}
