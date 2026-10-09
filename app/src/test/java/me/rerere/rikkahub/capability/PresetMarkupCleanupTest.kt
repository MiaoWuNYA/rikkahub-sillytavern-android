package me.rerere.rikkahub.capability

import me.rerere.rikkahub.data.ai.transformers.cleanPresetMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设标记清理。
 *
 * 社区预设常要求模型用自定义标签组织输出：
 *
 *     {{setvar:cotBegin:我们来看看用户的任务}}
 *     <thinking>…推理…</thinking>
 *     <正文>
 *     成都的夏夜闷得像个蒸笼…
 *     </正文>
 *
 * 这些是给机器看的。项目原来只处理 `<think>`，其余原样显示在气泡里——
 * 用户看到「思考过程、标签、宏全混在正文里」。
 */
class PresetMarkupCleanupTest {

    @Test
    fun `the reported sample is cleaned`() {
        // 用户实际遇到的那条
        val raw = """{{setvar:cotBegin:我们来看看用户的任务}}
<thinking>
用户提供了一个非常具体且具备挑战性的创作
</thinking>

<正文>

成都的夏夜闷得像个蒸笼，连宫檐下的铜雀都仿佛要吐出火来。
</正文>"""

        val out = raw.cleanPresetMarkup()

        assertFalse("宏不该留下", out.contains("setvar"))
        assertFalse("正文标签不该留下", out.contains("<正文>"))
        assertFalse("正文闭合标签不该留下", out.contains("</正文>"))
        assertTrue("正文内容必须保留", out.contains("成都的夏夜闷得像个蒸笼"))
        // <thinking> 由 ThinkTagTransformer 负责，这里不碰——
        // 两个 transformer 都处理会打架
        assertTrue("思考标签留给 ThinkTagTransformer", out.contains("<thinking>"))
    }

    @Test
    fun `all write-only macros are removed`() {
        // 写入型宏执行副作用、本身不产生文本，显示上应当不存在
        listOf(
            "{{setvar:x:1}}", "{{setglobalvar:x:1}}",
            "{{incvar:x}}", "{{decvar:x}}", "{{addvar:x:1}}",
            "{{deletevar:x}}", "{{flushvar}}",
        ).forEach { macro ->
            assertEquals("$macro 应被清掉", "", macro.cleanPresetMarkup())
        }
    }

    @Test
    fun `read-only macros are kept`() {
        // {{getvar}} 是要显示内容的，不能删
        val out = "{{getvar:name}}".cleanPresetMarkup()
        assertEquals("{{getvar:name}}", out)
    }

    @Test
    fun `wrapper tags keep their content`() {
        listOf("正文", "content", "reply", "response", "output").forEach { tag ->
            val out = "<$tag>要点内容</$tag>".cleanPresetMarkup()
            assertEquals("$tag 应只去标签", "要点内容", out)
        }
    }

    @Test
    fun `unpaired tags from mid-stream are removed`() {
        // 流式生成中途常出现半个标签
        assertEquals("内容", "<正文>内容".cleanPresetMarkup())
        assertEquals("内容", "内容</正文>".cleanPresetMarkup())
    }

    @Test
    fun `unknown tags are left alone`() {
        // 名单刻意短：不认识的一律不动。
        // 删错内容的代价远大于留下一个标签。
        listOf(
            "<StatusBlock>状态</StatusBlock>",
            "<details><summary>记忆</summary>内容</details>",
            "<UI>界面</UI>",
            "<hd>时间</hd>",
        ).forEach { raw ->
            assertEquals("$raw 不该被改动", raw, raw.cleanPresetMarkup())
        }
    }

    @Test
    fun `plain text passes through unchanged`() {
        val plain = "他站在窗前，看着楼下的车流发呆。"
        assertEquals(plain, plain.cleanPresetMarkup())
    }

    @Test
    fun `excess blank lines left by removed macros are collapsed`() {
        val raw = "第一段\n{{setvar:a:1}}\n\n\n\n第二段"
        val out = raw.cleanPresetMarkup()
        assertFalse("不该留下三连空行", out.contains("\n\n\n"))
        assertTrue("两段都要在", out.contains("第一段") && out.contains("第二段"))
    }
}
