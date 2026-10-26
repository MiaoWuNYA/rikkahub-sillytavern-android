package me.rerere.rikkahub.data.ai.tools.phone

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手机控制动作的审批判定（纯 JVM，不依赖 Android 环境） */
class PhoneActApprovalTest {

    private fun args(action: String, target: String? = null, text: String? = null): JsonObject =
        buildJsonObject {
            put("action", action)
            target?.let { put("target", it) }
            text?.let { put("text", it) }
        }

    @Test
    fun `plain navigation needs no approval`() {
        assertFalse(phoneActNeedsApproval(args("tap", target = "搜索")))
        assertFalse(phoneActNeedsApproval(args("tap", target = "4")))
        assertFalse(phoneActNeedsApproval(args("scroll", text = null)))
        assertFalse(phoneActNeedsApproval(args("back")))
    }

    @Test
    fun `typing ordinary text needs no approval`() {
        assertFalse(phoneActNeedsApproval(args("input", target = "5", text = "机械键盘")))
    }

    @Test
    fun `money and destructive targets stop for the user`() {
        listOf("立即购买", "确认支付", "删除", "发送", "退出登录", "付款").forEach { label ->
            assertTrue(label, phoneActNeedsApproval(args("tap", target = label)))
        }
        assertTrue(phoneActNeedsApproval(args("input", target = "5", text = "delete all")))
    }

    @Test
    fun `missing args never require approval`() {
        assertFalse(phoneActNeedsApproval(null))
        assertFalse(phoneActNeedsApproval(buildJsonObject { }))
        assertFalse(phoneActNeedsApproval(buildJsonObject { put("action", "wait") }))
    }
}
