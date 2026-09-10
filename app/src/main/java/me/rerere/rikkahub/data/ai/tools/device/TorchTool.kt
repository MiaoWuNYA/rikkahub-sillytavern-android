package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** 手电筒开关（CameraManager.setTorchMode） */
internal fun buildTorchTool(context: Context): Tool = Tool(
    name = "set_torch",
    description = "Turn the device flashlight/torch on or off.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("on", buildJsonObject {
                    put("type", "boolean")
                    put("description", "True to turn torch on, false to turn off.")
                })
            },
            required = listOf("on")
        )
    },
    execute = { args ->
        val on = args.jsonObject["on"]?.jsonPrimitive?.booleanOrNull
        if (on == null) {
            return@Tool listOf(UIMessagePart.Text(deviceError("Missing or invalid required parameter 'on' (boolean)").toString()))
        }
        try {
            val cameraManager = context.getSystemService(CameraManager::class.java)
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("CameraManager unavailable").toString()))
            val flashId = cameraManager.cameraIdList.firstOrNull { id ->
                try {
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } catch (_: Exception) {
                    false
                }
            }
            if (flashId == null) {
                return@Tool listOf(UIMessagePart.Text(deviceError("No flashlight available on this device").toString()))
            }
            cameraManager.setTorchMode(flashId, on)
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("torch_on", on)
                    put("message", "Torch ${if (on) "turned on" else "turned off"}")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Failed to set torch").toString()))
        }
    }
)
