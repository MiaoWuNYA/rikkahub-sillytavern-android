package me.rerere.rikkahub.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 通用能力的接线与开关。
 *
 * 这轮把逆向工具链腾出的空间转投到了日常能力上，但「装了库」和
 * 「模型会用库」是两件事：
 *   - 库装了，工具描述里不写，模型仍然只用标准库手搓；
 *   - 图片识别只有远程一条路，没配模型就等于看不见。
 * 所以这里锁的不只是依赖清单，还有三处接线本身。
 */
class GeneralCapabilityTest {

    private val roots: List<File> by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile)
            .filterNotNull().distinct()
    }

    private fun read(rel: String): String {
        for (root in roots) {
            for (candidate in listOf(File(root, rel), File(root, "app/$rel"))) {
                if (candidate.exists()) return candidate.readText()
            }
        }
        return ""
    }

    private fun exists(rel: String): Boolean {
        for (root in roots) {
            for (candidate in listOf(File(root, rel), File(root, "app/$rel"))) {
                if (candidate.exists()) return true
            }
        }
        return false
    }
    /** 返回文件（存在时），不存在返回 null——用于断言「已删除」。 */
    private fun find(rel: String): File? {
        for (root in roots) {
            for (c in listOf(File(root, rel), File(root, "app/$rel"))) {
                if (c.exists()) return c
            }
        }
        return null
    }


    // ── 数据格式转换 ────────────────────────────────────────────

    @Test
    fun `yaml json csv toml conversions are implemented`() {
        val conv = read("src/main/python/convert.py")
        assertTrue("找不到 convert.py", conv.isNotEmpty())
        // 新增的格式互通必须真的在分派里，而不只是写在文档字符串里
        assertTrue("yaml → json 未实现", conv.contains("_load_yaml"))
        assertTrue("json → yaml 未实现", conv.contains("_dump_yaml"))
        assertTrue("json/yaml → table 未实现", conv.contains("json → table 需要对象数组或对象"))
        assertTrue("toml 读取未实现", conv.contains("_load_toml"))
        assertTrue("csv → table 分支应并入 csv 主分支",
            conv.contains("rows = list(csv.reader(io.StringIO(_csv_text)))"))
    }

    @Test
    fun `format conversions accept inline text not just file paths`() {
        // 模型手上常常只有一小段文本，强制落盘再读是多余的往返；
        // 早年这三个分支只认 input_path，直接给文本会 TypeError。
        val conv = read("src/main/python/convert.py")
        assertTrue("csv 读取应走 _text()", conv.contains("_csv_text = _text()"))
        assertTrue("json 读取应走 _text()", conv.contains("data = json.loads(_text())"))
        assertFalse("不该再有裸 open(input_path) 的 JSON 读取",
            conv.contains("json.load(open(input_path"))
    }

    @Test
    fun `yaml parser handles the structures that actually appear in configs`() {
        val conv = read("src/main/python/convert.py")
        // 对象列表（- key: value 后跟同缩进键）是配置文件里最常见的结构，
        // 只解析出第一个键是早期版本的缺陷，这里锁住修复。
        assertTrue("对象列表项应被识别并合并后续键", conv.contains("_looks_like_scalar_with_colon"))
        assertTrue("带冒号的标量不应被当成映射", conv.contains("if rest.startswith('//')"))
    }

    // ── 图片理解（本地 OCR 已移除）─────────────────────────────

    @Test
    fun `offline ocr is gone and its dependency is not declared`() {
        // ML Kit 中文识别包内嵌 10.55 MB 的 libmlkit_google_ocr_pipeline.so
        // （arm64），为「偶尔看图」付这个体积不划算，已改为完全依赖
        // 用户自己配置的视觉模型——识别质量本来就更好。
        assertNull(
            "LocalOcr.kt 应已删除",
            find("src/main/java/me/rerere/rikkahub/utils/LocalOcr.kt"),
        )
        val gradle = read("build.gradle.kts")
        assertFalse(
            "不应再声明 text-recognition-chinese 依赖",
            gradle.contains("text.recognition.chinese") ||
                gradle.contains("text-recognition-chinese"),
        )
    }

    @Test
    fun `image handling tells the model when no vision model is configured`() {
        val ocr = read("src/main/java/me/rerere/rikkahub/data/ai/transformers/OcrTransformer.kt")
        assertTrue("找不到 OcrTransformer.kt", ocr.isNotEmpty())
        // 撤掉本地兜底后不能再返回 "[Image]"——那对模型等于白纸一张。
        // 要给出可操作的说明，模型才有机会把配置问题告诉用户。
        assertFalse(
            "不应再出现裸的 [Image] 占位（模型看不懂）",
            ocr.contains("?: \"[Image]\""),
        )
        assertTrue("应说明需要配置视觉模型",
            ocr.contains("no vision model is configured") ||
                ocr.contains("vision model"))
        assertTrue("远程识别路径应保留", ocr.contains("recognizeRemotely"))
        assertFalse("本地识别路径应已移除", ocr.contains("recognizeLocally"))
    }

    // ── Python 库提示开关 ───────────────────────────────────────

    @Test
    fun `python library hints are switchable and actually wired`() {
        val tool = read("src/main/java/me/rerere/rikkahub/data/ai/tools/PythonTools.kt")
        assertTrue("createPythonTool 应接受 includeLibraryHints",
            tool.contains("includeLibraryHints: Boolean"))
        assertTrue("库清单应被抽成常量按需拼接",
            tool.contains("if (includeLibraryHints)") && tool.contains("append(PYTHON_LIBRARY_HINTS)"))

        val chat = read("src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        assertTrue("调用点应把开关接进去",
            chat.contains("includeLibraryHints = settings.huadengSettings.enablePythonLibraryHints"))
    }

    @Test
    fun `converter guidance survives even when library hints are off`() {
        val tool = read("src/main/java/me/rerere/rikkahub/data/ai/tools/PythonTools.kt")
        // convert 模块的说明不属于「库清单」那部分，关掉提示不该连它一起消失，
        // 否则模型完全不知道有这个转换入口。
        assertTrue("convert 指引应与库清单分开拼接",
            tool.contains("append(PYTHON_CONVERT_HINTS)"))
        val idx = tool.indexOf("if (includeLibraryHints)")
        val convIdx = tool.indexOf("append(PYTHON_CONVERT_HINTS)")
        assertTrue("convert 指引必须在条件块之外", idx in 0 until convIdx)
    }

    // ── 设置项本身 ──────────────────────────────────────────────

    @Test
    fun `settings fields default to on`() {
        val prefs = read("src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")
        // enableLocalOcrFallback 已废弃（本地 OCR 撤除），字段保留只为
        // 让老配置能正常反序列化，所以新默认值必须是 false
        assertTrue("enableLocalOcrFallback 应已废弃并置为 false",
            prefs.contains("val enableLocalOcrFallback: Boolean = false"))
        assertTrue("enablePythonLibraryHints 应默认开启",
            prefs.contains("val enablePythonLibraryHints: Boolean = true"))
    }

    @Test
    fun `settings page exposes both switches`() {
        val page = read("src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingHuaDengPage.kt")
        assertFalse("设置页不应再有已废弃的 OCR 开关",
            page.contains("enableLocalOcrFallback = enabled"))
        assertTrue("设置页应有库提示开关", page.contains("enablePythonLibraryHints = enabled"))

        listOf("values", "values-zh").forEach { locale ->
            val strings = read("src/main/res/$locale/strings.xml")
            // OCR 文案随功能一起撤掉，留着只会让人以为还有这个开关
            assertFalse("$locale 不应再有 OCR 文案",
                strings.contains("huadeng_local_ocr_title"))
            assertTrue("$locale 缺少库提示文案", strings.contains("huadeng_python_hints_title"))
        }
    }
}
