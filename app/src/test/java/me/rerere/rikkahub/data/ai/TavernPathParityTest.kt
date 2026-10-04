package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 酒馆模式的路径一致性。
 *
 * GenerationLoop 里有两条构建 system prompt 的路径：
 *   buildCachedSystemPrompt —— 主路径，prebuiltSystemMessages 的来源
 *   generateInternal 里的 fallbackSystem —— 仅在上面那条产出为空时兜底
 *
 * 两条路径各写一份 PromptContext 组装，酒馆模式的判断必须两边都在。
 * 历史上出过两次「一处有一处没有」：工具 systemPrompt 段、leadInInstructions，
 * 都是主路径修好了、fallback 漏掉，只在 fallback 被触发时才暴露。
 * 这类分支没有编译期提示，靠人眼比对源码极易再次漏掉，故用测试钉住。
 */
class TavernPathParityTest {

    private val source: String by lazy {
        // 单测的工作目录是模块根（app/），源码在同模块内
        val candidates = listOf(
            File("src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt"),
            File("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt"),
        )
        candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 GenerationLoop.kt，查找过：$candidates")
    }

    @Test
    fun `both system prompt paths gate tool prompts on tavern mode`() {
        // 工具 systemPrompt 段的追加语句，两条路径必须各自被 !tavernMode 包住
        val occurrences = Regex("""tools\.forEach \{ tool ->""").findAll(source).count()
        assertTrue("预期两条路径各有一处 tools.forEach，实际 $occurrences", occurrences >= 2)

        // 每一处 tools.forEach 之前都应能回溯到 !tavernMode 判断
        val guarded = Regex("""if \(!tavernMode\) \{\s*tools\.forEach \{ tool ->""")
            .findAll(source).count()
        assertTrue(
            "两处 tools.forEach 都必须被 if (!tavernMode) 包住，实际只有 $guarded 处",
            guarded >= 2,
        )
    }

    @Test
    fun `both system prompt paths gate lead in instructions on tavern mode`() {
        val assignments = Regex("""leadInInstructions =""").findAll(source).count()
        assertTrue("预期两处 leadInInstructions 赋值，实际 $assignments", assignments >= 2)

        // 写法有两种：`if (tavernMode) ""` 与 `if (tavernMode || routingLines.isEmpty()) ""`，
        // 只要条件里出现 tavernMode 即算通过
        val guarded = Regex("""leadInInstructions = if \(tavernMode""").findAll(source).count()
        assertTrue(
            "两处 leadInInstructions 都必须判断 tavernMode，实际只有 $guarded 处",
            guarded >= 2,
        )
    }

    @Test
    fun `both system prompt paths gate workspace description on tavern mode`() {
        val guarded = Regex("""workspaceDescription = if \(!tavernMode""").findAll(source).count()
        assertTrue(
            "两处 workspaceDescription 都必须判断 tavernMode，实际只有 $guarded 处",
            guarded >= 2,
        )
    }

    @Test
    fun `memory tool registration goes through the shared predicate`() {
        assertTrue(
            "记忆工具注册必须走 shouldRegisterMemoryTool，不能退回内联条件",
            source.contains("shouldRegisterMemoryTool("),
        )
        assertTrue(
            "不应再出现直接内联的 enableMemory 判断",
            !Regex("""if \(assistant\?\.enableMemory == true &&""").containsMatchIn(source),
        )
    }
}
