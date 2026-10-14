package me.rerere.rikkahub

import me.rerere.rikkahub.ui.components.richtext.looksLikeCardMarkup
import me.rerere.rikkahub.ui.components.richtext.splitCardSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSegmentTest {
    private val text = """
        正文正文。

        <main tickbubble>
        ```
        状态栏
        姓名：喵喵
        ```
        </main tickbubble>

        <option tickbubble>
        ```
        请选择你的下一步行动：
        1.前往正规左侧房间
        ```
        </option tickbubble>
        <StatusBlock>
        ```json
        『 周五 - 下午3点 』
        ```
        </StatusBlock>
    """.trimIndent()

    @Test
    fun `分块标签包裹的卡片块要被识别`() {
        val segments = splitCardSegments(text)
        val cards = segments.filter { it.first }
        assertTrue("应识别出卡片块，实际 ${cards.size}", cards.isNotEmpty())
        assertTrue("卡片块应含状态栏", cards.any { it.second.contains("姓名：喵喵") })
        assertTrue("卡片块应含选项", cards.any { it.second.contains("请选择你的下一步行动") })
        assertEquals("正文单独成段", 1, segments.count { !it.first })
    }

    @Test
    fun `带属性的分块标签算卡片标记`() {
        assertTrue(looksLikeCardMarkup("<main tickbubble>\n```\nx\n```\n</main tickbubble>"))
        assertTrue(looksLikeCardMarkup("<StatusBlock>\n```json\n{}\n```\n</StatusBlock>"))
    }
}
