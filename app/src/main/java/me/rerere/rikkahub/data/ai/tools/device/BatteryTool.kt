package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** 电池状态（ACTION_BATTERY_CHANGED 粘性广播） */
internal fun buildBatteryTool(context: Context): Tool = Tool(
    name = "get_battery_info",
    description = "Get the current battery status: level, charging status, charge type, temperature, and health.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        try {
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("Unable to get battery information").toString()))

            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

            val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            val chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val chargeType = when (chargePlug) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> if (isCharging) "Unknown" else "Not charging"
            }

            val temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            val temperatureCelsius = if (temperature >= 0) temperature / 10.0 else -1.0

            val health = when (batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified Failure"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                else -> "Unknown"
            }

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("level", batteryPct)
                    put("is_charging", isCharging)
                    put("charge_type", chargeType)
                    put("temperature_celsius", temperatureCelsius)
                    put("health", health)
                    put("message", "Battery: $batteryPct%, " +
                        (if (isCharging) "Charging via $chargeType" else "Not charging") +
                        ", Temperature: ${temperatureCelsius}°C, Health: $health")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
