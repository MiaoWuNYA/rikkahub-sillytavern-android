package me.rerere.rikkahub.data.st.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/** 脚本源设置读写接缝；Settings 侧字段落地后在此注入即可，无需改调用方。 */
interface TavernScriptSettingsBridge {
    fun readGlobalScripts(): String
    fun readGlobalScriptsEnabled(): Boolean
    fun readPresetScriptsJson(): String
    fun writeGlobalScripts(json: String?)
    fun writeGlobalScriptsEnabled(enabled: Boolean)
    fun writePresetScriptsGroup(group: String, json: String?)
}

/** 脚本源落在 Settings.huadengSettings（tavernGlobalScripts* / tavernPresetScripts），RikkaHubApp 启动时注入 bridge。 */
object TavernScriptStore {

    @Volatile
    var settingsBridge: TavernScriptSettingsBridge? = null

    @Volatile
    private var memoryGlobalScripts: String = "[]"

    @Volatile
    private var memoryGlobalScriptsEnabled: Boolean = true

    private val presetScriptsByGroup = LinkedHashMap<String, String>()
    private val presetGroupByModeInjection = LinkedHashMap<Uuid, String>()

    val globalScripts: String
        get() = settingsBridge?.readGlobalScripts() ?: memoryGlobalScripts

    var globalScriptsEnabled: Boolean
        get() = settingsBridge?.readGlobalScriptsEnabled() ?: memoryGlobalScriptsEnabled
        set(value) {
            memoryGlobalScriptsEnabled = value
            settingsBridge?.writeGlobalScriptsEnabled(value)
        }

    @Synchronized
    fun setGlobalScripts(json: String?) {
        val normalized = json?.takeIf { it.isNotBlank() } ?: "[]"
        memoryGlobalScripts = normalized
        settingsBridge?.writeGlobalScripts(normalized)
    }

    @Synchronized
    fun setPresetScripts(group: String, scriptsJson: String?) {
        if (group.isBlank()) return
        val normalized = scriptsJson?.takeIf { it.isNotBlank() } ?: "[]"
        presetScriptsByGroup[group] = normalized
        settingsBridge?.writePresetScriptsGroup(group, normalized)
    }

    @Synchronized
    fun presetScriptsRaw(group: String): String? =
        presetScriptsByGroup[group] ?: settingsBridge?.let { bridge ->
            val json = Json.parseToJsonElement(bridge.readPresetScriptsJson()) as? kotlinx.serialization.json.JsonObject
            (json?.get(group) as? kotlinx.serialization.json.JsonArray)?.toString()
        }

    @Synchronized
    fun removePresetScripts(group: String) {
        presetScriptsByGroup.remove(group)
        settingsBridge?.writePresetScriptsGroup(group, null)
    }

    @Synchronized
    fun renamePresetScriptsGroup(oldGroup: String, newGroup: String?) {
        val value = presetScriptsByGroup.remove(oldGroup) ?: return
        settingsBridge?.writePresetScriptsGroup(oldGroup, null)
        if (!newGroup.isNullOrBlank()) {
            presetScriptsByGroup[newGroup] = value
            settingsBridge?.writePresetScriptsGroup(newGroup, value)
        }
    }

    /** 等价于旧 settings.tavernPresetScripts（{"组名": [script, ...]}）的 JSON 文本。 */
    @Synchronized
    fun presetScriptsJson(): String {
        if (presetScriptsByGroup.isEmpty()) return settingsBridge?.readPresetScriptsJson() ?: "{}"
        return buildJsonObject {
            presetScriptsByGroup.forEach { (group, json) ->
                runCatching { Json.parseToJsonElement(json) }.getOrNull()?.let { put(group, it) }
            }
        }.toString()
    }

    @Synchronized
    fun bindPresetGroup(modeInjectionId: Uuid, group: String?) {
        if (group.isNullOrBlank()) presetGroupByModeInjection.remove(modeInjectionId)
        else presetGroupByModeInjection[modeInjectionId] = group
    }

    @Synchronized
    fun presetGroupsOf(modeInjectionIds: Set<Uuid>): Set<String> =
        modeInjectionIds.mapNotNull { presetGroupByModeInjection[it] }.toSet()
}
