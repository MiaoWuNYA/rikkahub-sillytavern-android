package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * 插件运行时信息
 */
data class PluginInfo(
    /**
     * 插件清单
     */
    val manifest: PluginManifest,

    /**
     * 插件目录
     */
    val directory: File,

    /**
     * 是否启用
     */
    val isEnabled: Boolean,

    /**
     * 用户配置值
     */
    val config: Map<String, JsonElement> = emptyMap(),

    /**
     * 加载错误信息（如果有）
     */
    val loadError: String? = null,

    /**
     * 所属文件夹 ID（逻辑分组，null 表示未分组）
     */
    val folderId: String? = null,
) {
    /**
     * 获取配置值，如果不存在则返回默认值
     */
    fun getConfigValue(key: String): JsonElement? {
        return config[key] ?: manifest.config.find { it.name == key }?.default
    }

    /**
     * 检查是否有配置项
     */
    fun hasConfig(): Boolean = manifest.config.isNotEmpty()

    /**
     * 获取入口文件的完整路径
     */
    fun getEntryFile(): File = File(directory, manifest.entry)
}
