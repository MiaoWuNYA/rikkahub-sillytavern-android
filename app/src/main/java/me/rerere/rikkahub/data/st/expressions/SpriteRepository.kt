package me.rerere.rikkahub.data.st.expressions

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.TavernAsset
import java.io.File
import java.util.Base64
import kotlin.uuid.Uuid

object SpriteRepository {

    private const val SPRITE_ROOT = "sprites"
    private const val CHARX_ROOT = "charx_assets"

    /** 支持的文件扩展名（png/jpg/webp/gif；官方立绘常见格式） */
    val SUPPORTED_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "webp", "gif")

    /** 该助手的立绘目录（不存在时创建） */
    fun spriteDir(context: Context, assistantId: Uuid): File =
        File(File(context.filesDir, SPRITE_ROOT), assistantId.toString()).apply { mkdirs() }

    /** 该助手的 charx 资产目录（不存在时创建） */
    fun charxAssetDir(context: Context, assistantId: Uuid): File =
        File(File(context.filesDir, CHARX_ROOT), assistantId.toString()).apply { mkdirs() }

    /** 仅查询目录（不创建） */
    private fun existingDir(context: Context, assistantId: Uuid): File =
        File(File(context.filesDir, SPRITE_ROOT), assistantId.toString())

    /** 该助手已拥有的立绘标签（按官方 28 标签顺序排序） */
    fun listLabels(context: Context, assistantId: Uuid): List<String> {
        val dir = existingDir(context, assistantId)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .mapNotNull { ExpressionLabels.labelFromFileName(it.name) }
            .distinct()
            .sortedBy { ExpressionLabels.ALL.indexOf(it) }
            .toList()
    }

    /** 标签 → 立绘文件（按扩展名优先级 png → jpg/jpeg → webp → gif；不存在返回 null） */
    fun resolveFile(context: Context, assistantId: Uuid, label: String): File? {
        val dir = existingDir(context, assistantId)
        if (!dir.isDirectory) return null
        val normalized = label.trim().lowercase()
        val exact = listOf("png", "jpg", "jpeg", "webp", "gif")
            .map { File(dir, "$normalized.$it") }
            .firstOrNull { it.isFile }
        if (exact != null) return exact
        return resolveAnySpriteOrAssetFile(context, assistantId, label)
    }

    fun resolveAnySpriteOrAssetFile(
        context: Context,
        assistantId: Uuid?,
        rawNameOrPath: String,
    ): File? {
        val decoded = runCatching { Uri.decode(rawNameOrPath) }.getOrDefault(rawNameOrPath)
            .substringBefore('?')
            .substringBefore('#')
            .replace('\\', '/')
            .trim()
            .removePrefix("embeded://")
            .removePrefix("embedded://")
            .removePrefix("ccdefault:")
            .trimStart('/')
        if (decoded.isBlank()) return null

        val baseName = decoded.substringAfterLast('/')
        val stem = if (baseName.contains('.')) baseName.substringBeforeLast('.') else baseName
        val safeStem = sanitizeAssetStem(stem)

        fun searchInDir(dir: File): File? {
            if (!dir.isDirectory) return null
            // 1) 精确相对路径
            val relCandidate = runCatching { File(dir, decoded).canonicalFile }.getOrNull()
            val dirCanonical = runCatching { dir.canonicalFile.path }.getOrNull()
            if (relCandidate != null && dirCanonical != null &&
                relCandidate.path.startsWith(dirCanonical + File.separator) &&
                relCandidate.isFile
            ) {
                return relCandidate
            }
            // 2) 直接子文件精确名或同 stem 跨扩展名
            for (ext in listOf("png", "webp", "jpg", "jpeg", "gif")) {
                File(dir, "$stem.$ext").takeIf { it.isFile }?.let { return it }
                if (safeStem != stem) {
                    File(dir, "$safeStem.$ext").takeIf { it.isFile }?.let { return it }
                }
            }
            // 3) 递归扫描子目录（针对 .charx 解压出的 assets/other/image/... 结构）
            return dir.walkTopDown()
                .maxDepth(5)
                .firstOrNull { f ->
                    if (!f.isFile || f.extension.lowercase() !in SUPPORTED_EXTENSIONS) return@firstOrNull false
                    val fName = f.name
                    val fStem = f.nameWithoutExtension
                    fName.equals(baseName, ignoreCase = true) ||
                        fStem.equals(stem, ignoreCase = true) ||
                        fStem.equals(safeStem, ignoreCase = true)
                }
        }

        if (assistantId != null) {
            searchInDir(File(File(context.filesDir, CHARX_ROOT), assistantId.toString()))?.let { return it }
            searchInDir(File(File(context.filesDir, SPRITE_ROOT), assistantId.toString()))?.let { return it }
        }

        // 回退扫描所有助手的 charx_assets 与 sprites 目录，以及 tavern-user/images
        listOf(
            File(context.filesDir, CHARX_ROOT),
            File(context.filesDir, SPRITE_ROOT),
            File(File(context.filesDir, "tavern-user"), "images"),
        ).forEach { root ->
            if (root.isDirectory) {
                searchInDir(root)?.let { return it }
            }
        }
        return null
    }

    suspend fun importFromUri(
        context: Context,
        assistantId: Uuid,
        uri: Uri,
        labelHint: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment.orEmpty()
        val label = labelHint?.let { ExpressionLabels.matchLabel(it) }
            ?: ExpressionLabels.labelFromFileName(displayName)
            ?: sanitizeAssetStem(displayName.substringBeforeLast('.')).takeIf { it.isNotBlank() }
            ?: return@withContext null
        val ext = resolveExtension(displayName, context.contentResolver.getType(uri)) ?: "png"
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null
        writeSprite(context, assistantId, label, bytes, ext)
        label
    }

    /** 删除某标签的全部立绘文件；返回是否有文件被删除 */
    suspend fun deleteLabel(context: Context, assistantId: Uuid, label: String): Boolean =
        withContext(Dispatchers.IO) {
            val dir = existingDir(context, assistantId)
            if (!dir.isDirectory) return@withContext false
            val normalized = label.trim().lowercase()
            val removed = dir.listFiles().orEmpty()
                .filter {
                    ExpressionLabels.labelFromFileName(it.name) == normalized ||
                        it.nameWithoutExtension.equals(normalized, ignoreCase = true)
                }
                .count { it.delete() }
            removed > 0
        }

    suspend fun syncFromCardAssets(
        context: Context,
        assistantId: Uuid,
        assets: List<TavernAsset>,
    ): List<String> = withContext(Dispatchers.IO) {
        if (assets.isEmpty()) return@withContext emptyList()
        val imported = linkedSetOf<String>()
        val charxDir = charxAssetDir(context, assistantId)
        for (asset in assets) {
            val decoded = decodeDataUri(asset.uri) ?: continue
            val ext = decoded.second
                ?: asset.ext.lowercase().takeIf { it in SUPPORTED_EXTENSIONS }
                ?: "png"
            val label = ExpressionLabels.labelFromFileName(asset.name)
            if (label != null) {
                writeSprite(context, assistantId, label, decoded.first, ext)
                imported += label
            }
            val rawStem = asset.name.substringAfterLast('/').substringBeforeLast('.').ifBlank { asset.name }
            val safeName = sanitizeAssetStem(rawStem)
            if (safeName.isNotBlank()) {
                writeSpriteByExactStem(context, assistantId, safeName, decoded.first, ext)
                runCatching { File(charxDir, "$safeName.$ext").writeBytes(decoded.first) }
                imported += safeName
            }
        }
        imported.toList()
    }

    internal fun sanitizeAssetStem(raw: String): String =
        raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
            .trim('.', ' ')

    // ==================== 内部实现 ====================

    private fun writeSprite(
        context: Context,
        assistantId: Uuid,
        label: String,
        bytes: ByteArray,
        ext: String,
    ) {
        val dir = spriteDir(context, assistantId)
        val safeExt = ext.lowercase().takeIf { it in SUPPORTED_EXTENSIONS } ?: "png"
        dir.listFiles().orEmpty()
            .filter { ExpressionLabels.labelFromFileName(it.name) == label && it.extension.lowercase() != safeExt }
            .forEach { it.delete() }
        File(dir, "$label.$safeExt").writeBytes(bytes)
    }

    private fun writeSpriteByExactStem(
        context: Context,
        assistantId: Uuid,
        safeStem: String,
        bytes: ByteArray,
        ext: String,
    ) {
        val dir = spriteDir(context, assistantId)
        val safeExt = ext.lowercase().takeIf { it in SUPPORTED_EXTENSIONS } ?: "png"
        File(dir, "$safeStem.$safeExt").writeBytes(bytes)
    }

    private fun resolveExtension(displayName: String, mime: String?): String? {
        val fromName = displayName.substringAfterLast('.', "").lowercase()
        if (fromName in SUPPORTED_EXTENSIONS) return fromName
        return when (mime?.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    fun decodeDataUri(uri: String): Pair<ByteArray, String?>? {
        if (!uri.startsWith("data:", ignoreCase = true)) return null
        val comma = uri.indexOf(',')
        if (comma <= 0) return null
        val meta = uri.substring(5, comma)
        if (!meta.contains("base64", ignoreCase = true)) return null
        val mime = meta.substringBefore(';').trim().lowercase()
        val ext = when (mime) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> null
        }
        val payload = uri.substring(comma + 1).filterNot { it.isWhitespace() }
        val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return bytes to ext
    }
}
