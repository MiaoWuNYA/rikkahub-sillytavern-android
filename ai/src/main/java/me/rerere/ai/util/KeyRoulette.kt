package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

interface KeyRoulette {
    /**
     * 从逗号/空格分隔的 key 列表里选一把。
     * sessionId（对话 id）非空时按 (providerId, sessionId) 确定性选 key：
     * 同一轮对话的每次请求（含 agentic 工具循环的每一步）都粘住同一把 key。
     * 供应商的前缀缓存按 key/账号隔离，多 key 轮换会让循环每步 miss 缓存。
     */
    fun next(keys: String, providerId: String = "", sessionId: String? = null): String

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询，持久化存储到 cacheDir/lru_key_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入；
         * 传 sessionId 时退化为会话粘性（确定性选 key，优先保证缓存命中）
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex() // 空格换行和逗号

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

/** 会话粘性选 key：同一对话永远选中同一把（hash 不用 String.hashCode，避免跨进程重启仍一致） */
private fun stickyIndexOf(providerId: String, sessionId: String, size: Int): Int {
    val digest = java.security.MessageDigest.getInstance("MD5")
        .digest("$providerId|$sessionId".toByteArray())
    return Math.floorMod(
        ((digest[0].toInt() and 0xff) shl 24) or
            ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or
            (digest[3].toInt() and 0xff),
        size,
    )
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String, sessionId: String?): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys
        if (sessionId != null && keyList.size > 1) {
            return keyList[stickyIndexOf(providerId, sessionId, keyList.size)]
        }
        return keyList.random()
    }
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object LruFileLock

// 文件结构: Map<providerId, Map<apiKey, lastUsedTimestamp>>
private typealias LruCache = Map<String, Map<String, Long>>

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    override fun next(keys: String, providerId: String, sessionId: String?): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys

        // 会话粘性：对话内每步请求粘同一把 key，保证供应商前缀缓存命中；
        // 不写 LRU 记录（不参与全局轮换的频率统计）
        if (sessionId != null && keyList.size > 1) {
            return keyList[stickyIndexOf(providerId, sessionId, keyList.size)]
        }

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()

            // 取本 provider 的记录，过滤掉已过期条目和不在当前 key 列表中的条目
            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (k, lastUsed) -> k in keyList && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()

            // 优先选从未使用的 key，否则选最久未使用的
            val selected = keyList.firstOrNull { it !in providerCache }
                ?: providerCache.minByOrNull { it.value }!!.key

            providerCache[selected] = now
            allCache[providerId] = providerCache

            // 清理整个 provider 条目均已过期的记录
            allCache.entries.removeIf { (id, cache) ->
                id != providerId && cache.values.all { now - it >= EXPIRE_DURATION_MS }
            }

            saveCache(allCache)
            return selected
        }
    }

    private fun loadCache(): LruCache {
        return try {
            val file = File(context.cacheDir, LRU_CACHE_FILE)
            if (!file.exists()) return emptyMap()
            Json.decodeFromString(file.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveCache(cache: LruCache) {
        try {
            File(context.cacheDir, LRU_CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (_: Exception) {
        }
    }
}
