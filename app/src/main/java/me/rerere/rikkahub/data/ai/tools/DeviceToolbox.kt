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

    // 懒发现省的是工具 schema 的 token，但如果模型根本不知道有哪些能力，它就永远不会
    // 调 action=list 去发现——"不知道所以不调用"。所以这里把全部工具名 + 一句话能力
    // 写进系统提示路由区（动态从 innerTools 生成，与实际目录永不漂移），参数 schema
    // 仍然走 action=list 按需拉取。
    val catalogLine = innerTools.joinToString("; ") { tool ->
        val firstSentence = tool.description.substringBefore(". ")
        "${tool.name} ($firstSentence)"
    }

    return Tool(
        name = "device_toolbox",
        description = "Device toolbox: control and inspect the phone — torch, vibrate, volume, brightness, " +
            "battery, storage, WiFi, sensors, SMS, contacts, call log, location, alarms, timers, music, " +
            "notifications, share, wallpaper, app launch, URL opening, media scanning, file download/open. " +
            "When the user asks anything involving these device capabilities, call this tool instead of saying " +
            "you cannot. Lazy discovery: call with action='list' to get full parameter schemas and permission " +
            "status, then action='run' + tool + args to execute.",
        // 工具路由说明：把全部子工具名直接写进系统提示，模型不需要先 list 就知道
        // "原来我能开手电筒 / 查电量 / 发通知"。这行进 <tool_selection> 附近的路由区，
        // 弱模型主要靠它决定用什么工具。
        systemPrompt = { _, _ ->
            "Phone control → device_toolbox (one meta-tool covering the whole device). " +
                "Built-in tools: $catalogLine. " +
                "For any user request touching these (turn on flashlight, set an alarm, check battery, " +
                "find my location, read SMS, open an app, post a notification...), call device_toolbox " +
                "(action='list' first if unsure of the exact parameters) instead of saying you can't."
        },
        needsApproval = { false },
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
