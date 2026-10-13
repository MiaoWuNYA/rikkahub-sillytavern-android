package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型常把颜色标签转义后输出，Jsoup 会把 &lt;span&gt; 解码成纯文本，
 * 聊天里就出现「看得见颜色代码但不上色」。还原逻辑必须覆盖这类输入，
 * 同时不能动代码块里的转义内容。
 */
class EscapedInlineTagTest {
    @Test
    fun `escaped span is restored so color takes effect`() {
        val input = "前文 &lt;span style=\"color:#7EAFC4\"&gt;彩色&lt;/span&gt; 后文"
        val out = restoreEscapedInlineTags(input)
        assertTrue(out.contains("<span style=\"color:#7EAFC4\">彩色</span>"))
    }

    @Test
    fun `escaped quotes and nested tags are restored`() {
        val input = "&lt;span style=&quot;color:#E18A24&quot;&gt;&lt;b&gt;粗&lt;/b&gt;&lt;/span&gt;"
        val out = restoreEscapedInlineTags(input)
        assertTrue(out.contains("<span style=\"color:#E18A24\">"))
        assertTrue(out.contains("<b>"))
        assertTrue(out.contains("</span>"))
    }

    @Test
    fun `escaped tags inside code blocks stay escaped`() {
        val input = "```\n&lt;span style=\"color:#7EAFC4\"&gt;x&lt;/span&gt;\n```"
        assertEquals(input, restoreEscapedInlineTags(input))
    }

    @Test
    fun `plain text without escapes is untouched`() {
        val input = "普通文本 <span style=\"color:#7EAFC4\">彩色</span>"
        assertEquals(input, restoreEscapedInlineTags(input))
    }
}
