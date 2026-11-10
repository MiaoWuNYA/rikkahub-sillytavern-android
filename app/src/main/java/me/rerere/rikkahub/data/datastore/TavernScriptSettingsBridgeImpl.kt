package me.rerere.rikkahub.data.datastore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.st.runtime.TavernScriptSettingsBridge

/** 脚本源落在 HuaDengSettings：读 settingsFlow 现值，写走 SettingsStore.update。 */
class TavernScriptSettingsBridgeImpl(
    private val settingsStore: SettingsStore,
) : TavernScriptSettingsBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val current get() = settingsStore.settingsFlow.value.huadengSettings

    override fun readGlobalScripts(): String = current.tavernGlobalScripts

    override fun readGlobalScriptsEnabled(): Boolean = current.tavernGlobalScriptsEnabled

    override fun readPresetScriptsJson(): String = current.tavernPresetScripts

    override fun writeGlobalScripts(json: String?) {
        val normalized = json?.takeIf { it.isNotBlank() } ?: "[]"
        update { it.copy(tavernGlobalScripts = normalized) }
    }

    override fun writeGlobalScriptsEnabled(enabled: Boolean) {
        update { it.copy(tavernGlobalScriptsEnabled = enabled) }
    }

    override fun writePresetScriptsGroup(group: String, json: String?) {
        if (group.isBlank()) return
        val element = json?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        update { prev ->
            val root = runCatching {
                Json.parseToJsonElement(prev.tavernPresetScripts) as? JsonObject
            }.getOrNull()
            val merged = buildJsonObject {
                root?.forEach { (key, value) -> if (key != group) put(key, value) }
                if (element != null) put(group, element)
            }
            prev.copy(tavernPresetScripts = merged.toString())
        }
    }

    private fun update(transform: (HuaDengSettings) -> HuaDengSettings) {
        scope.launch {
            settingsStore.update { it.copy(huadengSettings = transform(it.huadengSettings)) }
        }
    }
}
