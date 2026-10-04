package me.rerere.rikkahub.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 工具链完整性：逆向工具链已移除，通用能力已就位。
 *
 * 背景：曾经内置了一整套二进制逆向工具（7 个工具 + frida/blutter/rizin/ghidra
 * 等 native 库，压缩后约 55MB）。它们与这个 app 的主线场景——日常聊天与
 * 角色扮演——几乎不相交，却是体积的两倍。移除后空间转投通用能力：
 * 代码执行可用的库从 8 个涨到 40 余个，并补上了离线 OCR。
 *
 * 这个测试锁住三件事，防止后续重构把它们悄悄改回去：
 *   1. 逆向库与工具不会重新长出来
 *   2. Python 通用库不会在「精简依赖」的名义下被删掉
 *   3. convert.py 引用的包必须真的在 pip 列表里
 */
class ToolchainIntegrityTest {

    /**
     * Gradle 的测试工作目录不一定是项目根（实测是 app/ 或模块目录），
     * 所以按若干候选前缀逐个尝试，找不到再返回空串。
     */
    private val roots: List<File> by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile, File(".").absoluteFile)
            .filterNotNull()
            .distinct()
    }

    private fun read(vararg paths: String): String {
        for (root in roots) {
            for (rel in paths) {
                // 允许 rel 既相对项目根也相对 app/ 模块根
                val f = File(root, rel)
                if (f.exists()) return f.readText()
                val f2 = File(root, "app/$rel")
                if (f2.exists()) return f2.readText()
            }
        }
        return ""
    }

    private fun find(vararg paths: String): File? {
        for (root in roots) {
            for (rel in paths) {
                val f = File(root, rel)
                if (f.exists()) return f
                val f2 = File(root, "app/$rel")
                if (f2.exists()) return f2
            }
        }
        return null
    }

    private val gradle: String by lazy { read("build.gradle.kts") }

    @Test
    fun `reverse engineering native libraries are gone`() {
        listOf(
            "libfrida_server.so", "libblutter_5e4949e6decb093d82c1.so",
            "librz_arch.so", "libcore_ghidra.so", "libcapstone.so",
            "libkeystone.so", "libunicorn.so",
        ).forEach { name ->
            assertTrue(
                "逆向 native 库 $name 不该存在（它占了 55MB 却与日常场景无关）",
                find("src/main/jniLibs/arm64-v8a/$name") == null,
            )
        }
        assertTrue(
            "ghidra sleigh 数据不该存在",
            find("src/main/assets/rizin") == null,
        )
    }

    @Test
    fun `reverse engineering tool sources are gone`() {
        assertTrue("BinaryToolkit 不该存在",
            find("src/main/java/me/rerere/rikkahub/data/ai/tools/BinaryToolkit.kt") == null)
        assertTrue("RizinBridge 不该存在",
            find("src/main/java/com/soreverse/mcp/nativecore/RizinBridge.kt") == null)
        assertTrue("bindisasm 不该存在",
            find("src/main/python/bindisasm.py") == null)
    }

    @Test
    fun `plugin decryption helper survives the cleanup`() {
        // HdGuard 与逆向工具无关，是插件解密用的，误删会让插件全部失效
        assertTrue("HdGuard.kt 必须保留（插件解密依赖它）",
            find("src/main/java/com/soreverse/mcp/nativecore/HdGuard.kt") != null)
    }

    @Test
    fun `python general purpose libraries are declared`() {
        assertTrue("找不到 build.gradle.kts", gradle.isNotEmpty())
        val required = listOf(
            "numpy", "pandas", "pillow", "lxml", "regex", "chardet", "dateparser",
            "pypinyin", "opencc-python-reimplemented", "pdfminer.six", "xlsxwriter",
        )
        required.forEach {
            assertTrue(
                "pip 清单缺少 $it —— 它是通用能力的组成部分，不该被当冗余删掉",
                gradle.contains("""install("$it")"""),
            )
        }
    }

    @Test
    fun `converter dependencies are actually installed`() {
        // convert.py 曾经引用了 docx / pptx / fpdf 三个包，但它们从未出现在
        // pip 清单里，导致 docx 双向转换、pptx→txt、文本→PDF 三条路径运行时
        // 必然 ImportError。这个断言防的是同一类错误再次发生。
        val convert = read("src/main/python/convert.py")
        assertTrue("找不到 convert.py", convert.isNotEmpty())
        if (convert.contains("from docx import")) {
            assertTrue("convert.py 用了 docx，pip 必须装 python-docx",
                gradle.contains("""install("python-docx")"""))
        }
        if (convert.contains("from pptx import")) {
            assertTrue("convert.py 用了 pptx，pip 必须装 python-pptx",
                gradle.contains("""install("python-pptx")"""))
        }
        if (convert.contains("from fpdf import")) {
            assertTrue("convert.py 用了 fpdf，pip 必须装 fpdf2",
                gradle.contains("""install("fpdf2")"""))
        }
    }

    @Test
    fun `image understanding relies on the user's vision model only`() {
        // 本地离线 OCR 已撤除：ML Kit 中文包内嵌 10.55 MB 的
        // libmlkit_google_ocr_pipeline.so（arm64），为「偶尔看图」付这个
        // 体积不划算。图片理解改为完全走用户配置的视觉模型。
        assertNull("LocalOcr.kt 应已删除",
            find("src/main/java/me/rerere/rikkahub/utils/LocalOcr.kt"))
        val ocr = read("src/main/java/me/rerere/rikkahub/data/ai/transformers/OcrTransformer.kt")
        assertTrue("应保留远程识别路径", ocr.contains("recognizeRemotely"))
        assertFalse("本地识别路径应已移除", ocr.contains("recognizeLocally"))
        assertFalse("不应再声明 ML Kit 文字识别依赖",
            gradle.contains("text.recognition.chinese"))
    }

    @Test
    fun `python tool description advertises the libraries`() {
        val tool = read("src/main/java/me/rerere/rikkahub/data/ai/tools/PythonTools.kt")
        assertTrue("找不到 PythonTools.kt", tool.isNotEmpty())
        // 描述拆成了三段常量按需拼接，所以这里检查常量名和内容
        assertTrue(
            "工具描述必须列出可用库，否则模型不知道有 numpy/pandas 可用就不会去用",
            tool.contains("PYTHON_LIBRARY_HINTS") && tool.contains("Preinstalled:"),
        )
        assertTrue("描述里应提到 numpy", tool.contains("numpy"))
        assertTrue("描述里应提到 pandas", tool.contains("pandas"))
        assertFalse(
            "逆向引擎的 rev_* 说明应已移除",
            tool.contains("REVERSE ENGINEERING"),
        )
    }
}
