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
private const val ASSET_PATH_PREFIX = "/assets/"

/**
 * 卡片常引用的公共 CDN。这些域名的请求先落到内置库，本地没有才放行网络——
 * 手机 UI 类卡片全是 CDN 依赖，逐个请求超时会让卡片白等几十秒才渲染。
 */
private val CDN_HOSTS = setOf(
    "cdn.jsdelivr.net",
    "testingcf.jsdelivr.net",
    "fastly.jsdelivr.net",
    "gcore.jsdelivr.net",
    "cdnjs.cloudflare.com",
    "unpkg.com",
    "cdn.bootcdn.net",
    "s4.zstatic.net",
)

/**
 * 字体与图标样式源。直连基本超时，而这些只是装饰性资源，
 * 给一个立刻成功的空响应比让卡片干等强得多。
 */
private val OFFLINE_FONT_AND_STYLE_HOSTS = setOf(
    "fonts.googleapis.com",
    "fonts.gstatic.com",
    "use.fontawesome.com",
    "kit.fontawesome.com",
    "at.alicdn.com",
    "ka-f.fontawesome.com",
)

/** 常见库按文件名归一到内置副本，卡片引用的版本号再新也用同一份本地文件 */
private val BUNDLED_LIB_PREFIXES = listOf(
    "jquery" to "lib/jquery.min.js",
    "lodash" to "lib/lodash.min.js",
    "toastr" to "lib/toastr.min.js",
    "vue" to "lib/vue.runtime.global.prod.js",
)

object WebViewLocalAssets {
    fun intercept(context: Context, uri: Uri): WebResourceResponse? {
        val host = uri.host?.lowercase() ?: return null

        if (host == ASSET_HOST) {
            val path = uri.path ?: return null
            if (!path.startsWith(ASSET_PATH_PREFIX)) return null
            val assetPath = path.removePrefix(ASSET_PATH_PREFIX)
            if (assetPath.isEmpty() || assetPath.contains("..")) return null
            return open(context, assetPath)
        }

        if (host in CDN_HOSTS) {
            val path = uri.path ?: return null
            val fileName = path.substringAfterLast('/').lowercase()
            if (fileName.isEmpty()) return null
            when (fileName.substringAfterLast('.', "")) {
                "js", "mjs" -> {
                    val base = fileName.substringBefore('.')
                    BUNDLED_LIB_PREFIXES.firstOrNull { base.startsWith(it.first) }?.let {
                        return open(context, it.second)
                    }
                    // 本地没有的脚本放行网络，网络不通时脚本自然失败，行为与直连一致
                    return null
                }
                "css" -> return emptyResponse("text/css")
                "woff", "woff2", "ttf", "otf" -> return emptyResponse("font/woff2")
                else -> return null
            }
        }

        if (host in OFFLINE_FONT_AND_STYLE_HOSTS) {
            val path = (uri.path ?: "").lowercase()
            val isFont = path.endsWith(".woff") || path.endsWith(".woff2") ||
                path.endsWith(".ttf") || path.endsWith(".otf")
            return emptyResponse(if (isFont) "font/woff2" else "text/css")
        }

        return null
    }

    private fun open(context: Context, assetPath: String): WebResourceResponse? {
        val mimeType = mimeTypeOf(assetPath)
        return runCatching {
            WebResourceResponse(
                mimeType,
                if (mimeType.startsWith("text/") || mimeType.endsWith("json") || mimeType.endsWith("xml")) "UTF-8" else null,
                context.assets.open(assetPath)
            )
        }.getOrNull()
    }

    private fun emptyResponse(mime: String): WebResourceResponse = WebResourceResponse(
        mime,
        "utf-8",
        200,
        "OK",
        mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "max-age=86400",
        ),
        ByteArray(0).inputStream(),
    )

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
