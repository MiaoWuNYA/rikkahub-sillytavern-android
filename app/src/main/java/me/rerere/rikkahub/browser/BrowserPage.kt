package me.rerere.rikkahub.browser

import android.graphics.Bitmap

data class PageMetrics(
    val url: String = "",
    val title: String = "",
    val scrollY: Int = 0,
    val scrollHeight: Int = 0,
    val viewportHeight: Int = 0,
)

/** `BrowserActivity` 对内核暴露的能力，主进程任何代码都不持有 WebView 引用。 */
interface BrowserPage {
    fun isVisible(): Boolean

    suspend fun loadUrl(url: String)

    /** 返回 JS 表达式 `JSON.stringify(...)` 的结果（已去掉外层引号）。 */
    suspend fun evaluate(script: String): String?

    suspend fun waitSettled(timeoutMs: Long): Boolean

    suspend fun metrics(): PageMetrics

    /** 只截 WebView 区域（不含地址栏/工具条），要求窗口可见。 */
    suspend fun captureViewport(): Bitmap

    suspend fun scrollTo(y: Int)

    suspend fun goBack(): Boolean

    suspend fun goForward(): Boolean

    suspend fun reload()

    suspend fun fillCredentials(username: String, password: String): Boolean

    fun closeSelf()
}

data class BrowserToolLog(
    val id: Long = System.nanoTime(),
    val at: Long = System.currentTimeMillis(),
    val tool: String,
    val summary: String,
    val durationMs: Long,
    val tokensEst: Int,
    val note: String = "",
)
