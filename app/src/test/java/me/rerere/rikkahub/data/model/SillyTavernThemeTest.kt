package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SillyTavernThemeTest {

    // ---- parseCssColor ----

    @Test
    fun `parseCssColor handles rgba with decimal alpha`() {
        assertEquals(0xD9FFFFFFL, parseCssColor("rgba(255, 255, 255, 0.85)"))
        assertEquals(0xFF000000L, parseCssColor("rgba(0, 0, 0, 1)"))
        assertEquals(0xBF58705CL, parseCssColor("rgba(88, 112, 92, 0.75)"))
        assertEquals(0x00000000L, parseCssColor("rgba(0, 0, 0, 0)"))
    }

    @Test
    fun `parseCssColor handles rgb without alpha`() {
        assertEquals(0xFF3E8694L, parseCssColor("rgb(62, 134, 148)"))
        // rgba 函数名但只有 3 个通道，视为不透明
        assertEquals(0xFF01020AL, parseCssColor("rgba(1, 2, 10)"))
    }

    @Test
    fun `parseCssColor handles hex variants`() {
        assertEquals(0xFFFF8000L, parseCssColor("#FF8000"))
        assertEquals(0xFFFF8000L, parseCssColor("ff8000")) // # 可省略
        assertEquals(0x80FF8000L, parseCssColor("#80FF8000")) // #AARRGGBB
        assertEquals(0xFFFF8800L, parseCssColor("#F80")) // #RGB
        assertEquals(0x88FF8800L, parseCssColor("#8F80")) // #ARGB
        assertEquals(0xFFFFFFFFL, parseCssColor("#FFFFFF"))
    }

    @Test
    fun `parseCssColor handles modern slash syntax and percentages`() {
        assertEquals(0x80FF0000L, parseCssColor("rgb(255 0 0 / 0.5)"))
        assertEquals(0x80FF0000L, parseCssColor("rgba(100%, 0%, 0%, 0.5)"))
        assertEquals(0xFF00FF00L, parseCssColor("rgb(0%, 100%, 0%)"))
    }

    @Test
    fun `parseCssColor clamps rgb channels and rejects bad alpha`() {
        // 通道超范围按 CSS 语义 clamp 到 0-255
        assertEquals(0xFFFF0000L, parseCssColor("rgb(300, -5, 0)"))
        // alpha 超出 [0,1] 视为非法
        assertNull(parseCssColor("rgba(0, 0, 0, 2)"))
        assertNull(parseCssColor("rgba(0, 0, 0, -0.5)"))
    }

    @Test
    fun `parseCssColor rejects invalid input`() {
        assertNull(parseCssColor(null))
        assertNull(parseCssColor(""))
        assertNull(parseCssColor("   "))
        assertNull(parseCssColor("notacolor"))
        assertNull(parseCssColor("rgba(1, 2)"))
        assertNull(parseCssColor("rgba()"))
        assertNull(parseCssColor("#12345"))
        assertNull(parseCssColor("#GGGGGG"))
    }

    // ---- parseSillyTavernTheme ----

    @Test
    fun `parseSillyTavernTheme parses real theme with unknown fields`() {
        // 精简自真实主题 薄荷马卡龙.json（字段值原样保留）
        val json = """
            {
              "bogus_folders": true,
              "blur_strength": 3,
              "blur_tint_color": "rgba(245, 252, 248, 0.4)",
              "border_color": "rgba(230, 242, 235, 0.7)",
              "bot_mes_blur_tint_color": "rgba(255, 255, 255, 0.85)",
              "chat_display": 1,
              "chat_tint_color": "rgba(250, 255, 252, 0.55)",
              "chat_width": 60,
              "compact_input_area": true,
              "custom_css": "/*==== 薄荷马卡龙主题 ====*/\nbody { color: red; }",
              "fast_ui_mode": false,
              "font_scale": 0.92,
              "hotswap_enabled": true,
              "italics_text_color": "rgba(140, 188, 156, 0.9)",
              "main_text_color": "rgba(88, 112, 92, 0.75)",
              "name": "薄荷马卡龙",
              "noShadows": true,
              "quote_text_color": "rgba(100, 130, 110, 0.7)",
              "shadow_color": "rgba(200, 220, 210, 0.2)",
              "show_swipe_num_all_messages": "",
              "underline_text_color": "rgba(110, 168, 130, 0.8)",
              "user_mes_blur_tint_color": "rgba(255, 255, 255, 0.85)"
            }
        """.trimIndent()

        val theme = parseSillyTavernTheme(json)

        assertEquals("薄荷马卡龙", theme.name)
        assertEquals(0.92, theme.fontScale)
        assertEquals("rgba(88, 112, 92, 0.75)", theme.mainTextColor)
        assertEquals(3.0, theme.blurStrength!!, 0.0)
        assertTrue(theme.customCss!!.startsWith("/*==== 薄荷马卡龙主题 ====*/"))
    }

    @Test
    fun `parseSillyTavernTheme tolerates old format with missing fields`() {
        val theme = parseSillyTavernTheme("""{"name": "老版主题"}""")
        assertEquals("老版主题", theme.name)
        assertNull(theme.mainTextColor)
        assertNull(theme.fontScale)
    }

    @Test
    fun `parseSillyTavernTheme coerces numeric fields written as strings`() {
        val theme = parseSillyTavernTheme("""{"name": "X", "font_scale": "1.25", "blur_strength": "3"}""")
        assertEquals(1.25, theme.fontScale)
        assertEquals(3.0, theme.blurStrength!!, 0.0)
        // 整数字段也可能写成小数字面量
        val theme2 = parseSillyTavernTheme("""{"blur_strength": 3.0}""")
        assertEquals(3.0, theme2.blurStrength!!, 0.0)
    }

    @Test
    fun `parseSillyTavernTheme rejects invalid json`() {
        assertThrows(IllegalArgumentException::class.java) { parseSillyTavernTheme("not json at all") }
        assertThrows(IllegalArgumentException::class.java) { parseSillyTavernTheme("[1, 2, 3]") }
        assertThrows(IllegalArgumentException::class.java) {
            parseSillyTavernTheme("""{"name": "X", "font_scale": {"a": 1}}""")
        }
    }

    // ---- applyTo ----

    private fun themeFromJson(json: String): SillyTavernTheme = parseSillyTavernTheme(json.trimIndent())

    @Test
    fun `applyTo maps all supported fields`() {
        val base = DisplaySetting()
        val theme = themeFromJson(
            """
            {
              "name": "薄荷马卡龙",
              "main_text_color": "rgba(88, 112, 92, 0.75)",
              "chat_tint_color": "rgba(250, 255, 252, 0.55)",
              "user_mes_blur_tint_color": "rgba(255, 255, 255, 0.85)",
              "bot_mes_blur_tint_color": "rgba(255, 255, 255, 0.85)",
              "quote_text_color": "rgba(100, 130, 110, 0.7)",
              "italics_text_color": "rgba(140, 188, 156, 0.9)",
              "font_scale": 0.92
            }
            """
        )

        val patched = theme.applyTo(base)

        assertEquals(0xBF58705CL, patched.globalTextColor)
        assertEquals(0x8CFAFFFCL, patched.chatBackgroundColor)
        assertEquals(0xD9FFFFFFL, patched.userBubbleColor)
        assertEquals(0xD9FFFFFFL, patched.assistantBubbleColor)
        assertEquals("#B364826E", patched.quoteColor)
        assertEquals("#E68CBC9C", patched.italicsColor)
        assertEquals(0.92f, patched.fontSizeRatio)
        // 未映射字段不受影响
        assertNull(patched.primaryColor)
        assertNull(patched.thinkingBubbleColor)
        assertNull(patched.inputFieldColor)
        assertEquals(1.0f, patched.bubbleOpacity)
        assertEquals(16f, patched.bubbleCornerRadius)
        assertEquals("", patched.userBubbleImagePath)
    }

    @Test
    fun `applyTo keeps base values for missing or unparseable colors`() {
        val base = DisplaySetting(
            globalTextColor = 0xFF111111L,
            chatBackgroundColor = 0xFF222222L,
            quoteColor = "#E18A24",
        )
        val theme = themeFromJson(
            """
            {
              "name": "老格式",
              "main_text_color": "notacolor",
              "font_scale": 1.25
            }
            """
        )

        val patched = theme.applyTo(base)

        assertEquals(0xFF111111L, patched.globalTextColor)
        assertEquals(0xFF222222L, patched.chatBackgroundColor)
        assertNull(patched.userBubbleColor)
        assertNull(patched.assistantBubbleColor)
        assertEquals("#E18A24", patched.quoteColor)
        assertEquals("", patched.italicsColor)
        assertEquals(1.25f, patched.fontSizeRatio)
    }

    @Test
    fun `applyTo clamps font scale to app supported range`() {
        val base = DisplaySetting()
        assertEquals(2.0f, parseSillyTavernTheme("""{"font_scale": 3.0}""").applyTo(base).fontSizeRatio)
        assertEquals(0.5f, parseSillyTavernTheme("""{"font_scale": 0.1}""").applyTo(base).fontSizeRatio)
        // 缺省/非法字号保持原值
        assertEquals(1.0f, parseSillyTavernTheme("""{"name": "X"}""").applyTo(base).fontSizeRatio)
    }

    @Test
    fun `applyTo on empty theme is a no-op`() {
        val base = DisplaySetting(
            fontSizeRatio = 1.3f,
            quoteColor = "#123456",
            globalTextColor = 0xFF010203L,
        )
        val patched = parseSillyTavernTheme("""{"name": "空主题"}""").applyTo(base)
        assertEquals(base, patched)
    }

    @Test
    fun `applyTo keeps opaque hex without alpha prefix for quote colors`() {
        val base = DisplaySetting()
        val patched = parseSillyTavernTheme(
            """
            {
              "quote_text_color": "rgb(203, 142, 22)",
              "italics_text_color": "rgba(14, 96, 122, 1)"
            }
            """.trimIndent()
        ).applyTo(base)
        assertEquals("#CB8E16", patched.quoteColor)
        assertEquals("#0E607A", patched.italicsColor)
    }
}
