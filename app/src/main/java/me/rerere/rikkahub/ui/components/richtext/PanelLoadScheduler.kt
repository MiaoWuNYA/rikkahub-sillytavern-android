package me.rerere.rikkahub.ui.components.richtext

import android.util.Log
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
import kotlinx.coroutines.withTimeoutOrNull

private const val PANEL_TAG = "PanelLoad"

/** 面板分步装载阶段，驱动占位卡与状态文案。 */
internal sealed interface PanelLoadStage {
    data class Queued(val panelTitle: String, val position: Int, val totalQueued: Int) : PanelLoadStage
    data class WaitingHost(val panelTitle: String) : PanelLoadStage
    data class LoadingWebView(val panelTitle: String) : PanelLoadStage
    data class MountingDom(val panelTitle: String) : PanelLoadStage
    data class ResourcesLoading(val panelTitle: String) : PanelLoadStage
    data object Ready : PanelLoadStage
}

/** 阶段是否还没轮到本面板建 WebView（此时只显示占位卡）。 */
internal fun PanelLoadStage.isGatePending(): Boolean =
    this is PanelLoadStage.Queued || this is PanelLoadStage.WaitingHost

/**
 * 串行装载闸门：同一时刻只放行一个面板首次装载 WebView，期间留 [INTER_PANEL_COOLDOWN_MS] 冷却。
 */
internal object TavernPanelLoadScheduler {
    const val HOST_READY_WAIT_MAX_MS = 800L
    const val SINGLE_PANEL_TIMEOUT_MS = 2500L
    const val INTER_PANEL_COOLDOWN_MS = 60L

    class Slot internal constructor(val key: String, val title: String) {
        internal val gate = CompletableDeferred<Unit>()
        internal val done = CompletableDeferred<Unit>()

        /** 挂起直到轮到本面板；被取消时返回 false。 */
        suspend fun awaitTurn(): Boolean = runCatching { gate.await() }.isSuccess
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingQueue = ArrayDeque<Slot>()
    private var activeRequest: Slot? = null
    private var pumpJob: Job? = null

    private val _queueSnapshot = MutableStateFlow<List<String>>(emptyList())
    val queueSnapshot: StateFlow<List<String>> = _queueSnapshot.asStateFlow()

    /** 宿主就绪探针；本仓库没有独立主运行时，默认恒为就绪。 */
    @Volatile
    var hostReadyProvider: () -> Boolean = { true }

    fun enqueue(key: String, title: String, prioritize: Boolean = false): Slot {
        val slot: Slot
        synchronized(lock) {
            val active = activeRequest
            if (active != null && active.key == key) return active
            val existingIdx = pendingQueue.indexOfFirst { it.key == key }
            if (existingIdx >= 0) {
                val existing = pendingQueue.removeAt(existingIdx)
                if (prioritize) pendingQueue.addFirst(existing) else pendingQueue.addLast(existing)
                publishLocked()
                ensurePumpLocked()
                return existing
            }
            slot = Slot(key, title)
            if (prioritize) pendingQueue.addFirst(slot) else pendingQueue.addLast(slot)
            publishLocked()
            Log.i(PANEL_TAG, "[scheduler] queued key=$key pending=${pendingQueue.size}")
            ensurePumpLocked()
        }
        return slot
    }

    /** 面板完成首次装载（页面 onPageFinished），放行下一个。 */
    fun markDone(key: String) {
        synchronized(lock) {
            val active = activeRequest
            if (active != null && active.key == key) active.done.complete(Unit)
            val idx = pendingQueue.indexOfFirst { it.key == key }
            if (idx >= 0) {
                pendingQueue.removeAt(idx).done.complete(Unit)
                publishLocked()
            }
        }
    }

    fun cancel(key: String) {
        synchronized(lock) {
            val active = activeRequest
            if (active != null && active.key == key) {
                active.gate.cancel()
                active.done.complete(Unit)
            }
            val idx = pendingQueue.indexOfFirst { it.key == key }
            if (idx >= 0) {
                val removed = pendingQueue.removeAt(idx)
                removed.gate.cancel()
                removed.done.complete(Unit)
                publishLocked()
            }
            Log.i(PANEL_TAG, "[scheduler] cancel key=$key")
        }
    }

    /** 队列中的位次（1 起）；不在队列里返回 null。 */
    fun positionOf(key: String): Int? {
        synchronized(lock) {
            if (activeRequest?.key == key) return 1
            val idx = pendingQueue.indexOfFirst { it.key == key }
            return if (idx < 0) null else idx + (if (activeRequest != null) 2 else 1)
        }
    }

    fun queueSize(): Int = synchronized(lock) { pendingQueue.size + (if (activeRequest != null) 1 else 0) }

    fun resetForTest() {
        synchronized(lock) {
            pumpJob?.cancel()
            pumpJob = null
            activeRequest?.done?.complete(Unit)
            activeRequest = null
            pendingQueue.clear()
            _queueSnapshot.value = emptyList()
        }
    }

    private fun publishLocked() {
        _queueSnapshot.value = buildList {
            activeRequest?.let { add(it.key) }
            pendingQueue.forEach { add(it.key) }
        }
    }

    private fun ensurePumpLocked() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch { pumpLoop() }
    }

    private suspend fun pumpLoop() {
        var waited = 0L
        while (!hostReadyProvider() && waited < HOST_READY_WAIT_MAX_MS) {
            delay(50L)
            waited += 50L
        }
        while (true) {
            val next = synchronized(lock) {
                if (pendingQueue.isEmpty()) {
                    activeRequest = null
                    publishLocked()
                    return
                }
                pendingQueue.removeFirst().also { activeRequest = it }.also { publishLocked() }
            }
            Log.i(PANEL_TAG, "[scheduler] grant key=${next.key} title=${next.title}")
            next.gate.complete(Unit)
            withTimeoutOrNull(SINGLE_PANEL_TIMEOUT_MS) { next.done.await() }
            synchronized(lock) {
                if (activeRequest?.key == next.key) activeRequest = null
                publishLocked()
            }
            delay(INTER_PANEL_COOLDOWN_MS)
        }
    }
}

internal enum class PanelUpdateAction { NO_OP, RELOAD_DOCUMENT }

/**
 * 单张卡片的挂载状态：记录已装载文档、重载代次与最近一次错误。
 */
internal class PanelMountState {
    internal companion object {
        const val STREAMING_REMOUNT_COALESCE_MS = 48L
        val SCRIPT_TAG_RE = Regex("<script[\\s>]", RegexOption.IGNORE_CASE)
    }

    var mountedPage: String? = null
        private set
    var reloadGen: Int = 0
        private set
    var lastError: String? = null
        private set
    var reloadCount: Int = 0
        private set
    var inPlaceRemountCount: Int = 0
        private set

    private var lastReloadAtMs = 0L

    fun decideUpdateAction(page: String): PanelUpdateAction {
        val prev = mountedPage
        if (prev == null) {
            mountedPage = page
            reloadCount++
            return PanelUpdateAction.RELOAD_DOCUMENT
        }
        if (page == prev) return PanelUpdateAction.NO_OP
        mountedPage = page
        reloadCount++
        return PanelUpdateAction.RELOAD_DOCUMENT
    }

    fun reportError(message: String) {
        lastError = message
        Log.w(PANEL_TAG, "[panel] script error: $message")
    }

    fun clearError() {
        lastError = null
    }

    /** 文档级重载：递增代次，调用方据此换新 WebView 并重装页面。 */
    fun reloadNow(): Int {
        mountedPage = null
        lastError = null
        reloadCount++
        reloadGen += 1
        return reloadGen
    }

    /** 48ms 内重复请求合并成一次重载，避免连点把渲染进程打满。 */
    fun submit(nowMs: Long = android.os.SystemClock.uptimeMillis()): Boolean {
        if (nowMs - lastReloadAtMs < STREAMING_REMOUNT_COALESCE_MS) {
            inPlaceRemountCount++
            Log.i(PANEL_TAG, "[panel] reload coalesced (${nowMs - lastReloadAtMs}ms)")
            return false
        }
        lastReloadAtMs = nowMs
        return true
    }
}
