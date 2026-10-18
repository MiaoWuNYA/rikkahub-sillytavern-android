package me.rerere.rikkahub.data.db.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

internal val partTypeMapping = mapOf(
    "Text" to "text",
    "UIMessagePart.Text" to "text",
    "me.rerere.ai.ui.UIMessagePart.Text" to "text",
    "Image" to "image",
    "UIMessagePart.Image" to "image",
    "me.rerere.ai.ui.UIMessagePart.Image" to "image",
    "Video" to "video",
    "UIMessagePart.Video" to "video",
    "me.rerere.ai.ui.UIMessagePart.Video" to "video",
    "Audio" to "audio",
    "UIMessagePart.Audio" to "audio",
    "me.rerere.ai.ui.UIMessagePart.Audio" to "audio",
    "Document" to "document",
    "UIMessagePart.Document" to "document",
    "me.rerere.ai.ui.UIMessagePart.Document" to "document",
    "Reasoning" to "reasoning",
    "UIMessagePart.Reasoning" to "reasoning",
    "me.rerere.ai.ui.UIMessagePart.Reasoning" to "reasoning",
    "Search" to "search",
    "UIMessagePart.Search" to "search",
    "me.rerere.ai.ui.UIMessagePart.Search" to "search",
    "ToolCall" to "tool_call",
    "UIMessagePart.ToolCall" to "tool_call",
    "me.rerere.ai.ui.UIMessagePart.ToolCall" to "tool_call",
    "ToolResult" to "tool_result",
    "UIMessagePart.ToolResult" to "tool_result",
    "me.rerere.ai.ui.UIMessagePart.ToolResult" to "tool_result",
    "Tool" to "tool",
    "UIMessagePart.Tool" to "tool",
    "me.rerere.ai.ui.UIMessagePart.Tool" to "tool",
)

internal fun migrateMessagesJson(messagesJson: String): String {
    return runCatching {
        val element = JsonInstant.parseToJsonElement(messagesJson)
        val migrated = migrateMessagesElement(element)
        if (migrated == element) messagesJson else JsonInstant.encodeToString(migrated)
    }.getOrElse { messagesJson }
}

internal fun migrateMessagesElement(element: JsonElement): JsonElement {
    val rootArray = element as? JsonArray ?: return element
    val migratedArray = JsonArray(
        rootArray.map { message ->
            val messageObject = message as? JsonObject ?: return@map message
            val partsElement = messageObject["parts"] as? JsonArray ?: return@map message
            val migratedParts = migratePartsArray(partsElement)
            if (migratedParts == partsElement) {
                message
            } else {
                JsonObject(messageObject.toMutableMap().apply {
                    put("parts", migratedParts)
                })
            }
        }
    )
    return if (migratedArray == rootArray) element else migratedArray
}

internal fun migratePartsArray(partsElement: JsonArray): JsonArray {
    return JsonArray(
        partsElement.map { part ->
            val partObject = part as? JsonObject ?: return@map part
            val typeValue = partObject["type"]?.jsonPrimitiveOrNull?.contentOrNull
            val mappedType = typeValue?.let { partTypeMapping[it] } ?: typeValue

            var updatedPart: JsonElement = part
            if (mappedType != null && mappedType != typeValue) {
                updatedPart = JsonObject(partObject.toMutableMap().apply {
                    put("type", JsonPrimitive(mappedType))
                })
            }

            val updatedObject = updatedPart as? JsonObject ?: return@map updatedPart
            val outputElement = updatedObject["output"] as? JsonArray ?: return@map updatedPart
            val migratedOutput = migratePartsArray(outputElement)
            if (migratedOutput == outputElement) {
                updatedPart
            } else {
                JsonObject(updatedObject.toMutableMap().apply {
                    put("output", migratedOutput)
                })
            }
        }
    )
}

/**
 * Safe ADD COLUMN — checks PRAGMA table_info before executing.
 * Prevents "duplicate column name" crashes when restoring backups from newer versions
 * that already contain the column. SQLite has no native ADD COLUMN IF NOT EXISTS.
 *
 * 表不存在时直接跳过：残缺备份缺的表交给恢复流程的结构对齐补齐，而不是让整次迁移崩掉。
 */
internal fun SupportSQLiteDatabase.addColumnIfNotExists(
    table: String,
    column: String,
    definition: String,
) {
    if (!hasTable(table)) return
    query("PRAGMA table_info($table)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return
        }
    }
    execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
}

/**
 * Safe DROP COLUMN — 表或列不存在时跳过。
 *
 * 合并上游时容易漏掉「上游通过 AutoMigration 删列」的步骤，留着这些列会让
 * 表结构与当前实体对不上（NOT NULL 且无默认值的列还会让后续插入直接失败）。
 */
internal fun SupportSQLiteDatabase.dropColumnIfNotExists(table: String, column: String) {
    if (!hasTable(table)) return
    var exists = false
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) {
                exists = true
                break
            }
        }
    }
    if (!exists) return
    runCatching { execSQL("ALTER TABLE `$table` DROP COLUMN `$column`") }
}

internal fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
    query("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { cursor ->
        cursor.moveToFirst() && cursor.getInt(0) > 0
    }
