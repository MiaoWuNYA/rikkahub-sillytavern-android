package me.rerere.rikkahub.data.st.macro

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * 酒馆宏引擎移植后的基本行为验证：{{char}} / {{user}} / {{time}}。
 */
class MacroSubstitutionTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-03-05T14:07:00Z"), ZoneOffset.UTC)

    private fun testEnv(): MacroEnv = MacroEnv(
        names = MacroEnv.Names(user = "Alice", char = "Bob"),
        clock = fixedClock,
        zone = ZoneOffset.UTC,
        locale = Locale.US,
    )

    @Before
    fun setUp() {
        MacroDefinitions.registerAll()
    }

    @Test
    fun substitutesCharAndUserNames() {
        val out = MacroEngine.evaluate("{{user}} meets {{char}}.", testEnv())
        assertEquals("Alice meets Bob.", out)
    }

    @Test
    fun substitutesTimeWithInjectedClock() {
        val out = MacroEngine.evaluate("{{time}}", testEnv())
        assertEquals("2:07 PM", out)
    }

    @Test
    fun scopedAndVariableMacrosRoundTrip() {
        val out = MacroEngine.evaluate("{{setvar::mood::happy}}mood={{getvar::mood}}", testEnv())
        assertEquals("mood=happy", out)
    }

    @Test
    fun leavesUnknownMacrosAsIs() {
        val out = MacroEngine.evaluate("{{notAMacro123}}", testEnv())
        assertEquals("{{notAMacro123}}", out)
    }
}
