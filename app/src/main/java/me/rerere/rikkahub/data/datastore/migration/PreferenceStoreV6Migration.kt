package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

/**
 * V6：群聊默认改成一问一答——用户一句话后每个启用成员各回一句。
 * 仍是旧默认（自然选人 / 追加角色卡 / 5 秒自动接话）的存量群聊同步新默认，
 * 用户改过任一项的群聊保持原样。
 */
class PreferenceStoreV6Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 6
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.GROUP_CHATS]?.let { json ->
            prefs[SettingsStore.GROUP_CHATS] = migrateGroupChatTurnTaking(json)
        }
        prefs[SettingsStore.VERSION] = 6
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun migrateGroupChatTurnTaking(json: String): String = runCatching {
    val root = JsonInstant.parseToJsonElement(json) as? JsonArray ?: return json
    var changed = false
    val migrated = JsonArray(root.map { element ->
        val obj = element as? JsonObject ?: return@map element
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        fun int(key: String) = (obj[key] as? JsonPrimitive)?.intOrNull
        fun bool(key: String) = (obj[key] as? JsonPrimitive)?.booleanOrNull
        val untouchedDefaults = str("activationStrategy") == "NATURAL" &&
            str("generationMode") == "APPEND" &&
            int("autoModeDelay") == 5 &&
            int("autoChatRounds") == 5 &&
            bool("allowSelfResponses") != true
        if (!untouchedDefaults) return@map element
        changed = true
        JsonObject(
            obj + mapOf(
                "activationStrategy" to JsonPrimitive("LIST"),
                "generationMode" to JsonPrimitive("SWAP"),
                "autoModeDelay" to JsonPrimitive(0),
            )
        )
    })
    if (!changed) json else JsonInstant.encodeToString(migrated)
}.getOrDefault(json)
