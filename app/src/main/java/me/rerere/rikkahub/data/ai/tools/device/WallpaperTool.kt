package me.rerere.rikkahub.data.ai.tools.device

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置壁纸（WallpaperManager）。
 * 图片来源：本地绝对路径 / file:// 路径 / http(s) URL / data:image base64。
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun buildSetWallpaperTool(context: Context): Tool = Tool(
    name = "set_wallpaper",
    description = "Set the device wallpaper from an image. Supports a local file path, a http(s) URL, or a base64 data URI. " +
        "Sets home screen, lock screen, or both.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("image", buildJsonObject {
                    put("type", "string")
                    put("description", "Image source: absolute file path, file:// path, http(s) URL, or data:image/...;base64,... URI")
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "Which wallpaper to set: home, lock, or both. Default: both")
                })
            },
            required = listOf("image")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val image = params["image"]?.jsonPrimitive?.contentOrNull
        val target = params["target"]?.jsonPrimitive?.contentOrNull ?: "both"
        if (image.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(deviceError("Missing required parameter 'image'").toString()))
        }
        if (target !in listOf("home", "lock", "both")) {
            return@Tool listOf(UIMessagePart.Text(deviceError("target must be one of: home, lock, both").toString()))
        }
        try {
            val imageFile = withContext(Dispatchers.IO) { resolveImageToFile(context, image) }
                ?: return@Tool listOf(UIMessagePart.Text(deviceError(
                    "Failed to resolve image. Unsupported scheme or download failed: ${image.take(120)}"
                ).toString()))
            if (!imageFile.exists() || !imageFile.isFile) {
                return@Tool listOf(UIMessagePart.Text(deviceError("File not found: ${imageFile.absolutePath}").toString()))
            }
            val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(imageFile.absolutePath) }
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("Failed to decode image file").toString()))

            val wm = WallpaperManager.getInstance(context)
            val flags = when (target) {
                "home" -> WallpaperManager.FLAG_SYSTEM
                "lock" -> WallpaperManager.FLAG_LOCK
                else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
            wm.setBitmap(bitmap, null, true, flags)
            bitmap.recycle()
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("target", target)
                    put("message", "Wallpaper set for $target")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Failed to set wallpaper").toString()))
        }
    }
)

/** 把图片来源解析为本地文件（file:// / 绝对路径 / base64 / http 下载） */
@OptIn(ExperimentalEncodingApi::class)
private suspend fun resolveImageToFile(context: Context, imageUrl: String): File? = withContext(Dispatchers.IO) {
    when {
        imageUrl.startsWith("file://") -> File(imageUrl.removePrefix("file://"))
        imageUrl.startsWith("/") -> File(imageUrl)
        imageUrl.startsWith("data:image") -> {
            val tempDir = context.filesDir.resolve("images").apply { if (!exists()) mkdirs() }
            val tempFile = File(tempDir, "wallpaper_${System.currentTimeMillis()}.png")
            runCatching {
                tempFile.writeBytes(Base64.decode(imageUrl.substringAfter("base64,").toByteArray()))
            }.getOrNull() ?: return@withContext null
            tempFile
        }
        imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> {
            runCatching {
                val connection = URL(imageUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                val tempDir = context.filesDir.resolve("images").apply { if (!exists()) mkdirs() }
                val tempFile = File(tempDir, "wallpaper_${System.currentTimeMillis()}.png")
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                tempFile
            }.getOrNull()
        }
        else -> null
    }
}
