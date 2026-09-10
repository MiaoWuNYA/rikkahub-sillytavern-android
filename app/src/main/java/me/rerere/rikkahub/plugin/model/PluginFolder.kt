package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.Serializable

/**
 * 插件文件夹（逻辑分组）
 * 用于在 UI 上将插件归类到不同文件夹，不改变物理目录结构
 */
@Serializable
data class PluginFolder(
    /**
     * 文件夹唯一标识
     */
    val id: String,

    /**
     * 文件夹显示名称
     */
    val name: String,

    /**
     * 排序序号，越小越靠前
     */
    val sortOrder: Int = 0,
)
