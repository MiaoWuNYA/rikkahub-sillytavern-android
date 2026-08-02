package me.rerere.rikkahub

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.SlashVarOp
import me.rerere.rikkahub.ui.components.ai.applyMacroVarSlash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 变量斜杠命令语义测试（对齐酒馆官方 variables.js）。
 */
class MacroVarSlashOpsTest {

    private val chatKey = "chat-1"

    @Test
    fun `set then get chat variable`() {
        var settings = Settings()
        val (s1, r1) = applyMacroVarSlash(settings, SlashVarOp.SET, "color", "red", global = false, chatKey)
        assertEquals("color = red", r1)
        assertEquals("red", s1.macroChatVariables[chatKey]?.get("color"))

        val (s2, r2) = applyMacroVarSlash(s1, SlashVarOp.GET, "color", "", global = false, chatKey)
        assertEquals("red", r2)
        assertSame(s1, s2)
    }

    @Test
    fun `get missing variable returns placeholder`() {
        val base = Settings()
        val (s, r) = applyMacroVarSlash(base, SlashVarOp.GET, "missing", "", global = false, chatKey)
        assertEquals("（未设置）", r)
        assertSame(base, s) // no change -> same instance
    }

    @Test
    fun `add performs numeric addition and string concatenation`() {
        var settings = Settings()
        val (s1, _) = applyMacroVarSlash(settings, SlashVarOp.SET, "score", "10", global = false, chatKey)
        settings = s1

        val (s2, r2) = applyMacroVarSlash(settings, SlashVarOp.ADD, "score", "5", global = false, chatKey)
        assertEquals("15", r2)
        assertEquals("15", s2.macroChatVariables[chatKey]?.get("score"))

        val (s3, r3) = applyMacroVarSlash(s2, SlashVarOp.ADD, "name", "小村", global = false, chatKey)
        assertEquals("小村", r3)

        val (s4, r4) = applyMacroVarSlash(s3, SlashVarOp.ADD, "name", "学者", global = false, chatKey)
        assertEquals("小村学者", r4)
        assertEquals("小村学者", s4.macroChatVariables[chatKey]?.get("name"))
    }

    @Test
    fun `inc and dec default to zero`() {
        val (s1, r1) = applyMacroVarSlash(Settings(), SlashVarOp.INC, "count", "", global = false, chatKey)
        assertEquals("1", r1)

        val (s2, r2) = applyMacroVarSlash(s1, SlashVarOp.DEC, "count", "", global = false, chatKey)
        assertEquals("0", r2)
    }

    @Test
    fun `flush deletes variable`() {
        val (s1, _) = applyMacroVarSlash(Settings(), SlashVarOp.SET, "temp", "x", global = false, chatKey)
        val (s2, r2) = applyMacroVarSlash(s1, SlashVarOp.FLUSH, "temp", "", global = false, chatKey)
        assertEquals("已删除 temp", r2)
        assertEquals(null, s2.macroChatVariables[chatKey]?.get("temp"))
    }

    @Test
    fun `global variables are separate from chat variables`() {
        val (s1, _) = applyMacroVarSlash(Settings(), SlashVarOp.SET, "flag", "1", global = true, chatKey)
        assertEquals("1", s1.macroGlobalVariables["flag"])
        assertEquals(true, s1.macroChatVariables.isEmpty())

        val (s2, r2) = applyMacroVarSlash(s1, SlashVarOp.GET, "flag", "", global = true, chatKey)
        assertEquals("1", r2)

        // chat scope should not see the global variable
        val (_, r3) = applyMacroVarSlash(s1, SlashVarOp.GET, "flag", "", global = false, chatKey)
        assertEquals("（未设置）", r3)
    }

    @Test
    fun `list shows chat and global variables`() {
        var settings = Settings()
        val (s1, _) = applyMacroVarSlash(settings, SlashVarOp.SET, "a", "1", global = false, chatKey)
        settings = s1
        val (s2, _) = applyMacroVarSlash(settings, SlashVarOp.SET, "g", "9", global = true, chatKey)
        settings = s2

        val (s3, r) = applyMacroVarSlash(settings, SlashVarOp.LIST, "", "", global = false, chatKey)
        assertEquals("本对话: a=1\n全局: g=9", r)
        assertSame(settings, s3)

        val (_, rEmpty) = applyMacroVarSlash(Settings(), SlashVarOp.LIST, "", "", global = false, chatKey)
        assertEquals("（暂无变量）", rEmpty)
    }

    @Test
    fun `chat variables are scoped per conversation`() {
        val (s1, _) = applyMacroVarSlash(Settings(), SlashVarOp.SET, "secret", "v", global = false, "chat-1")
        val (_, r) = applyMacroVarSlash(s1, SlashVarOp.GET, "secret", "", global = false, "chat-2")
        assertEquals("（未设置）", r)
    }
}
