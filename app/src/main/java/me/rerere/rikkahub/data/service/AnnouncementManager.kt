package me.rerere.rikkahub.data.service

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.model.Announcement
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 公告的读取与「不再提示」记录。
 *
 * ## 两级来源
 *
 *   1. **云端**（优先）：仓库里的 `announcement.json`，走 jsDelivr / ghproxy /
 *      ghfast 多级镜像。国内直连 GitHub 常常不通，镜像这一层是必需的——
 *      少了它，公告在目标用户那里等于不存在。
 *   2. **内置**（兜底）：`assets/announcement/announcement.json`。云端取不到
 *      （没网、镜像全挂、JSON 写坏了）时用它，保证任何时候都有内容可显示。
 *
 * 云端与内置的取舍不是「哪个新用哪个」，而是「谁能用用谁」：启动时先试
 * 云端，失败就退回内置。公告晚一天看到无妨，**卡住启动才是事故**。
 *
 * 请求带很短的超时（5 秒）且不重试——它是启动路径上的一步，
 * 不值得为了它让用户多等。
 */
object AnnouncementManager {

    private const val TAG = "Announcement"

    private const val PREFS = "rikkahub.announcement"

    /**
     * 已关闭的公告 id 集合。
     *
     * **必须是一组，不是一个。**
     *
     * 之前只存单个 id：用户关掉任何一条公告，就把那个值覆盖成它的 id，
     * 结果是「关掉这条 = 以后所有公告都不再显示」。用户看到的下一条
     * 公告（可能是数据安全相关的提醒）会被静默吞掉，而他并不知道
     * 自己什么时候同意过这件事。
     *
     * 用 Set<String> 的序列化形式存，读的时候容错：格式不对就当成空集合，
     * 大不了让用户多看一次公告——这个方向的错误远好过静默丢公告。
     */
    private const val KEY_DISMISSED_IDS = "dismissed_ids"

    private const val ASSET_DIR = "announcement"
    private const val ASSET_FILE = "$ASSET_DIR/announcement.json"

    /** 配图所在的 assets 前缀，供内置配图用。 */
    const val ASSET_IMAGE_PREFIX = "$ASSET_DIR/"

    /** 公告与更新检查同仓库同分支，取的是同一个 raw 文件通道。 */
    private const val REPO = "MiaoWuNYA/rikkahub-sillytavern-android"
    private const val BRANCH = "huadeng"
    private const val RAW_URL = "https://raw.githubusercontent.com/$REPO/$BRANCH/announcement.json"

    /**
     * 国内可达性排序：jsDelivr 是真 CDN，直连最稳，放第一位；
     * 后面两个是 GitHub 代理，实测支持原始文件。
     */
    /**
     * 云端公告的总时限。
     *
     * 超过它就直接用内置那份。公告晚一点看到无妨，
     * 让用户对着空白的弹窗等才是问题。
     */
    private const val REMOTE_TIMEOUT_MS = 4000L

    private val MIRRORS = listOf(
        "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/announcement.json",
        "https://ghproxy.net/$RAW_URL",
        "https://ghfast.top/$RAW_URL",
        RAW_URL,
    )

    /**
     * 公告专用客户端。
     *
     * 全局 OkHttpClient 的 readTimeout 是 10 分钟（给流式对话用的），
     * 拿它来取公告的话，网络稍微一卡，启动就被吊住十分钟。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 超时压到 3 秒。
            //
            // 公告的正文本身不到 1KB，正常网络几十毫秒就回来了。
            // 原来给 8 秒是为了「稳」，但代价是：四个镜像**串行**尝试，
            // 第一个卡住就干等 8 秒。用户点进设置，弹窗出来了、
            // 内容却迟迟不出现，观感就是「这软件好慢」。
            //
            // 宁可早点放弃去试下一个镜像，也不要在这里死等。
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 取当前要显示的公告。
     *
     * 顺序：云端 -> 内置 -> null。任何一步失败都退到下一步，
     * **永不抛异常**——公告坏了不该影响启动。
     */
    suspend fun load(context: Context): Announcement? {
        val remote = fetchRemote()
        if (remote != null) return remote
        Log.d(TAG, "Remote announcement unavailable, using bundled copy")
        return loadBundled(context)
    }

    /**
     * 云端公告：**四个镜像同时发，谁先回来用谁。**
     *
     * 原来是一个一个试（jsDelivr -> ghproxy -> ghfast -> 直连），
     * 前一个超时了才试下一个。四个镜像各自可能卡 4 秒，最坏就是十几秒——
     * 而用户看到的是弹窗里内容迟迟不出现。
     *
     * 并发竞速把这个上限压到「最快的那个镜像的耗时」。代价是多发三个
     * 不到 1KB 的请求，对用户流量和服务端都无足轻重。
     *
     * 全部失败时返回 null，调用方退回内置那份。
     */
    private suspend fun fetchRemote(): Announcement? = withContext(Dispatchers.IO) {
        val jobs = MIRRORS.map { mirror ->
            async {
                runCatching {
                    val request = Request.Builder()
                        .url(mirror)
                        .header("Accept", "application/json")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body?.string()
                    }
                }.getOrNull()
                    ?.let { raw ->
                        runCatching { JsonInstant.decodeFromString<Announcement>(raw) }.getOrNull()
                    }
                    ?.takeIf { it.isUsable() }
                    ?.also { Log.d(TAG, "Announcement served by $mirror") }
            }
        }

        try {
            // 真竞速：用 channelFlow 收「第一个成功的结果」。
            //
            // 不能写成 `jobs.mapNotNull { it.await() }`——那会按顺序等，
            // 第一个 await 卡住时后面即使早就回来了也拿不到，
            // 等于没并发。
            val winner = withTimeoutOrNull(REMOTE_TIMEOUT_MS) {
                coroutineScope {
                    val channel = kotlinx.coroutines.channels.Channel<Announcement>(1)
                    jobs.forEach { job ->
                        launch {
                            val r = runCatching { job.await() }.getOrNull()
                            if (r != null) channel.trySend(r)
                        }
                    }
                    channel.receive()
                }
            }
            winner
        } finally {
            // 拿到结果就把剩下的请求取消，别让它们后台空跑
            jobs.forEach { it.cancel() }
        }
    }

    /** 内置公告：随安装包发布，永远拿得到（除非没放）。 */
    private suspend fun loadBundled(context: Context): Announcement? =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = context.assets.open(ASSET_FILE)
                    .bufferedReader()
                    .use { it.readText() }
                JsonInstant.decodeFromString<Announcement>(raw)
            }.onFailure {
                Log.w(TAG, "Failed to load bundled announcement (treated as none)", it)
            }.getOrNull()?.takeIf { it.isUsable() }
        }

    /**
     * 一条公告是否值得显示。
     *
     * 要求 id 非空（「不再提示」按它记，没有 id 就没法记）
     * 且至少有一个可见字段。
     */
    private fun Announcement.isUsable(): Boolean =
        id.isNotBlank() && (title.isNotBlank() || body.isNotBlank())

    /**
     * 「知道了」后的静默时长。
     *
     * 不是永久关闭：用户点掉公告只是说「我现在看过了」，同一条公告
     * 一小时后再进来还是应该再见到（比如群号公告，用户第一次没扫码）。
     */
    private const val REAPPEAR_AFTER_MS = 60L * 60 * 1000

    /**
     * 读取已关闭的公告及其关闭时间。
     *
     * 存储格式：每行 `id@关闭时间戳`（毫秒）。用换行分隔而不是 JSON：
     * 内容只是若干条记录，用不着引入解析开销，而且格式坏掉时的
     * 降级行为很直观（读不出就是空集合）。
     *
     * 旧版本存的是纯 id（没有 @），那些行视为**永久关闭**——
     * 用户当时明确点过「知道了」，新逻辑只对之后新点的生效。
     */
    private fun dismissedRecords(context: Context): Map<String, Long?> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DISMISSED_IDS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.associate { line ->
                val at = line.lastIndexOf('@')
                if (at <= 0 || at == line.length - 1) line to null
                else line.substring(0, at) to line.substring(at + 1).toLongOrNull()
            }
            ?: emptyMap()

    /**
     * 该公告当前是否处于「不再提示」状态。
     *
     * **只看这一条**，不影响别的公告。关闭时间在一小时内才算还在静默期；
     * 超过一小时视为过期，公告会再次弹出。
     *
     * 时间基准是墙钟（[System.currentTimeMillis]），与手机系统时间一致：
     * 用户手动把手机时间往后调过一小时，公告同样会回来——
     * 不用开机流逝时长（elapsedRealtime），那会随重启清零且不受调时间影响，
     * 语义和「手机时间为准」的要求不符。
     */
    fun isDismissed(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        val dismissedAt = dismissedRecords(context)[id]
        // 旧格式（无时间戳）= 永久关闭
        if (dismissedAt == null) return true
        return System.currentTimeMillis() - dismissedAt < REAPPEAR_AFTER_MS
    }

    fun dismiss(context: Context, id: String) {
        if (id.isBlank()) return
        val records = dismissedRecords(context).toMutableMap()
        records[id] = System.currentTimeMillis()
        // 旧格式记录（无时间戳）保持原样写回，仍是永久关闭
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_DISMISSED_IDS, records.entries.joinToString("\n") { (k, v) ->
                    if (v == null) k else "$k@$v"
                })
            }
    }

    /**
     * 清空所有「不再提示」记录。
     *
     * 供设置页用。顺带把旧版本留下的单值键一起清掉，免得升级上来的
     * 用户还留着一条历史记录。
     */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                remove(KEY_DISMISSED_IDS)
                remove("dismissed_id") // 旧版的单值键
            }
    }

    /** 已关闭的数量，供设置页显示状态。 */
    fun dismissedCount(context: Context): Int = dismissedRecords(context).size

    /**
     * 配图对应的 assets 路径；没有配图时返回 null。
     *
     * 只对**内置**公告有效：云端公告的图如果也放 assets 里，
     * 不改安装包是换不了的，那就失去了云端的意义。
     * 云端要配图请用 [remoteImageUrl]。
     */
    fun imageAssetPath(announcement: Announcement): String? {
        val name = announcement.image?.takeIf { it.isNotBlank() } ?: return null
        // 已经是完整 URL 的，说明是云端图，不走 assets
        if (name.startsWith("http")) return null
        return ASSET_IMAGE_PREFIX + name
    }

    /** 云端配图的完整地址；不是 URL 时返回 null。 */
    fun remoteImageUrl(announcement: Announcement): String? =
        announcement.image?.takeIf { it.startsWith("http") }
}

/**
 * 公告正文。
 *
 * 放在这里而不是 assets：云端下发的就是这个结构，
 * 内置那份只是它的一份快照，两边字段必须一致。
 */
@Serializable
data class AnnouncementPayload(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val image: String? = null,
)
