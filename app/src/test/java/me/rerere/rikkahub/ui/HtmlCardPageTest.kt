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

    // === 卡片显示不全的回归测试 ===
    // 症状：内容多的前端卡只显示前六七十行，网页本身也不能上下滑动，
    // 只能点右下角小眼睛进全屏。根因有三，逐一守住。

    @Test
    fun `height measurement walks the whole subtree`() {
        val html = buildCardDocumentPage(card, Color.White, Color.Black)
        // 只量根盒 rect 会漏掉内层 max-height + overflow 溢出的内容，
        // 必须遍历子树取最大下沿
        assertTrue("高度测量未遍历子树", html.contains("querySelectorAll('*')"))
        assertTrue("高度测量未考虑内层溢出", html.contains("scrollHeight"))
    }

    @Test
    fun `height measurement is not capped by viewport`() {
        val html = buildCardDocumentPage(card, Color.White, Color.Black)
        // 不能因为 innerHeight 把内容高度截断
        assertFalse(
            "高度测量不应把内容限制在视口内",
            html.contains("Math.min(") && html.contains("innerHeight")
        )
    }

    @Test
    fun `late rendering cards keep being measured`() {
        val html = buildCardDocumentPage(card, Color.White, Color.Black)
        // 字体就绪会改变换行进而改变高度
        assertTrue("缺少 document.fonts.ready 复查", html.contains("fonts.ready"))
        // 内层容器展开/折叠也要复查
        assertTrue("缺少滚动复查", html.contains("addEventListener('scroll'"))
    }

    @Test
    fun `height reporting script stays syntactically valid`() {
        val html = buildCardDocumentPage(card, Color.White, Color.Black)
        // 高度脚本必须真的产出可执行 JS：关键调用点齐全
        assertTrue(html.contains("rikkaHost.reportHeight"))
        assertTrue(html.contains("setTimeout(report"))
        // Kotlin 原始字符串不处理转义，一旦在 JS 里误写成连续两个反斜杠，
        // 产出的正则会静默失效且不报错。这里只检查高度脚本自身的形状。
        val heightScript = html
            .substringAfter("function contentHeight()")
            .substringBefore("</script>")
        val doubledBackslash = "\\" + "\\"
        assertFalse(
            "高度脚本出现双重反斜杠，JS 会静默失效",
            heightScript.contains(doubledBackslash),
        )
    }

    // === 卡片滚动手势的回归测试（源码级） ===
    //
    // 症状反复出现：「界面抢网页滑动」，卡片里怎么划都不动。
    //
    // 真正的原因：消息列表是 LazyColumn，它和 WebView 都是原生 View，
    // 两者的滑动竞争发生在 Android 视图树的 onInterceptTouchEvent 分发里。
    // LazyColumn 会在第一次 MOVE 时把手势整体截走，WebView 只收到一个 DOWN。
    //
    // 曾被误用的两种 Compose 方案都无效，因为 Modifier.nestedScroll 属于
    // Compose 的机制，在原生容器面前插不上手 —— onPostScroll 版、onPreScroll
    // 版实测均毫无效果。正解是在原生层禁止祖先拦截。
    //
    // 这里直接对源码做约束，防止再被改回 Compose 方案。

    @Test
    fun `card webview forbids ancestors from stealing the gesture`() {
        val src = cardWebViewSource()
        assertTrue(
            "必须在按下时禁止祖先拦截，否则 LazyColumn 会把手势整体截走",
            src.contains("requestDisallowInterceptTouchEvent"),
        )
    }

    @Test
    fun `the gesture is handed back at the card boundary`() {
        val src = cardWebViewSource()
        // 到达边界后要放行，否则手势卡死在卡片上，整个页面都动不了
        assertTrue(
            "到边界必须放行给外层",
            src.contains("requestDisallowInterceptTouchEvent(false)"),
        )
        assertTrue(
            "边界判定需要同时看顶部与底部",
            src.contains("atTop") && src.contains("atBottom"),
        )
    }

    @Test
    fun `a card with nothing to scroll never blocks the list`() {
        val src = cardWebViewSource()
        // 短卡片不该有阻断感
        assertTrue(
            "必须按可滚区间决定是否抢手势",
            src.contains("hasScrollableRange"),
        )
        assertTrue(
            "可滚区间 = 内容高度 > 视口高度",
            src.contains("contentHeight > height"),
        )
    }

    @Test
    fun `native scrolling is delegated to the webview itself`() {
        val src = cardWebViewSource()
        // 不能自己实现滚动：必须 super.onTouchEvent 让 WebView 原生
        // OverScroller 干活，否则惯性滚动退化成逐段 scrollBy（"很卡、划不动"）。
        assertTrue(
            "必须把事件交回 WebView 原生处理",
            src.contains("super.onTouchEvent(event)"),
        )
    }

    @Test
    fun `the ineffective compose nested scroll approach is gone`() {
        val src = cardWebViewSource()
        // 剥掉注释再断言，避免把解释性文字当成真实代码
        val code = src.lineSequence()
            .joinToString("\n") { line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
            }
        assertFalse(
            "Modifier.nestedScroll 对原生容器的滑动竞争无效，不应再出现",
            code.contains(".nestedScroll("),
        )
        assertFalse(
            "onPreScroll/onPostScroll 同样无效，不应再出现",
            code.contains("override fun onPreScroll") || code.contains("override fun onPostScroll"),
        )
    }

    private companion object {
        const val CARD_SOURCE =
            "app/src/main/java/me/rerere/rikkahub/ui/components/richtext/HtmlWebViewBlock.kt"
    }

    /** 读取 HtmlWebViewBlock.kt 源码，从测试工作目录向上找到仓库根 */
    private fun cardWebViewSource(): String {
        var root: java.io.File = java.io.File(".").absoluteFile
        while (!java.io.File(root, CARD_SOURCE).exists()) {
            root = root.parentFile ?: break
        }
        return java.io.File(root, "app/src/main/java/me/rerere/rikkahub/ui/components/richtext/HtmlWebViewBlock.kt")
            .readText()
    }
}
