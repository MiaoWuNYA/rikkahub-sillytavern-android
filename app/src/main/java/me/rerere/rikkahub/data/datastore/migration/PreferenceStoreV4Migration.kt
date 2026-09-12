package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

/**
 * V4：记忆开关默认值迁移。
 *
 * 记忆相关开关的默认值从 false 翻转为 true（2.5.3fix1 起），但旧版本序列化的助手
 * JSON 里显式写着 false，默认值翻转对已有助手不生效，表现为"记忆功能默认不读取"。
 * 本迁移把所有助手 JSON 中显式为 false 的记忆开关一次性改写为新默认 true。
 */
class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.ASSISTANTS] = migrateAssistantsMemoryDefaults(
            prefs[SettingsStore.ASSISTANTS] ?: "[]"
        )
        prefs[SettingsStore.VERSION] = 4
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/** 需要翻转为 true 的记忆开关字段（与 Assistant 的新默认值一致） */
internal val MEMORY_DEFAULT_KEYS = setOf(
    "enableMemory",
    "enableMemoryRag",
    "enableEpisodicMemory",
    "enableThreeLayerMemory",
    "enableCrossWindowMemory",
)

internal fun migrateAssistantsMemoryDefaults(assistantsJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
            ?: return@runCatching assistantsJson
        val migrated = JsonArray(root.map { element ->
            val obj = element as? JsonObject ?: return@map element
            val touched = MEMORY_DEFAULT_KEYS.any { key ->
                (obj[key] as? JsonPrimitive)?.content == "false"
            }
            if (!touched) return@map element
            JsonObject(obj.map { (key, value) ->
                if (key in MEMORY_DEFAULT_KEYS && (value as? JsonPrimitive)?.content == "false") {
                    key to JsonPrimitive(true)
                } else {
                    key to value
                }
            }.toMap())
        })
        JsonInstant.encodeToString(migrated)
    }.getOrDefault(assistantsJson)
}
