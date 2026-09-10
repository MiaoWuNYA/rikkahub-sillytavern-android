package me.rerere.rikkahub.ui.pages.voice

import android.content.Context
import java.io.File

enum class VideoCallSelfViewMode {
    USER_AVATAR,
    FRONT_CAMERA,
}

data class VideoCallVisualSettings(
    val userAvatarPath: String = "",
    val backgroundPath: String = "",
    val backgroundOpacity: Float = 0.85f,
    val selfViewMode: VideoCallSelfViewMode = VideoCallSelfViewMode.USER_AVATAR,
)

class VideoCallVisualSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): VideoCallVisualSettings {
        val mode = runCatching {
            VideoCallSelfViewMode.valueOf(
                prefs.getString(KEY_SELF_VIEW_MODE, VideoCallSelfViewMode.USER_AVATAR.name)
                    ?: VideoCallSelfViewMode.USER_AVATAR.name
            )
        }.getOrDefault(VideoCallSelfViewMode.USER_AVATAR)

        return VideoCallVisualSettings(
            userAvatarPath = prefs.getString(KEY_USER_AVATAR_PATH, "").orEmpty(),
            backgroundPath = prefs.getString(KEY_BACKGROUND_PATH, "").orEmpty(),
            backgroundOpacity = prefs.getFloat(KEY_BACKGROUND_OPACITY, 0.85f).coerceIn(0.2f, 1f),
            selfViewMode = mode,
        )
    }

    fun write(settings: VideoCallVisualSettings) {
        prefs.edit()
            .putString(KEY_USER_AVATAR_PATH, settings.userAvatarPath)
            .putString(KEY_BACKGROUND_PATH, settings.backgroundPath)
            .putFloat(KEY_BACKGROUND_OPACITY, settings.backgroundOpacity.coerceIn(0.2f, 1f))
            .putString(KEY_SELF_VIEW_MODE, settings.selfViewMode.name)
            .apply()
    }

    fun assetsDir(): File = File(appContext.filesDir, "video_call_assets").apply { mkdirs() }

    companion object {
        private const val PREFS_NAME = "tumin_video_call_visual_settings"
        private const val KEY_USER_AVATAR_PATH = "user_avatar_path"
        private const val KEY_BACKGROUND_PATH = "background_path"
        private const val KEY_BACKGROUND_OPACITY = "background_opacity"
        private const val KEY_SELF_VIEW_MODE = "self_view_mode"
    }
}
