package me.rerere.rikkahub.plugin

import me.rerere.rikkahub.plugin.loader.PluginToolNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginToolNamingTest {

    @Test
    fun `reverse domain id produces prefixed name with hash`() {
        val name = PluginToolNaming.buildToolName("com.example.plugin.weather", "get_weather")
        assertTrue(name.startsWith("plugin_"))
        assertTrue(name.contains("com_example_plugin_weather"))
        assertTrue(name.endsWith("_get_weather"))
        assertTrue(PluginToolNaming.isValidToolName(name))
    }

    @Test
    fun `lossy sanitized ids get distinct hashes`() {
        val a = PluginToolNaming.buildToolName("com.a.plugin", "run")
        val b = PluginToolNaming.buildToolName("com-b-plugin", "run")
        // sanitize 后同为 com_a_plugin，但短哈希不同，不得撞名
        assertNotEquals(a, b)
        assertTrue(PluginToolNaming.isValidToolName(a))
        assertTrue(PluginToolNaming.isValidToolName(b))
    }

    @Test
    fun `clean id without illegal chars has no hash`() {
        val name = PluginToolNaming.buildToolName("my_plugin", "do_thing")
        assertEquals("plugin_my_plugin_do_thing", name)
    }

    @Test
    fun `illegal characters are replaced`() {
        val name = PluginToolNaming.buildToolName("com.test.插件", "工具.名称-1")
        assertTrue(PluginToolNaming.isValidToolName(name))
        assertTrue(name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' })
    }

    @Test
    fun `empty id falls back to unnamed`() {
        val name = PluginToolNaming.buildToolName("...", "run")
        assertTrue(name.startsWith("plugin_unnamed"))
        assertTrue(PluginToolNaming.isValidToolName(name))
    }

    @Test
    fun `name length is capped`() {
        val longId = "com." + "a".repeat(100) + ".plugin"
        val longTool = "t".repeat(100)
        val name = PluginToolNaming.buildToolName(longId, longTool)
        assertTrue(name.length <= 64)
        assertTrue(PluginToolNaming.isValidToolName(name))
    }

    @Test
    fun `hash is stable`() {
        assertEquals(
            PluginToolNaming.shortHash("com.example"),
            PluginToolNaming.shortHash("com.example")
        )
        assertEquals(6, PluginToolNaming.shortHash("x").length)
    }

    @Test
    fun `sanitize compresses underscores and trims`() {
        assertEquals("a_b", PluginToolNaming.sanitize("a--b"))
        assertEquals("a_b", PluginToolNaming.sanitize("__a__b__"))
        assertEquals("unnamed", PluginToolNaming.sanitize("###"))
    }
}
