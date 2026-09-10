package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备工具箱元工具的审批判定逻辑（纯 JVM 测试，不依赖 Android 环境）。
 */
class DeviceToolboxApprovalTest {

    private fun needsApprovalOfRun(toolName: String): Boolean = deviceToolboxNeedsApproval(
        buildJsonObject {
            put("action", "run")
            put("tool", toolName)
        }
    )

    @Test
    fun `list action never requires approval`() {
        assertFalse(deviceToolboxNeedsApproval(buildJsonObject { put("action", "list") }))
        // list 不应受 tool 参数影响
        assertFalse(deviceToolboxNeedsApproval(buildJsonObject {
            put("action", "list")
            put("tool", "read_sms")
        }))
    }

    @Test
    fun `run free-read tool does not require approval`() {
        listOf(
            "get_volume", "get_brightness", "get_battery_info", "get_storage_info",
            "get_wifi_info", "get_audio_info", "get_telephony_info",
            "list_sensors", "read_sensor", "show_toast",
        ).forEach { toolName ->
            assertFalse(toolName, needsApprovalOfRun(toolName))
        }
    }

    @Test
    fun `run sensitive tools require approval`() {
        listOf(
            "set_volume", "set_brightness", "set_torch", "set_wallpaper",
            "post_notification", "set_alarm", "set_timer", "control_music",
            "share", "launch_app", "open_url", "open_file",
            "read_sms", "list_contacts", "search_contacts", "list_call_log",
            "get_location", "download_file", "scan_media", "vibrate",
        ).forEach { toolName ->
            assertTrue(toolName, needsApprovalOfRun(toolName))
        }
    }

    @Test
    fun `unknown tool defaults to approval required`() {
        assertTrue(needsApprovalOfRun("not_a_real_tool"))
        assertTrue(needsApprovalOfRun(""))
        // run 但缺 tool 参数
        assertTrue(deviceToolboxNeedsApproval(buildJsonObject { put("action", "run") }))
    }

    @Test
    fun `missing or unknown action defaults to approval required`() {
        // 缺少 action / 未知 action / 非 object 入参：宁可多确认一次也不静默执行
        assertTrue(deviceToolboxNeedsApproval(buildJsonObject { put("tool", "get_battery_info") }))
        assertTrue(deviceToolboxNeedsApproval(buildJsonObject { put("action", "something_else") }))
        assertTrue(deviceToolboxNeedsApproval(null))
    }

    @Test
    fun `approval free set matches catalog expectations`() {
        assertEquals(10, DEVICE_TOOL_APPROVAL_FREE.size)
        assertFalse(isDeviceToolApprovalFree(null))
        assertTrue(isDeviceToolApprovalFree("get_battery_info"))
    }
}
