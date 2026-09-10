package me.rerere.rikkahub.data.ai.tools.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val NOTIFICATION_CHANNEL_ID = "device_toolbox"

/** 发一条系统通知（NotificationManagerCompat） */
internal fun buildNotificationPostTool(context: Context): Tool = Tool(
    name = "post_notification",
    description = "Post a system notification to the user. Requires notification permission.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification title (required)")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification body text (optional)")
                })
                put("id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Notification ID. If not provided, uses current timestamp in seconds.")
                })
            },
            required = listOf("title")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull
        val body = params["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val id = params["id"]?.jsonPrimitive?.intOrNull ?: (System.currentTimeMillis() / 1000).toInt()
        if (title.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(deviceError("Missing required parameter 'title'").toString()))
        }
        try {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) {
                return@Tool listOf(UIMessagePart.Text(deviceError(
                    "Notifications are not enabled for this app",
                    "needs_permission" to "POST_NOTIFICATIONS",
                ).toString()))
            }
            // 按需创建通知渠道
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                try {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            NOTIFICATION_CHANNEL_ID,
                            "AI Toolbox",
                            NotificationManager.IMPORTANCE_DEFAULT,
                        ).apply { description = "Notifications posted by the device toolbox" }
                    )
                } catch (_: Exception) {
                    // 渠道创建失败时仍尝试直接通知
                }
            }
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager.notify(id, notification)
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("notification_id", id)
                    put("title", title)
                    put("message", "Notification posted: $title")
                }.toString()
            ))
        } catch (e: SecurityException) {
            listOf(UIMessagePart.Text(deviceError(
                "SecurityException: ${e.message}",
                "needs_permission" to "POST_NOTIFICATIONS",
            ).toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
