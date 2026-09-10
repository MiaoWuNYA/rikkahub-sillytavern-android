package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.media.MediaScannerConnection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** 触发媒体扫描，让文件出现在相册/媒体库（MediaScannerConnection） */
internal fun buildMediaScannerTool(context: Context): Tool = Tool(
    name = "scan_media",
    description = "Notify the media scanner to scan specified file paths so they appear in gallery apps.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("paths", buildJsonObject {
                    put("type", "array")
                    put("description", "Array of absolute file paths to scan")
                    put("items", buildJsonObject { put("type", "string") })
                })
            },
            required = listOf("paths")
        )
    },
    execute = { args ->
        val pathsArray = args.jsonObject["paths"] as? JsonArray
        if (pathsArray == null || pathsArray.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text(deviceError("Missing or empty required parameter 'paths'").toString()))
        }
        try {
            val paths = pathsArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            if (paths.isEmpty()) {
                return@Tool listOf(UIMessagePart.Text(deviceError("No valid paths found in 'paths' array").toString()))
            }
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("scanned", paths.size)
                    put("message", "Media scan initiated for ${paths.size} file(s)")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
