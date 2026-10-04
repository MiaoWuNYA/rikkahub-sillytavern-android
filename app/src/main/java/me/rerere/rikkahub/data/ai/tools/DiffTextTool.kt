package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 文本差异对比。
 *
 * 替换掉原先的二进制对比（diff_binary）——那个工具要看的是编译产物，
 * 与日常场景几乎不相交；而这个工具面对的是改动过的配置、两版稿子、
 * 修过的代码，属于「十次对话遇到五次」的范畴。
 *
 * 为什么单独做一个工具而不是让模型自己写 difflib：
 *   1. 输出格式统一。让模型每次自己拼差异输出，十次能有十种格式，
 *      既费 token 又难读；unified diff 是它和人共同熟悉的表示。
 *   2. 对比对象常常是文件。模型得先读两个文件、再拼进代码里，
 *      中途任何一步截断都会让结果失真。
 *
 * 走 Python 的 difflib（标准库），不引入任何新依赖。
 */
internal fun buildDiffTextTool(appContext: Context): Tool = Tool(
    name = "diff_text",
    needsApproval = { false },
    description = buildString {
        appendLine("Compare two texts or two files and report exactly what changed.")
        appendLine()
        appendLine("Returns a unified diff (`--- a` / `+++ b` / `@@` hunks) plus a one-line summary")
        appendLine("of how many lines were added, removed and left unchanged.")
        appendLine()
        appendLine("When to use:")
        appendLine("- The user pastes two versions of a text and asks what changed")
        appendLine("- Two files need comparing (a config before/after, a draft revision)")
        appendLine("- You edited something and want to show the user a precise diff")
        appendLine()
        appendLine("When NOT to use:")
        appendLine("- Comparing binary files (bytes, images, executables) — this is text-only")
        appendLine("- A trivial change you can describe in one sentence")
        appendLine()
        appendLine("Args:")
        appendLine("- left / right: the two things to compare. Each may be either a file path or the")
        appendLine("  literal text. A value is treated as a path only when the file actually exists;")
        appendLine("  otherwise it is taken as text, so a pasted paragraph never gets mistaken for a path.")
        appendLine("- left_path / right_path: boolean, force the value to be read as a file (default false)")
        appendLine("- ignore_whitespace: ignore leading/trailing and repeated whitespace (default false)")
        appendLine("- context: unchanged lines shown around each change (default 3)")
        appendLine()
        appendLine("Either side may be omitted if you only want to see what the other side contains.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("left", buildJsonObject {
                    put("type", "string")
                    put("description", "First version: a file path, or the literal text")
                })
                put("right", buildJsonObject {
                    put("type", "string")
                    put("description", "Second version: a file path, or the literal text")
                })
                put("left_path", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Force 'left' to be read as a file path (default false)")
                })
                put("right_path", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Force 'right' to be read as a file path (default false)")
                })
                put("ignore_whitespace", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Ignore whitespace differences (default false)")
                })
                put("context", buildJsonObject {
                    put("type", "integer")
                    put("description", "Unchanged context lines around each change (default 3)")
                })
            },
            required = listOf("left", "right"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val left = obj["left"]?.jsonPrimitive?.content.orEmpty()
        val right = obj["right"]?.jsonPrimitive?.content.orEmpty()
        val leftIsPath = obj["left_path"]?.jsonPrimitive?.content?.toBoolean() ?: false
        val rightIsPath = obj["right_path"]?.jsonPrimitive?.content?.toBoolean() ?: false
        val ignoreWs = obj["ignore_whitespace"]?.jsonPrimitive?.content?.toBoolean() ?: false
        val contextLines = obj["context"]?.jsonPrimitive?.content?.toIntOrNull() ?: 3

        if (!Python.isStarted()) {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Python.start(AndroidPlatform(appContext))
                }
            }
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String> {
                val py = Python.getInstance()
                val bridge = py.getModule("difftext")
                bridge.callAttr(
                    "diff_entries",
                    left, right, leftIsPath, rightIsPath, ignoreWs, contextLines,
                ).toString()
            }
            val result = future.get(60, TimeUnit.SECONDS)
            listOf(UIMessagePart.Text(result))
        } catch (e: Throwable) {
            listOf(UIMessagePart.Text("diff failed: ${e.message}"))
        } finally {
            executor.shutdown()
        }
    },
)
