package me.rerere.rikkahub.tavern

import android.content.res.AssetManager
import java.io.IOException
import java.io.InputStream

/**
 * Read-only access to the bundled SillyTavern front-end under `assets/tavern/`.
 *
 * The asset tree is produced by `tools/build_tavern_assets.py`; nothing here is wired into the
 * UI yet. Every path handed in is relative to the `tavern/` root and is normalized before it is
 * resolved, so `../` cannot escape the root.
 */
class TavernAssets(
    private val assets: AssetManager,
    private val root: String = DEFAULT_ROOT,
) {

    /** Opens [path] under `assets/tavern/`, or returns null when it is missing or escapes the root. */
    fun open(path: String): InputStream? {
        val assetPath = resolve(path) ?: return null
        return try {
            assets.open(assetPath, AssetManager.ACCESS_STREAMING)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun exists(path: String): Boolean = open(path)?.use { true } ?: false

    /** 列出某个目录下的直接子项；目录不存在时返回空表。 */
    fun list(path: String): List<String> {
        val dir = resolve(path) ?: return emptyList()
        return try {
            assets.list(dir)?.toList().orEmpty()
        } catch (_: IOException) {
            emptyList()
        }
    }

    fun contentType(path: String): String {
        val extension = path.substringAfterLast('.', "").substringBefore('?').substringBefore('#').lowercase()
        return CONTENT_TYPES[extension] ?: DEFAULT_CONTENT_TYPE
    }

    /**
     * Normalizes [path] into an asset path such as `tavern/css/style.css`.
     *
     * Returns null when the path is empty, absolute, contains a NUL byte or backslash, or when
     * resolving `.` / `..` segments would leave the [root] prefix.
     */
    fun resolve(path: String): String? {
        val trimmed = path.trim().substringBefore('?').substringBefore('#')
        if (trimmed.isEmpty() || trimmed.contains('\u0000') || trimmed.contains('\\')) return null
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) return null

        val segments = ArrayList<String>()
        for (segment in trimmed.split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        if (segments.isEmpty()) return null

        val resolved = root + "/" + segments.joinToString("/")
        // Final guard: after normalization the path must still live under the tavern root.
        if (!resolved.startsWith("$root/")) return null
        if (segments.any { it == ".." }) return null
        return resolved
    }

    companion object {
        const val DEFAULT_ROOT = "tavern"
        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

        private val CONTENT_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "htm" to "text/html; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "js" to "application/javascript; charset=utf-8",
            "mjs" to "application/javascript; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "ico" to "image/x-icon",
            "woff2" to "font/woff2",
            "woff" to "font/woff",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "txt" to "text/plain; charset=utf-8",
        )
    }
}
