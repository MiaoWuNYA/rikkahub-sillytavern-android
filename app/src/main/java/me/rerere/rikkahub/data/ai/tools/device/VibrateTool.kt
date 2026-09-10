package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** 震动（Vibrator），支持单次时长或波形模式 */
internal fun buildVibrateTool(context: Context): Tool = Tool(
    name = "vibrate",
    description = "Vibrate the device. Provide either duration_ms (single vibration) or pattern (waveform of alternating off/on milliseconds).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Duration of vibration in milliseconds (1-5000). Default 500. Use this for a single vibration.")
                })
                put("pattern", buildJsonObject {
                    put("type", "array")
                    put("description", "Waveform pattern of alternating off/on durations in ms (e.g. [0,500,200,500]). Max 20 elements. Mutually exclusive with duration_ms.")
                    put("items", buildJsonObject { put("type", "integer") })
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val durationMs = params["duration_ms"]?.jsonPrimitive?.intOrNull
        val patternArray = params["pattern"] as? JsonArray

        if (durationMs != null && patternArray != null) {
            return@Tool listOf(UIMessagePart.Text(deviceError("provide either duration_ms or pattern, not both").toString()))
        }
        try {
            val vibrator = context.getSystemService(Vibrator::class.java)
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("Vibrator service unavailable").toString()))
            if (!vibrator.hasVibrator()) {
                return@Tool listOf(UIMessagePart.Text(deviceError("Device has no vibrator").toString()))
            }
            if (patternArray != null) {
                if (patternArray.isEmpty() || patternArray.size > 20) {
                    return@Tool listOf(UIMessagePart.Text(deviceError("pattern array must contain 1-20 elements").toString()))
                }
                val timings = patternArray.mapNotNull { it.jsonPrimitive.intOrNull?.toLong() }
                if (timings.size != patternArray.size) {
                    return@Tool listOf(UIMessagePart.Text(deviceError("pattern array contains non-integer values").toString()))
                }
                vibrator.vibrate(VibrationEffect.createWaveform(timings.toLongArray(), -1))
                listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("mode", "pattern")
                        put("timings", JsonArray(timings.map { JsonPrimitive(it) }))
                        put("message", "Vibration pattern started")
                    }.toString()
                ))
            } else {
                val ms = (durationMs ?: 500).coerceIn(1, 5000)
                vibrator.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
                listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("mode", "oneshot")
                        put("duration_ms", ms)
                        put("message", "Vibrated for ${ms}ms")
                    }.toString()
                ))
            }
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Vibration failed").toString()))
        }
    }
)
