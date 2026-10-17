package me.rerere.rikkahub.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.plugin.model.PluginManifest
import me.rerere.rikkahub.plugin.model.SillyTavernManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestParserTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `parse full manifest`() {
        val content = """
            {
              "id": "com.example.plugin.weather",
              "name": "天气查询",
              "description": "查询指定城市的天气信息",
              "version": "1.1.0",
              "author": "Silas",
              "icon": "🌤️",
              "entry": "main.js",
              "tools": [
                {
                  "name": "get_weather",
                  "description": "查询当前天气",
                  "parameters": [
                    { "name": "city", "type": "string", "required": true, "description": "城市" },
                    { "name": "days", "type": "integer", "required": false }
                  ]
                }
              ],
              "config": [
                { "name": "api_key", "type": "password", "label": "API Key", "default": "abc" },
                { "name": "units", "type": "select", "label": "单位",
                  "options": [{ "value": "c", "label": "摄氏" }, { "value": "f", "label": "华氏" }] }
              ],
              "allowedHosts": ["wttr.in"]
            }
        """.trimIndent()

        val manifest = json.decodeFromString(PluginManifest.serializer(), content)

        assertEquals("com.example.plugin.weather", manifest.id)
        assertEquals("main.js", manifest.entry)
        assertEquals(1, manifest.tools.size)
        assertEquals("get_weather", manifest.tools[0].name)
        assertEquals(2, manifest.tools[0].parameters.size)
        assertTrue(manifest.tools[0].parameters[0].required)
        assertEquals(2, manifest.config.size)
        assertEquals(JsonPrimitive("abc"), manifest.config[0].default)
        assertEquals(2, manifest.config[1].options?.size)
        assertEquals(listOf("wttr.in"), manifest.allowedHosts)
    }

    @Test
    fun `unknown fields are ignored`() {
        val content = """
            {
              "id": "com.example.plugin",
              "name": "p",
              "description": "d",
              "version": "1.0.0",
              "author": "a",
              "icon": "x",
              "entry": "main.js",
              "customPage": "memory_bank",
              "ui": { "something": true },
              "hooks": [{ "event": "message_sent", "handler": "h" }],
              "futureField": 123
            }
        """.trimIndent()

        val manifest = json.decodeFromString(PluginManifest.serializer(), content)
        assertEquals("com.example.plugin", manifest.id)
        assertTrue(manifest.tools.isEmpty())
        assertTrue(manifest.allowedHosts.isEmpty())
    }

    @Test
    fun `empty allowlist defaults to no network`() {
        val content = """
            {
              "id": "com.example.plugin",
              "name": "p",
              "description": "d",
              "version": "1.0.0",
              "author": "a",
              "icon": "x",
              "entry": "main.js"
            }
        """.trimIndent()
        val manifest = json.decodeFromString(PluginManifest.serializer(), content)
        assertTrue(manifest.allowedHosts.isEmpty())
        assertNull(manifest.config.firstOrNull())
    }

    @Test
    fun `parse frontend-only manifest`() {
        val content = """
            {
              "id": "com.example.frontend",
              "name": "前端插件",
              "description": "d",
              "version": "1.0.0",
              "author": "a",
              "icon": "🧩",
              "frontend": {
                "scripts": [
                  { "name": "状态栏", "entry": "scripts/status.js", "enabled": true, "info": "状态" }
                ]
              }
            }
        """.trimIndent()
        val manifest = json.decodeFromString(PluginManifest.serializer(), content)
        assertEquals("", manifest.entry)
        val scripts = manifest.frontend?.scripts.orEmpty()
        assertEquals(1, scripts.size)
        assertEquals("scripts/status.js", scripts[0].entry)
        assertTrue(scripts[0].enabled)
    }

    @Test
    fun `sillytavern manifest maps to frontend plugin`() {
        val content = """
            { "display_name": "酒馆助手", "js": "dist/index.js", "author": "KAKAA",
              "version": "4.11.3", "homePage": "https://example.com" }
        """.trimIndent()
        val st = json.decodeFromString(SillyTavernManifest.serializer(), content)
        val manifest = st.toPluginManifest("fallback")
        assertEquals("酒馆助手", manifest.name)
        assertEquals("", manifest.entry)
        assertEquals("dist/index.js", manifest.frontend?.scripts?.first()?.entry)
        assertEquals("4.11.3", manifest.version)
    }

    @Test
    fun `missing required field throws`() {
        val content = """
            {
              "id": "com.example.plugin",
              "name": "p"
            }
        """.trimIndent()
        try {
            json.decodeFromString(PluginManifest.serializer(), content)
            throw AssertionError("expected SerializationException")
        } catch (_: kotlinx.serialization.SerializationException) {
            // expected
        }
    }
}
