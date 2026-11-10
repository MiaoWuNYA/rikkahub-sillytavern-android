package me.rerere.rikkahub.data.st.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StSlashParserTest {

    @Test
    fun `parses plain send command`() {
        val segments = StSlashParser.parseParts("/send hello")
        assertEquals(1, segments.size)
        assertEquals("send", segments[0].command)
        assertEquals("hello", segments[0].args)
        assertTrue(segments[0].isCommand)
    }

    @Test
    fun `parses command with named arguments`() {
        val segments = StSlashParser.parseParts("/sys name=Alice at=2 hello there")
        assertEquals("sys", segments[0].command)

        val (named, rest) = StSlashParser.extractNamedArgs(segments[0].args)
        assertEquals("Alice", named["name"])
        assertEquals("2", named["at"])
        assertEquals("hello there", rest)
    }

    @Test
    fun `splits pipe respecting quotes`() {
        val segments = StSlashParser.parseParts("/send \"a | b\" | /trigger")
        assertEquals(2, segments.size)
        assertEquals("send", segments[0].command)
        assertEquals("\"a | b\"", segments[0].args)
        assertEquals("trigger", segments[1].command)
        assertFalse(segments[1].noPipeInject)
    }

    @Test
    fun `double pipe disables pipe injection`() {
        val segments = StSlashParser.parseParts("/setvar key=x || /getvar key")
        assertEquals(2, segments.size)
        assertEquals("setvar", segments[0].command)
        assertTrue(segments[1].noPipeInject)
    }

    @Test
    fun `unwrap quotes keeps inner text`() {
        assertEquals("hello world", StSlashParser.unwrapQuotes("\"hello world\""))
        assertEquals("hello", StSlashParser.unwrapQuotes("hello"))
    }
}
