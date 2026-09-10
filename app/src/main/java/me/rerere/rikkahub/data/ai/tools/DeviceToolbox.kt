package me.rerere.rikkahub.data.ai.tools

import android.Manifest
import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import me.rerere.rikkahub.data.ai.tools.device.buildAudioInfoTool
import me.rerere.rikkahub.data.ai.tools.device.buildBatteryTool
import me.rerere.rikkahub.data.ai.tools.device.buildGetBrightnessTool
import me.rerere.rikkahub.data.ai.tools.device.buildGetVolumeTool
import me.rerere.rikkahub.data.ai.tools.device.buildListContactsTool
import me.rerere.rikkahub.data.ai.tools.device.buildListSensorsTool
import me.rerere.rikkahub.data.ai.tools.device.buildSetBrightnessTool
import me.rerere.rikkahub.data.ai.tools.device.buildSetVolumeTool
import me.rerere.rikkahub.data.ai.tools.device.buildToastTool as buildShowToastTool
import me.rerere.rikkahub.data.ai.tools.device.buildTorchTool as buildSetTorchTool
import me.rerere.rikkahub.data.ai.tools.device.buildMediaScannerTool as buildScanMediaTool
import me.rerere.rikkahub.data.ai.tools.device.buildCallLogTool
import me.rerere.rikkahub.data.ai.tools.device.buildDownloadFileTool
import me.rerere.rikkahub.data.ai.tools.device.buildLaunchAppTool
import me.rerere.rikkahub.data.ai.tools.device.buildLocationTool
import me.rerere.rikkahub.data.ai.tools.device.buildMusicTool
import me.rerere.rikkahub.data.ai.tools.device.buildNotificationPostTool
import me.rerere.rikkahub.data.ai.tools.device.buildOpenFileTool
import me.rerere.rikkahub.data.ai.tools.device.buildOpenUrlTool
import me.rerere.rikkahub.data.ai.tools.device.buildReadSensorTool
import me.rerere.rikkahub.data.ai.tools.device.buildReadSmsTool
import me.rerere.rikkahub.data.ai.tools.device.buildSearchContactsTool
import me.rerere.rikkahub.data.ai.tools.device.buildSetAlarmTool
import me.rerere.rikkahub.data.ai.tools.device.buildSetTimerTool
import me.rerere.rikkahub.data.ai.tools.device.buildSetWallpaperTool
import me.rerere.rikkahub.data.ai.tools.device.buildShareTool
import me.rerere.rikkahub.data.ai.tools.device.buildStorageInfoTool
import me.rerere.rikkahub.data.ai.tools.device.buildTelephonyInfoTool
import me.rerere.rikkahub.data.ai.tools.device.buildVibrateTool
import me.rerere.rikkahub.data.ai.tools.device.buildSetWallpaperTool as buildWallpaperTool
import me.rerere.rikkahub.data.ai.tools.device.buildWifiInfoTool
import me.rerere.rikkahub.data.ai.tools.device.hasAnyRuntimePermission

/**
 * 免审批的设备工具集合（纯读取/无害反馈）。
 * 元工具的审批策略：不在集合内的工具（含未知工具）执行前都需要用户确认，
 * 因此 set_torch/set_alarm/set_timer/control_music/share/open_file 等会改状态
 * 或拉起界面的工具天然需要审批。
 */
val DEVICE_TOOL_APPROVAL_FREE: Set<String> = setOf(
    "get_volume",
    "get_brightness",
    "get_battery_info",
    "get_storage_info",
    "get_wifi_info",
    "get_audio_info",
    "get_telephony_info",
    "list_sensors",
    "read_sensor",
    "show_toast",
)

/** 判断设备工具是否需要审批（未知工具名默认需要审批） */
fun isDeviceToolApprovalFree(toolName: String?): Boolean =
    toolName != null && toolName in DEVICE_TOOL_APPROVAL_FREE

/**
 * 元工具的审批判定（纯逻辑，便于 JVM 单测）：
 * action=list 免审批；action=run 按工具名判断；action 缺失/未知时默认需要审批（宁可多确认一次）。
 */
fun deviceToolboxNeedsApproval(args: JsonObject?): Boolean =
    when (args?.get("action")?.jsonPrimitive?.contentOrNull) {
        "list" -> false
        "run" -> !isDeviceToolApprovalFree(args?.get("tool")?.jsonPrimitive?.contentOrNull)
        else -> true
    }

/** 目录展示用的权限映射（列表内任一权限授予即可用） */
internal val DEVICE_TOOL_PERMISSIONS: Map<String, List<String>> = mapOf(
    "read_sms" to listOf(Manifest.permission.READ_SMS),
    "list_contacts" to listOf(Manifest.permission.READ_CONTACTS),
    "search_contacts" to listOf(Manifest.permission.READ_CONTACTS),
    "list_call_log" to listOf(Manifest.permission.READ_CALL_LOG),
    "get_location" to listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ),
    "post_notification" to listOf(Manifest.permission.POST_NOTIFICATIONS),
)

/**
 * 设备工具箱元工具：懒发现模式，只占用一个工具位（省 token）。
 * 先 action="list" 获取内置工具目录，再 action="run" + tool + args 分发执行。
 */
fun createDeviceToolboxTool(context: Context): Tool {
    val innerTools: List<Tool> = listOf(
        buildSetTorchTool(context),
        buildVibrateTool(context),
        buildGetVolumeTool(context),
        buildSetVolumeTool(context),
        buildGetBrightnessTool(context),
        buildSetBrightnessTool(context),
        buildShowToastTool(context),
        buildBatteryTool(context),
        buildStorageInfoTool(context),
        buildWifiInfoTool(context),
        buildAudioInfoTool(context),
        buildTelephonyInfoTool(context),
        buildListSensorsTool(context),
        buildReadSensorTool(context),
        buildShareTool(context),
        buildWallpaperTool(context),
        buildNotificationPostTool(context),
        buildSetAlarmTool(context),
        buildSetTimerTool(context),
        buildMusicTool(context),
        buildReadSmsTool(context),
        buildListContactsTool(context),
        buildSearchContactsTool(context),
        buildCallLogTool(context),
        buildLocationTool(context),
        buildLaunchAppTool(context),
        buildOpenUrlTool(context),
        buildScanMediaTool(context),
        buildDownloadFileTool(context),
        buildOpenFileTool(context),
    )
    val innerToolsByName = innerTools.associateBy { it.name }

    return Tool(
        name = "device_toolbox",
        description = "Device toolbox: control and inspect the phone (torch, vibrate, volume, brightness, " +
            "battery, storage, WiFi, sensors, SMS, contacts, call log, location, alarms, music, notifications, " +
            "share, wallpaper, app launch, downloads and more). " +
            "Lazy discovery: first call with action='list' to get the full catalog of built-in device tools " +
            "with their parameter schemas and permission status, then call with action='run' + tool + args to execute one.",
        needsApproval = { args -> deviceToolboxNeedsApproval(args as? JsonObject) },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", "'list' to get the tool catalog, 'run' to execute a device tool")
                        put("enum", buildJsonArray { add("list"); add("run") })
                    })
                    put("tool", buildJsonObject {
                        put("type", "string")
                        put("description", "For action='run': tool name from the catalog, e.g. 'set_torch'")
                    })
                    put("args", buildJsonObject {
                        put("type", "object")
                        put("description", "For action='run': arguments object matching the tool's parameter schema")
                    })
                },
                required = listOf("action")
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            when (val action = obj["action"]?.jsonPrimitive?.contentOrNull) {
                "list" -> {
                    val catalog = buildJsonArray {
                        innerTools.forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters()?.let { schema ->
                                    Json.encodeToJsonElement(InputSchema.serializer(), schema)
                                } ?: JsonNull)
                                put("requires_approval", !isDeviceToolApprovalFree(tool.name))
                                val perms = DEVICE_TOOL_PERMISSIONS[tool.name]
                                if (perms != null) {
                                    put("required_permissions", buildJsonArray { perms.forEach { add(it) } })
                                    put("permission_granted", isPermissionGranted(context, tool.name, perms))
                                } else {
                                    put("permission_granted", true)
                                }
                            })
                        }
                    }
                    listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("tools", catalog)
                            put("message", "Call action='run' with tool=<name> and args=<object> to execute one of these tools")
                        }.toString()
                    ))
                }
                "run" -> {
                    val toolName = obj["tool"]?.jsonPrimitive?.contentOrNull
                    if (toolName.isNullOrBlank()) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", "Missing required parameter 'tool' for action='run' (use action='list' to see available tools)")
                            }.toString()
                        ))
                    }
                    val tool = innerToolsByName[toolName]
                        ?: return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", "Unknown device tool: $toolName (use action='list' to see available tools)")
                            }.toString()
                        ))
                    val toolArgs = obj["args"] as? JsonObject ?: buildJsonObject { }
                    try {
                        tool.execute(toolArgs)
                    } catch (e: Exception) {
                        listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", e.message ?: "Unknown error")
                            }.toString()
                        ))
                    }
                }
                else -> listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "Unknown action: $action. Use 'list' or 'run'.")
                    }.toString()
                ))
            }
        },
    )
}

/** 目录里展示的权限状态（API 33 以下通知权限视为已授予） */
private fun isPermissionGranted(context: Context, toolName: String, permissions: List<String>): Boolean {
    if (toolName == "post_notification" && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return hasAnyRuntimePermission(context, permissions)
}
