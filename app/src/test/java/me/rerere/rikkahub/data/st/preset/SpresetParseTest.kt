package me.rerere.rikkahub.data.st.preset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpresetParseTest {

    private val minimalSpreset = """
        {
          "extensions": {
            "SPreset": {
              "version": "1.0",
              "MacroNest": { "enabled": true, "maxDepth": 3 },
              "RegexBinding": { "enabled": ["rx-1"], "disabled": ["rx-2"] },
              "ChatSquash": { "enabled": true, "role": "user", "stopString": "###" },
              "MessageInjections": {
                "inj-1": {
                  "enabled": true,
                  "reasoning": "思考",
                  "toolCalls": [{ "id": "call-1", "name": "search", "arguments": { "q": "hi" } }]
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun parsesMinimalSpresetFromExtensions() {
        val config = SpresetParser.parse(Json.parseToJsonElement(minimalSpreset) as JsonObject)

        assertEquals(3, config.macroNestDepth)
        assertTrue("rx-1" in config.regexEnabledIds)
        assertTrue("rx-2" in config.regexDisabledIds)
        assertTrue(config.chatSquash.enabled)
        assertEquals("user", config.chatSquash.role)
        assertEquals("###", config.chatSquash.stopString)
        assertEquals("1.0", config.version)

        assertEquals(1, config.messageInjections.size)
        val injection = config.messageInjections.first()
        assertEquals("inj-1", injection.id)
        assertTrue(injection.enabled)
        assertEquals("思考", injection.reasoning)
        assertEquals(1, injection.toolCalls.size)
        assertEquals("search", injection.toolCalls.first().name)
    }

    @Test
    fun nullSpresetFallsBackToDefaults() {
        val config = SpresetParser.parse(null)

        assertEquals(0, config.macroNestDepth)
        assertEquals(false, config.chatSquash.enabled)
        assertTrue(config.messageInjections.isEmpty())
    }
}
