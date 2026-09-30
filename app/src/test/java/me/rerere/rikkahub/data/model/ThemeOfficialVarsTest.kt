package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酒馆官方 CSS 变量表回归测试。
 *
 * 主题大量写 `var(--SmartThemeChatTintColor)` / `var(--ui-color-main)`
 * 这类**官方运行时注入**的变量，主题文件里并不定义。
 * 若不注入，var() 展开不出值，整条声明被丢弃，
 * 表现就是"气泡完全不生效、所有主题背景都不生效"。
 *
 * 实测 534 个主题里 457 个（86%）引用了至少一个自身未定义的变量。
 */
class ThemeOfficialVarsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `official variables are derived from the theme colour fields`() {
        val theme = json.decodeFromString<SillyTavernTheme>(
            """
            {
              "name": "t",
              "main_text_color": "rgba(38, 78, 156, 1)",
              "chat_tint_color": "rgba(0, 0, 0, 1)",
              "bot_mes_blur_tint_color": "rgba(29, 33, 40, 0.9)",
              "border_color": "rgba(0, 0, 0, 1)",
              "shadow_width": 3
            }
            """.trimIndent()
        )
        val vars = theme.officialCssVariables()
        assertEquals("rgba(38, 78, 156, 1)", vars["--SmartThemeBodyColor"])
        assertEquals("rgba(0, 0, 0, 1)", vars["--SmartThemeChatTintColor"])
        assertEquals("rgba(29, 33, 40, 0.9)", vars["--SmartThemeBotMesBlurTintColor"])
        assertEquals("3px", vars["--shadowWidth"])
        // 别名：--ui-color-main 跟随气泡色
        assertEquals("rgba(29, 33, 40, 0.9)", vars["--ui-color-main"])
        // 别名：--chat-background-color 跟随聊天色调
        assertEquals("rgba(0, 0, 0, 1)", vars["--chat-background-color"])
    }

    @Test
    fun `theme's own variable definition wins over the injected table`() {
        // 主题自己定义的 --ui-color-main 必须盖过我们从字段推导的别名，
        // 否则作者显式写的颜色会被悄悄改掉。
        val css = """
            :root { --ui-color-main: #123456; }
            .mes { background-color: var(--ui-color-main); }
        """.trimIndent()
        val theme = json.decodeFromString<SillyTavernTheme>(
            """{"bot_mes_blur_tint_color": "rgba(29, 33, 40, 0.9)"}"""
        )
        val vars = theme.officialCssVariables()
        val rules = parseCssRules(css, vars).filter { it.selector == ".mes" }
        val bg = rules.flatMap { it.declarations }.first { it.property == "background-color" }
        assertEquals("#123456", bg.value)
    }

    @Test
    fun `smart theme tokens resolve instead of dropping the declaration`() {
        // 修复前：var(--SmartThemeBorderColor) 展开不出值 -> 整条 border 丢失
        val css = ".mes_block { border: 1px solid var(--SmartThemeBorderColor); }"
        val theme = json.decodeFromString<SillyTavernTheme>(
            """{"border_color": "rgba(10, 20, 30, 1)"}"""
        )
        val rules = parseCssRules(css, theme.officialCssVariables())
        val border = rules.flatMap { it.declarations }.first { it.property == "border" }
        assertEquals("1px solid rgba(10, 20, 30, 1)", border.value)
    }

    @Test
    fun `unknown official variables still yield nothing rather than a literal var`() {
        val theme = json.decodeFromString<SillyTavernTheme>("""{"name":"bare"}""")
        assertTrue(theme.officialCssVariables().isEmpty())
    }

    @Test
    fun `bubble colour extraction sees through the injected variable table`() {
        // 真实形态（Titania_柴犬）：.mes 用 var(--ui-color-main)，
        // .mes_block 才是真正的气泡色，且 .mes 带 !important。
        val css = """
            .mes { background-color: var(--ui-color-main) !important; }
            .mes_block { background-color: #7C4F49; border-radius: 0px 0px 12px 12px; }
        """.trimIndent()
        val theme = json.decodeFromString<SillyTavernTheme>(
            """{"bot_mes_blur_tint_color": "rgba(29, 33, 40, 0.9)"}"""
        )
        val bg = extractBubbleBackgroundColor(css, theme.officialCssVariables())
        assertNotNull(bg)
        // 关键断言：var() 必须被展开成真实颜色。
        // 修复前 --ui-color-main 无定义，.mes 那条 !important 声明整条丢失，
        // 取到的会是 .mes_block 的 #7C4F49(=4286336841)。
        // 现在 .mes 的 !important 正常参与层叠，赢下的是它展开后的 rgba(29,33,40,.9)。
        assertEquals(
            "var() 未展开时会退化成 .mes_block 的 #7C4F49",
            3860668712L,
            bg!!.generic!!.color!!.toLong() and 0xFFFFFFFFL,
        )
        assertEquals(12f, extractBubbleCornerRadius(css, theme.officialCssVariables()))
    }
}
