package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyToolGroupsTest {
    private fun tool(name: String) = Tool(
        name = name,
        description = name,
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    @Test
    fun `core groups stay visible and lazy groups load on demand`() {
        LazyToolGroups.register(
            mapOf(
                "plugin" to listOf(tool("plugin_author")),
                "settings" to listOf(tool("settings_control")),
                "file" to listOf(tool("file")),
                "time" to listOf(tool("time_info")),
            )
        )
        val all = listOf(
            tool("plugin_author"),
            tool("settings_control"),
            tool("file"),
            tool("time_info"),
            tool("load_tools"),
        )

        val before = LazyToolGroups.effective("conv", all).map { it.name }
        assertTrue("plugin_author 必须常驻", "plugin_author" in before)
        assertTrue("settings_control 必须常驻", "settings_control" in before)
        assertTrue("load_tools 必须常驻", "load_tools" in before)
        assertFalse("file 组未加载前不该出现", "file" in before)

        val groups = LazyToolGroups.availableGroups().keys
        assertTrue("file 应可从 load_tools 加载", "file" in groups)
        assertFalse(groups.contains("plugin"))

        LazyToolGroups.markLoaded("conv", "file")
        val after = LazyToolGroups.effective("conv", all).map { it.name }
        assertTrue("加载后 file 出现在请求里", "file" in after)
    }
}
