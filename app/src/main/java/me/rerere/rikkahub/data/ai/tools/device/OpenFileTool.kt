package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

/**
 * 用系统查看器打开文件（ACTION_VIEW + FileProvider）。
 * 公共存储路径用 file:// URI，应用私有目录走 FileProvider content:// URI。
 */
internal fun buildOpenFileTool(context: Context): Tool = Tool(
    name = "open_file",
    description = "Open a file in the user's OS viewer (Gallery / PDF reader / audio player / text editor). " +
        "Path accepts an absolute file path. Optional mime_type forces a specific viewer when the extension is ambiguous.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to the file")
                })
                put("mime_type", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional MIME type override — defaults to the OS guess from the file extension")
                })
            },
            required = listOf("path")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val rawPath = params["path"]?.jsonPrimitive?.contentOrNull
        if (rawPath.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(deviceError("path is required").toString()))
        }
        try {
            val file = File(rawPath)
            if (!file.exists() || !file.isFile) {
                return@Tool listOf(UIMessagePart.Text(deviceError("File not found: $rawPath").toString()))
            }
            val mime = params["mime_type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "*/*"
            // 私有目录文件必须走 FileProvider，目标应用才能跨沙盒读取
            val absolute = file.absolutePath
            val isPublicStorage = absolute.startsWith("/storage/") || absolute.startsWith("/sdcard/")
            val uri = if (isPublicStorage) {
                android.net.Uri.fromFile(file)
            } else {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("path", absolute)
                    put("mime", mime)
                    put("message", "Opened ${file.name} with a $mime viewer")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Failed to open file").toString()))
        }
    }
)
