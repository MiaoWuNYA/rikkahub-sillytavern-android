package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** 通过系统分享面板分享文本/链接 */
internal fun buildShareTool(context: Context): Tool = Tool(
    name = "share",
    description = "Share text or URL via the system share sheet.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text content to share (optional)")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL to share (optional)")
                })
                put("subject", buildJsonObject {
                    put("type", "string")
                    put("description", "Subject for email-type sharing (optional)")
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val text = params["text"]?.jsonPrimitive?.contentOrNull
        val url = params["url"]?.jsonPrimitive?.contentOrNull
        val subject = params["subject"]?.jsonPrimitive?.contentOrNull
        if (text.isNullOrBlank() && url.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(deviceError("At least one of 'text' or 'url' must be provided").toString()))
        }
        try {
            val combinedText = listOfNotNull(text, url).joinToString("\n")
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, combinedText)
                if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("message", "Share sheet opened")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
