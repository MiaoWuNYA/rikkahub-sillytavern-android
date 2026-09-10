package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.media.AudioManager
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private val STREAM_MAP = mapOf(
    "media" to AudioManager.STREAM_MUSIC,
    "ring" to AudioManager.STREAM_RING,
    "notification" to AudioManager.STREAM_NOTIFICATION,
    "alarm" to AudioManager.STREAM_ALARM,
    "voice_call" to AudioManager.STREAM_VOICE_CALL,
    "system" to AudioManager.STREAM_SYSTEM,
)

private val STREAM_NAMES = listOf("media", "ring", "notification", "alarm", "voice_call", "system")

private fun streamSchemaDescription(): String =
    "Audio stream name: media, ring, notification, alarm, voice_call, system. Default: media"

/** 读取指定音频流的音量 */
internal fun buildGetVolumeTool(context: Context): Tool = Tool(
    name = "get_volume",
    description = "Get the current volume level for a given audio stream (media/ring/notification/alarm/voice_call/system). Returns volume, max, and percentage.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("stream", buildJsonObject {
                    put("type", "string")
                    put("description", streamSchemaDescription())
                    put("enum", buildJsonArray { STREAM_NAMES.forEach { add(it) } })
                })
            }
        )
    },
    execute = { args ->
        val streamName = args.jsonObject["stream"]?.jsonPrimitive?.contentOrNull ?: "media"
        val streamType = STREAM_MAP[streamName]
            ?: return@Tool listOf(UIMessagePart.Text(deviceError("unknown stream: $streamName").toString()))
        try {
            val am = context.getSystemService(AudioManager::class.java)
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("AudioManager unavailable").toString()))
            val vol = am.getStreamVolume(streamType)
            val max = am.getStreamMaxVolume(streamType)
            val percent = if (max > 0) (vol * 100 / max) else 0
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("stream", streamName)
                    put("volume", vol)
                    put("max", max)
                    put("percent", percent)
                    put("message", "$streamName volume: $vol/$max ($percent%)")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)

/** 按百分比设置指定音频流的音量 */
internal fun buildSetVolumeTool(context: Context): Tool = Tool(
    name = "set_volume",
    description = "Set the volume for a given audio stream by percentage (0-100). Changing ring/notification volume may require Do Not Disturb access.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("stream", buildJsonObject {
                    put("type", "string")
                    put("description", "Audio stream name: media, ring, notification, alarm, voice_call, system")
                    put("enum", buildJsonArray { STREAM_NAMES.forEach { add(it) } })
                })
                put("percent", buildJsonObject {
                    put("type", "integer")
                    put("description", "Volume percentage (0-100). Will be clamped to valid range.")
                })
            },
            required = listOf("stream", "percent")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val streamName = params["stream"]?.jsonPrimitive?.contentOrNull
        val percent = params["percent"]?.jsonPrimitive?.intOrNull
        if (streamName == null || percent == null) {
            return@Tool listOf(UIMessagePart.Text(deviceError("Missing required parameters 'stream' and 'percent'").toString()))
        }
        val streamType = STREAM_MAP[streamName]
            ?: return@Tool listOf(UIMessagePart.Text(deviceError("unknown stream: $streamName").toString()))
        try {
            val am = context.getSystemService(AudioManager::class.java)
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("AudioManager unavailable").toString()))
            val max = am.getStreamMaxVolume(streamType)
            val targetVol = ((percent.coerceIn(0, 100) / 100.0) * max).toInt().coerceIn(0, max)
            am.setStreamVolume(streamType, targetVol, 0)
            val actualVol = am.getStreamVolume(streamType)
            val actualPercent = if (max > 0) (actualVol * 100 / max) else 0
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("stream", streamName)
                    put("volume", actualVol)
                    put("max", max)
                    put("percent", actualPercent)
                    put("message", "$streamName volume set to $actualVol/$max ($actualPercent%)")
                }.toString()
            ))
        } catch (e: SecurityException) {
            val hint = if (streamName == "ring" || streamName == "notification") {
                "Changing $streamName volume requires Do Not Disturb access (Settings > Do Not Disturb)."
            } else null
            listOf(UIMessagePart.Text(deviceError("SecurityException: ${e.message}", *(listOfNotNull(
                if (hint != null) "hint" to hint else null
            ).toTypedArray())).toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
