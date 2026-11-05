package me.rerere.rikkahub.tavern

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Minimal static-file responder over [TavernAssets]. Pure Kotlin: no WebView, no Compose, no
 * extra Android dependency beyond the [TavernAssets] instance handed in.
 */
class TavernAssetHandler(
    private val assets: TavernAssets,
) {

    fun handle(path: String): TavernAssetResponse {
        val requested = normalizeRequest(path)
        val stream = assets.open(requested) ?: return notFound()
        val bytes = stream.use { readCapped(it) }
            ?: return TavernAssetResponse(
                status = STATUS_TOO_LARGE,
                contentType = TavernAssets.DEFAULT_CONTENT_TYPE,
                bytes = null,
            )
        return TavernAssetResponse(
            status = STATUS_OK,
            contentType = assets.contentType(requested),
            bytes = bytes,
        )
    }

    /** Turns a request target into a tavern-relative path; `/` and empty serve `index.html`. */
    private fun normalizeRequest(path: String): String {
        val stripped = path.trim().substringBefore('?').substringBefore('#')
        val relative = when {
            stripped.isEmpty() || stripped == "/" -> DEFAULT_DOCUMENT
            stripped.startsWith("/") -> stripped.dropWhile { it == '/' }
            else -> stripped
        }
        return relative.ifEmpty { DEFAULT_DOCUMENT }
    }

    /** Returns null when the payload exceeds [MAX_BYTES]. */
    private fun readCapped(stream: InputStream): ByteArray? {
        val buffer = ByteArray(BUFFER_SIZE)
        val out = ByteArrayOutputStream()
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun notFound() = TavernAssetResponse(
        status = STATUS_NOT_FOUND,
        contentType = "text/plain; charset=utf-8",
        bytes = null,
    )

    companion object {
        const val STATUS_OK = 200
        const val STATUS_NOT_FOUND = 404
        const val STATUS_TOO_LARGE = 413
        const val DEFAULT_DOCUMENT = "index.html"
        private const val MAX_BYTES = 32 * 1024 * 1024
        private const val BUFFER_SIZE = 16 * 1024
    }
}

/** Result of one static-asset request; [bytes] is null for every non-200 outcome. */
data class TavernAssetResponse(
    val status: Int,
    val contentType: String,
    val bytes: ByteArray?,
) {
    val isSuccess: Boolean get() = status == TavernAssetHandler.STATUS_OK
}
