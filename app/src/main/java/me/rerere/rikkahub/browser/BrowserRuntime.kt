package me.rerere.rikkahub.browser

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * 浏览器单例状态机。主进程只通过这里和 Activity 打交道：
 * 状态、日志、导航都从这里出去，WebView 本身永远留在 [BrowserActivity] 里。
 */
object BrowserRuntime {
    private const val TAG = "BrowserRuntime"
    private const val ATTACH_TIMEOUT_MS = 20_000L
    private const val SETTLE_TIMEOUT_MS = 15_000L
    private const val IDLE_TICK_MS = 20_000L
    private const val CLOSE_TIMEOUT_MS = 3_000L
    private const val MAX_RENDERER_REBUILDS = 2
    private const val HOME_URL = "https://m.taobao.com"
    private const val MAX_LOGS = 50

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<BrowserState>(BrowserState.Closed)
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<BrowserToolLog>>(emptyList())
    val logs: StateFlow<List<BrowserToolLog>> = _logs.asStateFlow()

    private var appContext: Context? = null
    private var page: BrowserPage? = null
    private var attachSignal: CompletableDeferred<BrowserPage>? = null
    private var idleJob: Job? = null
    private val lastActivity = AtomicLong(System.currentTimeMillis())
    private var rendererRebuilds = 0
    private var lastPage: PageInfo = PageInfo()

    // 界面消失的信号：close 之后要等它，工具返回时用户那侧必须已经关掉
    private var closedSignal: CompletableDeferred<Unit>? = null

    // 界面还在启动（没 attach 上）时就收到了关闭请求
    private var closePendingAttach = false

    @Volatile
    var config: BrowserSettings = BrowserSettings()
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun configure(settings: BrowserSettings) {
        config = settings
    }

    fun recordLog(log: BrowserToolLog) {
        _logs.value = (_logs.value + log).takeLast(MAX_LOGS)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun attach(p: BrowserPage) {
        if (closePendingAttach) {
            closePendingAttach = false
            _state.value = BrowserState.Closed
            p.closeSelf()
            return
        }
        page = p
        lastActivity.set(System.currentTimeMillis())
        _state.value = BrowserState.Opening
        attachSignal?.complete(p)
        attachSignal = null
        startIdleWatcher()
    }

    fun detach(p: BrowserPage) {
        closedSignal?.complete(Unit)
        closedSignal = null
        if (page !== p) return
        page = null
        attachSignal?.completeExceptionally(BrowserFailure(BrowserError.USER_CLOSED, "用户关闭了浏览器"))
        attachSignal = null
        idleJob?.cancel()
        idleJob = null
        _state.value = BrowserState.Closed
    }

    /** 渲染进程挂了：返回 true 表示还允许 Activity 重建 WebView。 */
    fun onRendererGone(didCrash: Boolean): Boolean {
        rendererRebuilds += 1
        Log.w(TAG, "renderer gone, crash=$didCrash, rebuilds=$rendererRebuilds")
        if (rendererRebuilds > MAX_RENDERER_REBUILDS) {
            _state.value = BrowserState.Error(BrowserError.RENDERER_CRASHED, "渲染进程反复崩溃，已停止自动重建")
            return false
        }
        _state.value = BrowserState.Opening
        return true
    }

    fun onRendererRecovered() {
        if (page != null) _state.value = BrowserState.Opening
    }

    fun setUserBusy(busy: Boolean) {
        if (page == null) return
        _state.value = if (busy) BrowserState.UserBusy else BrowserState.Ready(lastPage)
    }

    fun isUserBusy(): Boolean = _state.value is BrowserState.UserBusy

    suspend fun open(url: String?, reuse: Boolean = true): PageInfo {
        if (!config.allowOpenClose) {
            throw BrowserFailure(BrowserError.DISABLED, "浏览器权限已在设置中关闭")
        }
        if (!isForeground()) {
            throw BrowserFailure(BrowserError.REQUIRES_FOREGROUND, "浏览器需要在前台显示，请回到 App 后再试")
        }
        val target = url?.takeIf { it.isNotBlank() }
        if (target != null) {
            config.checkUrl(target)?.let {
                throw BrowserFailure(it, "该地址被站点策略拦截：$target")
            }
        }

        mutex.lock()
        try {
            lastActivity.set(System.currentTimeMillis())
            // 重建场景不等界面销毁，否则 open 要白等一次 Activity 销毁
            if (!reuse) closeLocked(awaitClosed = false)
            val existing = page
            if (existing != null) {
                if (!existing.isVisible()) bringToFront()
                if (target != null) {
                    _state.value = BrowserState.Opening
                    existing.loadUrl(target)
                }
                val settled = existing.waitSettled(SETTLE_TIMEOUT_MS)
                return publish(existing, settled)
            }
            val started = ensurePage()
            _state.value = BrowserState.Opening
            started.loadUrl(target ?: HOME_URL)
            val settled = started.waitSettled(SETTLE_TIMEOUT_MS)
            return publish(started, settled)
        } finally {
            mutex.unlock()
        }
    }

    suspend fun close(reason: String = "tool") {
        Log.d(TAG, "close: $reason")
        mutex.lock()
        try {
            closeLocked()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 调用方必须已经持有 [mutex]。
     *
     * [awaitClosed] 为 true 时等到 Activity 真正销毁再返回：AI 关掉浏览器后
     * 用户那侧不能还留着一个窗口，否则下一次 browser_open 看到的是旧界面。
     */
    private suspend fun closeLocked(awaitClosed: Boolean = true) {
        val p = page
        page = null
        idleJob?.cancel()
        idleJob = null
        _state.value = BrowserState.Closed
        lastPage = PageInfo()
        SnapshotEngine.reset()
        if (p != null) {
            val signal = CompletableDeferred<Unit>()
            closedSignal = signal
            p.closeSelf()
            if (awaitClosed) {
                val closed = withTimeoutOrNull(CLOSE_TIMEOUT_MS) { signal.await() }
                if (closed == null) {
                    // 部分 ROM 会拖慢 finish：再发一次关闭意图，靠 onNewIntent 收尾
                    appContext?.let { ctx ->
                        runCatching { ctx.startActivity(BrowserActivity.closeIntent(ctx)) }
                    }
                }
            }
            if (closedSignal === signal) closedSignal = null
            return
        }
        // 界面正在启动、还没 attach：让它在 attach 那一刻自己关掉，并把已启动的实例收掉
        if (attachSignal != null) {
            closePendingAttach = true
            appContext?.let { ctx ->
                runCatching { ctx.startActivity(BrowserActivity.closeIntent(ctx)) }
            }
        }
    }

    /** 所有会动页面的工具都从这里进：串行 + 状态检查。 */
    suspend fun <T> withPage(mutating: Boolean = true, block: suspend (BrowserPage) -> T): T {
        mutex.lock()
        try {
            when (val s = _state.value) {
                is BrowserState.UserBusy -> throw BrowserFailure(BrowserError.USER_BUSY, "用户正在手动操作浏览器")
                is BrowserState.Error -> throw BrowserFailure(s.code, s.message)
                BrowserState.Closed -> throw BrowserFailure(BrowserError.NOT_OPEN, "浏览器未打开，请先调用 browser_open")
                BrowserState.Opening -> if (mutating) {
                    throw BrowserFailure(BrowserError.NOT_READY, "页面正在加载，请稍后重试或调用 browser_wait")
                }
                is BrowserState.Ready -> Unit
            }
            val p = page ?: throw BrowserFailure(BrowserError.NOT_OPEN, "浏览器未打开，请先调用 browser_open")
            if (mutating) lastActivity.set(System.currentTimeMillis())
            return block(p)
        } finally {
            mutex.unlock()
        }
    }

    suspend fun refreshInfo(p: BrowserPage): PageInfo = publish(p, settled = true)

    fun currentInfo(): PageInfo = lastPage

    fun touch() {
        lastActivity.set(System.currentTimeMillis())
    }

    private suspend fun publish(p: BrowserPage, settled: Boolean): PageInfo {
        val metrics = runCatching { p.metrics() }.getOrElse { PageMetrics() }
        val info = PageInfo(
            url = metrics.url,
            title = metrics.title,
            hash = lastPage.hash.takeIf { metrics.url == lastPage.url } ?: "",
            settled = settled,
            scrollY = metrics.scrollY,
            scrollHeight = metrics.scrollHeight,
            viewportHeight = metrics.viewportHeight,
        )
        lastPage = info
        _state.value = BrowserState.Ready(info)
        return info
    }

    private suspend fun ensurePage(): BrowserPage {
        page?.let { return it }
        val ctx = appContext ?: throw BrowserFailure(BrowserError.NOT_OPEN, "浏览器尚未初始化")
        val signal = CompletableDeferred<BrowserPage>()
        attachSignal = signal
        try {
            ctx.startActivity(BrowserActivity.intent(ctx))
        } catch (e: Throwable) {
            attachSignal = null
            throw BrowserFailure(
                BrowserError.REQUIRES_FOREGROUND,
                "无法启动浏览器界面：${e.message ?: e.javaClass.simpleName}"
            )
        }
        val started = try {
            withTimeoutOrNull(ATTACH_TIMEOUT_MS) { signal.await() }
        } finally {
            if (attachSignal === signal) attachSignal = null
        }
        if (started == null) {
            runCatching { ctx.startActivity(BrowserActivity.closeIntent(ctx)) }
            throw BrowserFailure(BrowserError.TIMEOUT, "浏览器界面启动超时")
        }
        return started
    }

    private fun bringToFront() {
        val ctx = appContext ?: return
        runCatching {
            ctx.startActivity(
                BrowserActivity.intent(ctx).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    private suspend fun isForeground(): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(true)
    }

    private fun startIdleWatcher() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (true) {
                delay(IDLE_TICK_MS)
                if (page == null) break
                val minutes = config.idleTimeoutMinutes
                if (minutes <= 0) continue
                val idleMs = System.currentTimeMillis() - lastActivity.get()
                if (idleMs > minutes * 60_000L) {
                    Log.i(TAG, "idle for ${idleMs}ms, auto closing")
                    runCatching { close("idle") }
                    break
                }
            }
        }
    }
}
