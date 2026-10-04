package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.datastore.HuaDengSettings
import me.rerere.rikkahub.data.datastore.Settings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具描述的类型必须稳定——不能含随日期变化的内容。
 *
 * 工具定义位于请求的 tools 数组，属于提示词前缀区。供应商的前缀缓存从
 * 第一个 token 起连续匹配，一旦前缀区里出现每天变化的内容，跨天复用同一
 * 对话时缓存就会从该处分叉，之后全部重算。
 *
 * 项目里已有的共识见 buildUserContext 的注释：「记忆与日期都不再进入前缀区」，
 * 日期被挪到上下文尾部。工具描述里的日期属于同一问题的遗漏。
 *
 * 日期信息本身不丢失：buildUserContext 注入了 "Current date: ..."。
 */
class ToolDescriptionStabilityTest {

    private fun memoryToolDescription(tavern: Boolean): String {
        val settings = Settings(huadengSettings = HuaDengSettings(enableTavernMode = tavern))
        return buildMemoryTools(
            json = Json,
            onCreation = { _, _ -> error("unused") },
            onUpdate = { _, _, _ -> error("unused") },
            onDelete = { error("unused") },
            onList = { emptyList() },
        ).first { it.name == "memory_tool" }.description
    }

    @Test
    fun `memory tool description has no volatile date`() {
        assertFalse(
            "工具描述不得含每天变化的内容，否则跨天会打断前缀缓存",
            memoryToolDescription(tavern = false).contains("Today is"),
        )
    }

    @Test
    fun `memory tool description keeps its core semantics`() {
        val desc = memoryToolDescription(tavern = false)
        assertTrue("仍要说明四个 action", desc.contains("create") && desc.contains("delete"))
        assertTrue("仍要说明必填参数", desc.contains("create needs"))
    }

    @Test
    fun `description is stable across calls`() {
        val a = memoryToolDescription(tavern = false)
        val b = memoryToolDescription(tavern = false)
        assertTrue("同一输入必须产出同一描述，否则前缀缓存无法命中", a == b)
    }
}
