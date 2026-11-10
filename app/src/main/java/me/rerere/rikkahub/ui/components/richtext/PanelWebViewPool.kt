package me.rerere.rikkahub.ui.components.richtext

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val POOL_TAG = "PanelPool"

/** 活跃面板 WebView 的 LRU 名额，超出即淘汰最久未用的一张。 */
internal object ActivePanelWebViewPool {
    const val MAX_ACTIVE_PANEL_WEBVIEWS = 16

    private val lock = Any()
    private val lruKeys = LinkedHashSet<String>()
    private val _activeKeys = MutableStateFlow<Set<String>>(emptySet())
    val activeKeys: StateFlow<Set<String>> = _activeKeys.asStateFlow()

    fun acquire(key: String) {
        val evicted = mutableListOf<String>()
        synchronized(lock) {
            if (lruKeys.remove(key)) {
                lruKeys.add(key)
                publish()
                return
            }
            while (lruKeys.size >= MAX_ACTIVE_PANEL_WEBVIEWS) {
                val oldest = lruKeys.firstOrNull() ?: break
                lruKeys.remove(oldest)
                evicted += oldest
            }
            lruKeys.add(key)
            publish()
            Log.i(POOL_TAG, "[panel-pool] acquire key=$key active=${lruKeys.size}/$MAX_ACTIVE_PANEL_WEBVIEWS")
        }
        for (old in evicted) {
            DetachedPanelWebViewCache.evictAndDestroy(old)
        }
    }

    fun touch(key: String) {
        synchronized(lock) {
            if (lruKeys.remove(key)) {
                lruKeys.add(key)
                publish()
            }
        }
    }

    fun release(key: String) {
        synchronized(lock) {
            if (lruKeys.remove(key)) {
                publish()
                Log.i(POOL_TAG, "[panel-pool] release key=$key active=${lruKeys.size}/$MAX_ACTIVE_PANEL_WEBVIEWS")
            }
        }
    }

    fun isActive(key: String): Boolean = synchronized(lock) { lruKeys.contains(key) }

    fun activeCount(): Int = synchronized(lock) { lruKeys.size }

    fun clearAll() {
        synchronized(lock) {
            lruKeys.clear()
            publish()
        }
    }

    private fun publish() {
        _activeKeys.value = lruKeys.toSet()
    }
}

/**
 * 滑出视口的面板暂存（最多 2 个）：连容器与挂载状态一起缓存，
 * 复用后 pageReady/pendingHtml 仍在，重新挂载不必重开页面。
 */
internal object DetachedPanelWebViewCache {
    const val MAX_DETACHED_WEBVIEWS = 2

    class CachedPanelEntry(
        val key: String,
        val container: FrameLayout,
        val mountState: PanelMountState,
    ) {
        val webView: WebView? get() = container.getChildAt(0) as? WebView
    }

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, CachedPanelEntry>(8, 0.75f, true) {}
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getMountState(key: String): PanelMountState? = synchronized(lock) { entries[key]?.mountState }

    fun has(key: String): Boolean = synchronized(lock) { entries.containsKey(key) }

    fun size(): Int = synchronized(lock) { entries.size }

    fun takeContainer(key: String): CachedPanelEntry? = synchronized(lock) {
        val entry = entries.remove(key) ?: return null
        mainHandler.post {
            entry.webView?.let { wv ->
                wv.onResume()
                runCatching {
                    wv.evaluateJavascript(
                        "try{if(window.__rikkaPanelAwaitSettled){window.__rikkaPanelAwaitSettled();}}catch(e){}",
                        null,
                    )
                }
            }
        }
        Log.i(POOL_TAG, "[panel-pool] take key=$key cached=${entries.size}/$MAX_DETACHED_WEBVIEWS")
        entry
    }

    fun parkContainer(key: String, container: FrameLayout, mountState: PanelMountState) {
        val overflow = mutableListOf<CachedPanelEntry>()
        synchronized(lock) {
            val old = entries.put(key, CachedPanelEntry(key, container, mountState))
            if (old != null && old.container !== container) overflow += old
            while (entries.size > MAX_DETACHED_WEBVIEWS) {
                val eldest = entries.entries.firstOrNull() ?: break
                entries.remove(eldest.key)
                overflow += eldest.value
            }
        }
        (container.parent as? ViewGroup)?.removeView(container)
        container.getChildAt(0)?.let { wv -> runCatching { (wv as WebView).onPause() } }
        Log.i(POOL_TAG, "[panel-pool] parked key=$key cached=${size()}/$MAX_DETACHED_WEBVIEWS")
        overflow.forEach { destroy(it) }
    }

    fun evictAndDestroy(key: String) {
        val victim = synchronized(lock) { entries.remove(key) }
        if (victim != null) {
            Log.i(POOL_TAG, "[panel-pool] cache evict key=$key cached=${size()}/$MAX_DETACHED_WEBVIEWS")
            destroy(victim)
        }
    }

    fun clearAll() {
        val all = synchronized(lock) {
            val list = entries.values.toList()
            entries.clear()
            list
        }
        all.forEach { destroy(it) }
    }

    private fun destroy(entry: CachedPanelEntry) {
        mainHandler.post {
            entry.mountState.release()
            (entry.container.parent as? ViewGroup)?.removeView(entry.container)
            entry.container.removeAllViews()
        }
    }
}

/** 面板高度缓存：key = messageKey#contentHash，最多 64 条。 */
internal object PanelHeightCache {
    const val MAX_ENTRIES = 64

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Int>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
            size > MAX_ENTRIES
    }

    private fun compositeKey(key: String, contentHash: Int): String = "$key#$contentHash"

    fun get(key: String, contentHash: Int = 0): Int = synchronized(lock) {
        entries[compositeKey(key, contentHash)] ?: 0
    }

    fun put(key: String, contentHash: Int, height: Int) {
        if (height <= 0) return
        synchronized(lock) { entries[compositeKey(key, contentHash)] = height }
    }

    fun invalidateForMessage(key: String) {
        synchronized(lock) {
            entries.keys.removeAll { it == key || it.startsWith("$key#") }
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}

/** GPU 纹理上限下的安全高度（dp）：min(1600, 4096/density)，不低于 480。 */
internal fun gpuSafeMaxPanelHeightDp(density: Float, maxTexturePx: Int = 4096): Int =
    minOf(1600, (maxTexturePx / density.coerceAtLeast(0.5f)).toInt()).coerceAtLeast(480)

/** 面板高度约束：返回 (显示高度 dp, 是否被 GPU 上限截断)。 */
internal fun computePanelHeightConstraint(
    rawDp: Int,
    density: Float,
    maxTexturePx: Int = 4096,
): Pair<Int, Boolean> {
    val gpuSafeMaxDp = gpuSafeMaxPanelHeightDp(density, maxTexturePx)
    val capped = rawDp > gpuSafeMaxDp
    return rawDp.coerceIn(48, gpuSafeMaxDp) to capped
}
