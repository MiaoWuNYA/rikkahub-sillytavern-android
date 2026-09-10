package me.rerere.rikkahub.plugin.loader

import me.rerere.rikkahub.plugin.model.PluginInfo

/**
 * 已加载的插件
 */
data class LoadedPlugin(
    /**
     * 插件信息
     */
    val info: PluginInfo,

    /**
     * JS 沙箱
     */
    val sandbox: PluginSandbox
) {
    /**
     * 获取插件 ID
     */
    val id: String get() = info.manifest.id

    /**
     * 获取插件名称
     */
    val name: String get() = info.manifest.name

    /**
     * 检查是否有某个工具
     */
    fun hasTool(toolName: String): Boolean {
        return info.manifest.tools.any { it.name == toolName }
    }

    /**
     * 获取工具定义
     */
    fun getTool(toolName: String) = info.manifest.tools.find { it.name == toolName }
}
