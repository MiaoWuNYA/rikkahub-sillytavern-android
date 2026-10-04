package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

/**
 * 把一张本地图片直接显示在聊天里。
 *
 * 为什么需要单独一个工具：execute_python 出图后虽然会把文件附到结果里，
 * 但那条路径是「被动」的——只有当代码恰好生成了新文件才会触发。模型
 * 手上已经有的图（用户之前给的、别的工具存下来的、截图）没有任何办法
 * 让用户看到，只能描述「我生成了一张图」。
 *
 * 和 present_file 的区别：present_file 拉起系统分享面板，图不会出现在
 * 对话中；这个工具产出的是 UIMessagePart.Image，由聊天界面直接渲染。
 *
 * 支持三种来源：
 *   - 绝对路径      /data/.../files/chart.png
 *   - 相对路径      相对 execute_python 的工作目录（context.filesDir）
 *   - content://    内容 URI，直接透传
 */
private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif",
)

private fun String.extensionLower(): String =
    substringAfterLast('.', "").lowercase()

/** 简单的魔数校验：扩展名会骗人，字节头不会（svg 是文本，单独判断）。 */
private fun looksLikeImage(bytes: ByteArray, ext: String): Boolean {
    if (bytes.size < 4) return false
    // PNG
    if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    ) return true
    // JPEG
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
    // GIF
    if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte()
    ) return true
    // WebP: RIFF....WEBP
    if (bytes.size >= 12 &&
        bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
        bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
        bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
    ) return true
    // BMP
    if (bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) return true
    // HEIC/AVIF: ....ftyp
    if (bytes.size >= 12 &&
        bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
        bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()
    ) return true
    // SVG 没有魔数，靠扩展名 + 文本形态兜底
    if (ext == "svg") {
        val head = bytes.take(512).toByteArray().toString(Charsets.UTF_8)
        return head.contains("<svg", ignoreCase = true) ||
            head.trimStart().startsWith("<?xml")
    }
    return false
}

internal fun buildShowImageTool(context: Context): Tool = Tool(
    name = "show_image",
    description = "Display a local image file in the chat so the user can see it. " +
        "Use after generating or locating a picture (charts, screenshots, downloaded " +
        "images). Accepts an absolute path, a path relative to the Python working " +
        "directory, or a content:// URI. Pass a title to caption it.",
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Image path: absolute, relative to the Python " +
                        "working directory, or a content:// URI.")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional caption shown together with the image.")
                })
            },
            required = listOf("path"),
        )
    },
    execute = { args ->
        val raw = args.jsonObject["path"]?.jsonPrimitive?.content
            ?: error("path parameter is required")
        val title = args.jsonObject["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val parts = mutableListOf<UIMessagePart>()

        // content:// 直接透传：调用方已经拿到了可用的 URI，不必再读一遍
        if (raw.startsWith("content://")) {
            parts.add(UIMessagePart.Image(url = raw))
            title?.let { parts.add(UIMessagePart.Text(it)) }
            return@Tool parts
        }

        val cleaned = raw.removePrefix("file://")
        val candidates = listOf(
            File(cleaned),
            // 相对路径按 execute_python 的工作目录解析——模型经常直接写
            // "chart.png"，那是它在这个目录下生成的文件名。
            File(context.filesDir, cleaned),
        )
        val file = candidates.firstOrNull { it.exists() && it.isFile }
            ?: error(
                "Image not found: $raw (tried ${candidates.joinToString { it.absolutePath }})"
            )

        val size = file.length()
        require(size <= MAX_IMAGE_BYTES) {
            "Image too large: ${size / 1024 / 1024}MB (max ${MAX_IMAGE_BYTES / 1024 / 1024}MB)"
        }
        require(size > 0) { "Image is empty: ${file.absolutePath}" }

        val bytes = file.readBytes()
        val ext = file.name.extensionLower()
        require(ext in IMAGE_EXTENSIONS || looksLikeImage(bytes, ext)) {
            "Not a recognized image format: ${file.name}. " +
                "Supported: ${IMAGE_EXTENSIONS.joinToString()}"
        }
        // 扩展名可能缺失或写错，用魔数复核一次更稳
        check(looksLikeImage(bytes, ext)) {
            "File content does not look like an image: ${file.name}"
        }

        if (ext == "svg") {
            // 矢量图以 data URI 传给 Image，聊天侧能直接渲染，
            // 也不必额外落一份文件。
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            parts.add(UIMessagePart.Image(url = "data:image/svg+xml;base64,$b64"))
        } else {
            val uris = getKoin().get<FilesManager>().createChatFilesByByteArrays(listOf(bytes))
            parts.add(UIMessagePart.Image(url = uris.first().toString()))
        }

        title?.let { parts.add(UIMessagePart.Text(it)) }
        parts
    },
)
