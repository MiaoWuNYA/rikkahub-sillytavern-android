package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 只圆部分角时取最大角：`0 8px 8px 0` 若取首值会得到 0、气泡变直角，
        // 作者本意是圆右侧两角，Compose 整体圆角取 8 最接近
        val theme2 = parseSillyTavernTheme(
            """{"custom_css": ".mes { border-radius: 0 8px 8px 0; }"}"""
        )
        assertEquals(8f, theme2.applyTo(base).bubbleCornerRadius)
        // 真实主题常见「只圆下方两角」，必须取到 12 而不是 0
        val theme2b = parseSillyTavernTheme(
            """{"custom_css": ".mes_block { border-radius: 0px 0px 12px 12px; }"}"""
        )
        assertEquals(12f, theme2b.applyTo(base).bubbleCornerRadius)
        // 大圆角不再被夹到 28px（旧实现会截断，导致"大圆角主题看起来几乎没圆角"）
        val theme3 = parseSillyTavernTheme("""{"custom_css": ".mes_block { border-radius: 50px; }"}""")
        assertEquals(40f, theme3.applyTo(base).bubbleCornerRadius)
        // 百分比圆角按胶囊处理，映射为固定大圆角
        val theme4 = parseSillyTavernTheme("""{"custom_css": ".mes { border-radius: 50%; }"}""")
        assertEquals(24f, theme4.applyTo(base).bubbleCornerRadius)
    }

    // ---- 图标主题 ----

    @Test
    fun `theme icons map send button image color size and hide`() {
        val css = """
            #send_but {
                background-image: url('https://x/send.png');
                color: #ff8800;
                font-size: 30px;
            }
        """.trimIndent()
        val icons = extractThemeIconSet(css)
        assertEquals("https://x/send.png", icons.sendImageUrl)
        // #ff8800 → ARGB 0xFFFF8800
        assertEquals(0xFFFF8800L, icons.sendTint)
        // 30px / 20px 基准 = 1.5 倍
        assertEquals(1.5f, icons.sendScale)
        assertTrue(!icons.hideSend)
    }

    @Test
    fun `theme icons detect hidden send button`() {
        val icons = extractThemeIconSet("#send_but { display: none; }")
        assertTrue(icons.hideSend)
    }

    @Test
    fun `background image none is not treated as a themed icon`() {
        // 作者清掉默认图标（为配合字体图标），不应把 "none" 当图片地址
        val icons = extractThemeIconSet("#send_but { background-image: none; color: rgb(1,2,3); }")
        assertNull(icons.sendImageUrl)
        assertEquals(0xFF010203L, icons.sendTint)
    }

    @Test
    fun `avatar frames are split by is_user side`() {
        val css = """
            .mes[is_user="true"] .avatar::before { background-image: url('https://x/u.png'); width: 112px; }
            .mes[is_user="false"] .avatar::before { background-image: url('https://x/b.png'); }
        """.trimIndent()
        val icons = extractThemeIconSet(css)
        assertEquals("https://x/u.png", icons.avatarFrame(forUser = true))
        assertEquals("https://x/b.png", icons.avatarFrame(forUser = false))
        // 112px 框相对 50px 基准 → 2.24，落在允许区间内
        assertEquals(2.24f, icons.avatarFrameScale)
    }

    @Test
    fun `generic avatar frame applies to both sides`() {
        val icons = extractThemeIconSet(".avatar::after { background-image: url('https://x/ring.png'); }")
        assertEquals("https://x/ring.png", icons.avatarFrame(forUser = true))
        assertEquals("https://x/ring.png", icons.avatarFrame(forUser = false))
    }

    @Test
    fun `empty css yields empty icon set`() {
        assertTrue(extractThemeIconSet(null).isEmpty)
        assertTrue(extractThemeIconSet("").isEmpty)
        assertTrue(extractThemeIconSet(".mes { color: red; }").isEmpty)
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

    // ---- 注释剥离：主题作者把占位串/说明写在注释里 ----

    @Test
    fun `comments are stripped before extracting background url`() {
        // 实测主题写法：注释里写占位串，真正的 url 在后面
        val css = """
            /*你的图片链接*/
            body { background-image: url('https://real.example/bg.jpg'); }
        """.trimIndent()
        assertEquals("https://real.example/bg.jpg", extractBackgroundImageUrl(css))
    }

    @Test
    fun `comment mentioning body does not hijack background extraction`() {
        // 注释里出现 body，不应让图标背景被当成聊天背景
        val css = """
            /* 这个 body 图标说明 */
            .drawer-icon { background-image: url('https://x/icon.png'); }
        """.trimIndent()
        assertNull(extractBackgroundImageUrl(css))
    }

    @Test
    fun `comment containing mes does not create bubble background`() {
        val css = """
            /* 修改 .mes 样式 */
        """.trimIndent()
        assertNull(extractBubbleBackgroundImageUrl(css, forUser = true))
        assertNull(extractBubbleBackgroundImageUrl(css, forUser = false))
    }

    // ---- 气泡底图 ----

    @Test
    fun `extractBubbleBackgroundImageUrl reads mes and mes_block`() {
        assertEquals(
            "https://x/bubble.png",
            extractBubbleBackgroundImageUrl(""".mes { background-image: url('https://x/bubble.png'); }""", true)
        )
        assertEquals(
            "https://x/block.png",
            extractBubbleBackgroundImageUrl(""".mes_block { background: url(https://x/block.png) no-repeat; }""", false)
        )
    }

    @Test
    fun `pseudo element on bubble body is treated as the bubble background`() {
        // 官方主题最主流的写法：几何与图片分处两条规则，几何在 .mes_block::before
        val css = """
            .mes_block::before {
                content: ''; position: absolute; top: 0; left: 0;
                width: 100%; height: 200px;
                background-size: cover; background-position: center;
            }
            .mes[is_user="false"] .mes_block::before { background-image: url('https://x/bot.png'); }
            .mes[is_user="true"] .mes_block::before { background-image: url('https://x/user.png'); }
        """.trimIndent()
        assertEquals("https://x/user.png", extractBubbleBackgroundImageUrl(css, forUser = true))
        assertEquals("https://x/bot.png", extractBubbleBackgroundImageUrl(css, forUser = false))
        assertEquals("cover", extractBubbleBackgroundSize(css))
    }

    @Test
    fun `pseudo element on unrelated element is not a bubble background`() {
        // 头像框、抽屉图标等装饰伪元素仍必须排除
        val css = """
            .avatar::before { background-image: url('https://x/frame.png'); }
            .drawer-icon::after { background-image: url('https://x/icon.png'); }
        """.trimIndent()
        assertNull(extractBubbleBackgroundImageUrl(css, forUser = true))
        assertNull(extractBubbleBackgroundImageUrl(css, forUser = false))
    }

    @Test
    fun `background size is read from a separate rule from the image`() {
        // 拆分书写：尺寸规则里没有 url，图片规则里没有尺寸
        val css = """
            .mes_block { background-size: contain; }
            .mes { background-image: url('https://x/a.png'); }
        """.trimIndent()
        assertEquals("contain", extractBubbleBackgroundSize(css))
    }

    @Test
    fun `extractBubbleBackgroundImageUrl respects is_user sides`() {
        val css = """
            .mes[is_user="true"] { background-image: url('https://x/user.png'); }
            .mes[is_user="false"] { background-image: url('https://x/bot.png'); }
        """.trimIndent()
        assertEquals("https://x/user.png", extractBubbleBackgroundImageUrl(css, forUser = true))
        assertEquals("https://x/bot.png", extractBubbleBackgroundImageUrl(css, forUser = false))
    }

    @Test
    fun `generic mes background applies to both sides`() {
        val css = """.mes { background-image: url('https://x/same.png'); }"""
        assertEquals("https://x/same.png", extractBubbleBackgroundImageUrl(css, forUser = true))
        assertEquals("https://x/same.png", extractBubbleBackgroundImageUrl(css, forUser = false))
    }

    @Test
    fun `side specific background wins over generic`() {
        val css = """
            .mes { background-image: url('https://x/generic.png'); }
            .mes[is_user="true"] { background-image: url('https://x/user.png'); }
        """.trimIndent()
        assertEquals("https://x/user.png", extractBubbleBackgroundImageUrl(css, forUser = true))
        assertEquals("https://x/generic.png", extractBubbleBackgroundImageUrl(css, forUser = false))
    }

    @Test
    fun `bubble background ignores css variable and unrelated elements`() {
        assertNull(extractBubbleBackgroundImageUrl(""".mes { background-image: var(--bg); }""", true))
        // .mes_text / .mes_buttons 是子元素，不是气泡本体
        assertNull(extractBubbleBackgroundImageUrl(""".mes_text { background: url(https://x/t.png); }""", true))
        assertNull(extractBubbleBackgroundImageUrl(null, true))
    }

    @Test
    fun `theme with bubble background enables assistant bubble`() {
        val base = DisplaySetting(showAssistantBubble = false)
        val theme = SillyTavernTheme(
            name = "bubble-card",
            customCss = """.mes_block { background-image: url('https://x/b.png'); }"""
        )
        // 底图需要承载元素，否则静默丢失
        assertTrue(theme.applyTo(base).showAssistantBubble)
    }

    // ---- 真实主题夹具回归 ----

    @Test
    fun `real theme with commented placeholder extracts the real url`() {
        // 朝雾系列：旧实现会导入注释里的字面量「你的图片链接」
        val url = extractBackgroundImageUrl(ThemeCssFixtures.PLACEHOLDER_IN_COMMENT)
        assertEquals("https://i.postimg.cc/43Jv3pM5/IMG-4662.jpg", url)
    }

    @Test
    fun `real theme comment mentioning body does not hijack background`() {
        assertNull(extractBackgroundImageUrl(ThemeCssFixtures.COMMENT_MENTIONS_BODY))
    }

    @Test
    fun `real theme with bubble texture on mes_block is extracted`() {
        assertEquals(
            "https://files.catbox.moe/im5vzu.jpeg",
            extractBubbleBackgroundImageUrl(ThemeCssFixtures.BUBBLE_ON_MES_BLOCK, forUser = false)
        )
        assertEquals(
            "https://files.catbox.moe/im5vzu.jpeg",
            extractBubbleBackgroundImageUrl(ThemeCssFixtures.BUBBLE_ON_MES_BLOCK, forUser = true)
        )
    }

    @Test
    fun `real theme bubble on mes applies to both sides`() {
        val css = ThemeCssFixtures.BUBBLE_ON_MES
        assertEquals("https://iili.io/fWSOCIR.png", extractBubbleBackgroundImageUrl(css, forUser = true))
        assertEquals("https://iili.io/fWSOCIR.png", extractBubbleBackgroundImageUrl(css, forUser = false))
    }

    @Test
    fun `real theme is_user sides are separated`() {
        val css = ThemeCssFixtures.BUBBLE_IS_USER
        assertEquals("https://x.example/user.png", extractBubbleBackgroundImageUrl(css, forUser = true))
        assertEquals("https://x.example/bot.png", extractBubbleBackgroundImageUrl(css, forUser = false))
    }

    @Test
    fun `real theme avatar frames are never treated as bubbles`() {
        val css = ThemeCssFixtures.PSEUDO_DECORATIONS
        assertNull(extractBubbleBackgroundImageUrl(css, forUser = true))
        assertNull(extractBubbleBackgroundImageUrl(css, forUser = false))
        assertNull(extractBackgroundImageUrl(css))
    }

    @Test
    fun `real theme ui shell skin yields no chat or bubble background`() {
        val css = ThemeCssFixtures.UI_SHELL_ONLY
        assertNull(extractBackgroundImageUrl(css))
        assertNull(extractBubbleBackgroundImageUrl(css, forUser = true))
        assertNull(extractBubbleBackgroundImageUrl(css, forUser = false))
    }

    @Test
    fun `avatar wrapper decoration is not a bubble background`() {
        // 真实主题 Titania_柴犬主题美化_0326 的写法：气泡本身没有底图，
        // 只在 .mesAvatarWrapper 上铺了一张 250px 宽的名牌图。
        // 旧实现按「祖先链里有 .mes 就算气泡」判定，会把名牌图导成气泡底图，
        // 表现为气泡背景完全不对、主题真正的 background-color 反而看不见。
        val css = """
            .mes { border: none !important; background-color: #7C4F49 !important; }
            .mes_block { background-color: #7C4F49; border-radius: 0px 0px 12px 12px; }
            .mes[is_user='true'] .mesAvatarWrapper {
                width: 100%; height: 100px; position: relative;
                background: url(https://x/nameplate-user.jpg) repeat center;
            }
            .mes[is_user='false'] .mesAvatarWrapper {
                width: 100%; height: 100px; position: relative;
                background: url(https://x/nameplate-bot.jpg) repeat center;
            }
        """.trimIndent()
        assertNull(extractBubbleBackgroundImageUrl(css, forUser = true))
        assertNull(extractBubbleBackgroundImageUrl(css, forUser = false))
    }

    @Test
    fun `avatar wrapper pseudo decoration is not a bubble background`() {
        val css = """
            .mes { background-color: #222; }
            .mes[is_user='true'] .mesAvatarWrapper::before {
                content: ''; position: absolute; width: 250px; height: 100px;
                background: url(https://x/plate.png) no-repeat center;
            }
            .mes[is_user='true'] .mesAvatarWrapper .avatar::after {
                content: ''; position: absolute; width: 100%; height: 100%;
                background: url(https://x/frame.png) no-repeat center / contain;
            }
        """.trimIndent()
        assertNull(extractBubbleBackgroundImageUrl(css, forUser = true))
    }

    @Test
    fun `avatar image no longer leaks into bubble when real bubble art exists`() {
        // 既有真气泡底图、又有名牌图时，必须取气泡那张
        val css = """
            .mes_block::before {
                content: ''; position: absolute; width: 100%; height: 200px;
                background-image: url(https://x/bubble-user.png);
                background-size: cover;
            }
            .mes[is_user='true'] .mes_block::before {
                background-image: url(https://x/bubble-user.png);
            }
            .mes[is_user='true'] .mesAvatarWrapper {
                width: 100%; height: 100px; background: url(https://x/nameplate.jpg);
            }
        """.trimIndent()
        assertEquals("https://x/bubble-user.png", extractBubbleBackgroundImageUrl(css, forUser = true))
    }

    @Test
    fun `search icon maps from character list search entry`() {
        val css = """
            #rm_print_characters_block { color: #ff8800; }
            #rm_print_characters_block::before {
                background-image: url(https://x/search.png);
                font-size: 40px;
            }
        """.trimIndent()
        val icons = extractThemeIconSet(css)
        assertEquals("https://x/search.png", icons.searchImageUrl)
        assertEquals(0xFFFF8800L, icons.searchTint)
    }

    @Test
    fun `settings icon maps from drawer entry`() {
        val css = """
            #rightNavDrawerIcon {
                background-image: url(https://x/gear.svg);
                color: rgba(12, 34, 56, 1);
            }
        """.trimIndent()
        val icons = extractThemeIconSet(css)
        assertEquals("https://x/gear.svg", icons.settingsImageUrl)
        assertEquals(0xFF0C2238L, icons.settingsTint)
    }

    @Test
    fun `model and reasoning icons have their own slots`() {
        val css = """
            #generic_model_select { background-image: url(https://x/model.png); }
            #reasoning_effort { background-image: url(https://x/think.png); }
        """.trimIndent()
        val icons = extractThemeIconSet(css)
        assertEquals("https://x/model.png", icons.modelImageUrl)
        assertEquals("https://x/think.png", icons.reasoningImageUrl)
        // 有专属槽位时直接用它，不参与回退
        assertEquals("https://x/model.png", icons.effectiveModelImageUrl)
        assertEquals("https://x/think.png", icons.effectiveReasoningImageUrl)
    }

    @Test
    fun `missing slots reuse the theme icon instead of built-in default`() {
        // 主题只给了发送按钮的图：搜索/设置/模型/思考等级必须复用这张，
        // 否则界面上会一半是主题画风、一半是内置矢量图标
        val css = """
            #send_but {
                background-image: url(https://x/theme-icon.png);
                color: #ff0000;
                font-size: 30px;
            }
        """.trimIndent()
        val icons = extractThemeIconSet(css)
        assertEquals("https://x/theme-icon.png", icons.sendImageUrl)
        assertNull(icons.searchImageUrl)
        assertEquals("https://x/theme-icon.png", icons.effectiveSearchImageUrl)
        assertEquals("https://x/theme-icon.png", icons.effectiveSettingsImageUrl)
        assertEquals("https://x/theme-icon.png", icons.effectiveModelImageUrl)
        assertEquals("https://x/theme-icon.png", icons.effectiveReasoningImageUrl)
        // 颜色与缩放跟随同一槽位
        assertEquals(0xFFFF0000L, icons.effectiveSearchTint)
        assertEquals(1.5f, icons.effectiveSearchScale)
    }

    @Test
    fun `fallback prefers the most prominent available theme icon`() {
        // 同时有菜单与停止图标时，应优先复用更显眼的菜单图标
        val css = """
            #options_button { background-image: url(https://x/menu.png); }
            #mes_stop { background-image: url(https://x/stop.png); }
        """.trimIndent()
        val icons = extractThemeIconSet(css)
        assertEquals("https://x/menu.png", icons.effectiveSearchImageUrl)
    }

    @Test
    fun `no theme icon anywhere means no fallback`() {
        val icons = extractThemeIconSet(".mes { color: #fff; }")
        assertTrue(icons.isEmpty)
        assertNull(icons.effectiveSearchImageUrl)
        assertNull(icons.effectiveSettingsImageUrl)
        assertNull(icons.effectiveModelImageUrl)
        assertNull(icons.effectiveReasoningImageUrl)
    }

    @Test
    fun `extra icon slots keep the set non-empty`() {
        // 只有搜索图标时，isEmpty 必须为 false，否则 applyTo 会把整套图标丢掉
        val icons = extractThemeIconSet("#search { color: #ffffff; }")
        assertFalse(icons.isEmpty)
        assertNull(icons.sendImageUrl)
    }

    @Test
    fun `extracted radius never exceeds the shared slider cap`() {
        // 提取端与设置滑条共用 MAX_BUBBLE_RADIUS_DP；若不一致，
        // 主题圆角会被滑条静默夹小，表现为"圆角程度不对"
        val css = """
            .mes_block { border-radius: 999px; }
        """.trimIndent()
        val r = extractBubbleCornerRadius(css)
        assertEquals(MAX_BUBBLE_RADIUS_DP, r)
    }

    // ── 气泡背景 / 内层样式 / 字体：对齐官方主题的真实写法 ──

    @Test
    fun `blockless import does not swallow the following variable scope`() {
        // 534 个主题里 389 个（73%）用 @import 引第三方字体。旧解析器的 CSS_RULE
        // 匹配不了无块 at-rule，会把它并进下一条规则的**选择器**，于是 :root 不再是
        // 独立作用域、全部 CSS 变量丢失，气泡背景 var(--paper) 永远解析不出来。
        val css = """
            @import url("https://fontsapi.zeoseven.com/309/main/result.css");
            :root { --paper: #fff; }
            .mes .mes_block { background: var(--paper) !important; }
        """.trimIndent()
        val bg = extractBubbleBackgroundColor(css)
        assertEquals(0xFFFFFFFFL, bg?.generic?.color)
    }

    @Test
    fun `important suffix does not break colour parsing`() {
        // 主题大量写 `background: var(--x) !important`，展开后得到 "#fff !important"，
        // 直接喂给颜色解析会失败，整条规则被静默丢弃 —— 这就是"气泡不生效"的成因。
        val css = """
            .mes_block { background: #fff !important; }
        """.trimIndent()
        assertEquals(0xFFFFFFFFL, extractBubbleBackgroundColor(css)?.generic?.color)
    }

    @Test
    fun `inner mes_block fill outranks transparent outer mes`() {
        // 官方双层结构：.mes 是外层容器（常被清成 transparent），.mes_block 才是气泡本体。
        // 按出现顺序取第一条会把外层 transparent 当成气泡背景。
        val css = """
            .mes { background: transparent; }
            .mes .mes_block { background: #fff; }
        """.trimIndent()
        val bg = extractBubbleBackgroundColor(css)
        assertEquals(0xFFFFFFFFL, bg?.generic?.color)
    }

    @Test
    fun `explicitly transparent bubble stays transparent`() {
        // 113 个主题刻意把气泡做成透明的（内容直接浮在背景图上），
        // 不能被回落成应用默认气泡色，否则这些主题全部失真。
        val css = """
            .mes .mes_block { background: transparent !important; border: 0; }
        """.trimIndent()
        val bg = extractBubbleBackgroundColor(css)
        assertTrue(bg?.generic?.transparent == true)
        assertNull(bg?.generic?.color)
    }

    @Test
    fun `gradient bubble background keeps its first colour stop`() {
        val css = """
            .mes .mes_block { background: linear-gradient(180deg, #ffeedd 0%, #ffccdd 100%); }
        """.trimIndent()
        assertEquals(0xFFFFEEDDL, extractBubbleBackgroundColor(css)?.generic?.color)
    }

    @Test
    fun `inner element styles are extracted from real theme shapes`() {
        // 主题给代码框/引用块/高亮写的远不止颜色：底色、圆角、左侧竖线都要带出来。
        val css = """
            .mes .mes_text blockquote { background: #f0f0f080; border-inline-start: 3px solid #c8c8c866; }
            .mes .mes_text code { background: #ccedfc26; color: #7ab8d9; }
            .mes .mes_text pre { background: #fff; border: 1px solid #dcdcdc66; border-radius: 12px; }
            .mes .mes_text mark { background: #ccedfc4d; }
        """.trimIndent()
        val s = extractThemeMarkdownStyle(css)
        assertFalse(s.isEmpty)
        assertEquals(0xFF7AB8D9L, s.inlineCodeColorArgb)
        assertEquals(12f, s.codeBlockCornerRadiusDp)
        assertEquals(3f, s.quoteAccentWidthDp)
        assertTrue(s.markBackgroundArgb != null)
    }

    @Test
    fun `empty markdown style when theme defines none`() {
        // 主题没写这些元素时必须返回空样式，渲染层才能走回自己的默认外观，
        // 而不是把元素涂成某种"主题色"。
        assertTrue(extractThemeMarkdownStyle(".mes { background: #fff; }").isEmpty)
    }

    @Test
    fun `import font carries the declared family and is marked remote`() {
        // 73% 的主题用 @import 引第三方字体表；woff2 无法被 Android 加载，
        // 所以必须标记 isRemoteCss，由调用方解析出 ttf/otf 才应用。
        val css = """
            @import url("https://fontsapi.zeoseven.com/309/main/result.css");
            body { font-family: "KingHwaOldSong", sans-serif; }
        """.trimIndent()
        val f = extractThemeFont(css)
        assertEquals("KingHwaOldSong", f?.family)
        assertTrue(f?.isRemoteCss == true)
    }

    @Test
    fun `local ttf font face wins over remote import`() {
        val css = """
            @import url("https://example.com/f.css");
            @font-face { font-family: "MyFace"; src: url("https://example.com/a.ttf") format("truetype"); }
        """.trimIndent()
        val f = extractThemeFont(css)
        assertEquals("MyFace", f?.family)
        assertEquals("https://example.com/a.ttf", f?.url)
        assertFalse(f?.isRemoteCss == true)
    }

    @Test
    fun `woff2 only font face is not treated as loadable`() {
        // Android Typeface.createFromFile 不支持 woff2，也没有内置解码器，
        // 这类 @font-face 必须被跳过，否则会下到一个永远加载不了的"字体"。
        val css = """
            @font-face { font-family: "W2"; src: url("https://example.com/a.woff2") format("woff2"); }
        """.trimIndent()
        assertNull(extractThemeFont(css))
    }
}
