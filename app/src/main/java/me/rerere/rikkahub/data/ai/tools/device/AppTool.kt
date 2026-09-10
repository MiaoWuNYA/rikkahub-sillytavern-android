package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** 按包名启动应用（PackageManager，包可见性由 manifest 中 queries 声明保障） */
internal fun buildLaunchAppTool(context: Context): Tool = Tool(
    name = "launch_app",
    description = "Launch an installed app by package name (e.g. com.tencent.mm for WeChat).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "The package name of the app to launch")
                })
            },
            required = listOf("package_name")
        )
    },
    execute = { args ->
        val packageName = args.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
        if (packageName.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(deviceError("package_name is required").toString()))
        }
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                return@Tool listOf(UIMessagePart.Text(deviceError("App not found or not launchable: $packageName").toString()))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("package_name", packageName)
                    put("message", "Launched app: $packageName")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Failed to launch app").toString()))
        }
    }
)

/** 用浏览器打开 URL（ACTION_VIEW） */
internal fun buildOpenUrlTool(context: Context): Tool = Tool(
    name = "open_url",
    description = "Open a URL in the device browser.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The URL to open")
                })
            },
            required = listOf("url")
        )
    },
    execute = { args ->
        val url = args.jsonObject["url"]?.jsonPrimitive?.contentOrNull
        if (url.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(deviceError("url is required").toString()))
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("url", url)
                    put("message", "Opened URL: $url")
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Failed to open URL").toString()))
        }
    }
)
