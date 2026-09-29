package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

/**
 * V5：删除"内嵌世界书物化出的外置世界书"。
 *
 * 旧版本在导入角色卡时把卡内 character_book 复制成一个独立的外置 Lorebook
 * （`isCharacterBook == true`）并绑定到助手，再靠双向同步维持两份拷贝一致。
 * 这套"两份存储 + 同步"会导致编辑助手/编辑全局世界书时互相覆盖（issue #3）：
 * 同步函数只按"是否绑定"判断，会把卡内条目写进绑定的全局书、或反过来。
 *
 * 现在内嵌书回归官方模型——它就是角色卡的一部分，注入时直接从卡片构建，与全局书
 * 合并扫描。因此必须一次性清掉历史遗留的物化书（连同助手上对它们的绑定），
 * 否则同一份内容会以两个 id 注入两次。
 */
class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 5
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val lorebooksJson = prefs[SettingsStore.LOREBOOKS]
        val assistantsJson = prefs[SettingsStore.ASSISTANTS]

        val removedIds = characterBookIds(lorebooksJson ?: "[]")
        if (removedIds.isNotEmpty()) {
            prefs[SettingsStore.LOREBOOKS] = stripCharacterBooks(lorebooksJson ?: "[]")
            if (assistantsJson != null) {
                prefs[SettingsStore.ASSISTANTS] = stripLorebookIds(assistantsJson, removedIds)
            }
        }
        prefs[SettingsStore.VERSION] = 5
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/** 收集所有 `isCharacterBook == true` 的 Lorebook id（uuid 字符串） */
internal fun characterBookIds(lorebooksJson: String): Set<String> {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(lorebooksJson) as? JsonArray ?: return emptySet()
        root.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val isCharacterBook = (obj["isCharacterBook"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (!isCharacterBook) null
            else (obj["id"] as? JsonPrimitive)?.contentOrNullString()
        }.toSet()
    }.getOrDefault(emptySet())
}

/** 从 lorebooks 数组里剔除物化书 */
internal fun stripCharacterBooks(lorebooksJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(lorebooksJson) as? JsonArray ?: return lorebooksJson
        val kept = root.filterNot { element ->
            val obj = element as? JsonObject ?: return@filterNot false
            (obj["isCharacterBook"] as? JsonPrimitive)?.booleanOrNull == true
        }
        JsonInstant.encodeToString(JsonArray(kept))
    }.getOrDefault(lorebooksJson)
}

/** 从每个助手的 lorebookIds 里剔除已删除的物化书 id */
internal fun stripLorebookIds(assistantsJson: String, removedIds: Set<String>): String {
    if (removedIds.isEmpty()) return assistantsJson
    return runCatching {
        val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray ?: return assistantsJson
        val migrated = JsonArray(root.map { element ->
            val obj = element as? JsonObject ?: return@map element
            val ids = obj["lorebookIds"] as? JsonArray ?: return@map element
            val filtered = JsonArray(ids.filterNot { id ->
                (id as? JsonPrimitive)?.contentOrNullString() in removedIds
            })
            if (filtered.size == ids.size) return@map element
            JsonObject(obj.map { (key, value) ->
                if (key == "lorebookIds") key to filtered else key to value
            }.toMap())
        })
        JsonInstant.encodeToString(migrated)
    }.getOrDefault(assistantsJson)
}

private fun JsonPrimitive.contentOrNullString(): String? =
    runCatching { content.trim('"') }.getOrNull()
