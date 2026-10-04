package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 代码检查。
 *
 * 定位和 execute_python 不同：那个是「跑起来看结果」，这个是「跑之前先看
 * 有没有明显问题」。对 Python 是真正的静态分析（pyflakes + ast），能查出
 * 未定义名、未使用 import、语法错误；对其他语言只能做括号配对和常见写法
 * 提示——设备上没有 gcc/javac，这一点会在返回值里如实标注。
 *
 * 语言识别不靠内容猜：pygments 的 guess_lexer 实测很不准（Java 判成
 * Text only、Go 判成 GDScript）。改为文件名优先，所以描述里明确要求
 * 提供 filename。
 */
private const val CODE_CHECK_TIMEOUT_SEC = 60

private const val CHECK_TOOL_DESCRIPTION =
    "Statically check code. Reliable findings: Python (syntax, undefined names,\n" +
        "unused imports/vars) and JSON/YAML/TOML/XML, which are really parsed and\n" +
        "give exact line/column. Other languages: bracket matching + pitfall hints\n" +
        "only (no compiler on device), flagged as heuristic. Also detects language.\n" +
        "execute_python already checks Python automatically; use this tool for\n" +
        "other languages or data files."

internal fun buildCodeCheckTool(context: Context): Tool = Tool(
    name = "check_code",
    description = CHECK_TOOL_DESCRIPTION,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "Source code to check.")
                })
                put("filename", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Name the code would have (e.g. main.c, App.java). " +
                            "Strongly recommended — it is the most reliable way to " +
                            "detect the language.",
                    )
                })
                put("language", buildJsonObject {
                    put("type", "string")
                    put("description", "Override language detection, e.g. python, c, java.")
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("check"))
                        add(JsonPrimitive("lex"))
                    })
                    put(
                        "description",
                        "check (default) reports problems; lex returns tokens, " +
                            "keywords and comments — useful to summarize unfamiliar code.",
                    )
                })
            },
            required = listOf("code"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val code = obj["code"]?.jsonPrimitive?.content
            ?: error("code parameter is required")
        val filename = obj["filename"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val language = obj["language"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val action = obj["action"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "check"

        val raw = runBlocking {
            withContext(Dispatchers.IO) {
                val executor = Executors.newSingleThreadExecutor()
                try {
                    // Chaquopy 要求在主线程序列化启动
                    if (!Python.isStarted()) {
                        withContext(Dispatchers.Main) {
                            Python.start(AndroidPlatform(context))
                        }
                    }
                    val future = executor.submit<String> {
                        val module = Python.getInstance().getModule("codecheck")
                        when (action) {
                            "lex" -> module.callAttr(
                                "lex", code, filename ?: "", language ?: "",
                            ).toString()

                            else -> module.callAttr(
                                "check", code, filename ?: "", language ?: "",
                            ).toString()
                        }
                    }
                    future.get(CODE_CHECK_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
                } catch (e: Exception) {
                    """{"error":"${e.message?.replace("\"", "'") ?: "unknown"}"}"""
                } finally {
                    executor.shutdown()
                }
            }
        }

        listOf(UIMessagePart.Text(raw))
    },
)
