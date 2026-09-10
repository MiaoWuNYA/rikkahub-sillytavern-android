package me.rerere.rikkahub.data.ai.tools.device

import android.Manifest
import android.content.Context
import android.provider.Telephony
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 读取短信收件箱（Telephony.Sms.Inbox，需 READ_SMS 权限） */
internal fun buildReadSmsTool(context: Context): Tool = Tool(
    name = "read_sms",
    description = "Read SMS messages from the device inbox. Can filter by sender, keyword, and time range. Requires READ_SMS permission.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of SMS messages to return (default 20, max 100)")
                })
                put("sender", buildJsonObject {
                    put("type", "string")
                    put("description", "Filter by sender phone number or name (optional)")
                })
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "Filter by keyword in SMS content (optional)")
                })
                put("since_days", buildJsonObject {
                    put("type", "integer")
                    put("description", "Only return SMS from the last N days (default 7)")
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        if (!hasAnyRuntimePermission(context, listOf(Manifest.permission.READ_SMS))) {
            return@Tool listOf(UIMessagePart.Text(deviceError(
                "READ_SMS permission not granted. Grant it in system Settings > Apps > Permissions, or use default SMS app.",
                "needs_permission" to "READ_SMS",
            ).toString()))
        }
        val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 100)
        val sender = params["sender"]?.jsonPrimitive?.contentOrNull
        val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull
        val sinceDays = params["since_days"]?.jsonPrimitive?.intOrNull ?: 7
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        try {
            val sinceTime = System.currentTimeMillis() - sinceDays.toLong() * 24 * 60 * 60 * 1000
            val selection = buildString {
                append("${Telephony.Sms.DATE} >= ?")
                if (!sender.isNullOrBlank()) append(" AND ${Telephony.Sms.ADDRESS} LIKE ?")
                if (!keyword.isNullOrBlank()) append(" AND ${Telephony.Sms.BODY} LIKE ?")
            }
            val selectionArgs = mutableListOf(sinceTime.toString()).apply {
                if (!sender.isNullOrBlank()) add("%$sender%")
                if (!keyword.isNullOrBlank()) add("%$keyword%")
            }
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ),
                selection,
                selectionArgs.toTypedArray(),
                "${Telephony.Sms.DATE} DESC",
            )
            if (cursor == null) {
                return@Tool listOf(UIMessagePart.Text(deviceError("Unable to access SMS inbox").toString()))
            }
            val messages = buildJsonArray {
                cursor.use { c ->
                    var count = 0
                    while (c.moveToNext() && count < limit) {
                        add(buildJsonObject {
                            put("sender", c.getString(0) ?: "")
                            put("content", c.getString(1) ?: "")
                            put("date", dateFormat.format(Date(c.getLong(2))))
                            put("is_read", c.getInt(3) == 1)
                        })
                        count++
                    }
                }
            }
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("count", messages.size)
                    put("messages", messages)
                }.toString()
            ))
        } catch (e: SecurityException) {
            listOf(UIMessagePart.Text(deviceError(
                "READ_SMS permission not granted",
                "needs_permission" to "READ_SMS",
            ).toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
