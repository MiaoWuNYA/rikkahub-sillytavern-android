package me.rerere.ui.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse

/**
 * 应用内 WebView 使用的虚拟域名，用于给 loadDataWithBaseURL 提供一个稳定的 origin。
 */
const val WEB_VIEW_BASE_URL = "https://rikkahub.local"

/**
 * 虚拟域名下的静态资源前缀，会被映射到 app 的 assets 目录。
 *
 * 例如 `https://rikkahub.local/assets/html/mermaid.min.js` 对应 `assets/html/mermaid.min.js`，
 * 这样 mermaid/katex 之类的库可以本地加载，而不用从 CDN 下载（首次加载可能需要几十秒）。
 */
const val WEB_VIEW_ASSET_URL = "$WEB_VIEW_BASE_URL/assets"

private const val ASSET_HOST = "rikkahub.local"

private val CDN_HOSTS = setOf(
    "cdn.jsdelivr.net",
    "testingcf.jsdelivr.net",
    "fastly.jsdelivr.net",
    "gcore.jsdelivr.net",
    "cdnjs.cloudflare.com",
    "unpkg.com",
    "esm.sh",
    "cdn.bootcdn.net",
    "s4.zstatic.net",
)

private val OFFLINE_STYLE_HOSTS = setOf(
    "fonts.googleapis.com",
    "fonts.gstatic.com",
    "use.fontawesome.com",
    "kit.fontawesome.com",
    "at.alicdn.com",
    "ka-f.fontawesome.com",
)
private const val ASSET_PATH_PREFIX = "/assets/"

object WebViewLocalAssets {
    fun intercept(context: Context, uri: Uri): WebResourceResponse? {
        if (!uri.host.equals(ASSET_HOST, ignoreCase = true)) return null
        val path = uri.path ?: return null
        if (!path.startsWith(ASSET_PATH_PREFIX)) return null

        val assetPath = path.removePrefix(ASSET_PATH_PREFIX)
        if (assetPath.isEmpty() || assetPath.contains("..")) return null

        val mimeType = mimeTypeOf(assetPath)
        return runCatching {
            WebResourceResponse(
                mimeType,
                if (mimeType.startsWith("text/") || mimeType.endsWith("json") || mimeType.endsWith("xml")) "UTF-8" else null,
                context.assets.open(assetPath)
            )
        }.getOrNull()
    }

    /**
     * CDN 本地化：卡脚本里的 jquery/lodash/vue 等外链优先命中内置 vendor，
     * 断网或墙内也能渲染，且省掉 15~30s 的等待超时。
     */
    fun interceptCdn(context: Context, uri: Uri): WebResourceResponse? {
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        val lowerPath = path.lowercase()
        if (host in OFFLINE_STYLE_HOSTS) return offlineStyle(lowerPath)
        if (host !in CDN_HOSTS || path.isEmpty()) return null
        openAsset(context, "st-runtime/vendor$path")?.let { return it }
        deversionNpm(path)?.let { de -> openAsset(context, "st-runtime/vendor$de")?.let { return it } }
        val fileName = lowerPath.substringAfterLast('/')
        val mapped = when {
            fileName.startsWith("jquery") && fileName.endsWith(".js") -> "st-runtime/vendor/lib/jquery-3.5.1.min.js"
            fileName.startsWith("lodash") && fileName.endsWith(".js") -> "st-runtime/vendor/lib/lodash.min.js"
            fileName.startsWith("toastr") && fileName.endsWith(".js") -> "st-runtime/vendor/lib/toastr.min.js"
            fileName.startsWith("select2") && fileName.endsWith(".js") -> "st-runtime/vendor/lib/select2.min.js"
            fileName.startsWith("select2") && fileName.endsWith(".css") -> "st-runtime/vendor/lib/select2.min.css"
            fileName.startsWith("katex") && fileName.endsWith(".js") -> "st-runtime/vendor/katex/katex.min.js"
            fileName.startsWith("katex") && fileName.endsWith(".css") -> "st-runtime/vendor/katex/katex.min.css"
            fileName.startsWith("vue") && fileName.endsWith(".js") -> "st-runtime/vendor/lib/vue.runtime.global.prod.js"
            fileName.startsWith("dompurify") && fileName.endsWith(".js") -> "st-runtime/vendor/lib/dompurify.esm.js"
            fileName.startsWith("ejs") && fileName.endsWith(".js") -> "st-runtime/vendor/lib/ejs.umd.js"
            fileName.startsWith("yaml") && fileName.endsWith(".js") -> "st-runtime/vendor/lib/yaml.esm.js"
            fileName.startsWith("hljs") || fileName.startsWith("highlight") && fileName.endsWith(".js") ->
                "st-runtime/vendor/lib/hljs.min.js"
            fileName.endsWith(".css") || fileName.endsWith(".woff2") || fileName.endsWith(".woff") ||
                fileName.endsWith(".ttf") || fileName.contains("font-awesome") || fileName.contains("fontawesome") ->
                return offlineStyle(lowerPath)
            else -> null
        }
        return mapped?.let { openAsset(context, it) }
    }

    private fun openAsset(context: Context, assetPath: String): WebResourceResponse? = runCatching {
        val mimeType = mimeTypeOf(assetPath)
        WebResourceResponse(
            mimeType,
            if (mimeType.startsWith("text/") || mimeType.endsWith("json") || mimeType.endsWith("xml")) "UTF-8" else null,
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            context.assets.open(assetPath),
        )
    }.getOrNull()

    private fun deversionNpm(path: String): String? {
        if (!path.startsWith("/npm/")) return null
        val m = Regex("^/npm/((?:@[^/]+/)?[^/@]+)@[^/]+(.*)$").find(path) ?: return null
        return "/npm/${m.groupValues[1]}${m.groupValues[2]}"
    }

    private fun offlineStyle(lowerPath: String): WebResourceResponse {
        val isFont = lowerPath.endsWith(".woff2") || lowerPath.endsWith(".woff") ||
            lowerPath.endsWith(".ttf") || lowerPath.endsWith(".otf")
        val body = if (isFont) ByteArray(0) else "/* offline css shim */".toByteArray(Charsets.UTF_8)
        return WebResourceResponse(
            if (isFont) "font/woff2" else "text/css",
            "utf-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400"),
            body.inputStream(),
        )
    }

    private fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }
}
