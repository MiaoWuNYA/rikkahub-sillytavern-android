package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.provider.Settings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** 读取屏幕亮度 */
internal fun buildGetBrightnessTool(context: Context): Tool = Tool(
    name = "get_brightness",
    description = "Get the current screen brightness level (0-255) and whether auto-brightness is enabled.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        try {
            val cr = context.contentResolver
            val brightness = try {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Exception) {
                128
            }
            val autoBrightness = try {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } catch (_: Exception) {
                false
            }
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("brightness", brightness)
                    put("max_brightness", 255)
                    put("auto_brightness", autoBrightness)
                    put("message", "Brightness: $brightness/255, Auto: $autoBrightness")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)

/** 设置屏幕亮度（需要 WRITE_SETTINGS 特殊权限，写前检查 canWrite） */
internal fun buildSetBrightnessTool(context: Context): Tool = Tool(
    name = "set_brightness",
    description = "Set the screen brightness (1-255). Requires WRITE_SETTINGS special permission; disables auto-brightness first.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("value", buildJsonObject {
                    put("type", "integer")
                    put("description", "Brightness value (1-255). Values are clamped to the valid range.")
                })
            },
            required = listOf("value")
        )
    },
    execute = { args ->
        val value = args.jsonObject["value"]?.jsonPrimitive?.intOrNull
        if (value == null) {
            return@Tool listOf(UIMessagePart.Text(deviceError("Missing required parameter 'value' (integer)").toString()))
        }
        try {
            if (!Settings.System.canWrite(context)) {
                return@Tool listOf(UIMessagePart.Text(deviceError(
                    "WRITE_SETTINGS permission not granted",
                    "needs_permission" to "WRITE_SETTINGS",
                    "hint" to "Go to system Settings > Apps > this app > Modify system settings to grant it",
                ).toString()))
            }
            val clampedValue = value.coerceIn(1, 255)
            val cr = context.contentResolver
            try {
                Settings.System.putInt(
                    cr,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
            } catch (_: Exception) {
                // 无法切到手动模式时仍尝试直接写亮度
            }
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, clampedValue)
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("brightness", clampedValue)
                    put("auto_brightness", false)
                    put("message", "Brightness set to $clampedValue/255, auto-brightness disabled")
                }.toString()
            ))
        } catch (e: SecurityException) {
            listOf(UIMessagePart.Text(deviceError(
                "WRITE_SETTINGS not granted: ${e.message}",
                "needs_permission" to "WRITE_SETTINGS",
            ).toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
