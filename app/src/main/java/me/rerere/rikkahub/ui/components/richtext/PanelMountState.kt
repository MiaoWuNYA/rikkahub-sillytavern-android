package me.rerere.rikkahub.ui.components.richtext

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.rikkahub.data.st.runtime.TavernRuntimeManager
import org.json.JSONObject

/** 面板内容更新决策：什么都不做 / 整页重载 panel.html / 原地 __rikkaPanelMount 重挂。 */
internal enum class PanelUpdateAction { NO_OP, RELOAD_DOCUMENT, REMOUNT_IN_PLACE }

/**
 * 单张卡片的 panel.html 挂载状态机：就绪探测、差分注入、48ms 合并重挂、释放销毁。
 * 语义与参考实现（MessageHtmlBlock.PanelMountState）逐条对齐。
 */
internal class PanelMountState {
    var webView: WebView? = null
        private set

    private var pageReady = false

    @Volatile
    var isDisposed: Boolean = false
        private set

    internal companion object {
        const val MAX_PAGE_LOAD_RETRIES = 3
        const val STREAMING_REMOUNT_COALESCE_MS = 48L
        val SCRIPT_TAG_RE = Regex("<script[\\s>]", RegexOption.IGNORE_CASE)
    }

    private val mainHandler: Handler? = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()

    internal var clockMsProvider: () -> Long = {
        runCatching { android.os.SystemClock.uptimeMillis() }.getOrDefault(0L)
    }

    private var lastRemountTimestampMs: Long = -STREAMING_REMOUNT_COALESCE_MS
    private var coalescedRemountScheduled: Boolean = false
    private var pendingReadyProbeRunnable: Runnable? = null
    private val coalescedRemountRunnable = Runnable {
        coalescedRemountScheduled = false
        if (!isDisposed) {
            lastRemountTimestampMs = clockMsProvider()
            tryMount()
        }
    }

    fun scheduleReadyProbe(delayMs: Long = 220L, probe: () -> Unit) {
        cancelReadyProbe()
        if (isDisposed || pageReady || mounted) return
        val r = Runnable {
            pendingReadyProbeRunnable = null
            if (!isDisposed && !pageReady && !mounted) probe()
        }
        pendingReadyProbeRunnable = r
        runCatching { mainHandler?.postDelayed(r, delayMs) }
    }

    fun cancelReadyProbe() {
        pendingReadyProbeRunnable?.let { r ->
            pendingReadyProbeRunnable = null
            runCatching { mainHandler?.removeCallbacks(r) }
        }
    }

    fun flushPendingRemount() {
        if (coalescedRemountScheduled) {
            coalescedRemountScheduled = false
            runCatching { mainHandler?.removeCallbacks(coalescedRemountRunnable) }
        }
        if (!isDisposed && pageReady && pendingHtml != null && pendingHtml != mountedHtml) {
            lastRemountTimestampMs = clockMsProvider()
            tryMount()
        }
    }

    private var everMounted = false
    private var lastReloadGen = 0
    private var pageLoadRetries = 0
    var reloadCount: Int = 0
        private set
    var inPlaceRemountCount: Int = 0
        private set
    var mountedHtml by mutableStateOf<String?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)
    var resourcesSettled by mutableStateOf(false)
        private set
    var resourcesTimedOut by mutableStateOf(false)
        private set

    fun markResourcesSettled(timedOut: Boolean) {
        if (isDisposed || !mounted) return
        resourcesTimedOut = timedOut
        resourcesSettled = true
    }

    private fun resetResourceSettleState() {
        resourcesSettled = false
        resourcesTimedOut = false
    }

    fun reloadNow() {
        if (isDisposed) return
        cancelReadyProbe()
        if (coalescedRemountScheduled) {
            coalescedRemountScheduled = false
            runCatching { mainHandler?.removeCallbacks(coalescedRemountRunnable) }
        }
        val wv = webView ?: return
        lastError = null
        resetResourceSettleState()
        pageLoadRetries = 0
        pageReady = false
        everMounted = false
        mountedHtml = null
        appliedForeground = null
        appliedKey = null
        appliedViewportPx = 0
        runCatching { wv.loadUrl(TavernRuntimeManager.BASE_URL + "panel.html") }
    }

    fun decideUpdateAction(html: String, reloadGen: Int = lastReloadGen): PanelUpdateAction {
        val prev = mountedHtml
        if (!everMounted || prev == null || reloadGen != lastReloadGen) {
            everMounted = true
            lastReloadGen = reloadGen
            mountedHtml = html
            reloadCount++
            return PanelUpdateAction.RELOAD_DOCUMENT
        }
        if (html == prev) return PanelUpdateAction.NO_OP
        val hasScript = SCRIPT_TAG_RE.containsMatchIn(html) || SCRIPT_TAG_RE.containsMatchIn(prev)
        return if (hasScript) {
            mountedHtml = html
            reloadCount++
            PanelUpdateAction.RELOAD_DOCUMENT
        } else {
            mountedHtml = html
            inPlaceRemountCount++
            PanelUpdateAction.REMOUNT_IN_PLACE
        }
    }

    private var desiredInternalScroll: Boolean = false

    @Volatile
    private var pendingHtml: String? = null

    @Volatile
    private var pendingHtmlHash: Int = 0
    private var pendingForeground: String? = null
    private var appliedForeground: String? = null

    @Volatile
    private var pendingKey: String? = null
    private var appliedKey: String? = null
    private var appliedViewportPx: Int = 0
    private var pendingViewportPx: Int = 0

    fun currentPendingHtml(): String = pendingHtml ?: ""

    fun currentPendingHtmlHash(): Int = pendingHtmlHash

    fun currentPendingKey(): String = pendingKey ?: ""

    fun attach(wv: WebView) {
        isDisposed = false
        if (webView !== wv) {
            webView = wv
            pageReady = false
            everMounted = false
            mountedHtml = null
            resetResourceSettleState()
            appliedForeground = null
            appliedKey = null
            appliedViewportPx = 0
        }
    }

    fun onPageReady() {
        if (isDisposed) return
        cancelReadyProbe()
        pageReady = true
        tryMount()
    }

    fun tryMountIfNeeded() {
        if (!isDisposed && pageReady && !mounted) tryMount()
    }

    fun onHostRendererRestarted() {
        if (isDisposed) return
        val wv = webView ?: return
        if (mounted && pageReady) {
            runCatching {
                wv.evaluateJavascript(
                    "try{if(window.__rikkaSyncAfterHostRestart)window.__rikkaSyncAfterHostRestart();}catch(e){}",
                    null,
                )
            }
        } else {
            tryMountIfNeeded()
        }
    }

    fun onRuntimeMissing(): Boolean {
        if (isDisposed || pageReady || mounted) return false
        val wv = webView ?: return false
        if (pageLoadRetries >= MAX_PAGE_LOAD_RETRIES) {
            TavernRuntimeManager.appendLog(
                "error",
                "[panel] runtime still missing after $MAX_PAGE_LOAD_RETRIES retries key=$pendingKey; giving up",
            )
            return false
        }
        pageLoadRetries++
        pageReady = false
        everMounted = false
        mountedHtml = null
        resetResourceSettleState()
        appliedForeground = null
        appliedKey = null
        appliedViewportPx = 0
        return try {
            wv.loadUrl(TavernRuntimeManager.BASE_URL + "panel.html")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun onWatchdogTimeout(): Boolean {
        if (isDisposed) return false
        val wv = webView ?: return false
        if (pageLoadRetries >= MAX_PAGE_LOAD_RETRIES) return false
        pageLoadRetries++
        resetResourceSettleState()
        if (pageReady && pendingHtml != null) {
            everMounted = false
            mountedHtml = null
            tryMount()
            return true
        }
        pageReady = false
        everMounted = false
        mountedHtml = null
        appliedForeground = null
        appliedKey = null
        appliedViewportPx = 0
        return try {
            wv.loadUrl(TavernRuntimeManager.BASE_URL + "panel.html")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun submit(html: String, foregroundHex: String?, messageKey: String? = null, viewportPx: Int = 0) {
        if (isDisposed) return
        val isIdentical = html == pendingHtml && foregroundHex == pendingForeground && messageKey == pendingKey
        pendingHtml = html
        pendingHtmlHash = html.hashCode()
        pendingForeground = foregroundHex
        pendingKey = messageKey
        if (viewportPx > 0) pendingViewportPx = viewportPx
        if (isIdentical && mounted) return
        resetResourceSettleState()
        if (everMounted && pageReady) {
            val now = clockMsProvider()
            val elapsed = now - lastRemountTimestampMs
            if (elapsed in 0 until STREAMING_REMOUNT_COALESCE_MS) {
                if (!coalescedRemountScheduled) {
                    coalescedRemountScheduled = true
                    runCatching {
                        mainHandler?.postDelayed(coalescedRemountRunnable, STREAMING_REMOUNT_COALESCE_MS - elapsed)
                    }
                }
                return
            }
            lastRemountTimestampMs = now
        }
        if (coalescedRemountScheduled) {
            coalescedRemountScheduled = false
            runCatching { mainHandler?.removeCallbacks(coalescedRemountRunnable) }
        }
        tryMount()
    }

    fun onRendererGone() {
        cancelReadyProbe()
        if (coalescedRemountScheduled) {
            coalescedRemountScheduled = false
            runCatching { mainHandler?.removeCallbacks(coalescedRemountRunnable) }
        }
        webView = null
        pageReady = false
        everMounted = false
        mountedHtml = null
        resetResourceSettleState()
        appliedForeground = null
        appliedKey = null
        appliedViewportPx = 0
    }

    fun release() {
        isDisposed = true
        cancelReadyProbe()
        coalescedRemountScheduled = false
        runCatching { mainHandler?.removeCallbacks(coalescedRemountRunnable) }
        webView?.let { wv ->
            webView = null
            pageReady = false
            everMounted = false
            mountedHtml = null
            resetResourceSettleState()
            TavernRuntimeManager.unregisterAuxWebView(wv)
            try {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    internal fun simulateMountForTest(html: String) {
        pageReady = true
        everMounted = true
        mountedHtml = html
    }

    val mounted: Boolean get() = mountedHtml != null

    internal val hasPendingCoalescedRemount: Boolean get() = coalescedRemountScheduled

    private fun tryMount() {
        if (isDisposed) return
        val wv = webView ?: return
        val html = pendingHtml ?: return
        if (!pageReady) return
        val fg = pendingForeground
        if (fg != null && fg != appliedForeground) {
            appliedForeground = fg
            wv.evaluateJavascript(
                "document.documentElement.style.setProperty('--rikka-fg'," + JSONObject.quote(fg) + ");",
                null,
            )
        }
        val key = pendingKey
        if (key != appliedKey) {
            appliedKey = key
            wv.evaluateJavascript(
                "window.__rikkaPanelMessageKey=" + JSONObject.quote(key ?: "") + ";",
                null,
            )
        }
        val viewportPx = pendingViewportPx
        if (viewportPx > 0 && viewportPx != appliedViewportPx) {
            appliedViewportPx = viewportPx
            wv.evaluateJavascript(
                "window.__rikkaViewportHeightPx=" + viewportPx + ";" +
                    "try{document.documentElement.style.setProperty('--rikka-panel-vh','" + viewportPx + "px');}catch(e){}",
                null,
            )
        }

        if (html == mountedHtml) return
        if (everMounted) {
            val prevHtml = mountedHtml.orEmpty()
            val hasScript = SCRIPT_TAG_RE.containsMatchIn(html) || SCRIPT_TAG_RE.containsMatchIn(prevHtml)
            if (hasScript) {
                reloadCount++
                everMounted = false
                mountedHtml = null
                appliedForeground = null
                appliedKey = null
                appliedViewportPx = 0
                pageReady = false
                try {
                    wv.loadUrl(TavernRuntimeManager.BASE_URL + "panel.html")
                } catch (_: Throwable) {
                }
                return
            }
            inPlaceRemountCount++
            mountedHtml = html
            resetResourceSettleState()
            val keyQuotedRemount = JSONObject.quote(key ?: "")
            wv.evaluateJavascript(
                "try{" +
                    "window.__rikkaPanelMessageKey=" + keyQuotedRemount + ";" +
                    "var __h=(window.RikkaPanel&&window.RikkaPanel.getPendingHtml)?String(window.RikkaPanel.getPendingHtml()||''):'';" +
                    "if(__h&&window.__rikkaPanelMount){window.__rikkaPanelMount(__h,'remount');}" +
                    "}catch(e){console.error('[panel] remount err',e);}",
                null,
            )
            return
        }
        everMounted = true
        mountedHtml = html
        resetResourceSettleState()
        TavernRuntimeManager.appendLog(
            "debug",
            "[panel] mount key=" + key + " len=" + html.length + " retries=" + pageLoadRetries,
        )
        val keyQuoted = JSONObject.quote(key ?: "")
        wv.evaluateJavascript(
            "try{" +
                "window.__rikkaPanelMessageKey=" + keyQuoted + ";" +
                "var __h=(window.RikkaPanel&&window.RikkaPanel.getPendingHtml)?String(window.RikkaPanel.getPendingHtml()||''):'';" +
                "if(__h){window.__rikkaPanelMount?window.__rikkaPanelMount(__h):(window.__rikkaPanelPendingHtml=__h);}" +
                "}catch(e){console.error('[panel] mount err',e);}",
            null,
        )
    }
}

/** panel.html 里的 window.RikkaPanel：就绪/资源settle/高度/取待挂载 HTML 的回调面。 */
@Keep
internal class PanelBridge(
    private val handler: Handler,
    private val mountState: PanelMountState,
    private val onHeight: (Int) -> Unit,
) {
    private var firstMountLogged = false

    @JavascriptInterface
    fun onPanelRuntimeReady() {
        if (mountState.isDisposed) return
        handler.post {
            if (mountState.isDisposed) return@post
            TavernRuntimeManager.recordWebViewBootStage(
                hostType = "panel",
                hostId = mountState.currentPendingKey().ifEmpty { "anon" },
                stage = "runtime_ready",
                detail = "source=onPanelRuntimeReady",
            )
            mountState.onPageReady()
            mountState.tryMountIfNeeded()
        }
    }

    @JavascriptInterface
    fun onPanelResourcesSettled(timedOut: Boolean) {
        if (mountState.isDisposed) return
        handler.post {
            if (mountState.isDisposed) return@post
            TavernRuntimeManager.recordWebViewBootStage(
                hostType = "panel",
                hostId = mountState.currentPendingKey().ifEmpty { "anon" },
                stage = "resources_settled",
                detail = if (timedOut) "timed_out=true" else "complete",
            )
            mountState.markResourcesSettled(timedOut)
        }
    }

    @JavascriptInterface
    fun panelResize(height: Int) {
        if (mountState.isDisposed) return
        handler.post {
            if (mountState.isDisposed) return@post
            if (height > 0 && !firstMountLogged) {
                firstMountLogged = true
                TavernRuntimeManager.recordWebViewBootStage(
                    hostType = "panel",
                    hostId = mountState.currentPendingKey().ifEmpty { "anon" },
                    stage = "first_panel_mount",
                    detail = "height=" + height + "dp",
                )
            }
            onHeight(height.coerceIn(0, 2_000_000))
        }
    }

    @JavascriptInterface
    fun getPendingHtml(): String = if (mountState.isDisposed) "" else mountState.currentPendingHtml()

    @JavascriptInterface
    fun getPendingKey(): String = if (mountState.isDisposed) "" else mountState.currentPendingKey()

    @JavascriptInterface
    fun log(level: String, message: String) {
        if (mountState.isDisposed) return
        TavernRuntimeManager.appendLog(level, "[panel] " + message)
    }

    @JavascriptInterface
    fun onPanelError(message: String) {
        if (mountState.isDisposed) return
        handler.post {
            if (!mountState.isDisposed) mountState.lastError = message
        }
        TavernRuntimeManager.appendLog("error", "[panel] " + message)
    }
}
