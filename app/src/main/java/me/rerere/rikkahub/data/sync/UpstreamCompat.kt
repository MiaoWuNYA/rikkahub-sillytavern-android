package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File
import kotlin.math.roundToInt

/** 导出给上游 rikkahub 直接导入的备份：本地独有的数据一律舍弃。 */
internal object UpstreamCompat {
    // 上游 rikkahub 2.5.6 的 schema 版本与 identityHash（上游 app/schemas/.../25.json）
    private const val DATABASE_VERSION = 25
    private const val IDENTITY_HASH = "049f05fd92fc292b92c652743f056693"

    // 上游 2.5.6 Settings 的顶层字段（含备份格式里的 launchCount），其余顶层字段都是本地独有的
    private val SETTINGS_KEYS = setOf(
        "init", "dynamicColor", "themeId", "customThemes", "developerMode", "displaySetting", "networkSetting",
        "favoriteModels", "chatModelId", "fastModelId", "fastModelReasoningLevel", "imageGenerationModelId",
        "titlePrompt", "translateModeId", "translatePrompt", "translateThinkingBudget", "enableSuggestion",
        "suggestionPrompt", "ocrModelId", "ocrPrompt", "compressModelId", "compressPrompt", "assistantId",
        "providers", "assistants", "assistantTags", "searchServices", "searchCommonOptions", "searchServiceSelected",
        "mcpServers", "webDavConfig", "s3Config", "ttsProviders", "selectedTTSProviderId", "defaultTTSPlaybackSpeed",
        "asrProviders", "selectedASRProviderId", "modeInjections", "lorebooks", "quickMessages", "webServerEnabled",
        "webServerPort", "webServerJwtEnabled", "webServerAccessPassword", "webServerLocalhostOnly",
        "backupReminderConfig", "launchCount", "sponsorAlertDismissedAt",
    )

    // 上游 2.5.6 的表，导出的库里只留这些，其余（couple_*、media_creation_*、memory_file 等）全部删掉
    private val UPSTREAM_TABLES = setOf(
        "ConversationEntity", "MemoryEntity", "GenMediaEntity", "message_node",
        "managed_files", "favorites", "workspaces", "conversation_folder",
    )

    // 上游 2.5.6 有而本地没有的表，按上游的 DDL 补出来
    private val UPSTREAM_ONLY_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `MemoryEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`assistant_id` TEXT NOT NULL, `content` TEXT NOT NULL)",
    )

    // 上游 2.5.6 ConversationEntity 的列，本地多出来的列要删掉
    private val CONVERSATION_COLUMNS = setOf(
        "id", "assistant_id", "title", "nodes", "create_at", "update_at", "suggestions", "is_pinned",
        "custom_system_prompt", "mode_injection_ids", "lorebook_ids", "workspace_cwd", "folder_id",
    )

    // FTS 虚拟表与它的影子表、SQLite 自己的表原样保留
    private val KEPT_TABLE_PREFIXES = listOf("message_fts", "sqlite_")

    private val KEPT_TABLES = setOf("room_master_table", "android_metadata")

    // 上游 2.5.6 各 @Serializable 密封层次的子类序列化名，导出时不在名单里的多态对象整块丢掉
    private val UPSTREAM_SERIAL_NAMES = setOf(
        "aliyun", "annotations", "answered", "approved", "ask_user", "audio", "auto", "bing_local", "bocha",
        "brave", "calendar", "chart_display", "claude", "clipboard", "custom_js", "dashscope", "denied",
        "document", "doubao", "elevenlabs", "exa", "finish", "firecrawl", "fish-audio", "gemini", "google", "grok",
        "groq", "image", "image_delta", "image_end", "image_generation", "image_snapshot", "image_start",
        "javascript_engine", "jina", "linkup", "me.rerere.common.android.RequestLog",
        "me.rerere.common.android.TextLog", "me.rerere.rikkahub.data.ai.Messages",
        "me.rerere.rikkahub.data.model.Dummy", "me.rerere.rikkahub.data.model.Emoji",
        "me.rerere.rikkahub.data.model.Image", "metaso", "mimo", "minimax", "mode", "object", "ollama", "openai",
        "openai_realtime", "pending", "perplexity", "qwen", "raw", "reasoning", "reasoning_delta", "reasoning_end",
        "reasoning_start", "regex", "rikkahub", "screen_time", "search", "searxng", "serper", "server_tool",
        "server_tool_end", "server_tool_input_delta", "server_tool_input_end", "server_tool_start", "sse", "step",
        "streamable_http", "system", "tavily", "text", "text_delta", "text_end", "text_start", "time_info",
        "tinyfish", "tool", "tool_call", "tool_call_delta", "tool_call_end", "tool_call_start", "tool_result",
        "tts", "url_citation", "url_context", "usage", "video", "volcengine", "web_page", "xai", "zhipu",
    )

    // 这些键里是模型或工具的自由 JSON，递归时原样保留
    private val RAW_JSON_KEYS = setOf("input", "output", "metadata", "arguments", "params", "headers", "env", "result")

    private val MESSAGE_TYPE = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")

    // 上游没有这些注入位置，映射到最接近的一个，避免整条世界书条目被丢掉
    private val POSITION_FALLBACK = mapOf(
        "before_character" to "after_system_prompt",
        "after_character" to "after_system_prompt",
        "antagonize" to "after_system_prompt",
        "em_top" to "after_system_prompt",
        "em_bottom" to "after_system_prompt",
        "after_dialog" to "bottom_of_chat",
        "author_note" to "at_depth",
    )

    // 本地豆包 TTS 改了判别名和字段，按上游 volcengine 的字段重建，format/sampleRate 丢弃
    private val VOLCENGINE_KEYS = setOf("id", "name", "apiKey", "baseUrl", "resourceId", "speaker", "speechRate")

    fun settingsJson(encoded: String, json: Json): String {
        val root = json.parseToJsonElement(encoded).jsonObject
        val kept = buildJsonObject {
            for ((key, value) in root) {
                if (key !in SETTINGS_KEYS) continue
                prune(value, key)?.let { put(key, it) }
            }
        }
        return json.encodeToString(kept)
    }

    fun downgradeDatabase(context: Context, databaseFile: File, json: Json) {
        val configuration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READWRITE)
        )
        SQLiteDatabase.openDatabase(configuration, null) { error("Upstream backup database is corrupt") }
            .use { database ->
                database.version = DATABASE_VERSION
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
                )
                database.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, ?)",
                    arrayOf(IDENTITY_HASH),
                )
                for (sql in UPSTREAM_ONLY_TABLES) database.execSQL(sql)
                dropLocalTables(database)
                dropLocalColumns(database)
                dropLocalAnnotations(database, json)
                DatabaseBackup.checkpoint(database)
            }
        DatabaseBackup.removeSidecars(databaseFile)
    }

    private fun dropLocalTables(database: SupportSQLiteDatabase) {
        val tables = mutableListOf<String>()
        database.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { tables += it }
        }
        for (table in tables) {
            if (table in UPSTREAM_TABLES || table in KEPT_TABLES) continue
            if (KEPT_TABLE_PREFIXES.any { table.startsWith(it) }) continue
            database.execSQL("DROP TABLE IF EXISTS `$table`")
        }
    }

    private fun dropLocalColumns(database: SupportSQLiteDatabase) {
        val columns = mutableListOf<String>()
        database.query("PRAGMA table_info(`ConversationEntity`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) cursor.getString(nameIndex)?.let { columns += it }
        }
        for (column in columns) {
            if (column in CONVERSATION_COLUMNS) continue
            runCatching { database.execSQL("ALTER TABLE `ConversationEntity` DROP COLUMN `$column`") }
        }
    }

    private fun prune(element: JsonElement, parentKey: String? = null): JsonElement? = when (element) {
        is JsonObject -> pruneObject(element, parentKey)
        is JsonArray -> JsonArray(element.mapNotNull { prune(it, parentKey) })
        JsonNull -> null
        else -> element
    }

    private fun pruneObject(source: JsonObject, parentKey: String?): JsonObject? {
        val type = (source["type"] as? JsonPrimitive)?.contentOrNull
        if (type == "doubao" && parentKey == "ttsProviders") return volcengine(source)
        if (type != null && type !in UPSTREAM_SERIAL_NAMES) return null
        return buildJsonObject {
            for ((key, value) in source) {
                if (key in RAW_JSON_KEYS) {
                    put(key, value)
                    continue
                }
                val pruned = if (key == "position") fallbackPosition(value) else prune(value, key)
                if (pruned != null) put(key, pruned)
            }
        }
    }

    private fun fallbackPosition(value: JsonElement): JsonElement {
        val position = (value as? JsonPrimitive)?.contentOrNull ?: return value
        return POSITION_FALLBACK[position]?.let { JsonPrimitive(it) } ?: value
    }

    private fun volcengine(source: JsonObject): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("volcengine"))
        for ((key, value) in source) {
            if (key !in VOLCENGINE_KEYS || value is JsonNull) continue
            // 本地是 1.0 为正常的倍率，上游是 0 为正常的偏移量
            if (key == "speechRate") {
                val rate = (value as? JsonPrimitive)?.floatOrNull ?: 1f
                put(key, JsonPrimitive(((rate - 1f) * 100f).roundToInt()))
            } else {
                put(key, value)
            }
        }
    }

    private fun dropLocalAnnotations(database: SupportSQLiteDatabase, json: Json) {
        val rewritten = mutableListOf<Pair<String, String>>()
        database.query("SELECT id, messages FROM message_node").use { cursor ->
            while (cursor.moveToNext()) {
                // 超大行读不出来时保持原样，导出不该因此失败
                val row = runCatching { cursor.getString(0) to cursor.getString(1) }.getOrNull() ?: continue
                val id = row.first ?: continue
                val messages = row.second ?: continue
                if (!hasLocalType(messages)) continue
                val scrubbed = runCatching {
                    scrubMessages(json.parseToJsonElement(messages))?.let { json.encodeToString(it) }
                }.getOrNull() ?: continue
                rewritten += id to scrubbed
            }
        }
        for ((id, messages) in rewritten) {
            database.execSQL("UPDATE message_node SET messages = ? WHERE id = ?", arrayOf(messages, id))
        }
    }

    /** 行里只要出现上游不认识的 type 值就要重写，其余消息保持字节不变 */
    private fun hasLocalType(messages: String): Boolean =
        MESSAGE_TYPE.findAll(messages).any { it.groupValues[1] !in UPSTREAM_SERIAL_NAMES }

    private fun scrubMessages(element: JsonElement): JsonElement? = when (element) {
        is JsonArray -> JsonArray(element.mapNotNull { scrubMessage(it) })
        else -> element
    }

    private fun scrubMessage(element: JsonElement): JsonElement? {
        if (element !is JsonObject) return element
        return buildJsonObject {
            for ((key, value) in element) {
                when (key) {
                    "annotations" -> put(key, scrubAnnotations(value))
                    "parts" -> put(key, scrubParts(value))
                    else -> put(key, value)
                }
            }
        }
    }

    private fun scrubAnnotations(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.filterNot { it is JsonObject && !it.knownType() })
        else -> element
    }

    private fun scrubParts(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.mapNotNull { part ->
            if (part !is JsonObject || !part.knownType()) return@mapNotNull null
            buildJsonObject {
                for ((key, value) in part) put(key, if (key == "annotations") scrubAnnotations(value) else value)
            }
        })

        else -> element
    }

    private fun JsonObject.knownType(): Boolean {
        val type = (this["type"] as? JsonPrimitive)?.contentOrNull ?: return true
        return type in UPSTREAM_SERIAL_NAMES
    }
}
