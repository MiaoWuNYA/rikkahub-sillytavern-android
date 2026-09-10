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
        assertEquals(0xFFFF8800L, parseCssColor("#F80")) // #RGB
        assertEquals(0xFFFFFFFFL, parseCssColor("#FFFFFF"))
    }

    @Test
    fun `parseCssColor interprets 8-digit hex as RRGGBBAA per CSS spec`() {
        // 酒馆主题大量使用 CSS 规范的 #RRGGBBAA（如真实主题里的 #fbc4c450）
        assertEquals(0x50FBC4C4L, parseCssColor("#fbc4c450"))
        assertEquals(0xB59F9F9FL, parseCssColor("#9f9f9fb5"))
        // 4 位是 #RGBA
        assertEquals(0x00FF8844L, parseCssColor("#F840"))
        assertEquals(0x88FF8800L, parseCssColor("#F808"))
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
              "blur_tint_color": "rgba(245, 252, 248, 0.4)",
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
        // chat_tint 叠 blur_tint 叠浅色底（深色文字 → 浅底）合成不透明近似色
        assertEquals(0xFFF9FDFBL, patched.chatBackgroundColor)
        // 气泡色调叠在聊天背景上，合成后不透明
        assertEquals(0xFFFEFFFEL, patched.userBubbleColor)
        assertEquals(0xFFFEFFFEL, patched.assistantBubbleColor)
        // 引用/斜体色叠在聊天背景上合成不透明色，深浅背景下都可见
        assertEquals("#90A798", patched.quoteColor)
        assertEquals("#97C2A5", patched.italicsColor)
        // font_scale 0.92 → 偏差减半 0.96
        assertEquals(0.96f, patched.fontSizeRatio)
        // 无背景图时从 blur_tint 推导输入框颜色
        assertEquals(0xFFF8FBF9L, patched.inputFieldColor)
        // 未映射字段不受影响
        assertNull(patched.primaryColor)
        assertNull(patched.thinkingBubbleColor)
        assertEquals(1.0f, patched.bubbleOpacity)
        assertEquals(16f, patched.bubbleCornerRadius)
        assertEquals("", patched.userBubbleImagePath)
    }

    @Test
    fun `applyTo composites overlay-style dark bubble tints over light base`() {
        // 精简自真实主题 黑白糯米酒.json：低透明度气泡色调叠在深色 chat_tint 上
        val theme = themeFromJson(
            """
            {
              "name": "黑白糯米酒",
              "main_text_color": "rgba(0, 0, 0, 1)",
              "blur_tint_color": "rgba(255, 255, 255, 0.57)",
              "chat_tint_color": "rgba(0, 0, 0, 0.69)",
              "user_mes_blur_tint_color": "rgba(0, 0, 0, 0.11)",
              "bot_mes_blur_tint_color": "rgba(0, 0, 0, 0.22)"
            }
            """
        )

        val patched = theme.applyTo(DisplaySetting())

        // 250 底 → blur 后 (253,253,253) → chat 后 (78,78,78)
        assertEquals(0xFF4E4E4EL, patched.chatBackgroundColor)
        // 气泡 = 消息色调叠在 (78,78,78) 上
        assertEquals(0xFF454545L, patched.userBubbleColor)
        assertEquals(0xFF3D3D3DL, patched.assistantBubbleColor)
        assertEquals(0xFF000000L, patched.globalTextColor)
    }

    @Test
    fun `applyTo handles fully transparent bubble tints without crashing`() {
        // 精简自真实主题 蝶_桃花劫·画中仙：气泡/聊天色调 alpha 全为 0
        val theme = themeFromJson(
            """
            {
              "name": "画中仙",
              "main_text_color": "rgba(255, 255, 255, 1)",
              "blur_tint_color": "rgba(0, 0, 0, 0.91)",
              "chat_tint_color": "rgba(140, 120, 128, 0)",
              "user_mes_blur_tint_color": "rgba(255, 255, 255, 0)",
              "bot_mes_blur_tint_color": "rgba(255, 255, 255, 0)"
            }
            """
        )

        val patched = theme.applyTo(DisplaySetting())

        // 浅色文字 → 深色底；chat_tint 全透明 → 背景即 blur_tint 合成结果（接近纯黑）
        assertEquals(0xFFFFFFFFL, patched.globalTextColor)
        val chatBg = patched.chatBackgroundColor
        assertEquals(0xFFL, (chatBg ?: 0L) ushr 24)
        assertTrue("聊天背景应接近纯黑: $chatBg", ((chatBg ?: 0L) and 0xFFFFFFL) < 0x202020L)
        // 气泡色调全透明（酒馆里靠 CSS 背景图呈现）→ 保留应用原气泡色，避免气泡隐形
        assertNull(patched.userBubbleColor)
        assertNull(patched.assistantBubbleColor)
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
        assertEquals(1.125f, patched.fontSizeRatio)
    }

    @Test
    fun `applyTo clamps font scale to app supported range`() {
        val base = DisplaySetting()
        assertEquals(1.6f, parseSillyTavernTheme("""{"font_scale": 3.0}""").applyTo(base).fontSizeRatio)
        assertEquals(0.85f, parseSillyTavernTheme("""{"font_scale": 0.1}""").applyTo(base).fontSizeRatio)
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

    @Test
    fun `applyTo maps chat_display bubble mode to assistant bubble visibility`() {
        val base = DisplaySetting(showAssistantBubble = false)
        assertTrue(parseSillyTavernTheme("""{"chat_display": 1}""").applyTo(base).showAssistantBubble)
        val baseWithBubble = DisplaySetting(showAssistantBubble = true)
        // 平铺/文档模式不强制关闭用户的气泡设置（此前强制关闭导致"导入主题后气泡消失"）
        assertEquals(true, parseSillyTavernTheme("""{"chat_display": 0}""").applyTo(baseWithBubble).showAssistantBubble)
        assertEquals(true, parseSillyTavernTheme("""{"chat_display": 2}""").applyTo(baseWithBubble).showAssistantBubble)
        // 缺省保持原值
        assertEquals(true, parseSillyTavernTheme("""{"name": "X"}""").applyTo(baseWithBubble).showAssistantBubble)
    }

    @Test
    fun `applyTo damps font scale deviation to avoid tiny text`() {
        val base = DisplaySetting()
        // 主题 font_scale 普遍 0.8-0.9（中位 0.9），直接映射会明显偏小；偏差减半
        assertEquals(0.9f, parseSillyTavernTheme("""{"font_scale": 0.8}""").applyTo(base).fontSizeRatio)
        assertEquals(0.95f, parseSillyTavernTheme("""{"font_scale": 0.9}""").applyTo(base).fontSizeRatio)
        assertEquals(1.0f, parseSillyTavernTheme("""{"font_scale": 1.0}""").applyTo(base).fontSizeRatio)
        assertEquals(1.1f, parseSillyTavernTheme("""{"font_scale": 1.2}""").applyTo(base).fontSizeRatio)
        // 极端值 clamp
        assertEquals(0.85f, parseSillyTavernTheme("""{"font_scale": 0.1}""").applyTo(base).fontSizeRatio)
        assertEquals(1.6f, parseSillyTavernTheme("""{"font_scale": 3.0}""").applyTo(base).fontSizeRatio)
    }

    @Test
    fun `applyTo extracts bubble corner radius from custom css`() {
        val base = DisplaySetting()
        val theme = parseSillyTavernTheme(
            """
            {
              "name": "X",
              "custom_css": ".mes { border-radius: 12px !important; background: blue; } #chat_form { border-radius: 99px; }"
            }
            """.trimIndent()
        )
        assertEquals(12f, theme.applyTo(base).bubbleCornerRadius)
        // 多值取最大，.mes_text / #chat_form 不算消息气泡
        val theme2 = parseSillyTavernTheme(
            """{"custom_css": ".mes { border-radius: 0 8px 8px 0; }"}"""
        )
        assertEquals(8f, theme2.applyTo(base).bubbleCornerRadius)
        // 超范围 clamp 到滑条上限
        val theme3 = parseSillyTavernTheme("""{"custom_css": ".mes_block { border-radius: 50px; }"}""")
        assertEquals(28f, theme3.applyTo(base).bubbleCornerRadius)
        // 只有百分比 / 没有 px → 保持原值
        val theme4 = parseSillyTavernTheme("""{"custom_css": ".mes { border-radius: 50%; }"}""")
        assertEquals(16f, theme4.applyTo(base).bubbleCornerRadius)
    }

    @Test
    fun `extractBackgroundImageUrl finds chat background from theme css`() {
        // body 上的背景图
        assertEquals(
            "https://i.postimg.cc/a.png",
            extractBackgroundImageUrl("""body { background-image: url("https://i.postimg.cc/a.png") fixed center/cover; }""")
        )
        // #bg1 优先于 body
        assertEquals(
            "data:image/png;base64,AAAA",
            extractBackgroundImageUrl(
                """body { background-image: url(https://x/b.png); } #bg1 { background: url(data:image/png;base64,AAAA) no-repeat; }"""
            )
        )
        // 头像框等装饰图不误抓
        assertNull(extractBackgroundImageUrl(""".mes_avatar { background: url(https://x/avatar.png); }"""))
        // 没有背景图
        assertNull(extractBackgroundImageUrl("""body { color: red; }"""))
        assertNull(extractBackgroundImageUrl(null))
    }

    @Test
    fun `extractThemeFont picks ttf or otf fonts from font-face`() {
        // 精简自真实主题：URL 以 .ttf 结尾，format 为 truetype
        val font = extractThemeFont(
            """
            @font-face {
              font-family: 'cattie';
              src: url('https://x.example/%E4%B8%B9%E3%81%AE%E5%B0%8F%E5%90%90%E5%8F%B8-9.ttf') format('truetype');
              font-weight: normal;
            }
            """.trimIndent()
        )
        assertEquals("cattie", font?.family)
        assertEquals("https://x.example/%E4%B8%B9%E3%81%AE%E5%B0%8F%E5%90%90%E5%8F%B8-9.ttf", font?.url)
        // URL 以 .otf 结尾但 format 写成 WOFF2（真实主题常见错标），按扩展名采纳
        val font2 = extractThemeFont(
            """@font-face { font-family: "ZiTi"; src: url("http://x.example/SongSC-SemiBold.otf") format("WOFF2"); }"""
        )
        assertEquals("ZiTi", font2?.family)
        // 纯 woff/woff2 无法被 Android 原生加载 → 忽略
        val font3 = extractThemeFont(
            """@font-face { font-family: "W"; src: url("https://x.example/f.woff2") format("woff2"); }"""
        )
        assertNull(font3)
        // 没有 @font-face
        assertNull(extractThemeFont("""body { font-family: sans-serif; }"""))
        assertNull(extractThemeFont(null))
    }
}
