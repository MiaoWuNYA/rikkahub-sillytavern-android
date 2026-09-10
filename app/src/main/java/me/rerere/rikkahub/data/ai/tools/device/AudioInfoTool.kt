package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun audioDeviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "builtin_earpiece"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
    AudioDeviceInfo.TYPE_HDMI -> "hdmi"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb_accessory"
    AudioDeviceInfo.TYPE_DOCK -> "dock"
    AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "line_analog"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line_digital"
    AudioDeviceInfo.TYPE_AUX_LINE -> "aux_line"
    AudioDeviceInfo.TYPE_IP -> "ip"
    AudioDeviceInfo.TYPE_BUS -> "bus"
    AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "builtin_speaker_safe"
    else -> "unknown"
}

/** 音频状态：响铃模式、是否在放音乐、耳机连接情况 */
internal fun buildAudioInfoTool(context: Context): Tool = Tool(
    name = "get_audio_info",
    description = "Get the device's current audio state: ringer mode, whether music is playing, and whether wired/Bluetooth headphones are connected.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val am = context.getSystemService(AudioManager::class.java)
        val payload = if (am == null) {
            deviceError("AudioManager unavailable")
        } else {
            val ringer = when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                else -> "unknown"
            }
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val headphoneTypes = setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
            )
            buildJsonObject {
                put("success", true)
                put("ringer_mode", ringer)
                put("music_active", am.isMusicActive)
                put("headphones_connected", outputs.any { it.type in headphoneTypes })
                put("output_devices", buildJsonArray {
                    outputs.forEach { dev ->
                        addJsonObject {
                            put("type_name", audioDeviceTypeName(dev.type))
                            put("product_name", dev.productName?.toString() ?: "")
                        }
                    }
                })
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
