package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 聊天区背景必须能从 custom_css 里取到。
 *
 * 修复前只读 `chat_tint_color` / `blur_tint_color` 两个字段，
 * 主题把底色写在 CSS 里的一律丢失 —— 表现就是"只有纯色背景才可以生效"，
 * 实际只有恰好设了那两个字段的主题才生效。
 *
 * 实测全库 534 个带 CSS 主题：修复前能取到背景来源的仅 172 个，
 * 修复后 474 个（纯色底 442 + 渐变主色 10 + 背景图 182，有重叠）。
 */
class ChatBackgroundFromCssTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `solid background on a chat host is picked up`() {
        val css = "#sheld { background: #1d2128; }"
        assertEquals(0xFF1D2128L, extractChatBackgroundColor(css))
    }

    @Test
    fun `background written on body is picked up`() {
        val css = "body { background-color: rgb(12, 16, 21); }"
        assertEquals(0xFF0C1015L, extractChatBackgroundColor(css))
    }

    @Test
    fun `higher priority host wins over a lower one`() {
        val css = """
            #sheld { background: #111111; }
            body { background: #222222; }
        """.trimIndent()
        // body 的优先级高于 #sheld
        assertEquals(0xFF222222L, extractChatBackgroundColor(css))
    }

    @Test
    fun `bubble background is never mistaken for the chat background`() {
        // .mes_block 是气泡本体，它的底色不是聊天底色
        val css = """
            .mes_block { background: #ff0000; }
            #sheld { background: #00ff00; }
        """.trimIndent()
        assertEquals(0xFF00FF00L, extractChatBackgroundColor(css))
    }

    @Test
    fun `gradient falls back to its primary stop colour`() {
        val css = "body { background: linear-gradient(180deg, #090d12 0%, #0f151d 46%, #0a0f14 100%); }"
        // 渐变的第一个颜色停靠点即主色
        assertEquals(0xFF090D12L, extractChatBackgroundGradientColor(css))
        // 纯色提取对渐变也会退化成主色，两侧保持一致，避免上层再判一次
        assertEquals(0xFF090D12L, extractChatBackgroundColor(css))
    }

    @Test
    fun `empty or transparent background yields nothing`() {
        assertNull(extractChatBackgroundColor("body { background: transparent; }"))
        assertNull(extractChatBackgroundColor("#sheld { background: url('x.png'); }"))
        assertNull(extractChatBackgroundColor(null))
    }

    @Test
    fun `official variables are expanded while reading the chat background`() {
        val theme = json.decodeFromString<SillyTavernTheme>(
            """{"chat_tint_color": "rgba(0, 0, 0, 1)"}"""
        )
        val css = "#sheld { background-color: var(--SmartThemeChatTintColor); }"
        assertEquals(
            0xFF000000L,
            extractChatBackgroundColor(css, theme.officialCssVariables()),
        )
    }

    @Test
    fun `background image hosts now include sheld and drawer`() {
        // 修复前只认 body/#bg1/.bg1/#chat/#main，
        // 「去海边」的 .drawer-content 与 #sheld 全部取不到
        assertEquals(
            "https://example.com/a.jpg",
            extractBackgroundImageUrl("#sheld { background-image: url('https://example.com/a.jpg'); }"),
        )
        assertEquals(
            "https://example.com/b.jpg",
            extractBackgroundImageUrl(".drawer-content { background: url(\"https://example.com/b.jpg\") center/cover; }"),
        )
        // 「去海边」的 #sheld 主背景图
        assertEquals(
            "https://example.com/c.jpg",
            extractBackgroundImageUrl("#sheld { background: url('https://example.com/c.jpg') center/cover no-repeat; }"),
        )
    }

    @Test
    fun `applyTo uses the css background over the colour fields`() {
        val theme = json.decodeFromString<SillyTavernTheme>(
            """{
                 "chat_tint_color": "rgba(255, 0, 0, 1)",
                 "custom_css": "#sheld { background: #003366; }"
             }"""
        )
        val applied = theme.applyTo(me.rerere.rikkahub.data.datastore.DisplaySetting())
        assertEquals(
            "CSS 里的底色应当优先于字段",
            0xFF003366L,
            applied.chatBackgroundColor,
        )
    }

    @Test
    fun `applyTo still falls back to the colour fields when css has no background`() {
        val theme = json.decodeFromString<SillyTavernTheme>(
            """{"chat_tint_color": "rgba(0, 0, 0, 1)", "custom_css": ".mes_block { color: #fff; }"}"""
        )
        val applied = theme.applyTo(me.rerere.rikkahub.data.datastore.DisplaySetting())
        assertNotNull("没有 CSS 底色时应回落到字段推导", applied.chatBackgroundColor)
    }
}
