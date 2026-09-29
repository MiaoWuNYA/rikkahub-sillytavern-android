package me.rerere.rikkahub.data.model

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSS 计算引擎测试。
 *
 * 目标不是覆盖 CSS 全语法，而是锁住"主题里明明写了却不生效"的几类真实场景：
 * 变量不展开、特异度算错、!important 被忽略、简写属性没拆。
 */
class ThemeCssEngineTest {

    @Test
    fun `variables from root are expanded into rules`() {
        val css = """
            :root {
                --bar: #ff0000;
                --radius: 20px;
            }
            .mes_block {
                border-radius: var(--radius);
                background-color: var(--bar);
            }
        """.trimIndent()
        val rules = parseCssRules(css)
        val decls = cascadeDeclarations(rules) { it.contains(".mes_block") }
        assertEquals("20px", decls["border-radius"])
        assertEquals("#ff0000", decls["background-color"])
    }

    @Test
    fun `var with fallback uses fallback when undefined`() {
        val rules = parseCssRules(".mes { color: var(--missing, #00ff00); }")
        val decls = cascadeDeclarations(rules) { it.contains(".mes") }
        assertEquals("#00ff00", decls["color"])
    }

    @Test
    fun `nested variable references resolve`() {
        val css = """
            :root { --a: 4px; --b: var(--a); }
            .mes { border-radius: var(--b); }
        """.trimIndent()
        val decls = cascadeDeclarations(parseCssRules(css)) { it.contains(".mes") }
        assertEquals("4px", decls["border-radius"])
    }

    @Test
    fun `higher specificity wins regardless of order`() {
        val css = """
            .mes { border-radius: 8px; }
            .mes#main { border-radius: 24px; }
        """.trimIndent()
        // 选择器含 #main 时特异度高，即使 .mes 规则更晚也应胜出
        val decls = cascadeDeclarations(parseCssRules(css)) { true }
        assertEquals("24px", decls["border-radius"])
    }

    @Test
    fun `later rule wins at equal specificity`() {
        val css = """
            .mes { border-radius: 8px; }
            .mes { border-radius: 30px; }
        """.trimIndent()
        val decls = cascadeDeclarations(parseCssRules(css)) { true }
        assertEquals("30px", decls["border-radius"])
    }

    @Test
    fun `important beats higher specificity`() {
        val css = """
            #chat .mes { border-radius: 8px !important; }
            .mes { border-radius: 30px; }
        """.trimIndent()
        val decls = cascadeDeclarations(parseCssRules(css)) { true }
        assertEquals("8px", decls["border-radius"])
    }

    @Test
    fun `important flag is stripped from value`() {
        val rules = parseCssRules(".mes { color: #fff !important; }")
        val decl = rules.first().declarations.first()
        assertEquals("#fff", decl.value)
        assertTrue(decl.important)
    }

    @Test
    fun `specificity ordering matches css rules`() {
        // #id > .class > element
        assertTrue(selectorSpecificity("#a") > selectorSpecificity(".a"))
        assertTrue(selectorSpecificity(".a") > selectorSpecificity("div"))
        assertTrue(selectorSpecificity(".a.b") > selectorSpecificity(".a"))
        assertTrue(selectorSpecificity("#a .b") > selectorSpecificity(".a.b"))
    }

    @Test
    fun `selector matching is driven by caller`() {
        val css = """
            .mes { border-radius: 8px; }
            .drawer-icon { border-radius: 99px; }
        """.trimIndent()
        val rules = parseCssRules(css)
        val onlyMes = cascadeDeclarations(rules) { it.contains(".mes") }
        assertEquals("8px", onlyMes["border-radius"])
    }

    @Test
    fun `declarations with semicolon inside url are not split`() {
        val css = ".mes { background-image: url('data:image/svg+xml;a;b'); border-radius: 10px; }"
        val decls = cascadeDeclarations(parseCssRules(css)) { it.contains(".mes") }
        assertEquals("10px", decls["border-radius"])
        assertTrue(decls["background-image"]!!.contains("data:image/svg+xml"))
    }

    @Test
    fun `css comments do not leak into values`() {
        val css = """
            /* 你的图片链接 */
            .mes { border-radius: 12px; /* 注释 */ }
        """.trimIndent()
        val decls = cascadeDeclarations(parseCssRules(css)) { it.contains(".mes") }
        assertEquals("12px", decls["border-radius"])
    }

    @Test
    fun `length parsing supports px rem and percent`() {
        assertEquals(12f, cssLengthToDp("12px")?.value)
        assertEquals(1.5f, cssLengthToDp("1.5rem")?.value?.let { it / 16f })
        assertEquals(20f, cssLengthToDp("50%", percentBase = 40.dp)?.value)
        assertEquals(8f, cssLengthToDp("8")?.value)
        assertNull(cssLengthToDp("auto"))
    }

    @Test
    fun `color lookup tries properties in order`() {
        val decls = mapOf("background" to "#123456")
        assertEquals(0xFF123456L, cssColorFrom(decls, "background-color", "background"))
        // none 视为无颜色
        assertNull(cssColorFrom(mapOf("background-image" to "none"), "background-image"))
    }

    @Test
    fun `extra vars have lower priority than theme root vars`() {
        // 官方顺序：先 setProperty 注入字段变量，再插入 custom-style，
        // 同特异度下 custom_css 里的 :root 定义应当胜出
        val css = ":root { --SmartThemeBodyColor: #111111; } .mes { color: var(--SmartThemeBodyColor); }"
        val rules = parseCssRules(css, extraVars = mapOf("--SmartThemeBodyColor" to "#999999"))
        val decls = cascadeDeclarations(rules) { it.contains(".mes") }
        assertEquals("#111111", decls["color"])
    }

    @Test
    fun `bubble border shorthand is parsed into color and width`() {
        val css = """
            :root { --SmartThemeBorderColor: #d0d0d0; }
            .mes { border: 1px solid var(--SmartThemeBorderColor); }
        """.trimIndent()
        val border = extractBubbleBorder(css)
        assertTrue(border != null)
        assertEquals(0xFFD0D0D0L, border!!.color)
        assertEquals(1f, border.width)
    }

    @Test
    fun `border none yields no border`() {
        assertNull(extractBubbleBorder(".mes { border: none; }"))
    }

    @Test
    fun `border zero width yields no border`() {
        assertNull(extractBubbleBorder(".mes { border: 0px solid #fff; }"))
    }

    @Test
    fun `bubble shadow takes blur radius and color`() {
        // 官方主题常见写法：0 6px 20px rgba(0,0,0,0.4) —— 第 3 个长度为模糊半径
        val shadow = extractBubbleShadow(".mes { box-shadow: 0 6px 20px rgba(0, 0, 0, 0.4); }")
        assertTrue(shadow != null)
        assertEquals(20f, shadow!!.width)
        assertTrue(shadow.color != null)
    }

    @Test
    fun `box-shadow none yields no shadow`() {
        assertNull(extractBubbleShadow(".mes { box-shadow: none; }"))
    }

    @Test
    fun `border only on unrelated selector is ignored`() {
        // 头像框的 border 不能当成气泡边框
        assertNull(extractBubbleBorder(".avatar::before { border: 2px solid #fff; }"))
    }

    @Test
    fun `media queries do not break rule parsing`() {
        val css = """
            @media (max-width: 600px) {
                .mes { border-radius: 4px; }
            }
            .mes_block { border-radius: 20px; }
        """.trimIndent()
        val rules = parseCssRules(css)
        // @media 整体被跳过，但后面的规则必须仍然拿到
        val decls = cascadeDeclarations(rules) { it.contains(".mes_block") }
        assertEquals("20px", decls["border-radius"])
    }
}
