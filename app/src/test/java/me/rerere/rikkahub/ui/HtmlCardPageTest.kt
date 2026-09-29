package me.rerere.rikkahub.ui

import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.components.richtext.buildCardDocumentPage
import me.rerere.rikkahub.ui.components.richtext.buildFragmentPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成页面回归测试。
 *
 * 重点防住一类**静默**故障：Kotlin 原始字符串 `"""..."""` 不处理转义，
 * 若在其中把 JS 正则写成 `\\s`，产出的 JS 会变成 `/positions*:s*fixed/`
 * （反斜杠丢失），正则静默失效、不报错。这类 bug 在浏览器里只表现为
 * "样式没生效"，极难定位，因此必须有测试守住。
 */
class HtmlCardPageTest {
    private val card = """
        <!DOCTYPE html>
        <html>
        <head><style>
          body { background: #101010; }
          .wallpaper { position: fixed; width: 100vw; height: 100vh; }
          .panel { color: gold; }
        </style></head>
        <body><div class="wallpaper"></div><div class="panel"><script>window.x=1</script></div></body>
        </html>
    """.trimIndent()

    private fun page() = buildCardDocumentPage(card, Color.White, Color.Black)

    @Test
    fun `emitted js regexes keep their backslashes`() {
        val html = page()
        // 反斜杠必须真的传给 JS；双重转义会被 JS 当作已转义的反斜杠
        assertTrue("position 正则丢失反斜杠", html.contains("""/position\s*:\s*fixed/gi"""))
        assertFalse("存在被双重转义的正则", html.contains("""position\\s*"""))
        assertTrue("vh 正则丢失反斜杠", html.contains("""(\d+(?:\.\d+)?)vh"""))
    }

    @Test
    fun `no double escaped regex anywhere in page`() {
        val html = page()
        // 原始字符串里写 \\d 会产出字面量 \\d —— 在 JS 中是"反斜杠+d"而非数字类
        assertFalse("页面里出现双重转义", html.contains("\\\\d"))
        assertFalse("页面里出现双重转义", html.contains("\\\\s"))
    }

    @Test
    fun `card css is scoped and not leaked raw`() {
        val html = page()
        assertTrue("缺少隔离容器", html.contains("rikka-card-root"))
        // 作用域化后的选择器以 base64 传输，页面上不应出现未作用域的裸规则
        assertFalse("裸 body 规则泄漏", html.contains("body { background: #101010"))
    }

    @Test
    fun `script activation helper is present`() {
        val html = page()
        // template 里的 script 是惰性的，必须重建节点才能执行
        assertTrue("缺少脚本激活逻辑", html.contains("activateScripts"))
        assertTrue("缺少重建节点实现", html.contains("replaceChild"))
    }

    @Test
    fun `viewport normalization is applied in the page`() {
        val html = page()
        assertTrue(html.contains("normalizeViewportUnits"))
        assertTrue(html.contains("position:absolute"))
    }

    @Test
    fun `height reporting bridge is wired`() {
        val html = page()
        assertTrue("缺少高度上报", html.contains("rikkaHost"))
        assertTrue("缺少高度上报调用", html.contains("reportHeight"))
        assertTrue("缺少 ResizeObserver 兜底", html.contains("ResizeObserver"))
    }

    @Test
    fun `fragment page renders markdown pipeline and escapes fences`() {
        val html = buildFragmentPage("<div class=\"x\">hi</div>", Color.White, "/*marked*/", null)
        assertTrue(html.contains("marked"))
        assertTrue(html.contains("rikka-card-root"))
        // 围栏转 <pre> 的逻辑必须存在，否则 yaml 面板会显示字面反引号
        assertTrue(html.contains("<pre><code>"))
        assertFalse("片段页不应出现双重转义", html.contains("\\\\s"))
    }

    @Test
    fun `fragment page keeps html tag repair regex valid`() {
        val html = buildFragmentPage("< div>x</div>", Color.White, "", null)
        // "< img" 类修复正则里的 \b 必须完好
        assertTrue(html.contains("""\b"""))
        assertFalse(html.contains("""\\b"""))
    }

    @Test
    fun `base64 payload avoids breaking out of script tag`() {
        val nasty = "<!DOCTYPE html><html><body><div>a</script>b</div></body></html>"
        val html = buildCardDocumentPage(nasty, Color.White, Color.Black)
        // 内容以 base64 传入，不会因为含 </script> 而截断外部脚本
        val bodyScriptCount = Regex("</script>").findAll(html).count()
        assertTrue("脚本标签数量异常: $bodyScriptCount", bodyScriptCount in 1..6)
    }
}
