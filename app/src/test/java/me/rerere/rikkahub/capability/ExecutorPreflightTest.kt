package me.rerere.rikkahub.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 执行器的静态检查接入，以及文件扫描的对称性。
 *
 * 这两条都是「写了但没接上」的典型：
 *  - codecheck 造好了却只是个可选工具，模型想不起来调就等于没有
 *  - 扫描新文件时 before 用递归、after 用 listdir，模型把结果写进
 *    子目录时文件完全不被回传，表现是「明明生成了却看不到」
 *
 * 顺带盯住一个曾经踩过的坑：静态检查绝不能拦截执行。用户写的就是
 * 想跑，不让跑比不检查更糟。
 */
class ExecutorPreflightTest {

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

    private fun executor(): String = read("src/main/python/executor.py")
    private fun codecheck(): String = read("src/main/python/codecheck.py")

    @Test
    fun `execution runs the static check automatically`() {
        val s = executor()
        assertTrue("应有 _preflight_check", s.contains("def _preflight_check"))
        assertTrue("执行前应调用它", s.contains("_preflight_check(code)"))
        // 调用必须发生在 _run_user_code 之前，否则检查毫无意义
        val callIdx = s.indexOf("_preflight_check(code)")
        val runIdx = s.indexOf("result = _run_user_code(code, _scope)")
        assertTrue("检查应在执行之前", callIdx in 0 until runIdx)
    }

    @Test
    fun `preflight never blocks execution`() {
        val s = executor()
        // 报告是「附带信息」，代码必须照常跑完
        assertTrue("应说明不拦截", s.contains("仍会继续执行"))
        val i = s.indexOf("_preflight_check(code)")
        val j = s.indexOf("result = _run_user_code(code, _scope)")
        assertTrue("调用之后仍然执行用户代码", i in 0 until j)
    }

    @Test
    fun `preflight only reports reliable findings`() {
        val s = executor()
        // 启发式提示（C 的无边界函数之类）在正常代码里也大量命中，
        // 混进来会把输出变成噪音，模型反而忽略真正的问题
        assertTrue("应过滤掉不可靠结论", s.contains("if not report.get('reliable')"))
        assertTrue("异常不能影响执行", s.contains("检查本身绝不能影响执行"))
    }

    @Test
    fun `file scan is symmetric between before and after`() {
        val s = executor()
        // before 与 after 必须用同一套扫描方式，否则差集算错
        val walks = Regex("os\\.walk\\(workdir\\)").findAll(s).count()
        assertTrue("before 与 after 都应使用 os.walk，实际 $walks 处", walks >= 2)
        assertFalse(
            "after 不应退回只扫顶层的 os.listdir",
            s.contains("after = set(os.listdir(workdir))"),
        )
        assertTrue("应跳过 __pycache__", s.contains("'__pycache__'"))
    }

    @Test
    fun `structured formats get real parsing`() {
        val s = codecheck()
        assertTrue("应有 check_structured", s.contains("def check_structured"))
        // JSON/YAML/TOML/XML 有现成解析器，不该退回括号检查
        assertTrue("JSON 走真解析", s.contains("_json.loads(code)"))
        assertTrue("YAML 走 safe_load", s.contains("yaml.safe_load_all"))
        assertTrue("TOML 有解析", s.contains("tomllib") || s.contains("tomli"))
        assertTrue("XML 走 lxml", s.contains("etree.fromstring"))
        assertTrue("分派时应优先用结构化检查",
            s.contains("check_structured(code, lang, filename or '<code>')"))
    }

    @Test
    fun `inflect is extracted so typeguard can read its source`() {
        val gradle = read("build.gradle.kts")
        // inflect 依赖 typeguard，后者用 inspect.getsource 读被装饰函数
        // 所在模块的源码做插桩。源码留在 zip 里读不到，import 直接
        // OSError: could not get source code
        assertTrue("inflect 应加入 extractPackages",
            gradle.contains("""extractPackages("docx", "pptx", "inflect")"""))
    }
}
