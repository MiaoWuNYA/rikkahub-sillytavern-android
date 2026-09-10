package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 插件清单文件 (manifest.json) 对应的数据类
 *
 * 移植自 Tumin (OrangeChat) 插件系统；本仓库裁剪了 customPage / customPageWebView /
 * ui（声明式 UI）/ hooks / hookConfigs / permissions / promptTemplate 等扩展字段，
 * 仅保留工具型插件所需的核心字段。
 */
@Serializable
data class PluginManifest(
    /**
     * 插件唯一标识，建议使用反向域名格式，如 com.example.plugin.name
     */
    val id: String,

    /**
     * 插件显示名称
     */
    val name: String,

    /**
     * 插件描述
     */
    val description: String,

    /**
     * 插件版本，格式如 1.0.0
     */
    val version: String,

    /**
     * 作者名称
     */
    val author: String,

    /**
     * 插件图标，可以是 emoji 或 URL
     */
    val icon: String,

    /**
     * 入口文件路径，相对于插件目录
     */
    val entry: String,

    /**
     * 插件提供的工具列表
     */
    val tools: List<PluginToolDefinition> = emptyList(),

    /**
     * 插件配置字段定义
     */
    val config: List<PluginConfigField> = emptyList(),

    /**
     * 插件允许访问的网络域名白名单。
     * 插件通过 JS fetch() 发起的 HTTP 请求，目标域名必须在此列表中才会被放行。
     * 空列表表示禁止所有网络请求。
     * 特殊值 "*" 表示允许所有域名（不推荐，仅用于开发调试）。
     */
    val allowedHosts: List<String> = emptyList(),
)

/**
 * 插件工具定义
 */
@Serializable
data class PluginToolDefinition(
    /**
     * 工具名称，将作为函数名导出
     */
    val name: String,

    /**
     * 工具描述，用于 AI 理解工具用途
     */
    val description: String,

    /**
     * 工具参数列表
     */
    val parameters: List<PluginToolParameter> = emptyList(),
)

/**
 * 插件工具参数定义
 */
@Serializable
data class PluginToolParameter(
    /**
     * 参数名称
     */
    val name: String,

    /**
     * 参数类型：string, number, integer, boolean, object, array
     */
    val type: String,

    /**
     * 参数描述
     */
    val description: String? = null,

    /**
     * 是否必填
     */
    val required: Boolean = false,
)

/**
 * 插件配置字段定义
 */
@Serializable
data class PluginConfigField(
    /**
     * 配置项键名
     */
    val name: String,

    /**
     * 配置项类型：string, number, boolean, select, password, model
     * model 类型会显示模型选择器，保存选中模型的 ID
     */
    val type: String,

    /**
     * 显示标签
     */
    val label: String,

    /**
     * 配置项描述
     */
    val description: String? = null,

    /**
     * 是否必填
     */
    val required: Boolean = false,

    /**
     * 默认值
     */
    val default: JsonElement? = null,

    /**
     * 选项列表（用于 select 类型）
     */
    val options: List<ConfigOption>? = null,

    /**
     * 输入提示
     */
    val placeholder: String? = null,
)

/**
 * 配置选项
 */
@Serializable
data class ConfigOption(
    /**
     * 选项值
     */
    val value: String,

    /**
     * 显示标签
     */
    val label: String,
)
