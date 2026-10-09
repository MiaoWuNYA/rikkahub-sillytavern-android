package me.rerere.rikkahub.capability

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.cleanPresetMarkup
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import kotlin.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完整输出管线顺序：先清理预设标记，再识别思考块。
 *
 * 顺序反了的话，<thinking> 前面堵着 {{setvar:…}}，
 * 思考块的「必须在文本开头」检查永远匹配不上，整段漏成正文。
 */
class ThinkingPipelineTest {

    private val raw = """{{setvar:cotBegin:我们来看看用户的任务}}
<thinking>
用户提供了一个非常具体且具备挑战性的创作
</thinking>

<正文>

成都的夏夜闷得像个蒸笼，连宫檐下的铜雀都仿佛要吐出火来。
</正文>"""

    private fun run(text: String): UIMessage {
        // 与 outputTransformers 相同的顺序：OutputTagCleanup → ThinkTag
        val cleaned = text.cleanPresetMarkup()
        val msg = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(cleaned)))
        return listOf(msg).transformThinkTags(
            now = Clock.System.now(),
            generationFinished = true,
        ).first()
    }

    @Test
    fun `thinking becomes a reasoning part and body survives`() {
        val out = run(raw)

        val reasoning = out.parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertTrue("思考要转成 Reasoning part", reasoning.isNotEmpty())
        assertEquals(
            "思考内容要对",
            "用户提供了一个非常具体且具备挑战性的创作",
            reasoning.first().reasoning,
        )

        val body = out.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
        assertTrue("正文要保留", body.contains("成都的夏夜闷得像个蒸笼"))
        assertTrue("thinking 标签不能残留", !body.contains("<thinking>"))
        assertTrue("正文标签不能残留", !body.contains("<正文>"))
        assertTrue("宏不能残留", !body.contains("setvar"))
    }

    @Test
    fun `plain body without tags is untouched`() {
        val out = run("成都的夏夜闷得像个蒸笼。")
        val body = out.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        assertEquals("成都的夏夜闷得像个蒸笼。", body)
        assertTrue(out.parts.none { it is UIMessagePart.Reasoning })
    }

    @Test
    fun `thinking without setvar prefix still works`() {
        val out = run("<thinking>直接思考</thinking>\n\n正文内容")
        val reasoning = out.parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertTrue(reasoning.isNotEmpty())
        assertEquals("直接思考", reasoning.first().reasoning)
    }
}
