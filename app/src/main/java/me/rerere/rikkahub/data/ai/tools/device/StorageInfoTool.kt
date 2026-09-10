package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** 内置/外部存储容量（StatFs） */
internal fun buildStorageInfoTool(context: Context): Tool = Tool(
    name = "get_storage_info",
    description = "Get internal and external storage space usage info (total, free, used bytes).",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        try {
            val result = buildJsonObject {
                put("success", true)
                try {
                    val stat = StatFs(Environment.getDataDirectory().path)
                    val totalBytes = stat.totalBytes
                    val freeBytes = stat.freeBytes
                    putJsonObject("internal") {
                        put("total_bytes", totalBytes)
                        put("free_bytes", freeBytes)
                        put("used_bytes", totalBytes - freeBytes)
                    }
                } catch (e: Exception) {
                    putJsonObject("internal") { put("error", e.message ?: "Failed to read internal storage") }
                }
                try {
                    if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                        val stat = StatFs(Environment.getExternalStorageDirectory().path)
                        val totalBytes = stat.totalBytes
                        val freeBytes = stat.freeBytes
                        putJsonObject("external") {
                            put("total_bytes", totalBytes)
                            put("free_bytes", freeBytes)
                            put("used_bytes", totalBytes - freeBytes)
                        }
                    } else {
                        put("external", JsonNull)
                    }
                } catch (e: Exception) {
                    putJsonObject("external") { put("error", e.message ?: "Failed to read external storage") }
                }
            }
            listOf(UIMessagePart.Text(result.toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
