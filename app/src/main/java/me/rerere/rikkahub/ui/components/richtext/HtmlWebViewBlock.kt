package me.rerere.rikkahub.ui.components.richtext

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.View as ViewIcon
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.st.runtime.RikkaWebChromeClient
import me.rerere.rikkahub.data.st.runtime.TavernRuntimeManager
import me.rerere.rikkahub.utils.l10n
import me.rerere.ui.webview.WebViewLocalAssets
import java.util.Locale

private const val TAG = "HtmlWebViewBlock"
private const val MAX_RENDERER_CRASH_REBUILDS = 5
private val CARD_MIN_HEIGHT = 80.dp

/**
 * 卡片的渲染宿主：一张常驻 panel.html 的 WebView + 面板状态机。
 * 卡片 HTML 只经 RikkaPanel.getPendingHtml() 交给运行时，从不拼进 evaluateJavascript。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlWebViewBlock(
    html: String,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
    /** 混排分段推出来的面板名；为空时用默认文案。 */
    panelTitleOverride: String? = null,
    /** 面板所属消息 id：runtime 靠它定位楼层变量；为空退化为内容哈希。 */
    messageKey: String = "",
) {
    TrackWebViewPresence()
    val density = LocalDensity.current.density
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.roundToPx()
    }

    val panelTitle = panelTitleOverride ?: l10n("面板", "Panel")
    val contentHash = remember(html) { html.hashCode() }
    val panelKey = remember(messageKey, contentHash) {
        if (messageKey.isNotBlank()) messageKey else "panel#" + contentHash
    }
    val foregroundHex = remember(colorScheme.onSurface) {
        String.format(Locale.US, "#%06X", 0xFFFFFF and colorScheme.onSurface.toArgb())
    }

    val mountState = remember(panelKey) {
        DetachedPanelWebViewCache.getMountState(panelKey) ?: PanelMountState()
    }
    var heightDp by remember(panelKey) {
        mutableIntStateOf(PanelHeightCache.get(panelKey, contentHash))
    }
    var stage by remember(panelKey) {
        mutableStateOf<PanelLoadStage>(PanelLoadStage.WaitingHost(panelTitle))
    }
    var slotGranted by remember(panelKey) { mutableStateOf(false) }
    var localCrashCount by remember(panelKey) { mutableIntStateOf(0) }
    var rendererEpoch by remember(panelKey) { mutableIntStateOf(0) }

    val activeKeys by ActivePanelWebViewPool.activeKeys.collectAsState()
    val hibernated = slotGranted && !activeKeys.contains(panelKey)

    LaunchedEffect(panelKey) {
        val slot = TavernPanelLoadScheduler.enqueue(panelKey, panelTitle)
        stage = PanelLoadStage.Queued(
            panelTitle = panelTitle,
            position = TavernPanelLoadScheduler.positionOf(panelKey) ?: 1,
            totalQueued = maxOf(1, TavernPanelLoadScheduler.queueSize()),
        )
        if (!slot.awaitTurn()) return@LaunchedEffect
        stage = PanelLoadStage.LoadingWebView(panelTitle)
        ActivePanelWebViewPool.acquire(panelKey)
        slotGranted = true
    }
    DisposableEffect(panelKey) {
        onDispose {
            TavernPanelLoadScheduler.cancel(panelKey)
            ActivePanelWebViewPool.release(panelKey)
        }
    }
    // 资源 settle 兜底：运行时 2500ms 未回调也不让阶段卡在资源加载
    LaunchedEffect(panelKey, slotGranted) {
        if (slotGranted) {
            delay(4_000L)
            mountState.markResourcesSettled(timedOut = true)
        }
    }
    // 装载看门狗：DOM 挂载阶段长时间无进展就重来一次
    LaunchedEffect(panelKey, stage) {
        if (stage is PanelLoadStage.MountingDom || stage is PanelLoadStage.LoadingWebView) {
            delay(5_000L)
            if (!mountState.mounted) mountState.onWatchdogTimeout()
        }
    }

    val applyHeight: (Int) -> Unit = { raw ->
        if (raw > 0) {
            val (bounded, capped) = computePanelHeightConstraint(raw, density)
            if (capped) Log.w(TAG, "[panel] height $raw dp exceeds GPU safe max, clamped to $bounded dp")
            PanelHeightCache.put(panelKey, contentHash, bounded)
            heightDp = bounded
            stage = PanelLoadStage.ResourcesLoading(panelTitle)
            TavernPanelLoadScheduler.markDone(panelKey)
        }
    }

    val reloadPanel: () -> Unit = {
        PanelHeightCache.invalidateForMessage(panelKey)
        DetachedPanelWebViewCache.evictAndDestroy(panelKey)
        mountState.reloadNow()
        heightDp = 0
        localCrashCount = 0
        rendererEpoch += 1
        if (!slotGranted) {
            ActivePanelWebViewPool.acquire(panelKey)
            slotGranted = true
            stage = PanelLoadStage.LoadingWebView(panelTitle)
        }
    }

    var showFullscreen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (heightDp > 0) Modifier.height(heightDp.dp)
                    else Modifier.heightIn(min = CARD_MIN_HEIGHT)
                )
        ) {
            if (localCrashCount > MAX_RENDERER_CRASH_REBUILDS) {
                RendererCrashCard(onReload = reloadPanel, modifier = Modifier.fillMaxSize())
            } else if (!slotGranted || hibernated) {
                PanelStatusPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    title = panelTitle,
                    statusText = panelStatusText(
                        stage = stage,
                        lastError = mountState.lastError,
                        crashCount = localCrashCount,
                        manualReload = false,
                    ),
                    onReload = reloadPanel,
                )
            } else {
                key(rendererEpoch) {
                    AndroidView(
                        factory = { ctx ->
                            createPanelContainer(
                                ctx = ctx,
                                key = panelKey,
                                mountState = mountState,
                                onHeight = applyHeight,
                                onCrash = { localCrashCount += 1 },
                            )
                        },
                        update = { container ->
                            mountState.submit(html, foregroundHex, panelKey, screenHeightPx)
                        },
                        onRelease = { container ->
                            TavernPanelLoadScheduler.markDone(panelKey)
                            if (!mountState.isDisposed && mountState.mounted) {
                                DetachedPanelWebViewCache.parkContainer(panelKey, container, mountState)
                            } else {
                                DetachedPanelWebViewCache.evictAndDestroy(panelKey)
                                ActivePanelWebViewPool.release(panelKey)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (heightDp == 0) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onCollapse?.let {
                IconButton(onClick = it, modifier = Modifier.size(32.dp)) {
                    Icon(
                        HugeIcons.ArrowUp01,
                        contentDescription = stringResource(R.string.html_card_collapse),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = reloadPanel, modifier = Modifier.size(32.dp)) {
                Icon(
                    HugeIcons.Refresh01,
                    contentDescription = l10n("重载面板", "Reload panel"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showFullscreen = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    HugeIcons.ViewIcon,
                    contentDescription = stringResource(R.string.html_block_fullscreen),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showFullscreen) {
        val fullscreenState = remember(panelKey) { PanelMountState() }
        Dialog(
            onDismissRequest = { showFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(colorScheme.surface)) {
                AndroidView(
                    factory = { ctx ->
                        createPanelContainer(
                            ctx = ctx,
                            key = "$panelKey#fullscreen",
                            mountState = fullscreenState,
                            onHeight = {},
                            onCrash = {},
                        )
                    },
                    update = { fullscreenState.submit(html, foregroundHex, "$panelKey#fullscreen") },
                    onRelease = { fullscreenState.release() },
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(
                    onClick = { showFullscreen = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    Icon(
                        HugeIcons.Cancel01,
                        contentDescription = stringResource(android.R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 面板容器：不拦截触摸，WebView 拿到完整手势，按钮点击才算"真点击"。 */
private class PanelContainer(context: Context) : FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false
}

@SuppressLint("SetJavaScriptEnabled")
private fun createPanelContainer(
    ctx: Context,
    key: String,
    mountState: PanelMountState,
    onHeight: (Int) -> Unit,
    onCrash: () -> Unit,
): FrameLayout {
    val appContext = ctx.applicationContext
    TavernRuntimeManager.ensureContext(appContext)
    TavernRuntimeManager.ensureStarted(appContext)

    DetachedPanelWebViewCache.takeContainer(key)?.let { cached ->
        mountState.attach(cached.webView ?: return@let)
        TavernPanelLoadScheduler.markDone(key)
        return cached.container
    }

    val container = PanelContainer(ctx)
    container.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    val webView = WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(AndroidColor.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.blockNetworkImage = false
        settings.loadWithOverviewMode = false
        settings.useWideViewPort = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        isVerticalScrollBarEnabled = false
        isFocusable = false
        isFocusableInTouchMode = false
        isLongClickable = false
        setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                ActivePanelWebViewPool.touch(key)
                TavernRuntimeManager.recordWebViewBootStage("panel", key, "first_interaction")
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
            }
            if (v is WebView && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                v.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        webChromeClient = RikkaWebChromeClient(
            context = appContext,
            sourceTag = "panel:" + key,
            onConsoleError = { message -> mountState.lastError = message },
        )
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                if (url.toString() == "about:blank") return false
                val target = TavernRuntimeManager.BASE_URL
                if (url.toString().startsWith(target)) return false
                val scheme = url.scheme?.lowercase(Locale.US)
                if (request.isForMainFrame && request.hasGesture() && (scheme == "http" || scheme == "https")) {
                    runCatching {
                        view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
                    }
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                TavernRuntimeManager.interceptAsset(request.url, request.requestHeaders)?.let { return it }
                return WebViewLocalAssets.interceptCdn(view.context.applicationContext, request.url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (mountState.isDisposed) return
                TavernRuntimeManager.recordWebViewBootStage("panel", key, "dom_content_loaded")
                probePanelRuntime(view, mountState, key, retried = false)
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                val wasDisposed = mountState.isDisposed
                Log.w(TAG, "panel renderer gone key=$key crashed=${detail?.didCrash()}")
                me.rerere.rikkahub.utils.CrashHandler.logRendererCrash(
                    view.context.applicationContext,
                    "HtmlWebViewBlock",
                    "renderer crashed=${detail?.didCrash()}",
                )
                DetachedPanelWebViewCache.evictAndDestroy(key)
                mountState.onRendererGone()
                TavernPanelLoadScheduler.markDone(key)
                TavernRuntimeManager.onPanelRendererGone(
                    panelView = view,
                    messageKey = key,
                    didCrash = detail?.didCrash() == true,
                    priority = detail?.rendererPriorityAtExit() ?: 0,
                )
                (view.parent as? ViewGroup)?.removeView(view)
                runCatching { view.destroy() }
                if (!wasDisposed) {
                    Handler(Looper.getMainLooper()).post { onCrash() }
                }
                return true
            }
        }
        addJavascriptInterface(
            PanelBridge(Handler(Looper.getMainLooper()), mountState, onHeight),
            "RikkaPanel",
        )
        addJavascriptInterface(TavernRuntimeManager.TavernJsBridge(), "RikkaBridge")
        mountState.attach(this)
        TavernRuntimeManager.registerAuxWebView(this, "panel")
        TavernRuntimeManager.recordWebViewBootStage("panel", key, "webview_created")
        loadUrl(TavernRuntimeManager.BASE_URL + "panel.html")
    }
    container.addView(webView)
    return container
}

/** 就绪探测：runtime.js 已注册 __rikkaPanelMount 才算就绪，否则 220ms 复探一次。 */
private fun probePanelRuntime(
    view: WebView,
    mountState: PanelMountState,
    key: String,
    retried: Boolean,
) {
    view.evaluateJavascript("typeof window.__rikkaPanelMount") { result ->
        if (mountState.isDisposed) return@evaluateJavascript
        if (result?.contains("function") == true) {
            TavernRuntimeManager.recordWebViewBootStage("panel", key, "runtime_ready")
            mountState.onPageReady()
            mountState.tryMountIfNeeded()
            return@evaluateJavascript
        }
        if (retried) {
            TavernRuntimeManager.appendLog("warn", "[panel] runtime missing key=$key")
            mountState.onRuntimeMissing()
            return@evaluateJavascript
        }
        mountState.scheduleReadyProbe(220L) {
            probePanelRuntime(view, mountState, key, retried = true)
        }
    }
}

@Composable
private fun RendererCrashCard(onReload: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    l10n("渲染器连续异常退出", "Renderer exited repeatedly"),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    l10n("卡片脚本多次拖垮渲染进程，已停止自动重建", "Auto rebuild stopped after repeated crashes"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onReload) {
                Text(l10n("重新加载", "Reload"))
            }
        }
    }
}

@Composable
private fun PanelStatusPlaceholder(
    title: String,
    statusText: String,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium)
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onReload) {
                Text(l10n("重载面板", "Reload panel"))
            }
        }
    }
}

/** 占位卡文案：崩溃待重载 > 脚本异常 > 当前装载阶段。 */
private fun panelStatusText(
    stage: PanelLoadStage,
    lastError: String?,
    crashCount: Int,
    manualReload: Boolean,
): String = when {
    manualReload || crashCount > MAX_RENDERER_CRASH_REBUILDS ->
        l10n("渲染器反复崩溃，请手动重新加载面板", "Renderer crashed repeatedly, reload manually")
    lastError != null -> l10n("脚本异常：" + lastError, "Script error: " + lastError)
    stage is PanelLoadStage.Queued ->
        l10n(
            "正在按序排队加载「" + stage.panelTitle + "」（队列 " + stage.position + "/" + stage.totalQueued + "）…",
            "Queued: " + stage.panelTitle + " (" + stage.position + "/" + stage.totalQueued + ")",
        )
    stage is PanelLoadStage.WaitingHost -> l10n("等待宿主就绪…", "Waiting for host…")
    stage is PanelLoadStage.LoadingWebView -> l10n("正在装载 WebView…", "Loading WebView…")
    stage is PanelLoadStage.MountingDom -> l10n("正在挂载 DOM…", "Mounting DOM…")
    stage is PanelLoadStage.WaitingVars -> l10n("等待变量初始化…", "Waiting for variables…")
    stage is PanelLoadStage.ResourcesLoading -> l10n("资源未就绪，正在加载…", "Resources loading…")
    else -> l10n("已就绪", "Ready")
}
