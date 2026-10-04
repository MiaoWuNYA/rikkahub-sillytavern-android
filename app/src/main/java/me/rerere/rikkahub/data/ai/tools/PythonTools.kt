package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.python.PythonBridge
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.java.KoinJavaComponent
import java.io.File

/**
 * execute_python 的描述。
 *
 * 开销敏感：这段文字每次请求都会随工具定义发给模型，所以只保留
 * 「模型自己推断不出来的信息」——也就是有哪些库可用。
 * 每个库的功能不写，模型认识 numpy/pandas/PIL 这些名字，逐条解释
 * 功能纯属浪费 token（早先版本每个库带一句说明，约 270 token，
 * 压到现在这个规模）。
 */
private const val PYTHON_TOOL_HEAD =
    "Run Python 3.12 on-device (isolated). For computation, data handling, file and\n" +
        "document generation. (simple math -> calculator; shell -> execute_command)\n"

/** 库清单：约 60 token。关掉后模型仍可 import，只是不知道装了哪些。 */
private const val PYTHON_LIBRARY_HINTS =
    "Preinstalled: numpy, pandas, PIL(Pillow), docx, pptx, fpdf, pypdf, pdfminer,\n" +
        "openpyxl, xlsxwriter, bs4, lxml, requests, markdown, markdownify, regex,\n" +
        "chardet, dateparser, pypinyin, opencc, tabulate, pytz.\n" +
        "Also: sympy (symbolic math), matplotlib (plots), pygments (code lexing),\n" +
        "xlrd/xlwt (legacy .xls), docxtpl (docx templates), ebooklib (epub),\n" +
        "cn2an (Chinese numerals), zhon (Chinese punctuation), jsonschema,\n" +
        "python-frontmatter, pyyaml, pypdfium2 (PDF render).\n" +
        // 中文画图是个真实的坑：设备上没有含汉字字形的字体，图里的中文
        // 会渲染成方块。事先说一句，比让模型画完发现看不懂要省事。
        "(matplotlib has no CJK glyphs — use English labels in plots.)\n"

/**
 * convert 模块的入口说明。
 *
 * 这一条不随库清单开关关闭：模块名和签名是模型自己推断不出来的，
 * 去掉之后它完全不知道有这个转换入口，只能去手搓。
 */
private const val PYTHON_CONVERT_HINTS =
    "Format conversion helper: convert.convert(path, None, src_fmt, dst_fmt, workdir)\n" +
        "handles txt/md/html/docx/pdf/xlsx/pptx/epub/csv/json/yaml/toml and *->table\n" +
        "\n" +
        // office 模块每个函数能做什么写在它自己的 docstring 里，模型
        // 用 help(office.<fn>) 就能拿到。描述里只留「有这么个模块、
        // 干什么用」——十几个操作名不该出现在每次请求都发的工具定义里。
        "office.* : read/edit Word/PPT/Excel in place (tables, formulas, slides, sheets).\n" +
        "  inspect + edit pairs; help(office) lists operations.\n"

private const val PYTHON_TOOL_TAIL =
    "code: Python source. Last expression is returned; use print() to debug."

fun createPythonTool(
    context: Context,
    timeoutSec: Int = 30,
    includeLibraryHints: Boolean = true,
): Tool = Tool(
    name = "execute_python",
    description = buildString {
        append(PYTHON_TOOL_HEAD)
        if (includeLibraryHints) {
            append("\n")
            append(PYTHON_LIBRARY_HINTS)
        }
        append("\n")
        append(PYTHON_CONVERT_HINTS)
        append("\n")
        append(PYTHON_TOOL_TAIL)
    },
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "Python code to execute. Last expression value is returned. Use print() for debugging.")
                })
            },
            required = listOf("code"),
        )
    },
    execute = { args ->
        val code = args.jsonObject["code"]?.jsonPrimitive?.content
            ?: error("code parameter is required")

        // Start Python if needed (must be on main thread for Chaquopy init)
        if (!Python.isStarted()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                Python.start(AndroidPlatform(context))
            }
        }

        val py = Python.getInstance()
        val executor = py.getModule("executor")
        val workdir = context.filesDir.absolutePath

        // Inject Android bridge (passed as parameter to execute())
        val bridge = PythonBridge(
            context = context,
            db = KoinJavaComponent.get<AppDatabase>(AppDatabase::class.java),
            settingsStore = KoinJavaComponent.get<SettingsStore>(SettingsStore::class.java),
            conversationRepo = KoinJavaComponent.get<ConversationRepository>(ConversationRepository::class.java),
        )

        val rawResult = withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeout(timeoutSec * 1000L) {
                executor.callAttr("execute", code, workdir, bridge).toString()
            }
        }

        // Try to parse JSON result from Python
        val resultJson = try {
            kotlinx.serialization.json.Json.parseToJsonElement(rawResult).jsonObject
        } catch (e: Exception) {
            // Not JSON, return as plain text
            return@Tool listOf(UIMessagePart.Text(rawResult))
        }

        // Build structured response with files
        val parts = mutableListOf<UIMessagePart>()

        // Collect output text（safe access：stdout 非字符串类型时 jsonPrimitive 会直接抛异常）
        val output = buildString {
            resultJson["stdout"]?.jsonPrimitiveOrNull?.content?.let {
                if (it.isNotBlank()) appendLine("Output:\n$it")
            }
            resultJson["result"]?.jsonPrimitiveOrNull?.content?.let {
                if (it.isNotBlank()) appendLine("Result: $it")
            }
            resultJson["error"]?.jsonPrimitiveOrNull?.content?.let {
                appendLine("Error: $it")
            }
        }.truncateForToolResult()
        if (output.isNotBlank()) {
            parts.add(UIMessagePart.Text(output.trimEnd()))
        }

        // Attach generated files
        val files = resultJson["files"]?.let { elem ->
            try {
                elem.jsonArray.map { it.jsonPrimitive.content }
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()

        for (fname in files) {
            val file = File(workdir, fname)
            if (file.exists()) {
                parts.add(UIMessagePart.Document(
                    url = "file://" + file.absolutePath,
                    fileName = fname,
                    mime = fname.mimeType(),
                ))
            }
        }

        if (parts.isEmpty()) {
            parts.add(UIMessagePart.Text(rawResult))
        }
        parts
    },
)

private fun String.mimeType(): String = when {
    endsWith(".png") -> "image/png"
    endsWith(".svg") -> "image/svg+xml"
    endsWith(".jpg") || endsWith(".jpeg") -> "image/jpeg"
    endsWith(".gif") -> "image/gif"
    endsWith(".html") -> "text/html"
    endsWith(".json") -> "application/json"
    endsWith(".csv") -> "text/csv"
    endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    endsWith(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    endsWith(".pdf") -> "application/pdf"
    endsWith(".txt") || endsWith(".md") -> "text/plain"
    endsWith(".yaml") || endsWith(".yml") -> "application/x-yaml"
    else -> "application/octet-stream"
}
