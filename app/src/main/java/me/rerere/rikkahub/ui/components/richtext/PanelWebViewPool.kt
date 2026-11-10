package me.rerere.rikkahub.ui.components.richtext

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val POOL_TAG = "PanelPool"
private const val PANEL_HOST_JS = "rikkaHost"
private const val PANEL_HOST_GEN_JS = "rikkaHostGen"

/** 活跃面板 WebView 的 LRU 名额，超出即淘汰最久未用的一张。 */
internal object ActivePanelWebViewPool {
    const val MAX_ACTIVE_PANEL_WEBVIEWS = 3

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
            Log.i(
                POOL_TAG,
                "[panel-pool] evicted oldest panel WebView key=$old (active=${MAX_ACTIVE_PANEL_WEBVIEWS}/$MAX_ACTIVE_PANEL_WEBVIEWS)",
            )
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
 * 滑出视口的面板 WebView 暂存（最多 2 个）。刻意不调 pauseTimers：
 * 暂停定时器会让卡里的 setInterval 状态机冻住，重新出现时行为错乱。
 */
internal object DetachedPanelWebViewCache {
    const val MAX_DETACHED_WEBVIEWS = 2

    private class Entry(val key: String, val page: String, val view: WebView)

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {}
    private val mainHandler = Handler(Looper.getMainLooper())

    fun take(key: String, page: String): WebView? {
        synchronized(lock) {
            val entry = entries[key]
            if (entry == null || entry.page != page) return null
            entries.remove(key)
            val view = entry.view
            view.removeJavascriptInterface(PANEL_HOST_JS)
            view.removeJavascriptInterface(PANEL_HOST_GEN_JS)
            Log.i(POOL_TAG, "[panel-pool] reused detached WebView key=$key cached=${entries.size}/$MAX_DETACHED_WEBVIEWS")
            return view
        }
    }

    fun has(key: String): Boolean = synchronized(lock) { entries.containsKey(key) }

    fun size(): Int = synchronized(lock) { entries.size }

    fun park(key: String, page: String?, view: WebView): Boolean {
        if (page == null) return false
        val overflow = mutableListOf<WebView>()
        synchronized(lock) {
            val old = entries.put(key, Entry(key, page, view))
            if (old != null && old.view !== view) overflow += old.view
            while (entries.size > MAX_DETACHED_WEBVIEWS) {
                val eldest = entries.entries.firstOrNull() ?: break
                entries.remove(eldest.key)
                overflow += eldest.value.view
            }
        }
        Log.i(POOL_TAG, "[panel-pool] parked key=$key cached=${size()}/$MAX_DETACHED_WEBVIEWS")
        overflow.forEach { destroy(it) }
        return true
    }

    fun evictAndDestroy(key: String) {
        val victim = synchronized(lock) { entries.remove(key)?.view }
        if (victim != null) {
            Log.i(POOL_TAG, "[panel-pool] cache evict key=$key cached=${size()}/$MAX_DETACHED_WEBVIEWS")
            destroy(victim)
        }
    }

    fun clearAll() {
        val all = synchronized(lock) {
            val list = entries.values.map { it.view }
            entries.clear()
            list
        }
        all.forEach { destroy(it) }
    }

    private fun destroy(view: WebView) {
        mainHandler.post {
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.removeAllViews()
            view.destroy()
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
