package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * V5（融合合并两侧迁移）：
 * 1. 删除"内嵌世界书物化出的外置世界书"：旧版本导入角色卡时把卡内 character_book 复制成
 *    独立外置 Lorebook 并绑定助手，靠双向同步维持两份拷贝，会互相覆盖（issue #3）。
 *    内嵌书回归官方模型后，历史物化书和绑定必须一次性清掉，否则同一份内容注入两次。
 * 2. 提供商的余额查询 balanceOption 改成自定义额度查询 quotaQuery（合并上游 3.1.0）。
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

        prefs[SettingsStore.PROVIDERS]?.let { json ->
            prefs[SettingsStore.PROVIDERS] = migrateProvidersQuotaQuery(json)
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

/** 对提供商列表应用 [migrateProviderQuotaQuery] */
internal fun migrateProvidersQuotaQuery(providersJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(providersJson) as? JsonArray
            ?: return@runCatching providersJson
        JsonInstant.encodeToString(JsonArray(root.map(::migrateProviderQuotaQuery)))
    }.getOrDefault(providersJson)
}

/**
 * 余额查询 `balanceOption` 改成自定义额度查询 `quotaQuery`。
 *
 * 旧的查询只对 OpenAI 类型生效：GET `baseUrl + apiPath`（apiPath 是完整地址时直接用），带 Bearer key，
 * 这里转成等价的模板。Google、Claude 上的旧配置界面上从没开放过，直接丢掉。
 * 模型级覆盖里嵌套的提供商配置一并处理。
 */
internal fun migrateProviderQuotaQuery(provider: JsonElement): JsonElement {
    val obj = provider as? JsonObject ?: return provider
    val migrated = obj.toMutableMap()
    val balanceOption = migrated.remove(LEGACY_KEY) as? JsonObject
    val type = obj["type"]?.jsonPrimitiveOrNull?.contentOrNull
    if (balanceOption != null && type == "openai" && "quotaQuery" !in obj) {
        migrated["quotaQuery"] = balanceOption.toQuotaQuery()
    }
    (obj["models"] as? JsonArray)?.let { models ->
        migrated["models"] = JsonArray(
            models.map { model ->
                val modelObj = model as? JsonObject ?: return@map model
                val override = modelObj["providerOverwrite"] as? JsonObject ?: return@map model
                JsonObject(modelObj + ("providerOverwrite" to migrateProviderQuotaQuery(override)))
            }
        )
    }
    return JsonObject(migrated)
}

private fun JsonObject.toQuotaQuery(): JsonObject {
    val apiPath = get("apiPath")?.jsonPrimitiveOrNull?.contentOrNull ?: "/credits"
    return buildJsonObject {
        put("enabled", get("enabled")?.jsonPrimitiveOrNull?.booleanOrNull ?: false)
        put("url", if (apiPath.startsWith("http")) apiPath else "{{baseUrl}}$apiPath")
        putJsonArray("headers") {
            addJsonObject {
                put("name", "Authorization")
                put("value", "Bearer {{apiKey}}")
            }
        }
        put("resultPath", get("resultPath")?.jsonPrimitiveOrNull?.contentOrNull ?: "data.total_usage")
    }
}

private const val LEGACY_KEY = "balanceOption"
