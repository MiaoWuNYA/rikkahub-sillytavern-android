package me.rerere.rikkahub.plugin.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.plugin.model.PluginFolder

/**
 * 插件数据仓库
 * 使用 DataStore 存储插件配置、启用状态和文件夹分组
 */
class PluginRepository(
    private val context: Context
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "plugin_settings")

    /**
     * 获取插件配置
     */
    suspend fun getPluginConfig(pluginId: String): Map<String, JsonElement> {
        return context.dataStore.data.map { prefs ->
            val configKey = stringPreferencesKey("plugin_config_$pluginId")
            val configJson = prefs[configKey] ?: "{}"
            try {
                json.decodeFromString<Map<String, JsonElement>>(configJson)
            } catch (e: Exception) {
                emptyMap()
            }
        }.first()
    }

    /**
     * 保存插件配置
     */
    suspend fun savePluginConfig(pluginId: String, config: Map<String, JsonElement>) {
        context.dataStore.edit { prefs ->
            val configKey = stringPreferencesKey("plugin_config_$pluginId")
            prefs[configKey] = json.encodeToString(config)
        }
    }

    /**
     * 检查插件是否启用
     */
    suspend fun isPluginEnabled(pluginId: String): Boolean {
        return context.dataStore.data.map { prefs ->
            val enabledKey = booleanPreferencesKey("plugin_enabled_$pluginId")
            prefs[enabledKey] ?: true // 默认启用
        }.first()
    }

    /**
     * 设置插件启用状态
     */
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val enabledKey = booleanPreferencesKey("plugin_enabled_$pluginId")
            prefs[enabledKey] = enabled
        }
    }

    /**
     * 保存插件信息（初始化时调用）
     */
    suspend fun savePlugin(pluginInfo: me.rerere.rikkahub.plugin.model.PluginInfo) {
        context.dataStore.edit { prefs ->
            val enabledKey = booleanPreferencesKey("plugin_enabled_${pluginInfo.manifest.id}")
            if (prefs[enabledKey] == null) {
                prefs[enabledKey] = pluginInfo.isEnabled
            }

            // 保存配置
            if (pluginInfo.config.isNotEmpty()) {
                val configKey = stringPreferencesKey("plugin_config_${pluginInfo.manifest.id}")
                prefs[configKey] = json.encodeToString(pluginInfo.config)
            }
        }
    }

    /**
     * 移除插件
     */
    suspend fun removePlugin(pluginId: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(booleanPreferencesKey("plugin_enabled_$pluginId"))
            prefs.remove(stringPreferencesKey("plugin_config_$pluginId"))
        }
    }

    // ==================== 文件夹管理 ====================

    /**
     * 获取所有文件夹
     */
    suspend fun getFolders(): List<PluginFolder> {
        return context.dataStore.data.map { prefs ->
            val key = stringPreferencesKey("plugin_folders")
            val jsonStr = prefs[key] ?: "[]"
            try {
                json.decodeFromString<List<PluginFolder>>(jsonStr)
            } catch (e: Exception) {
                emptyList()
            }
        }.first()
    }

    /**
     * 保存文件夹列表
     */
    private suspend fun saveFolders(folders: List<PluginFolder>) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("plugin_folders")] = json.encodeToString(folders)
        }
    }

    /**
     * 添加文件夹
     */
    suspend fun addFolder(name: String): PluginFolder {
        val folders = getFolders().toMutableList()
        val nextSort = (folders.maxOfOrNull { it.sortOrder } ?: 0) + 1
        val folder = PluginFolder(
            id = kotlin.uuid.Uuid.random().toString(),
            name = name.trim(),
            sortOrder = nextSort
        )
        folders.add(folder)
        saveFolders(folders)
        return folder
    }

    /**
     * 重命名文件夹
     */
    suspend fun renameFolder(folderId: String, newName: String) {
        val folders = getFolders().map { folder ->
            if (folder.id == folderId) folder.copy(name = newName.trim()) else folder
        }
        saveFolders(folders)
    }

    /**
     * 删除文件夹
     * 同时清除该文件夹下所有插件的 folderId 关联
     */
    suspend fun deleteFolder(folderId: String) {
        val folders = getFolders().filterNot { it.id == folderId }
        saveFolders(folders)
        // 清除该文件夹下插件的归属
        val assignments = getFolderAssignments().toMutableMap()
        val toRemove = assignments.entries.filter { it.value == folderId }.map { it.key }
        toRemove.forEach { assignments.remove(it) }
        saveFolderAssignments(assignments)
    }

    // ==================== 插件-文件夹关联 ====================

    /**
     * 获取所有插件-文件夹关联
     * key: pluginId, value: folderId
     */
    suspend fun getFolderAssignments(): Map<String, String> {
        return context.dataStore.data.map { prefs ->
            val key = stringPreferencesKey("plugin_folder_assignments")
            val jsonStr = prefs[key] ?: "{}"
            try {
                json.decodeFromString<Map<String, String>>(jsonStr)
            } catch (e: Exception) {
                emptyMap()
            }
        }.first()
    }

    /**
     * 保存全部插件-文件夹关联
     */
    private suspend fun saveFolderAssignments(assignments: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("plugin_folder_assignments")] = json.encodeToString(assignments)
        }
    }

    /**
     * 设置插件的文件夹归属
     * folderId 为 null 表示移出文件夹（回到未分组）
     */
    suspend fun setPluginFolder(pluginId: String, folderId: String?) {
        val assignments = getFolderAssignments().toMutableMap()
        if (folderId == null) {
            assignments.remove(pluginId)
        } else {
            assignments[pluginId] = folderId
        }
        saveFolderAssignments(assignments)
    }
}
