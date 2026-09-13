package me.rerere.rikkahub.data.ai.tools.device

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.MediaStore
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.RouteActivity

private fun getAppName(context: Context, packageName: String): String = try {
    context.packageManager.getApplicationLabel(
        context.packageManager.getApplicationInfo(packageName, 0)
    ).toString()
} catch (_: PackageManager.NameNotFoundException) {
    packageName
}

/**
 * 音乐控制。
 * play_search 走 PLAY_FROM_SEARCH Intent，无需额外权限；
 * 会话级控制（播放/暂停/切歌等）依赖媒体会话服务，未授权时返回明确错误。
 */
internal fun buildMusicTool(context: Context): Tool = Tool(
    name = "control_music",
    description = "Control music playback. Actions: 'play_search' (search and play music in installed music apps, no special permission), " +
        "'get_now_playing'/'play'/'pause'/'next'/'previous'/'seek' (control the active media session, requires media session access).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Action to perform: get_now_playing, play, pause, next, previous, seek, play_search")
                    put("enum", buildJsonArray {
                        add("get_now_playing"); add("play"); add("pause"); add("next")
                        add("previous"); add("seek"); add("play_search")
                    })
                })
                put("position_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Position in milliseconds to seek to. Required for 'seek' action.")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search query for 'play_search' action, e.g. song name or artist")
                })
                put("artist", buildJsonObject {
                    put("type", "string")
                    put("description", "Artist name for 'play_search' action (optional)")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Song title for 'play_search' action (optional)")
                })
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: ""
        try {
            if (action == "play_search") {
                val query = params["query"]?.jsonPrimitive?.contentOrNull ?: ""
                val artist = params["artist"]?.jsonPrimitive?.contentOrNull ?: ""
                val title = params["title"]?.jsonPrimitive?.contentOrNull ?: ""
                if (query.isBlank() && artist.isBlank() && title.isBlank()) {
                    return@Tool listOf(UIMessagePart.Text(deviceError(
                        "At least one of 'query', 'artist', or 'title' must be provided for play_search"
                    ).toString()))
                }
                val searchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(
                        MediaStore.EXTRA_MEDIA_FOCUS,
                        if (artist.isNotBlank() || title.isNotBlank()) MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
                        else MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
                    )
                    putExtra(SearchManager.QUERY, query)
                    if (artist.isNotBlank()) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                    if (title.isNotBlank()) putExtra(MediaStore.EXTRA_MEDIA_TITLE, title)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(searchIntent)
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("action", "play_search")
                        put("message", "Sent play search request: " +
                            (if (query.isNotBlank()) query else "$artist - $title"))
                    }.toString()
                ))
            }

            // 会话级控制需要 MediaSessionManager，而它要求本应用拥有通知监听权限
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("MediaSessionManager unavailable").toString()))
            // 用本应用自身的组件名占位；未授予通知监听权限时 getActiveSessions 会抛 SecurityException
            val controllers = try {
                // 用类引用而不是字符串字面量：重命名包/类时编译器能兜住，字符串则静默失效
                msm.getActiveSessions(ComponentName(context, RouteActivity::class.java))
            } catch (_: Exception) {
                return@Tool listOf(UIMessagePart.Text(deviceError(
                    "Media session access not granted. Enable Notification access for this app in system settings to control playback.",
                    "needs_permission" to "NOTIFICATION_LISTENER",
                ).toString()))
            }
            if (controllers.isNullOrEmpty()) {
                return@Tool listOf(UIMessagePart.Text(deviceError("No active media sessions found. Please play music first.").toString()))
            }
            val controller = controllers.firstOrNull { it.playbackState != null } ?: controllers.firstOrNull()
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("No active media controller found").toString()))
            val controls = controller.transportControls
            when (action) {
                "get_now_playing" -> {
                    val metadata = controller.metadata
                    val state = controller.playbackState
                    listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("title", metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown")
                            put("artist", metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown")
                            put("is_playing", state?.state == PlaybackState.STATE_PLAYING)
                            put("position_ms", state?.position ?: -1L)
                            put("duration_ms", metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: -1L)
                            put("app_name", getAppName(context, controller.packageName))
                        }.toString()
                    ))
                }
                "play" -> controls.play()
                "pause" -> controls.pause()
                "next" -> controls.skipToNext()
                "previous" -> controls.skipToPrevious()
                "seek" -> {
                    val positionMs = params["position_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: return@Tool listOf(UIMessagePart.Text(deviceError(
                            "Missing required parameter 'position_ms' for seek action"
                        ).toString()))
                    controls.seekTo(positionMs)
                }
                else -> return@Tool listOf(UIMessagePart.Text(deviceError(
                    "Unknown action: $action. Supported: get_now_playing, play, pause, next, previous, seek, play_search"
                ).toString()))
            }
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("action", action)
                    put("app_name", getAppName(context, controller.packageName))
                    put("message", "Successfully sent $action command to ${getAppName(context, controller.packageName)}")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
