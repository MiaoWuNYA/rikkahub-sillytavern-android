package me.rerere.rikkahub.build

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 代码检查能力。
 *
 * 这里锁住两条容易被无声破坏的线：
 *  1. 语言识别必须靠文件名，不能退回到 pygments 的内容猜测
 *     （实测 Java→Text only、Go→GDScript，基本不可用）
 *  2. 非 Python 语言的结论必须标 reliable=false —— 设备上没有编译器，
 *     把启发式提示说成「确认有错」会误导模型改坏代码
 */
class CodeCheckTest {

    private val roots: List<File> by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile)
            .filterNotNull().distinct()
    }

    private fun read(rel: String): String {
        for (root in roots) {
            for (c in listOf(File(root, rel), File(root, "app/$rel"))) {
                if (c.exists()) return c.readText()
            }
        }
        return ""
    }

    private fun codecheck(): String = read("src/main/python/codecheck.py")
    private fun tool(): String =
        read("src/main/java/me/rerere/rikkahub/data/ai/tools/CodeCheckTool.kt")

    @Test
    fun `language detection prefers filename over content guessing`() {
        val s = codecheck()
        assertTrue("应提供扩展名映射表", s.contains("EXT_MAP"))
        assertTrue("应支持无扩展名的专属文件名（Dockerfile/Makefile）",
            s.contains("NAME_MAP") && s.contains("dockerfile"))
        // 文件名命中时直接返回，不走内容打分
        assertTrue("文件名命中应高置信度返回",
            s.contains("return NAME_MAP[base]") || s.contains("return NAME_MAP[base], 'high'"))
    }

    @Test
    fun `heuristic results are honest about reliability`() {
        val s = codecheck()
        assertTrue("Python 检查应标记 reliable=True", s.contains("'reliable': True"))
        assertTrue("其他语言应标记 reliable=False", s.contains("'reliable': False"))
        assertTrue("非 Python 的结果必须带免责说明", s.contains("HEURISTIC_NOTE"))
        // 这句是说给模型听的，必须存在
        assertTrue("免责说明应明确「不代表代码有错」",
            s.contains("不代表代码有错"))
    }

    @Test
    fun `python uses real static analysis`() {
        val s = codecheck()
        assertTrue("应调用 pyflakes", s.contains("pyflakes"))
        assertTrue("应先用 ast 做语法检查", s.contains("ast.parse"))
        // 语法不过就不该继续跑静态分析
        assertTrue("语法错误应短路返回", s.contains("syntax_ok': False"))
    }

    @Test
    fun `bracket check skips strings and comments`() {
        val s = codecheck()
        // 不做这层过滤的话，"{{{" 这种字符串会让检查满屏误报
        assertTrue("应剥离注释与字符串", s.contains("_strip_comments_and_strings"))
        assertTrue("应处理行注释", s.contains("line_comment"))
        assertTrue("应处理块注释", s.contains("block"))
        assertTrue("应处理三引号字符串", s.contains("triple"))
    }

    @Test
    fun `tool is registered and switchable`() {
        val localTools = read("src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt")
        val assistant = read("src/main/java/me/rerere/rikkahub/data/model/Assistant.kt")
        val page = read(
            "src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantLocalToolPage.kt"
        )
        assertTrue("check_code 工具应存在", tool().isNotEmpty())
        assertTrue("应注册 LocalToolOption.CodeCheck",
            localTools.contains("CodeCheck") && localTools.contains("check_code"))
        assertTrue("应加入默认工具集",
            assistant.contains("LocalToolOption.CodeCheck"))
        assertTrue("助手设置页应有开关",
            page.contains("LocalToolOption.CodeCheck"))
    }

    @Test
    fun `tool description stays short`() {
        val desc = Regex("""CHECK_TOOL_DESCRIPTION\s*=\s*(.*?)\n\n""", RegexOption.DOT_MATCHES_ALL)
            .find(tool())?.groupValues?.get(1).orEmpty()
        val text = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(desc)
            .joinToString("") { it.groupValues[1] }
        // 工具定义每次请求都要发给模型，不能像文档一样写
        assertTrue("描述应控制在 600 字符内，实际 ${text.length}", text.length < 600)
        assertTrue("描述里必须点明非 Python 只是启发式",
            text.contains("heuristic") || text.contains("no compiler"))
    }
}
