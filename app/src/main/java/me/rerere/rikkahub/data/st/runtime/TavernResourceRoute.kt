package me.rerere.rikkahub.data.st.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 面板/消息外链图片的网络通道。AUTO = 同时竞速系统默认（含 VPN）与物理直连。 */
enum class ResourceNetworkMode {
    AUTO,
    DIRECT,
    VPN,
}

data class ResourceFetchStat(
    val url: String,
    val channel: String,
    val ok: Boolean,
    val cacheHit: Boolean = false,
    val ttfbMs: Long = -1L,
    val totalMs: Long = -1L,
    val bytes: Long = 0L,
    val error: String = "",
    val atMs: Long = System.currentTimeMillis(),
)

/**
 * 外链图片双通道加载器：
 * - 先从磁盘缓存返回（WebView 首屏完全不依赖当前 VPN/直连状态）；
 * - 未命中时按 [mode] 选择系统默认网络（VPN）和/或物理直连网络，谁先返回成功响应谁赢；
 * - 结果写入 [filesDir]/tavern-resource-cache，并按 LRU 上限回收；
 * - 只代理图片类 GET 请求，其余请求返回 null 交回 WebView 原生网络，避免破坏脚本/流式接口。
 */
object TavernResourceRoute {
    private const val CACHE_DIR = "tavern-resource-cache"
    private const val MAX_CACHE_BYTES = 160L * 1024L * 1024L
    private const val MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val TOTAL_TIMEOUT_MS = 12_000L
    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "svg", "avif", "jfif",
    )
    private val MIME_BY_EXT = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "jfif" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "svg" to "image/svg+xml",
        "avif" to "image/avif",
    )

    @Volatile
    var mode: ResourceNetworkMode = ResourceNetworkMode.AUTO

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadSemaphore = Semaphore(2)
    private val inflight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<CachedResource?>>()

    private val _recentStats = MutableStateFlow<List<ResourceFetchStat>>(emptyList())
    val recentStats: StateFlow<List<ResourceFetchStat>> = _recentStats.asStateFlow()

    fun updateMode(newMode: ResourceNetworkMode) {
        mode = newMode
        TavernRuntimeManager.appendLog("info", "[net] resource route mode=$newMode")
    }

    /** WebView shouldInterceptRequest 入口：命中缓存返回本地响应，未命中直接交回 WebView 原生网络。 */
    fun intercept(context: Context, uri: Uri, requestHeaders: Map<String, String>?): WebResourceResponse? {
        // shouldInterceptRequest 正常在后台线程；若被主线程调用，绝不阻塞 UI，直接交回原生网络。
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        if (scheme != "http" && scheme != "https") return null
        val url = uri.toString()
        if (!looksLikeImageRequest(uri, requestHeaders)) return null
        val range = requestHeaders?.entries?.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
        if (!range.isNullOrBlank()) return null

        val cached = readCache(context, url)
        if (cached != null) {
            record(ResourceFetchStat(url, channel = "cache", ok = true, cacheHit = true, bytes = cached.file.length()))
            return responseFor(cached)
        }

        // [P0 修复] **绝不**在 shouldInterceptRequest 里 runBlocking 等待网络。
        //
        // 旧实现在这里 `runBlocking { withTimeoutOrNull(12s) { fetchToCache(...) } }`，
        // 而 shouldInterceptRequest 跑在 Chromium 全局共享的拦截线程上：一个慢图/失效外链
        // 就会把该线程占满 12 秒，随后所有 WebView（包括主运行时）的资源与 XHR 请求一起排队，
        // 表现为「面板/生成卡住、上游收不到请求」。
        // 新策略：本函数只做**纯本地**的事（读磁盘缓存）。未命中就返回 null 让 WebView 走原生网络，
        // 同时把「下载并写入缓存」作为**后台任务**发起（不阻塞调用线程）；该任务写完后，
        // 用户下次滚动/重进面板即可命中缓存。这与 prefetchImages 走同一条后台通道。
        if (url.startsWith("https://appassets.androidplatform.net", true) ||
            url.startsWith("http://appassets.androidplatform.net", true)
        ) {
            return null
        }
        val appContext = context.applicationContext
        // [P0 修复] 后台补缓存，但**避免无谓的协程膨胀**：
        // shouldInterceptRequest 对同一张图可能被调用多次（多 WebView / 重试 / Range 探测），
        // 如果每次都 launch 一个协程，20 图面板会瞬间造出上百个协程。
        // 这里先用 inflight 做一次廉价预检：已有任务在跑就什么都不做。
        if (!inflight.containsKey(cacheKey(url))) {
            scope.launch {
                runCatching { withTimeoutOrNull(TOTAL_TIMEOUT_MS) { fetchToCache(appContext, url) } }
            }
        }
        return null
    }

    /** 供原生 Coil 图片使用：成功返回本地文件，失败返回 null（调用方保留原始 URL）。 */
    suspend fun fetchImageToCache(
        context: Context,
        url: String,
        timeoutMs: Long = TOTAL_TIMEOUT_MS,
    ): File? {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (parsed.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        // 调用方明确是图片组件：即使 URL 没有扩展名（CDN 常见），也走双通道缓存。
        readCache(context, url)?.let { return it.file }
        return withTimeoutOrNull(timeoutMs.coerceAtLeast(1_000L)) { fetchToCache(context, url)?.file }
    }

    suspend fun probeChannels(context: Context, url: String): List<ResourceFetchStat> = coroutineScope {
        val attempts = buildAttempts(context)
        attempts.map { attempt ->
            async(Dispatchers.IO) {
                runCatching { fetchOnce(attempt, url) }.fold(
                    onSuccess = { result ->
                        ResourceFetchStat(
                            url = url,
                            channel = result.channel,
                            ok = true,
                            ttfbMs = result.ttfbMs,
                            totalMs = result.totalMs,
                            bytes = result.bytes.size.toLong(),
                        )
                    },
                    onFailure = { error ->
                        ResourceFetchStat(
                            url = url,
                            channel = attempt.label,
                            ok = false,
                            error = error.javaClass.simpleName + ": " + (error.message ?: ""),
                        )
                    },
                )
            }
        }.map { it.await() }
    }

    /** 面板 HTML 解析到外链 <img> 时提前竞速下载，WebView 随后请求会直接命中同一个 inflight。 */
    fun prefetchImages(context: Context, html: String, maxImages: Int = 8) {
        if (html.isBlank() || !html.contains("<img", ignoreCase = true)) return
        val appContext = context.applicationContext
        scope.launch {
            val urls = Regex("""<img\b[^>]*\bsrc\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .mapNotNull { it.groupValues.getOrNull(2)?.trim() }
                .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
                .filterNot { it.startsWith("https://appassets.androidplatform.net", true) }
                .filterNot { it.startsWith("http://appassets.androidplatform.net", true) }
                .distinct()
                .take(maxImages.coerceAtLeast(1))
                .toList()
            urls.forEach { url ->
                runCatching { fetchToCache(appContext, url) }
            }
        }
    }

    fun cacheStats(context: Context): Pair<Int, Long> {
        val dir = File(context.filesDir, CACHE_DIR)
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".bin") }.orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    fun clearCache(context: Context) {
        val dir = File(context.filesDir, CACHE_DIR)
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        _recentStats.value = emptyList()
    }

    /** 只清理指定 URL 的缓存（开发者测试台使用，避免污染用户图片缓存）。 */
    fun evictCachedUrl(context: Context, url: String) {
        val file = cacheFile(context, url)
        runCatching { file.delete() }
        runCatching { File(file.parentFile, file.nameWithoutExtension + ".mime").delete() }
    }

    private data class CachedResource(val file: File, val mime: String)

    private data class FetchAttempt(val label: String, val network: Network?, val openConnection: (URL) -> java.net.URLConnection)

    private data class FetchResult(
        val bytes: ByteArray,
        val mime: String,
        val channel: String,
        val ttfbMs: Long,
        val totalMs: Long,
    )

    private fun looksLikeImageRequest(uri: Uri, headers: Map<String, String>?): Boolean {
        val accept = headers?.entries?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value.orEmpty()
        if (accept.contains("text/html", ignoreCase = true)) return false
        val pathExt = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (pathExt in IMAGE_EXTENSIONS) return true
        return accept.contains("image/", ignoreCase = true)
    }

    private fun cacheKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun cacheFile(context: Context, url: String): File {
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        return File(dir, cacheKey(url) + ".bin")
    }

    private fun readCache(context: Context, url: String): CachedResource? {
        val file = cacheFile(context, url)
        if (!file.isFile || file.length() <= 0L) return null
        if (file.length() > MAX_DOWNLOAD_BYTES) {
            runCatching { file.delete() }
            return null
        }
        val mimeFile = File(file.parentFile, file.nameWithoutExtension + ".mime")
        val mime = runCatching { mimeFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: mimeFromUrl(url)
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return CachedResource(file, mime)
    }

    private suspend fun fetchToCache(context: Context, url: String): CachedResource? {
        val key = cacheKey(url)
        val deferred = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                readCache(context, url) ?: run {
                    // 同时最多 3 个外链下载，避免 20 图面板把 20 份 byte[] 同时塞进内存。
                    val result = downloadSemaphore.withPermit { fetchRacing(context, url) }
                        ?: return@async null
                    writeCache(context, url, result)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } finally {
                inflight.remove(key)
            }
        }
        val previous = inflight.putIfAbsent(key, deferred)
        if (previous != null && previous !== deferred) {
            deferred.cancel()
            return runCatching { previous.await() }.getOrNull()
        }
        deferred.start()
        return runCatching { deferred.await() }.getOrNull()
    }

    private suspend fun fetchRacing(context: Context, url: String): FetchResult? = coroutineScope {
        val attempts = buildAttempts(context)
        if (attempts.isEmpty()) return@coroutineScope null
        if (attempts.size == 1) {
            return@coroutineScope runCatching { fetchOnce(attempts.first(), url) }.getOrNull()
        }
        val channel = Channel<Result<FetchResult>>(attempts.size)
        val jobs = attempts.map { attempt ->
            launch(Dispatchers.IO) {
                channel.send(runCatching { fetchOnce(attempt, url) })
            }
        }
        repeat(attempts.size) {
            val result = channel.receive()
            if (result.isSuccess) {
                jobs.forEach { it.cancel() }
                // 不要 close：容量已等于尝试数，未完成的分支在 send 时不会阻塞；
                // close 会让并发 send 抛 ClosedSendChannelException，反而取消父协程。
                return@coroutineScope result.getOrNull()
            }
        }
        jobs.forEach { it.cancel() }
        null
    }

    private fun buildAttempts(context: Context): List<FetchAttempt> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val defaultAttempt = FetchAttempt(
            label = defaultChannelLabel(cm),
            network = null,
            openConnection = { URL(it.toString()).openConnection() as HttpURLConnection },
        )
        val directNetwork = if (mode == ResourceNetworkMode.VPN) null else directPhysicalNetwork(cm)
        val networkAttempt = directNetwork?.let { network ->
            FetchAttempt(
                label = "直连",
                network = network,
                openConnection = { url -> network.openConnection(url) as HttpURLConnection },
            )
        }
        return when (mode) {
            ResourceNetworkMode.VPN -> listOf(defaultAttempt)
            ResourceNetworkMode.DIRECT -> listOfNotNull(networkAttempt ?: defaultAttempt)
            ResourceNetworkMode.AUTO -> {
                if (networkAttempt != null && networkAttempt.label != defaultAttempt.label) {
                    listOf(defaultAttempt, networkAttempt)
                } else {
                    listOf(defaultAttempt)
                }
            }
        }
    }

    private fun defaultChannelLabel(cm: ConnectivityManager?): String {
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "系统默认"
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "VPN" else "直连"
    }

    private fun directPhysicalNetwork(cm: ConnectivityManager?): Network? {
        if (cm == null) return null
        val active = cm.activeNetwork
        return cm.allNetworks.firstOrNull { network ->
            if (network == active) return@firstOrNull false
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    private fun fetchOnce(attempt: FetchAttempt, url: String): FetchResult {
        val started = System.currentTimeMillis()
        val connection = attempt.openConnection(URL(url)).apply {
            if (this is HttpURLConnection) {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                setRequestProperty("User-Agent", "RikkaST-ResourceRoute/1.0")
            }
        }
        try {
            val http = connection as? HttpURLConnection
            val ttfb = System.currentTimeMillis() - started
            val code = http?.responseCode ?: 200
            if (http != null && code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val declared = http?.contentLengthLong ?: -1L
            if (declared > MAX_DOWNLOAD_BYTES) throw IllegalStateException("too large: $declared")
            val bytes = connection.getInputStream().use { input ->
                val out = java.io.ByteArrayOutputStream(declared.coerceAtLeast(16 * 1024).toInt())
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_DOWNLOAD_BYTES) throw IllegalStateException("response exceeds cap")
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            if (bytes.isEmpty()) throw IllegalStateException("empty body")
            val mime = http?.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
                ?: mimeFromUrl(url)
            val stat = ResourceFetchStat(
                url = url,
                channel = attempt.label,
                ok = true,
                ttfbMs = ttfb,
                totalMs = System.currentTimeMillis() - started,
                bytes = bytes.size.toLong(),
            )
            record(stat)
            return FetchResult(bytes, mime, attempt.label, ttfb, stat.totalMs)
        } catch (e: CancellationException) {
            // 竞速失败方被主动取消：不写失败日志，避免把成功请求的“最后通道”污染成 FAIL。
            throw e
        } catch (e: Exception) {
            record(
                ResourceFetchStat(
                    url = url,
                    channel = attempt.label,
                    ok = false,
                    totalMs = System.currentTimeMillis() - started,
                    error = e.javaClass.simpleName + ": " + (e.message ?: ""),
                )
            )
            throw e
        } finally {
            runCatching { (connection as? HttpURLConnection)?.disconnect() }
        }
    }

    private fun writeCache(context: Context, url: String, result: FetchResult): CachedResource? {
        val target = cacheFile(context, url)
        val tmp = File(target.parentFile, target.name + ".tmp")
        return runCatching {
            FileOutputStream(tmp).use { it.write(result.bytes) }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            File(target.parentFile, target.nameWithoutExtension + ".mime").writeText(result.mime)
            trimCache(context)
            CachedResource(target, result.mime)
        }.getOrNull()
    }

    private fun trimCache(context: Context) {
        val dir = File(context.filesDir, CACHE_DIR)
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= MAX_CACHE_BYTES * 3 / 4) return
            val len = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= len
        }
    }

    private fun responseFor(resource: CachedResource): WebResourceResponse? = runCatching {
        WebResourceResponse(
            resource.mime,
            null,
            200,
            "OK",
            linkedMapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "public, max-age=86400",
                "Content-Length" to resource.file.length().toString(),
            ),
            FileInputStream(resource.file),
        )
    }.getOrNull()

    private fun mimeFromUrl(url: String): String {
        val ext = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return MIME_BY_EXT[ext] ?: "application/octet-stream"
    }

    private fun record(stat: ResourceFetchStat) {
        _recentStats.value = (listOf(stat) + _recentStats.value).take(40)
        // 缓存命中不写日志，避免 20 图面板每次滚动都刷盘；网络失败与真实请求保留诊断。
        if (stat.cacheHit) return
        TavernRuntimeManager.appendLog(
            if (stat.ok) "debug" else "warn",
            "[net] ${stat.channel} ${if (stat.ok) "OK" else "FAIL"} " +
                "ttfb=${stat.ttfbMs}ms total=${stat.totalMs}ms bytes=${stat.bytes} url=${stat.url.substringBefore('?').take(120)}" +
                if (stat.error.isBlank()) "" else " err=${stat.error}",
        )
    }
}
