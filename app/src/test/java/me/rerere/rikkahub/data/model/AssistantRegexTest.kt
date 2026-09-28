package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.export.SillyTavernRegexImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantRegexTest {
    private fun assistant(vararg regexes: AssistantRegex) = Assistant(
        id = kotlin.uuid.Uuid.random(),
        name = "test",
        regexes = regexes.toList(),
    )

    private val sample = "<think>chain</think>你好\n世界 <!-- State: ok -->"

    @Test
    fun `default rule affects both display and prompt layers`() {
        // 酒馆默认导入（markdownOnly=false, promptOnly=false → visualOnly=false）
        // 官方语义是两层都生效；旧实现 strict equality 导致显示层永远不命中
        val reg = AssistantRegex(
            id = kotlin.uuid.Uuid.random(),
            findRegex = "<think>[\\s\\S]*?</think>",
            replaceString = "",
            affectingScope = setOf(AssistantAffectScope.ASSISTANT),
        )
        val a = assistant(reg)
        val display = sample.replaceRegexes(a, AssistantAffectScope.ASSISTANT, visual = true)
        val prompt = sample.replaceRegexes(a, AssistantAffectScope.ASSISTANT, visual = false)
        assertEquals("你好\n世界 <!-- State: ok -->", display)
        assertEquals(display, prompt)
    }

    @Test
    fun `visualOnly rule affects display but not prompt`() {
        val reg = AssistantRegex(
            id = kotlin.uuid.Uuid.random(),
            findRegex = "<!-- State[\\s\\S]*? -->",
            replaceString = "",
            affectingScope = setOf(AssistantAffectScope.ASSISTANT),
            visualOnly = true,
        )
        val a = assistant(reg)
        assertEquals("<think>chain</think>你好\n世界 ", sample.replaceRegexes(a, AssistantAffectScope.ASSISTANT, visual = true))
        assertEquals(sample, sample.replaceRegexes(a, AssistantAffectScope.ASSISTANT, visual = false))
    }

    @Test
    fun `scope filter still applies`() {
        val reg = AssistantRegex(
            id = kotlin.uuid.Uuid.random(),
            findRegex = "<think>[\\s\\S]*?</think>",
            replaceString = "",
            affectingScope = setOf(AssistantAffectScope.USER),
        )
        val a = assistant(reg)
        assertEquals(sample, sample.replaceRegexes(a, AssistantAffectScope.ASSISTANT, visual = true))
    }

    @Test
    fun `slash form flags are honored`() {
        val reg = AssistantRegex(
            id = kotlin.uuid.Uuid.random(),
            findRegex = "/disclaimer/gs",
            replaceString = "",
            affectingScope = setOf(AssistantAffectScope.ASSISTANT),
        )
        // /disclaimer/gs 本体是字面量，这里只验证 slash 解析不崩且 dotall 生效
        val multiLine = "a<disclaimer>x\ny</disclaimer>b"
        val a = assistant(AssistantRegex(
            id = kotlin.uuid.Uuid.random(),
            findRegex = "/<disclaimer>[\\s\\S]*?</disclaimer>/gs",
            replaceString = "",
            affectingScope = setOf(AssistantAffectScope.ASSISTANT),
        ))
        assertEquals("ab", multiLine.replaceRegexes(a, AssistantAffectScope.ASSISTANT, visual = true))
    }

    @Test
    fun `invalid regex is a silent no-op`() {
        val reg = AssistantRegex(
            id = kotlin.uuid.Uuid.random(),
            findRegex = "([bad",
            replaceString = "",
            affectingScope = setOf(AssistantAffectScope.ASSISTANT),
        )
        val a = assistant(reg)
        assertEquals(sample, sample.replaceRegexes(a, AssistantAffectScope.ASSISTANT, visual = true))
    }

    // ---- 酒馆正则脚本导入映射 ----

    private fun parseOne(json: String) = SillyTavernRegexImporter.parse(json).single()

    @Test
    fun `import default (F,F) maps to both layers`() {
        val reg = parseOne(
            """{"scriptName":"strip","findRegex":"<disclaimer>.*?</disclaimer>","replaceString":"",
               "placement":[1,2],"markdownOnly":false,"promptOnly":false,"disabled":false}"""
        )
        assertFalse(reg.visualOnly) // 两层都生效
        assertTrue(AssistantAffectScope.USER in reg.affectingScope)
        assertTrue(AssistantAffectScope.ASSISTANT in reg.affectingScope)
        assertTrue(reg.enabled)
    }

    @Test
    fun `import markdownOnly maps to display layer`() {
        val reg = parseOne(
            """{"scriptName":"display","findRegex":"x","replaceString":"",
               "placement":[2],"markdownOnly":true,"promptOnly":false,"minDepth":1,"maxDepth":2}"""
        )
        assertTrue(reg.visualOnly)
        assertEquals(setOf(AssistantAffectScope.ASSISTANT), reg.affectingScope)
        assertEquals(1, reg.minDepth)
        assertEquals(2, reg.maxDepth)
    }

    @Test
    fun `import promptOnly maps to prompt layer`() {
        val reg = parseOne(
            """{"scriptName":"prompt","findRegex":"x","replaceString":"",
               "placement":[],"markdownOnly":false,"promptOnly":true}"""
        )
        assertFalse(reg.visualOnly)
        // placement 为空按官方保守策略映射为两者
        assertTrue(AssistantAffectScope.USER in reg.affectingScope)
        assertTrue(AssistantAffectScope.ASSISTANT in reg.affectingScope)
    }

    @Test
    fun `import disabled script stays disabled`() {
        val reg = parseOne(
            """{"scriptName":"off","findRegex":"x","replaceString":"","disabled":true}"""
        )
        assertFalse(reg.enabled)
    }

    @Test
    fun `import rejects entries without scriptName or findRegex`() {
        assertEquals(0, SillyTavernRegexImporter.parse("""[{"scriptName":"a"},{"findRegex":"x"}]""").size)
    }
}
