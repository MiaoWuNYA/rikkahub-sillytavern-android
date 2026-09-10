package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun alarmIntentHandled(context: Context, intent: Intent): Boolean =
    context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()

/** 通过系统时钟应用设置闹钟（AlarmClock Intent，需 SET_ALARM 权限） */
internal fun buildSetAlarmTool(context: Context): Tool = Tool(
    name = "set_alarm",
    description = "Set an alarm on the user's device through the system clock app.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("hour", buildJsonObject {
                    put("type", "integer")
                    put("description", "Hour in 24-hour format (0-23).")
                })
                put("minute", buildJsonObject {
                    put("type", "integer")
                    put("description", "Minute (0-59).")
                })
                put("label", buildJsonObject {
                    put("type", "string")
                    put("description", "A label/name for the alarm (optional)")
                })
            },
            required = listOf("hour", "minute")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val hour = params["hour"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val minute = params["minute"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val label = params["label"]?.jsonPrimitive?.contentOrNull ?: ""
        if (hour == null || minute == null) {
            return@Tool listOf(UIMessagePart.Text(deviceError("Missing required parameters: hour and minute").toString()))
        }
        if (hour !in 0..23 || minute !in 0..59) {
            return@Tool listOf(UIMessagePart.Text(deviceError("Invalid time: hour must be 0-23, minute must be 0-59").toString()))
        }
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!alarmIntentHandled(context, intent)) {
                return@Tool listOf(UIMessagePart.Text(deviceError("No clock app found that supports setting alarms").toString()))
            }
            context.startActivity(intent)
            val time = String.format("%02d:%02d", hour, minute)
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("alarm_time", time)
                    put("label", label)
                    put("message", "Alarm set for $time" + if (label.isNotBlank()) " ($label)" else "")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Failed to set alarm").toString()))
        }
    }
)

/** 通过系统时钟应用设置倒计时（AlarmClock Intent，需 SET_ALARM 权限） */
internal fun buildSetTimerTool(context: Context): Tool = Tool(
    name = "set_timer",
    description = "Set a countdown timer on the user's device through the system clock app. Useful for reminders like 'remind me in 10 minutes'.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timer duration in seconds. For example, 300 for 5 minutes. Must be positive.")
                })
                put("label", buildJsonObject {
                    put("type", "string")
                    put("description", "A label/name for the timer (optional)")
                })
            },
            required = listOf("seconds")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val seconds = params["seconds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val label = params["label"]?.jsonPrimitive?.contentOrNull ?: ""
        if (seconds == null) {
            return@Tool listOf(UIMessagePart.Text(deviceError("Missing required parameter: seconds").toString()))
        }
        if (seconds <= 0) {
            return@Tool listOf(UIMessagePart.Text(deviceError("Timer duration must be positive (seconds > 0)").toString()))
        }
        try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!alarmIntentHandled(context, intent)) {
                return@Tool listOf(UIMessagePart.Text(deviceError("No clock app found that supports setting timers").toString()))
            }
            context.startActivity(intent)
            val minutes = seconds / 60
            val remaining = seconds % 60
            val display = when {
                minutes > 0 && remaining > 0 -> "${minutes}m ${remaining}s"
                minutes > 0 -> "${minutes}m"
                else -> "${remaining}s"
            }
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("timer_seconds", seconds)
                    put("timer_display", display)
                    put("label", label)
                    put("message", "Timer set for $display" + if (label.isNotBlank()) " ($label)" else "")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Failed to set timer").toString()))
        }
    }
)
