package me.rerere.rikkahub.plugin.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 插件数据存储，每个插件独立命名空间
 * 支持 KV 存储和文件目录管理
 */
class PluginDataStore(
    private val context: Context,
    private val pluginId: String
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("plugin_data_$pluginId", Context.MODE_PRIVATE)
    }

    /**
     * 存储数据
     */
    fun setData(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /**
     * 读取数据
     */
    fun getData(key: String): String? {
        return prefs.getString(key, null)
    }

    /**
     * 删除数据
     */
    fun deleteData(key: String) {
        prefs.edit().remove(key).apply()
    }

    /**
     * 列出所有 key
     */
    fun listData(): List<String> {
        return prefs.all.keys.toList()
    }

    /**
     * 清空所有数据
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * 获取插件数据目录（用于存储图片等文件）
     */
    fun getDataDir(): java.io.File {
        val dir = java.io.File(context.filesDir, "plugin_data/$pluginId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
