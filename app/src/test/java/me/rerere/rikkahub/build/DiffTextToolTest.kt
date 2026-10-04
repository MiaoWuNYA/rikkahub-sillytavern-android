package me.rerere.rikkahub.build

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 通用文本差异对比工具。
 *
 * 它替换的是原先的二进制对比（diff_binary）——那个工具看的是编译产物，
 * 与日常场景不相交；这个看的是改过的配置、两版稿子、修过的代码，
 * 属于「十次对话遇到五次」的范畴。
 */
class DiffTextToolTest {

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

    private fun exists(rel: String): Boolean {
        for (root in roots) {
            for (c in listOf(File(root, rel), File(root, "app/$rel"))) {
                if (c.exists()) return true
            }
        }
        return false
    }

    @Test
    fun `diff tool source exists on both sides`() {
        assertTrue("Kotlin 侧工具应存在",
            exists("src/main/java/me/rerere/rikkahub/data/ai/tools/DiffTextTool.kt"))
        assertTrue("Python 侧实现应存在", exists("src/main/python/difftext.py"))
    }

    @Test
    fun `diff tool is registered and switchable`() {
        val local = read("src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt")
        assertTrue("应注册 LocalToolOption.DiffText",
            local.contains("data object DiffText : LocalToolOption()"))
        assertTrue("序列化名应为 diff_text",
            local.contains("""@SerialName("diff_text")"""))
        assertTrue("getTools 应分派它", local.contains("LocalToolOption.DiffText"))
        assertTrue("应持有 diffTextTool", local.contains("val diffTextTool by lazy"))

        val page = read("src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantLocalToolPage.kt")
        assertTrue("助手工具页应有开关", page.contains("toggleLocalTool(LocalToolOption.DiffText"))

        listOf("values", "values-zh").forEach { loc ->
            val s = read("src/main/res/$loc/strings.xml")
            assertTrue("$loc 缺少 diff 工具文案", s.contains("local_tool_diff_text_title"))
        }
    }

    @Test
    fun `python side uses stdlib only`() {
        val py = read("src/main/python/difftext.py")
        assertTrue("找不到 difftext.py", py.isNotEmpty())
        assertTrue("应使用标准库 difflib", py.contains("import difflib"))
        // 不引入新依赖：diff 是标准库就能做好的事
        assertFalse("不该 import 第三方库", py.contains("import numpy") || py.contains("import pandas"))
    }

    @Test
    fun `path detection prefers existing files and falls back to text`() {
        val py = read("src/main/python/difftext.py")
        // 判定顺序很重要：文件存在才算路径，不存在就当文本。
        // 反过来会让一段长得像路径的文字被误读。
        assertTrue("应检查文件是否存在", py.contains("os.path.isfile(value)"))
        assertTrue("应支持强制路径", py.contains("force_path"))
        assertTrue("路径不存在时应明确报错而非静默当文本", py.contains("指定为文件但不存在"))
    }

    @Test
    fun `output is bounded and labelled`() {
        val py = read("src/main/python/difftext.py")
        assertTrue("输出应有上限", py.contains("MAX_OUTPUT_CHARS"))
        assertTrue("输入应有上限", py.contains("MAX_INPUT_BYTES"))
        assertTrue("截断时应告知调用方", py.contains("已截断"))
        // 两侧都是内联文本时不能都叫 inline，否则分不清左右
        assertTrue("内联文本应带左右标识", py.contains("inline-{side}"))
    }

    @Test
    fun `diff handles whitespace and context options`() {
        val py = read("src/main/python/difftext.py")
        assertTrue("应支持忽略空白", py.contains("ignore_whitespace"))
        assertTrue("应支持上下文行数", py.contains("context_lines"))
        assertTrue("应报告增删行数", py.contains("行 / -"))
    }

    @Test
    fun `binary diff tool is gone`() {
        assertFalse("DiffBinary 枚举不该存在",
            read("src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt").contains("DiffBinary"))
        assertFalse("BinaryToolkit 不该存在",
            exists("src/main/java/me/rerere/rikkahub/data/ai/tools/BinaryToolkit.kt"))
    }
}
