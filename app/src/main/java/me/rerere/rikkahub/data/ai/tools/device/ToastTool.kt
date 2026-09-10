package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** 屏幕弹出 Toast 提示 */
internal fun buildToastTool(context: Context): Tool = Tool(
    name = "show_toast",
    description = "Show a brief Toast notification on the screen. Use sparingly, only for short feedback.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to display in the toast.")
                })
                put("long", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to use long duration (3.5s) instead of short (2s). Default false.")
                })
            },
            required = listOf("text")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val text = params["text"]?.jsonPrimitive?.contentOrNull
        val long = params["long"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        if (text.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(deviceError("Missing required parameter 'text'").toString()))
        }
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    // Toast 必须在主线程且有窗口权限，失败时静默
                }
            }
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("text", text)
                    put("long", long)
                    put("message", "Toast shown: $text")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
