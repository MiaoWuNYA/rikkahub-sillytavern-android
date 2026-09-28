package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.ToolApprovalState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalPolicyTest {
    @Test
    fun `ask_user always waits for the user instead of being auto approved`() {
        // 自动批准设置只影响普通工具；ask_user 必须挂起，否则问答 UI 不会出现
        assertTrue(mustWaitForUser("ask_user", ToolApprovalState.Auto))
    }

    @Test
    fun `ask_user is not re-pended once already handled`() {
        // 已经回答/已批准/已拒绝的状态不能被重置回 Pending，否则会重复追问
        assertFalse(mustWaitForUser("ask_user", ToolApprovalState.Answered("""{"answers":{}}""")))
        assertFalse(mustWaitForUser("ask_user", ToolApprovalState.Approved))
        assertFalse(mustWaitForUser("ask_user", ToolApprovalState.Denied("no")))
        assertFalse(mustWaitForUser("ask_user", ToolApprovalState.Pending))
    }

    @Test
    fun `ordinary tools are unaffected by the hitl rule`() {
        assertFalse(mustWaitForUser("search_web", ToolApprovalState.Auto))
        assertFalse(mustWaitForUser("workspace_shell", ToolApprovalState.Auto))
    }

    @Test
    fun `hitl set contains ask_user only`() {
        assertTrue("ask_user" in HITL_TOOL_NAMES)
    }
}
