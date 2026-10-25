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

    private val chatService: String by lazy {
        val candidates = listOf(
            File("src/main/java/me/rerere/rikkahub/service/ChatService.kt"),
            File("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt"),
        )
        candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 ChatService.kt，查找过：$candidates")
    }

    @Test
    fun `both tool paths register memory tools in tavern mode`() {
        // ChatService 的两条生成路径都走 buildTavernModeTools，记忆工具必须在那里注册
        val body = chatService
            .substringAfter("private suspend fun buildTavernModeTools(")
            .substringBefore("suspend fun generateForAssistant")
        assertTrue(
            "buildTavernModeTools 必须注册记忆工具，否则酒馆模式下 AI 写不进记忆",
            body.contains("buildMemoryTools("),
        )
        assertTrue(
            "记忆工具必须受助手自己的记忆开关控制",
            Regex("""if \(assistant\.enableMemory\) \{\s*addAll\(buildMemoryTools\(""").containsMatchIn(body),
        )
        assertTrue(
            "两条生成路径都必须调用 buildTavernModeTools",
            Regex("""buildTavernModeTools\(""").findAll(chatService).count() >= 3,
        )
    }

    @Test
    fun `non tavern chat paths also register memory tools`() {
        // ChatService 手写工具列表绕开了 ChatToolFactory：那里有记忆工具，手写这份曾经没有，
        // 于是普通模式下 AI 也拿不到 memory_write，只能回答"我这轮没有写记忆的工具"
        val registrations = Regex("""addAll\(buildMemoryTools\(memoryRepository, assistant\.memoryId\)\)""")
            .findAll(chatService).count()
        assertTrue(
            "普通路径与酒馆路径都必须注册记忆工具，实际只有 $registrations 处",
            registrations >= 3,
        )
    }

    @Test
    fun `tavern mode does not force reasoning off`() {
        // 深度思考由助手的思考档位决定：酒馆模式只裁上下文，不替用户关思考
        val disable = source.substringAfter("disableReasoning =").substringBefore(",", "")
        assertTrue("disableReasoning 取值里不应再出现 tavernMode", !disable.contains("enableTavernMode"))
        assertTrue(
            "disableReasoning 必须只看思考档位是否为 OFF",
            disable.contains("ReasoningLevel.OFF"),
        )
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
    fun `memory tools survive tavern mode when tools are kept`() {
        // 酒馆模式在公共叠加点收窄工具面：保留工具时只留白名单，
        // 不保留时整组清空。记忆工具在白名单里——此前被剔除，酒馆模式下
        // 助手再也写不进记忆，用户只能看着 AI 说自己没有写记忆的工具。
        assertTrue(
            "酒馆模式必须在公共叠加点按白名单收窄工具（retainAll isTavernBaseTool）",
            source.contains("""retainAll { it.name.isTavernBaseTool() }"""),
        )
        assertTrue(
            "酒馆模式不保留工具时必须在公共叠加点整组清空（clear）",
            Regex("""\} else \{\s*clear\(\)\s*\}""").containsMatchIn(source),
        )
        val whitelist = source
            .substringAfter("private fun String.isTavernBaseTool(): Boolean", "")
            .substringBefore("}")
        assertTrue("找不到酒馆工具白名单定义", whitelist.isNotEmpty())
        assertTrue("酒馆工具白名单必须保留记忆工具，否则 AI 无法写记忆", whitelist.contains("memory_"))
    }
}
