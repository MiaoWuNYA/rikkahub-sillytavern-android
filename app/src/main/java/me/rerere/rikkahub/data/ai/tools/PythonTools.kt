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

fun createPythonTool(context: Context, timeoutSec: Int = 30): Tool = Tool(
    name = "execute_python",
    description = "Execute Python code for data processing, API calls, or file generation.\n\n" +
        "Use this tool when you need programmatic logic — loops, conditionals, API integration — that goes beyond simple math.\n\n" +
        "When to use:\n" +
        "- Data processing and transformation (CSV, JSON)\n" +
        "- API calls to external services\n" +
        "- File generation (CSV, JSON, images)\n" +
        "- Complex computations that calculator cannot handle\n\n" +
        "When NOT to use:\n" +
        "- Simple math (use calculator)\n" +
        "- Shell commands (use execute_command)\n" +
        "- File operations (use file tools)\n\n" +
        "*** 命理排盘见系统提示词 <mingli_routing> 段（不虚构数据）***\n" +
        "Args:\n" +
        "- code: Python code to execute. Last expression value returned. Use print() for debugging.\n" +
        "Code runs in an isolated Python environment on the device.",
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

        // Collect output text
        val output = buildString {
            resultJson["stdout"]?.jsonPrimitive?.content?.let {
                if (it.isNotBlank()) appendLine("Output:\n$it")
            }
            resultJson["result"]?.jsonPrimitive?.content?.let {
                if (it.isNotBlank()) appendLine("Result: $it")
            }
            resultJson["error"]?.jsonPrimitive?.content?.let {
                appendLine("Error: $it")
            }
        }
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
