package me.rerere.rikkahub

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.SlashVarOp
import me.rerere.rikkahub.ui.components.ai.applyMacroVarSlash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 变量斜杠命令语义测试（对齐酒馆官方 variables.js）。
 * context 传 null（纯 JVM 测试环境），文案走内置中文回退。
 */
class MacroVarSlashOpsTest {

    private val chatKey = "chat-1"

    @Test
    fun `set then get chat variable`() {
        var settings = Settings()
        val (s1, r1) = applyMacroVarSlash(context = null, settings = settings, op = SlashVarOp.SET, name = "color", value = "red", chatKey = chatKey)
        assertEquals("color = red", r1)
        assertEquals("red", s1.macroChatVariables[chatKey]?.get("color"))

        val (s2, r2) = applyMacroVarSlash(context = null, settings = s1, op = SlashVarOp.GET, name = "color", value = "", chatKey = chatKey)
        assertEquals("red", r2)
        assertSame(s1, s2)
    }

    @Test
    fun `get missing variable returns placeholder`() {
        val base = Settings()
        val (s, r) = applyMacroVarSlash(context = null, settings = base, op = SlashVarOp.GET, name = "missing", value = "", chatKey = chatKey)
        assertEquals("（未设置）", r)
        assertSame(base, s) // no change -> same instance
    }

    @Test
    fun `add performs numeric addition and string concatenation`() {
        var settings = Settings()
        val (s1, _) = applyMacroVarSlash(context = null, settings = settings, op = SlashVarOp.SET, name = "score", value = "10", chatKey = chatKey)
        settings = s1

        val (s2, r2) = applyMacroVarSlash(context = null, settings = settings, op = SlashVarOp.ADD, name = "score", value = "5", chatKey = chatKey)
        assertEquals("15", r2)
        assertEquals("15", s2.macroChatVariables[chatKey]?.get("score"))

        val (s3, r3) = applyMacroVarSlash(context = null, settings = s2, op = SlashVarOp.ADD, name = "name", value = "小村", chatKey = chatKey)
        assertEquals("小村", r3)

        val (s4, r4) = applyMacroVarSlash(context = null, settings = s3, op = SlashVarOp.ADD, name = "name", value = "学者", chatKey = chatKey)
        assertEquals("小村学者", r4)
        assertEquals("小村学者", s4.macroChatVariables[chatKey]?.get("name"))
    }

    @Test
    fun `inc and dec default to zero`() {
        val (s1, r1) = applyMacroVarSlash(context = null, settings = Settings(), op = SlashVarOp.INC, name = "count", value = "", chatKey = chatKey)
        assertEquals("1", r1)

        val (s2, r2) = applyMacroVarSlash(context = null, settings = s1, op = SlashVarOp.DEC, name = "count", value = "", chatKey = chatKey)
        assertEquals("0", r2)
    }

    @Test
    fun `flush deletes variable`() {
        val (s1, _) = applyMacroVarSlash(context = null, settings = Settings(), op = SlashVarOp.SET, name = "temp", value = "x", chatKey = chatKey)
        val (s2, r2) = applyMacroVarSlash(context = null, settings = s1, op = SlashVarOp.FLUSH, name = "temp", value = "", chatKey = chatKey)
        assertEquals("已删除 temp", r2)
        assertEquals(null, s2.macroChatVariables[chatKey]?.get("temp"))
    }

    @Test
    fun `list shows chat variables`() {
        var settings = Settings()
        val (s1, _) = applyMacroVarSlash(context = null, settings = settings, op = SlashVarOp.SET, name = "a", value = "1", chatKey = chatKey)
        settings = s1
        val (s2, _) = applyMacroVarSlash(context = null, settings = settings, op = SlashVarOp.SET, name = "g", value = "9", chatKey = chatKey)
        settings = s2

        val (s3, r) = applyMacroVarSlash(context = null, settings = settings, op = SlashVarOp.LIST, name = "", value = "", chatKey = chatKey)
        assertEquals("本对话: a=1、g=9", r)
        assertSame(settings, s3)

        val (_, rEmpty) = applyMacroVarSlash(context = null, settings = Settings(), op = SlashVarOp.LIST, name = "", value = "", chatKey = chatKey)
        assertEquals("（暂无变量）", rEmpty)
    }

    @Test
    fun `chat variables are scoped per conversation`() {
        val (s1, _) = applyMacroVarSlash(context = null, settings = Settings(), op = SlashVarOp.SET, name = "secret", value = "v", chatKey = "chat-1")
        val (_, r) = applyMacroVarSlash(context = null, settings = s1, op = SlashVarOp.GET, name = "secret", value = "", chatKey = "chat-2")
        assertEquals("（未设置）", r)
    }
}
