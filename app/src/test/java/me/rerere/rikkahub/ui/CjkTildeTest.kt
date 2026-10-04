package me.rerere.rikkahub.ui

import me.rerere.rikkahub.ui.components.richtext.normalizeCjkTildes
import me.rerere.rikkahub.ui.components.richtext.preProcessMarkdown
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中文语气波浪线不能被 GFM 当成删除线。
 *
 * 中文里 `~` 是语气/拖长音记号（`啊~嗯❤~`、`好舒服~`），不是删除线语法。
 * 但 GFM 的删除线既支持 `~~文字~~` 也支持单个 `~文字~`，于是
 *     "啊~嗯❤~"是这个...啊~嗯❤~
 * 这类两段语气词之间的内容会被整段吞成删除线——表现为回复里莫名其妙
 * 出现一条划线。这是中文角色扮演文本的高频写法。
 *
 * 处理策略刻意保守：只改单个 ~，且与 CJK 相邻时才改；连续 `~~` 一律不动，
 * 因为那是用户明确写出的删除线语法，必须保留原义。
 */
class CjkTildeTest {

    private val parser = MarkdownParser(GFMFlavourDescriptor())

    /** 走完整的预处理 → 解析链路，与真实渲染路径一致。 */
    private fun rendersStrikethrough(raw: String): Boolean {
        val preprocessed = preProcessMarkdown(raw)
        val tree = parser.buildMarkdownTreeFromString(preprocessed)
        val sb = StringBuilder()
        fun walk(n: org.intellij.markdown.ast.ASTNode) {
            sb.append(n.type).append('|')
            n.children.forEach { walk(it) }
        }
        walk(tree)
        return sb.contains("STRIKETHROUGH")
    }

    // ── 误判必须消除 ──────────────────────────────────────────────

    @Test
    fun `paired tone tildes no longer become strikethrough`() {
        assertFalse(
            "两段语气词之间的内容此前会被吞成删除线",
            rendersStrikethrough("\"啊~嗯❤~\"是这个声音...啊~嗯❤~"),
        )
    }

    @Test
    fun `adjacent tone tildes do not merge into strikethrough`() {
        assertFalse(
            "连续两段语气词此前会配对成删除线",
            rendersStrikethrough("啊~嗯❤~啊~嗯❤~"),
        )
    }

    @Test
    fun `tone tildes with separator do not trigger strikethrough`() {
        assertFalse(rendersStrikethrough("啊~嗯❤~ 和 哦~啊~"))
    }

    // ── 真实删除线必须保留 ────────────────────────────────────────

    @Test
    fun `real strikethrough with double tilde is preserved`() {
        assertTrue(
            "~~是用户明确的删除线语法，必须保留",
            rendersStrikethrough("~~这句话要删除~~"),
        )
    }

    @Test
    fun `strikethrough among normal text is preserved`() {
        assertTrue(rendersStrikethrough("正常文本 ~~要删的~~ 继续"))
    }

    // ── 不误伤其他场景 ────────────────────────────────────────────

    @Test
    fun `ascii adjacent tildes are untouched`() {
        val s = "~/home/user 和 a~b 和 1~2"
        assertTrue("ASCII 相邻的 ~ 应保持原样", s.normalizeCjkTildes() == s)
    }

    @Test
    fun `tilde inside code block is untouched`() {
        val out = preProcessMarkdown("说明文字\n```\npath~file 和 a~b\n```\n结束")
        assertTrue("代码块内的 ~ 必须原样保留", out.contains("path~file 和 a~b"))
    }

    @Test
    fun `cjk tilde becomes fullwidth`() {
        assertTrue("啊~嗯".normalizeCjkTildes().contains('\uFF5E'))
    }

    @Test
    fun `empty and tilde free strings are unchanged`() {
        assertTrue("".normalizeCjkTildes().isEmpty())
        assertTrue("完全没有波浪线".normalizeCjkTildes() == "完全没有波浪线")
    }

    @Test
    fun `latex preprocessing still works alongside`() {
        val input = "公式 " + "\\" + "(" + "x^2" + "\\" + ")" + " 结束"
        val out = preProcessMarkdown(input)
        assertTrue("LaTeX 替换不能被波浪线处理破坏", out.contains("\$x^2\$"))
    }
}
