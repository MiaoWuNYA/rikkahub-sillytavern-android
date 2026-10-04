package me.rerere.rikkahub.build

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chaquopy 依赖瘦身。
 *
 * requirements-common.imy 占了 APK 体积的一多半，而它把每个包完整的
 * tests/ 目录也打了进去——pandas 一家的测试套件就有十几 MB。生产环境
 * 不跑 pytest，这些文件纯属「引入了资源但永远不会被使用」。
 *
 * 剔除它们的风险在于「手滑删错」：numpy/tests 该删，numpy/testing 不能删；
 * *.so 一个都不能丢。这个测试锁住那条线。
 */
class ChaquopySlimTest {

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

    private fun script(): String = read("build-tools/strip_chaquopy.py")

    @Test
    fun `strip script exists and is wired into packaging`() {
        assertTrue("找不到瘦身脚本", script().isNotEmpty())
        val gradle = read("build.gradle.kts")
        assertTrue("gradle 应注册 stripChaquopyTests 任务",
            gradle.contains("stripChaquopyTests"))
        assertTrue("应在 mergeReleaseAssets 之后执行",
            gradle.contains("mergeReleaseAssets") && gradle.contains("finalizedBy"))
        assertTrue("packageRelease 必须在瘦身之后",
            gradle.contains("packageRelease") && gradle.contains("mustRunAfter"))
    }

    @Test
    fun `only whole path segments named tests are dropped`() {
        val s = script()
        // 子串匹配会误伤 numpy.testing / pytest-cov 之类的正常模块。
        // 必须按整段比较。
        assertTrue("应按整段匹配 tests",
            s.contains("\"tests\" in segments"))
        assertFalse("不该用子串匹配 tests",
            s.contains("\"tests\" in name") || s.contains("'tests' in name"))
    }

    @Test
    fun `runtime artifacts are never dropped`() {
        val s = script()
        // 这是最危险的一类：.so 是原生扩展，丢了整个包就废了
        assertFalse("不能删 .so", s.contains("\".so\""))
        assertFalse("不能删 .pyc", s.contains("\".pyc\""))
        assertFalse("不能删 .py", s.contains("endswith(\".py\")"))
        // RECORD 是包自检用的，保留
        assertTrue("dist-info 里要保留 RECORD", s.contains("RECORD"))
    }

    @Test
    fun `stdlib archives are left alone`() {
        val s = script()
        // stdlib 里没有 tests 可丢，重新压一遍反而变大（实测 4.2MB → 9.6MB）
        val names = Regex("IMY_NAMES\\s*=\\s*\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL)
            .find(s)?.groupValues?.get(1).orEmpty()
        assertTrue("应处理 requirements-common", names.contains("requirements-common.imy"))
        assertFalse("不该碰 stdlib-common", names.contains("stdlib-common.imy"))
        assertFalse("不该碰 stdlib-arm64", names.contains("stdlib-arm64-v8a.imy"))
    }

    @Test
    fun `regression guard against growing the archive`() {
        val s = script()
        // 第一版脚本用 ZIP_STORED 重写，把 imy 撑大到两倍。
        // 现在的保护是：重压后没变小就丢弃结果。
        assertTrue("应有负优化保护", s.contains("if after >= before"))
        assertTrue("应使用 deflate", s.contains("ZIP_DEFLATED"))
        assertTrue("必须显式设置 compress_type",
            s.contains("out_info.compress_type"))
    }

    @Test
    fun `new capability packages are declared`() {
        val gradle = read("build.gradle.kts")
        // 这批是「日常十次遇到五次」的能力，不该被当成冗余删掉
        listOf("sympy", "pygments", "xlrd", "xlwt", "xlutils",
               "docxtpl", "cn2an", "zhon", "jsonschema", "python-frontmatter",
               "ebooklib", "pyyaml", "pypdfium2").forEach {
            assertTrue("pip 清单缺少 $it", gradle.contains("""install("$it")"""))
        }
    }

    @Test
    fun `matplotlib stays out now that chart_display covers plotting`() {
        val gradle = read("build.gradle.kts")
        // 上游的 chart_display 是原生 Compose 渲染、直接显示在聊天里；
        // matplotlib 只能出静态 PNG，8 MB 换来的能力与它高度重叠。
        // 这条测试防止有人「顺手」把它加回来。
        assertFalse(
            "matplotlib 已被 chart_display 取代，不该再出现在 pip 清单里",
            gradle.contains("""install("matplotlib")"""),
        )
    }

    @Test
    fun `docx and pptx are extracted so their templates resolve`() {
        val gradle = read("build.gradle.kts")
        // Chaquopy 默认从 APK 加载模块，__file__ 指向 zip 内部；
        // python-docx / python-pptx 用 dirname(__file__)/../templates 找模板，
        // 那种布局下解析不到 → 页眉页脚、PPT 备注全部失败。
        // extractPackages 让这两个包在首次 import 时解压成真实文件。
        assertTrue(
            "必须声明 extractPackages(\"docx\", \"pptx\")，否则页眉页脚/PPT备注会崩",
            gradle.contains("extractPackages") &&
                gradle.contains("\"docx\"") && gradle.contains("\"pptx\""),
        )
    }

    @Test
    fun `image display capability is wired end to end`() {
        val localTools = read("src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt")
        val assistant = read("src/main/java/me/rerere/rikkahub/data/model/Assistant.kt")
        val pythonTools = read("src/main/java/me/rerere/rikkahub/data/ai/tools/PythonTools.kt")

        assertTrue("缺少 show_image 工具", read("src/main/java/me/rerere/rikkahub/data/ai/tools/ShowImageTool.kt").isNotEmpty())
        assertTrue("show_image 未注册到 LocalToolOption", localTools.contains("ShowImage"))
        assertTrue("show_image 未加入默认工具集", assistant.contains("LocalToolOption.ShowImage"))
        // Python 生成的图片必须走 Image（内嵌渲染）而不是 Document（文件卡片）
        assertTrue(
            "Python 出图应走 UIMessagePart.Image 才能显示在聊天里",
            pythonTools.contains("UIMessagePart.Image"),
        )
    }

    @Test
    fun `stderr is surfaced to the model`() {
        val pythonTools = read("src/main/java/me/rerere/rikkahub/data/ai/tools/PythonTools.kt")
        // 之前 stderr 被整个丢弃：Python 的警告和库的提示模型看不到，
        // 就不知道自己的代码其实有问题。
        assertTrue("Python 工具应回传 stderr", pythonTools.contains("\"stderr\""))
    }

    @Test
    fun `office module exists and is advertised to the model`() {
        assertTrue("office.py 应存在", read("src/main/python/office.py").isNotEmpty())
        val tool = read("src/main/java/me/rerere/rikkahub/data/ai/tools/PythonTools.kt")
        // 装了不告诉模型 = 白装。这是「资源没被使用」最常见的成因。
        assertTrue("工具描述必须提到 office 模块", tool.contains("office.*"))
        assertTrue("应提到 Office 读写能力",
            tool.contains("Word/PPT/Excel"))
    }
}
