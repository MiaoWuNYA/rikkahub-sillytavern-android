package me.rerere.rikkahub.ui

import me.rerere.rikkahub.ui.components.richtext.findFencedHtmlDocument
import me.rerere.rikkahub.ui.components.richtext.isFullHtmlDocument
import me.rerere.rikkahub.ui.components.richtext.scopeCardCss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无语言标注围栏（裸 ```）包裹完整 HTML 文档的酒馆卡。
 *
 * 起因：一张卡把整篇 iPhone UI 的 HTML 直接塞进不带 html 标注的围栏里，
 * 首行是 ``` ，正文以 <html lang="en"> 开头。旧实现只认 ```html，
 * findFencedHtmlDocument 返回 null，整段落回 splitCardSegments 被拆成
 * 「散文 / 卡片」混合，<head>/<style>/<title> 全被当正文渲染成源码，
 * 卡片样式整体失效——表现就是「卡加载出来是一堆裸文本，样式全没」。
 *
 * 放宽围栏识别后必须同时保证：普通代码块不能被误认成卡片。
 */
class CardNoLangFenceTest {

    /** 真实卡的形态：``` 开、\r\n 行尾、完整文档、末尾 ``` 闭合。 */
    private val bareFenceCard = buildString {
        append("```\r\n")
        append("<html lang=\"en\">\r\n")
        append("<head>\r\n")
        append("<meta charset=\"UTF-8\" />\r\n")
        append("<title>Peach iPhone UI Popup</title>\r\n")
        append("<style>.letter-container{width:90%}</style>\r\n")
        append("</head>\r\n")
        append("<body>\r\n")
        append("<div class=\"letter-container\">正文</div>\r\n")
        append("<script>console.log(1)</script>\r\n")
        append("</body>\r\n")
        append("</html>\r\n")
        append("```")
    }

    @Test
    fun `bare fence wrapping a full html document is recognized`() {
        val result = findFencedHtmlDocument(bareFenceCard)
        assertNotNull("裸围栏包裹完整 HTML 文档时必须识别为卡片", result)
        val (prose, doc) = result!!
        assertTrue("围栏前没有散文", prose.isEmpty())
        assertTrue("文档以 <html 开头", doc.startsWith("<html"))
        assertTrue("文档以 </html> 结尾", doc.endsWith("</html>"))
    }

    @Test
    fun `bare fence card keeps head, style and script content`() {
        val (_, doc) = findFencedHtmlDocument(bareFenceCard)!!
        assertTrue("head 不能被丢掉", doc.contains("<head>"))
        assertTrue("样式必须保留", doc.contains(".letter-container{width:90%}"))
        assertTrue("title 不能被当正文", doc.contains("<title>"))
        assertTrue("脚本必须保留", doc.contains("console.log(1)"))
    }

    @Test
    fun `domContentLoaded script survives extraction`() {
        // 卡片交互依赖 DOMContentLoaded，脚本被截断会导致小手机点不开
        val card = buildString {
            append("```\n<html><head></head><body>\n")
            append("<script>document.addEventListener('DOMContentLoaded', function(){ init(); });</script>\n")
            append("</body></html>\n```")
        }
        val (_, doc) = findFencedHtmlDocument(card)!!
        assertTrue(doc.contains("DOMContentLoaded"))
    }

    @Test
    fun `plain code fences are still not treated as cards`() {
        // 放宽围栏后必须不误判：代码块不是卡片
        assertNull(findFencedHtmlDocument("```python\nprint('hi')\n```"))
        assertNull(findFencedHtmlDocument("```json\n{\"a\":1}\n```"))
        assertNull(findFencedHtmlDocument("```yaml\nkey: value\n```"))
        assertNull(findFencedHtmlDocument("```js\nvar a = 1;\n```"))
        assertNull(findFencedHtmlDocument("```bash\nls -la\n```"))
    }

    @Test
    fun `fence with html label still works`() {
        assertNotNull(findFencedHtmlDocument("```html\n<html></html>\n```"))
        assertNotNull(findFencedHtmlDocument("``` html\n<html></html>\n```"))
        assertNotNull(findFencedHtmlDocument("```HTML\n<html></html>\n```"))
    }

    @Test
    fun `non document content in bare fence is rejected`() {
        // 裸围栏里是普通文本/片段，不是完整文档 → 不当卡片
        assertNull(findFencedHtmlDocument("```\n就是一段普通文字\n```"))
        assertNull(findFencedHtmlDocument("```\n<div>片段</div>\n```"))
    }

    @Test
    fun `isFullHtmlDocument accepts html tag with attributes`() {
        assertTrue(isFullHtmlDocument("<html lang=\"en\">x</html>"))
        assertTrue(isFullHtmlDocument("<html>"))
    }

    @Test
    fun `scoped css keeps custom properties and nested media`() {
        val css = ":root{--pink:#ff69b4}\n.iphone-popup{position:fixed;bottom:60px}\n" +
            "@media (max-width:400px){.letter-container{width:100%}}"
        val scoped = scopeCardCss(css)
        assertTrue("自定义属性要跟着容器走", scoped.contains("#rikka-card-root{--pink:#ff69b4}"))
        assertTrue("普通选择器要加前缀", scoped.contains("#rikka-card-root .iphone-popup"))
        assertTrue("@media 内的规则同样要加前缀", scoped.contains("#rikka-card-root .letter-container"))
    }

    @Test
    fun `scoped css strips external imports`() {
        val scoped = scopeCardCss("@import url('https://evil/x.css');\n.a{color:red}")
        assertTrue("外部样式表必须剥掉", !scoped.contains("@import"))
        assertTrue(scoped.contains("#rikka-card-root .a"))
    }
}
