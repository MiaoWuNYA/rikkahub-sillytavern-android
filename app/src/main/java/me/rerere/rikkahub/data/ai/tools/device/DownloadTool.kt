package me.rerere.rikkahub.data.ai.tools.device

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import androidx.core.net.toUri
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** 通过系统 DownloadManager 下载文件到公共 Downloads 目录 */
internal fun buildDownloadFileTool(context: Context): Tool = Tool(
    name = "download_file",
    description = "Queue a file download via Android's DownloadManager. Files land in the public Downloads directory. Returns immediately with a download_id; the actual download proceeds in the background.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The URL of the file to download")
                })
                put("filename", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional filename to save as (defaults to last URL path segment)")
                })
            },
            required = listOf("url")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val url = params["url"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text(deviceError("url is required").toString()))
        val filenameParam = params["filename"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        try {
            val uri = url.toUri()
            val name = (filenameParam
                ?: uri.lastPathSegment
                ?: "download_${System.currentTimeMillis()}")
                .substringAfterLast('/')
                .trimStart('.')
                .ifEmpty { "download_${System.currentTimeMillis()}" }
            val request = DownloadManager.Request(uri)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .setTitle(name)
            val dm = context.getSystemService(DownloadManager::class.java)
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("DownloadManager unavailable").toString()))
            val id = dm.enqueue(request)
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("download_id", id)
                    put("filename", name)
                    put("message", "Download queued as $name (id=$id)")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Failed to queue download").toString()))
        }
    }
)
