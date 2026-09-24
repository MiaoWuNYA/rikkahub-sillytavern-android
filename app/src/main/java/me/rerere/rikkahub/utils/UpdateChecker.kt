package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 本定制版的仓库。GitHub Release 是权威来源，仓库根目录的 update.json 是兜底快照。 */
private const val REPO = "MiaoWuNYA/rikkahub-sillytavern-android"
private const val BRANCH = "huadeng"

private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
private const val JSON_URL = "https://raw.githubusercontent.com/$REPO/$BRANCH/update.json"

/**
 * 兜底源，按国内可达性排序。
 *
 * 注意 GitHub API 没有可用镜像：实测 ghproxy.net / ghfast.top / api.kkgithub.com 之流
 * 只代理原始文件与发布资源，拿它们套 API 地址一律 403。所以兜底不能指望 API，
 * 只能读仓库里的 update.json —— jsDelivr 是真 CDN，国内直连最稳，放第一位。
 */
private val JSON_MIRRORS = listOf(
    "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/update.json",
    "https://ghproxy.net/$JSON_URL",
    "https://ghfast.top/$JSON_URL",
    JSON_URL,
)

/**
 * 资源代理前缀。GitHub 的 releases/download 地址在国内直连不稳，
 * 这些代理实测可用（都支持 Range 请求，DownloadManager 能正常断点续传）。
 */
private val ASSET_MIRRORS = listOf(
    "https://ghproxy.net/",
    "https://ghfast.top/",
)

class UpdateChecker(
    private val client: OkHttpClient,
    private val appScope: AppScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 更新检查专用客户端：全局 OkHttpClient 的 readTimeout 是 10 分钟（给流式对话用的），
     * 拿它探路会在被墙的源上挂很久。这里压到 15 秒，让兜底能快速接力。
     */
    private val probeClient = client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    val updateState: StateFlow<UiState<UpdateInfo>> = checkUpdate().stateIn(
        scope = appScope,
        started = SharingStarted.Lazily,
        initialValue = UiState.Loading,
    )

    private fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(UiState.Success(fetchLatest()))
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    /** 依次尝试所有源，第一个成功的就用；全都失败时抛出最后一个错误。 */
    private suspend fun fetchLatest(): UpdateInfo {
        var lastError: Throwable? = null
        runCatching { fetchFromGitHubApi() }
            .onSuccess { return it }
            .onFailure { lastError = it }
        for (mirror in JSON_MIRRORS) {
            runCatching { fetchFromJson(mirror) }
                .onSuccess { return it }
                .onFailure { lastError = it }
        }
        throw lastError ?: IllegalStateException("Failed to fetch update info")
    }

    /**
     * 主源：GitHub Releases API。
     *
     * 拿到的是权威数据（tag_name / body / assets），但代价是共享出口 IP 容易被限流
     * ——403 时上层会静默切到 update.json 兜底。
     */
    private suspend fun fetchFromGitHubApi(): UpdateInfo {
        val response = probeClient.newCall(
            Request.Builder()
                .url(API_URL)
                .get()
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", userAgent())
                .build()
        ).await()
        if (!response.isSuccessful) {
            throw IllegalStateException("GitHub API responded ${response.code}")
        }
        val release = json.decodeFromString<GhRelease>(response.body.string())
        // 缺 tag_name 说明这不是一个正常的 release 响应，交给兜底源
        val tag = release.tagName?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GitHub API returned no tag_name")
        // API 拿到了，说明 GitHub 直连可用，资源也优先走直连，代理留作兜底。
        return UpdateInfo(
            version = tag.removePrefix("v"),
            publishedAt = release.publishedAt.orEmpty(),
            changelog = release.body.orEmpty(),
            downloads = release.assets.map { asset ->
                UpdateDownload(
                    name = asset.name,
                    url = asset.browserDownloadUrl,
                    size = formatSize(asset.size),
                    fallbackUrl = withAssetMirror(asset.browserDownloadUrl),
                )
            },
        )
    }

    /**
     * 兜底源：仓库里的 update.json。
     *
     * 能读到它就说明 GitHub API 这条路不通（被墙或限流），所以资源也一并改走代理。
     */
    private suspend fun fetchFromJson(url: String): UpdateInfo {
        val response = probeClient.newCall(
            Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", userAgent())
                .build()
        ).await()
        if (!response.isSuccessful) {
            throw IllegalStateException("$url responded ${response.code}")
        }
        val info = json.decodeFromString<UpdateInfo>(response.body.string())
        return info.copy(
            downloads = info.downloads.map { download ->
                download.copy(
                    url = withAssetMirror(download.url) ?: download.url,
                    fallbackUrl = download.url,
                )
            }
        )
    }

    private fun userAgent(): String =
        "RikkaHub ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"

    /** 给 GitHub 资源地址套一个代理前缀；不是 GitHub 地址就原样返回 null。 */
    private fun withAssetMirror(url: String): String? {
        if (!url.startsWith("https://github.com/")) return null
        return ASSET_MIRRORS.firstOrNull()?.plus(url)
    }

    /**
     * 下载更新包。
     *
     * 不能直接 enqueue 就完事——DownloadManager 对不可达的地址照样返回成功，会把
     * 100MB 的下载丢进队列里挂着，用户只看到永远不动的进度条。所以先用 HEAD 探一下
     * 哪条路通，探通了再交给 DownloadManager（保留断点续传和通知栏进度）。
     */
    fun downloadUpdate(context: Context, download: UpdateDownload) {
        // AppScope 跑在主线程，探路必须挪到 IO，否则直接 NetworkOnMainThreadException
        appScope.launch(Dispatchers.IO) {
            val candidates = listOfNotNull(download.url, download.fallbackUrl).distinct()
            val reachable = candidates.firstOrNull { probe(it) } ?: candidates.firstOrNull()
            withContext(Dispatchers.Main) {
                if (reachable == null) {
                    Toast.makeText(context, "Failed to update", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                runCatching { enqueue(context, download.name, reachable) }
                    .onFailure {
                        Toast.makeText(context, "Failed to update", Toast.LENGTH_SHORT).show()
                        context.openUrl(reachable) // 跳转到下载页面
                    }
            }
        }
    }

    /** 探路：只发 HEAD，拿到任意 2xx/3xx/206 就算这条链路可用。 */
    private suspend fun probe(url: String): Boolean = runCatching {
        probeClient.newCall(
            Request.Builder().url(url).head().addHeader("User-Agent", userAgent()).build()
        ).await().use { it.isSuccessful || it.code == 206 }
    }.getOrDefault(false)

    private fun enqueue(context: Context, name: String, url: String) {
        val request = DownloadManager.Request(url.toUri()).apply {
            // 设置下载时通知栏的标题和描述
            setTitle(name)
            setDescription("正在下载更新包...")
            // 下载完成后通知栏可见
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 允许在移动网络和WiFi下下载
            setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )
            // 设置文件保存路径
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            // 允许下载的文件类型
            setMimeType("application/vnd.android.package-archive")
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String,
    /**
     * 备用地址（直连被墙时走代理）。不参与 update.json 的书写，只由 UpdateChecker
     * 在解析时补上，所以老格式的 update.json 照样能读。
     */
    val fallbackUrl: String? = null,
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload> = emptyList(),
)

/** GitHub Releases API 的响应，只取用得上的字段。字段可空是因为 API 不保证都给。 */
@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L,
)

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(Locale.US, bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.0f MB".format(Locale.US, bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 版本号，支持本项目自己的 `2.5.4.13` 四段命名（历史上曾是 `2.5.4fixN`）。
 *
 * 上游那套纯 SemVer 解析器处理不了历史 `fixN` 后缀：`split(".")` 之后拿到的 patch 段是
 * `4fix9`，`toIntOrNull()` 返回 null 被当成 0，于是 2.5.4fix9 和 2.5.4 判成相等，
 * 更新提示直接不出现。这里把 `fixN` 后缀单独解析成一个补丁序号参与比较：
 * 2.5.4 < 2.5.4fix1 < 2.5.4.9 < 2.5.4.10 < 2.5.5。
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        val a = parse(value)
        val b = parse(other.value)

        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val x = a.core.getOrElse(i) { 0 }
            val y = b.core.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }

        // 同一个基础版本下，打过补丁的比原版新；都没补丁时按 -1 与 -1 相等
        val af = a.fix ?: -1
        val bf = b.fix ?: -1
        if (af != bf) return af.compareTo(bf)

        return comparePrerelease(a.prerelease, b.prerelease)
    }

    companion object {
        fun compare(version1: String, version2: String): Int =
            Version(version1).compareTo(Version(version2))

        /**
         * 预发布标识符比较：有预发布标识的低于没有的（1.0.0-alpha < 1.0.0），
         * 逐段比较时数字按数值、字符串按字典序，数字段优先级低于字符串段。
         */
        private fun comparePrerelease(a: List<String>?, b: List<String>?): Int = when {
            a == null && b == null -> 0
            a != null && b == null -> -1
            a == null && b != null -> 1
            else -> {
                val x = a!!
                val y = b!!
                var result = 0
                val maxLen = maxOf(x.size, y.size)
                for (i in 0 until maxLen) {
                    // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                    if (i >= x.size) { result = -1; break }
                    if (i >= y.size) { result = 1; break }
                    val xn = x[i].toIntOrNull()
                    val yn = y[i].toIntOrNull()
                    val cmp = when {
                        xn != null && yn != null -> xn.compareTo(yn)
                        xn != null -> -1
                        yn != null -> 1
                        else -> x[i].compareTo(y[i])
                    }
                    if (cmp != 0) { result = cmp; break }
                }
                result
            }
        }

        private val VERSION_REGEX = Regex(
            """^(\d+(?:\.\d+)*)(?:[-._]?fix(\d+))?(?:-([0-9A-Za-z.]+))?$""",
            RegexOption.IGNORE_CASE,
        )

        private fun parse(raw: String): ParsedVersion {
            val cleaned = raw.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('+')
            val match = VERSION_REGEX.matchEntire(cleaned)
                ?: return ParsedVersion(
                    core = cleaned.split('.').mapNotNull { it.toIntOrNull() },
                    fix = null,
                    prerelease = null,
                )
            return ParsedVersion(
                core = match.groupValues[1].split('.').mapNotNull { it.toIntOrNull() },
                fix = match.groupValues[2].toIntOrNull(),
                prerelease = match.groupValues[3].takeIf { it.isNotEmpty() }?.split('.'),
            )
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val fix: Int?,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
