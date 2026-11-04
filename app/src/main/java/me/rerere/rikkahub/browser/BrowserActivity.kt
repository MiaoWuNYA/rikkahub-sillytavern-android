package me.rerere.rikkahub.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.utils.JsonInstant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 可见的浏览器界面。WebView 只活在这里：
 * 主进程拿到的永远只是一个 [BrowserPage]，布局与生命周期都和聊天界面隔开。
 */
class BrowserActivity : ComponentActivity(), BrowserPage {

    companion object {
        private const val EXTRA_CLOSE = "extra_close"
        private const val SETTLE_TIMEOUT_MS = 15_000L

        fun intent(context: Context): Intent =
            Intent(context, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun closeIntent(context: Context): Intent =
            intent(context).putExtra(EXTRA_CLOSE, true)
    }

    private var webView: WebView? = null
    private var attached = false
    private var resumed = false
    private var generation by mutableIntStateOf(0)
    private var displayUrl by mutableStateOf("")
    private var displayTitle by mutableStateOf("")
    private var loadProgress by mutableIntStateOf(100)
    private var statusNote by mutableStateOf("等待 AI 操作")
    private var userBusy by mutableStateOf(false)
    private var showLogs by mutableStateOf(false)
    private var editingUrl by mutableStateOf(false)
    private var pendingRestoreUrl: String? = null
    private var lastHttpError: Int? = null
    private var lastSslError: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotEngine by lazy { ScreenshotEngine(applicationContext) }
    private val shotCounter = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_CLOSE, false) == true) {
            finish()
            return
        }
        enableEdgeToEdge()
        BrowserRuntime.init(applicationContext)
        setContent {
            MaterialTheme {
                BrowserScaffold()
            }
        }
    }

    // ── BrowserPage ─────────────────────────────────────────────

    override fun isVisible(): Boolean = attached && resumed && !isFinishing && !isDestroyed

    override suspend fun loadUrl(url: String) {
        withContext(Dispatchers.Main) {
            val view = webView ?: throw BrowserFailure(BrowserError.WEBVIEW_UNAVAILABLE, "浏览器内核不可用")
            pendingRestoreUrl = url
            lastHttpError = null
            lastSslError = false
            displayUrl = url
            view.loadUrl(url)
        }
    }

    override suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext null
        suspendCancellableCoroutine { continuation ->
            try {
                view.evaluateJavascript(script) { raw ->
                    if (continuation.isActive) continuation.resume(unquote(raw))
                }
            } catch (e: Throwable) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    override suspend fun waitSettled(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var injected = false
        while (SystemClock.uptimeMillis() < deadline) {
            if (lastSslError) throw BrowserFailure(BrowserError.SSL_ERROR, "证书错误，已停止加载")
            val probe = evaluate(SnapshotScript.WAIT_PROBE)
            if (probe == null) {
                delay(150)
                continue
            }
            if (!injected) {
                evaluate(SnapshotScript.INIT)
                injected = true
            }
            val obj = runCatching { JsonInstant.parseToJsonElement(probe).jsonObject }.getOrNull()
            val ready = obj?.get("ready")?.jsonPrimitive?.content
            val quiet = obj?.get("quiet")?.jsonPrimitive?.longOrNull ?: 0L
            val pending = obj?.get("pending")?.jsonPrimitive?.intOrNull ?: 0
            if (ready == "complete" && quiet >= 500 && pending == 0) return true
            delay(120)
        }
        return false
    }

    override suspend fun metrics(): PageMetrics {
        val raw = evaluate(METRICS_JS) ?: return PageMetrics(displayUrl, displayTitle)
        val obj = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return PageMetrics(displayUrl, displayTitle)
        return PageMetrics(
            url = obj["u"]?.jsonPrimitive?.content ?: displayUrl,
            title = obj["t"]?.jsonPrimitive?.content ?: displayTitle,
            scrollY = obj["y"]?.jsonPrimitive?.intOrNull ?: 0,
            scrollHeight = obj["h"]?.jsonPrimitive?.intOrNull ?: 0,
            viewportHeight = obj["v"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    override suspend fun captureViewport(): Bitmap = withContext(Dispatchers.Main) {
        val view = webView ?: throw BrowserFailure(BrowserError.NOT_OPEN, "浏览器已关闭")
        if (!isVisible()) throw BrowserFailure(BrowserError.NOT_VISIBLE, "浏览器不在前台，无法截图")
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) {
            throw BrowserFailure(BrowserError.NOT_VISIBLE, "页面尺寸不可用")
        }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = Rect(location[0], location[1], location[0] + width, location[1] + height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            suspendCancellableCoroutine { continuation ->
                PixelCopy.request(
                    window,
                    rect,
                    bitmap,
                    { result ->
                        if (!continuation.isActive) return@request
                        if (result == PixelCopy.SUCCESS) {
                            continuation.resume(bitmap)
                        } else {
                            continuation.resumeWithException(
                                BrowserFailure(BrowserError.UNSUPPORTED, "截图失败 code=$result")
                            )
                        }
                    },
                    mainHandler,
                )
            }
        } catch (e: Throwable) {
            bitmap.recycle()
            drawFallback(view)
        }
    }

    private fun drawFallback(view: WebView): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    override suspend fun scrollTo(y: Int) {
        evaluate(SnapshotScript.scrollTo(y))
        delay(60)
    }

    override suspend fun goBack(): Boolean = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext false
        if (!view.canGoBack()) return@withContext false
        view.goBack()
        true
    }

    override suspend fun goForward(): Boolean = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext false
        if (!view.canGoForward()) return@withContext false
        view.goForward()
        true
    }

    override suspend fun reload() {
        withContext(Dispatchers.Main) { webView?.reload() }
    }

    override suspend fun fillCredentials(username: String, password: String): Boolean {
        val raw = evaluate(SnapshotScript.fillCredentials(username, password)) ?: return false
        return raw.contains("\"ok\":true")
    }

    override fun closeSelf() {
        // AI 关掉浏览器时用户那侧必须同步消失：停加载 + finish，交给 onDestroy 释放内核
        runOnUiThread {
            runCatching { webView?.stopLoading() }
            finish()
        }
    }

    // ── 生命周期 ─────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        resumed = true
        webView?.onResume()
        attachIfNeeded()
    }

    /** 只有 WebView 真正建好后才能对运行时可见，否则工具会在内核还不存在时就开始操作。 */
    private fun attachIfNeeded() {
        if (attached || webView == null || isFinishing) return
        attached = true
        BrowserRuntime.attach(this)
    }

    override fun onPause() {
        resumed = false
        webView?.onPause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CLOSE, false)) {
            closeSelf()
            return
        }
        val url = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!url.isNullOrBlank()) {
            lifecycleScope.launch { runCatching { loadUrl(url) } }
        }
    }

    @Suppress("DEPRECATION") // TRIM_MEMORY_* 常量在 API 35 被标为废弃，但它仍是唯一的分级信号
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            webView?.clearCache(false)
        }
    }

    override fun onDestroy() {
        attached = false
        resumed = false
        runCatching { CookieManager.getInstance().flush() }
        destroyWebView()
        BrowserRuntime.detach(this)
        super.onDestroy()
    }

    // ── WebView ─────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val view = try {
            WebView(this)
        } catch (e: Throwable) {
            statusNote = "系统 WebView 不可用：${e.message ?: e.javaClass.simpleName}"
            throw BrowserFailure(BrowserError.WEBVIEW_UNAVAILABLE, statusNote)
        }
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = (userAgentString ?: "") + " AIBrowser/1.0"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            enqueueDownload(url, contentDisposition, mimeType)
        }
        view.webViewClient = SafeWebViewClient()
        view.webChromeClient = SafeChromeClient()
        webView = view
        attachIfNeeded()
        pendingRestoreUrl?.let { url ->
            pendingRestoreUrl = null
            view.loadUrl(url)
        }
        return view
    }

    private fun destroyWebView() {
        val view = webView ?: return
        webView = null
        runCatching {
            view.stopLoading()
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeAllViews()
            view.destroy()
        }
    }

    private fun enqueueDownload(url: String, contentDisposition: String?, mimeType: String?) {
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalFilesDir(
                this,
                android.os.Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(url, contentDisposition, mimeType),
            )
            (getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.enqueue(request)
        }.onFailure { toast("下载失败：${it.message}") }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class SafeWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            displayUrl = url ?: view.url.orEmpty()
            loadProgress = 5
            statusNote = "页面加载中"
        }

        override fun onPageFinished(view: WebView, url: String?) {
            displayUrl = url ?: view.url.orEmpty()
            displayTitle = view.title.orEmpty()
            loadProgress = 100
            statusNote = "页面就绪"
            BrowserRuntime.touch()
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest?,
            errorResponse: android.webkit.WebResourceResponse?,
        ) {
            if (request?.isForMainFrame == true) {
                lastHttpError = errorResponse?.statusCode
                statusNote = "HTTP ${lastHttpError}"
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) {
                statusNote = "加载失败：${error?.description ?: "unknown"}"
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler?, error: android.net.http.SslError?) {
            lastSslError = true
            handler?.cancel()
            statusNote = "证书错误，已阻止加载"
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            if (url.startsWith("http://") || url.startsWith("https://")) return false
            statusNote = "已拦截外部 scheme"
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            val didCrash = detail?.didCrash() ?: false
            val allowRebuild = BrowserRuntime.onRendererGone(didCrash)
            val restoreUrl = displayUrl
            webView = null
            runCatching {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            if (allowRebuild) {
                statusNote = "渲染进程已重启"
                mainHandler.post {
                    pendingRestoreUrl = restoreUrl.takeIf { it.isNotBlank() }
                    generation += 1
                }
            } else {
                statusNote = "渲染进程反复崩溃，已关闭浏览器"
                mainHandler.post { finish() }
            }
            // 必须返回 true：交回系统处理会把整个进程一起杀掉
            return true
        }
    }

    private inner class SafeChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            loadProgress = newProgress
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.deny()
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            statusNote = "文件上传暂不支持"
            return true
        }
    }

    // ── UI ─────────────────────────────────────────────────────

    @Composable
    private fun BrowserScaffold() {
        val logs by BrowserRuntime.logs.collectAsStateWithLifecycle()
        val state by BrowserRuntime.state.collectAsStateWithLifecycle()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
                )
        ) {
            AddressBar()
            if (loadProgress in 1..99) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            StatusRow(state = state, lastLog = logs.lastOrNull(), logsOpen = showLogs)
            Box(modifier = Modifier.weight(1f)) {
                WebContent()
                if (showLogs) {
                    ToolLogPanel(logs = logs, onClose = { showLogs = false })
                }
            }
            BottomBar()
        }
    }

    @Composable
    private fun AddressBar() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { lifecycleScope.launch { runCatching { goBack() } } }) {
                Text("←")
            }
            IconButton(onClick = { lifecycleScope.launch { runCatching { goForward() } } }) {
                Text("→")
            }
            IconButton(onClick = { lifecycleScope.launch { runCatching { reload() } } }) {
                Text("⟳")
            }
            if (editingUrl) {
                OutlinedTextField(
                    value = displayUrl,
                    onValueChange = { displayUrl = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = {
                    editingUrl = false
                    val target = displayUrl.trim()
                    if (target.isNotEmpty()) {
                        val normalized = if (target.contains("://")) target else "https://$target"
                        lifecycleScope.launch { runCatching { loadUrl(normalized) } }
                    }
                }) { Text("前往") }
            } else {
                Text(
                    text = displayTitle.ifBlank { displayUrl.ifBlank { "about:blank" } },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { editingUrl = true }) { Text("✎") }
            }
            IconButton(onClick = { finish() }) { Text("✕") }
        }
        HorizontalDivider()
    }

    @Composable
    private fun StatusRow(state: BrowserState, lastLog: BrowserToolLog?, logsOpen: Boolean) {
        val text = when {
            lastLog != null -> "AI 正在操作：${lastLog.tool} ${lastLog.summary}".trim()
            else -> statusNote
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = state.code + if (logsOpen) " · 日志开" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    @Composable
    private fun WebContent() {
        key(generation) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ensureWebView() },
            )
        }
    }

    @Composable
    private fun BottomBar() {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                userBusy = !userBusy
                BrowserRuntime.setUserBusy(userBusy)
            }) { Text(if (userBusy) "交还给 AI" else "我来操作") }
            TextButton(onClick = { manualSnapshot() }) { Text("快照") }
            TextButton(onClick = { manualScreenshot() }) { Text("截图") }
            TextButton(onClick = { showLogs = !showLogs }) { Text("工具日志") }
        }
    }

    @Composable
    private fun ToolLogPanel(logs: List<BrowserToolLog>, onClose: () -> Unit) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("工具调用日志", style = MaterialTheme.typography.titleSmall)
                    Row {
                        TextButton(onClick = { BrowserRuntime.clearLogs() }) { Text("清空") }
                        TextButton(onClick = onClose) { Text("关闭") }
                    }
                }
                HorizontalDivider()
                if (logs.isEmpty()) {
                    Text(
                        "还没有工具调用",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(logs.asReversed()) { log ->
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(
                                    text = "${timeText(log.at)} ${log.tool} ${log.summary}  ${log.durationMs}ms  ${log.tokensEst}tok",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                if (log.note.isNotEmpty()) {
                                    Text(
                                        text = log.note,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun timeText(at: Long): String =
        java.time.Instant.ofEpochMilli(at)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .toString()
            .take(8)

    private fun manualSnapshot() {
        lifecycleScope.launch {
            runCatching {
                BrowserRuntime.withPage { page -> SnapshotEngine.capture(page) }
            }.onSuccess { snapshot ->
                statusNote = "快照 ${snapshot.elements.size} 元素 / ${snapshot.textLen} 字"
                toast(statusNote)
            }.onFailure { error ->
                statusNote = error.message ?: "快照失败"
                toast(statusNote)
            }
        }
    }

    private fun manualScreenshot() {
        lifecycleScope.launch {
            runCatching {
                val info = BrowserRuntime.withPage { page -> BrowserRuntime.refreshInfo(page) }
                BrowserRuntime.withPage { page ->
                    screenshotEngine.capture(
                        page = page,
                        info = info,
                        settings = BrowserRuntime.config,
                        fullPage = false,
                        maxWidth = 1024,
                        quality = 65,
                        region = null,
                    )
                }
            }.onSuccess { shot ->
                shotCounter.incrementAndGet()
                statusNote = "已截图 ${shot.width}x${shot.height} ${shot.bytes / 1024}KB"
                toast(statusNote)
            }.onFailure { error ->
                statusNote = error.message ?: "截图失败"
                toast(statusNote)
            }
        }
    }

    private fun unquote(raw: String?): String? {
        if (raw == null || raw == "null") return null
        val trimmed = raw.trim()
        if (!trimmed.startsWith("\"") || !trimmed.endsWith("\"") || trimmed.length < 2) return raw
        return runCatching {
            JsonInstant.parseToJsonElement(trimmed).jsonPrimitive.content
        }.getOrDefault(raw)
    }

    private val METRICS_JS = """
        (function(){
          try {
            var body = document.body || document.documentElement;
            return JSON.stringify({
              u: location.href, t: document.title,
              y: Math.round(window.scrollY), h: Math.round(body.scrollHeight),
              v: Math.round(window.innerHeight)
            });
          } catch (e) { return JSON.stringify({}); }
        })()
    """.trimIndent()
}
