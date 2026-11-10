package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.uuid.Uuid

class FrontendCardModeTest {
    private val json = JsonInstant

    private fun decodeMode(raw: String) = json.decodeFromString(FrontendCardMode.serializer(), raw)

    @Test
    fun legacyAutoBecomesCompat() = assertEquals(FrontendCardMode.COMPAT, decodeMode("\"AUTO\""))

    @Test
    fun legacyAlwaysBecomesCompat() = assertEquals(FrontendCardMode.COMPAT, decodeMode("\"ALWAYS\""))

    @Test
    fun legacyTavernBecomesCompat() = assertEquals(FrontendCardMode.COMPAT, decodeMode("\"TAVERN\""))

    @Test
    fun legacyNeverBecomesNative() = assertEquals(FrontendCardMode.NATIVE, decodeMode("\"NEVER\""))

    @Test
    fun unknownValueBecomesCompat() = assertEquals(FrontendCardMode.COMPAT, decodeMode("\"SOMETHING_ELSE\""))

    @Test
    fun serializesOnlyNewNames() {
        assertEquals("\"COMPAT\"", json.encodeToString(FrontendCardMode.serializer(), FrontendCardMode.COMPAT))
        assertEquals("\"NATIVE\"", json.encodeToString(FrontendCardMode.serializer(), FrontendCardMode.NATIVE))
    }

    @Test
    fun persistedAssistantWithLegacyModeLoads() {
        val assistant = Assistant(id = Uuid.random(), name = "t", frontendCardMode = FrontendCardMode.COMPAT)
        val saved = json.encodeToString(Assistant.serializer(), assistant)
        for ((legacy, expected) in listOf(
            "TAVERN" to FrontendCardMode.COMPAT,
            "AUTO" to FrontendCardMode.COMPAT,
            "ALWAYS" to FrontendCardMode.COMPAT,
            "NEVER" to FrontendCardMode.NATIVE,
        )) {
            val patched = saved.replace("\"frontendCardMode\":\"COMPAT\"", "\"frontendCardMode\":\"$legacy\"")
            assertNotEquals(saved, patched)
            assertEquals(expected, json.decodeFromString(Assistant.serializer(), patched).frontendCardMode)
        }
    }
}
