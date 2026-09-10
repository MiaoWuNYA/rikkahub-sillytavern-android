package me.rerere.rikkahub.data.ai.tools.device

import android.Manifest
import android.content.Context
import android.provider.CallLog
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun callTypeName(type: Int): String = when (type) {
    CallLog.Calls.INCOMING_TYPE -> "incoming"
    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
    CallLog.Calls.MISSED_TYPE -> "missed"
    CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
    CallLog.Calls.REJECTED_TYPE -> "rejected"
    CallLog.Calls.BLOCKED_TYPE -> "blocked"
    else -> "unknown"
}

/** 通话记录（CallLog，需 READ_CALL_LOG 权限） */
internal fun buildCallLogTool(context: Context): Tool = Tool(
    name = "list_call_log",
    description = "List recent phone calls from the device's call log. Supports filtering by type (incoming, outgoing, missed) and a since-timestamp. Most recent first. Requires READ_CALL_LOG permission.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max calls to return, default 20, max 200")
                })
                put("since_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional epoch millis lower bound for the call date")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional filter: \"incoming\", \"outgoing\", or \"missed\"")
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        if (!hasAnyRuntimePermission(context, listOf(Manifest.permission.READ_CALL_LOG))) {
            return@Tool listOf(UIMessagePart.Text(deviceError(
                "READ_CALL_LOG permission not granted",
                "needs_permission" to "READ_CALL_LOG",
                "hint" to "Grant Phone/Call log permission in system Settings > Apps > Permissions",
            ).toString()))
        }
        val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 200)
        val sinceMs = params["since_ms"]?.jsonPrimitive?.longOrNull
        val typeStr = params["type"]?.jsonPrimitive?.contentOrNull
        val typeInt = when (typeStr) {
            null, "" -> null
            "incoming" -> CallLog.Calls.INCOMING_TYPE
            "outgoing" -> CallLog.Calls.OUTGOING_TYPE
            "missed" -> CallLog.Calls.MISSED_TYPE
            else -> return@Tool listOf(UIMessagePart.Text(deviceError("unknown call type: $typeStr").toString()))
        }
        try {
            val selectionParts = mutableListOf<String>()
            val selectionArgs = mutableListOf<String>()
            if (typeInt != null) {
                selectionParts.add("${CallLog.Calls.TYPE} = ?")
                selectionArgs.add(typeInt.toString())
            }
            if (sinceMs != null) {
                selectionParts.add("${CallLog.Calls.DATE} >= ?")
                selectionArgs.add(sinceMs.toString())
            }
            val uri = CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter("limit", limit.toString())
                .build()
            val calls = buildJsonArray {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DATE,
                        CallLog.Calls.DURATION,
                    ),
                    if (selectionParts.isEmpty()) null else selectionParts.joinToString(" AND "),
                    if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray(),
                    "${CallLog.Calls.DATE} DESC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        addJsonObject {
                            put("number", c.getString(0) ?: "")
                            val name = c.getString(1)
                            if (!name.isNullOrEmpty()) put("name", name)
                            put("type", callTypeName(c.getInt(2)))
                            put("date_ms", c.getLong(3))
                            put("duration_s", c.getLong(4))
                        }
                    }
                }
            }
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("count", calls.size)
                    put("calls", calls)
                }.toString()
            ))
        } catch (_: SecurityException) {
            listOf(UIMessagePart.Text(deviceError("READ_CALL_LOG permission not granted").toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
