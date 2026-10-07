package me.rerere.rikkahub.data.service

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Announcement
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

/**
 * 公告的读取与「不再提示」记录。
 *
 * 内容来自 assets/announcement/announcement.json，随安装包发布，
 * **不做任何网络请求**——目标用户里国内网络访问不了 GitHub 是常态，
 * 一个依赖远端拉取的公告系统在他们那儿等于不存在，还会拖慢启动。
 *
 * 「不再提示」记在 SharedPreferences 里而不是 DataStore：份量只有
 * 一个 id 字符串，且要在应用启动早期就能同步读到，不需要 DataStore
 * 的事务和响应式能力。
 */
object AnnouncementManager {

    private const val TAG = "Announcement"

    private const val PREFS = "rikkahub.announcement"
    private const val KEY_DISMISSED_ID = "dismissed_id"

    private const val ASSET_DIR = "announcement"
    private const val ASSET_FILE = "$ASSET_DIR/announcement.json"

    /** 配图所在的 assets 前缀，供 UI 组装路径用。 */
    const val ASSET_IMAGE_PREFIX = "$ASSET_DIR/"

    /**
     * 读当前公告。
     *
     * 返回 null 的情况都当「没有公告」处理：文件不存在、解析失败、
     * id 为空。**公告坏了不该影响启动**，所以这里全部 runCatching。
     */
    suspend fun load(context: Context): Announcement? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = context.assets.open(ASSET_FILE)
                .bufferedReader()
                .use { it.readText() }
            val parsed = JsonInstant.decodeFromString<Announcement>(raw)
            parsed.takeIf { it.id.isNotBlank() && (it.title.isNotBlank() || it.body.isNotBlank()) }
        }.onFailure {
            // 打日志但不抛：没有公告是一回事，把启动搞崩是另一回事
            Log.w(TAG, "Failed to load announcement (treated as none)", it)
        }.getOrNull()
    }

    /** 该公告是否已被用户「不再提示」。 */
    fun isDismissed(context: Context, id: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DISMISSED_ID, null) == id

    fun dismiss(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_DISMISSED_ID, id) }
    }

    /** 供设置页「重新显示公告」用。 */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { remove(KEY_DISMISSED_ID) }
    }

    /**
     * 配图对应的 assets 路径；没有配图时返回 null。
     *
     * 图片放在 assets 里，需要用 BitmapFactory 从流里解，
     * 不能当普通文件路径用。
     */
    fun imageAssetPath(announcement: Announcement): String? {
        val name = announcement.image?.takeIf { it.isNotBlank() } ?: return null
        return ASSET_IMAGE_PREFIX + name
    }

    /** 配图是否真的存在。缺图时不该显示一块空白。 */
    fun imageExists(context: Context, path: String): Boolean =
        runCatching {
            context.assets.open(path).use { it.read() }
            true
        }.getOrDefault(false)
}
