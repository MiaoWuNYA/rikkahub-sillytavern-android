package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.Serializable

/** 下发给前端 WebView 的脚本载荷。 */
@Serializable
data class PluginFrontendPayload(
    val id: String,
    val pluginId: String,
    val pluginName: String,
    val name: String,
    val info: String = "",
    val source: String,
    val buttons: List<PluginFrontendButton> = emptyList(),
)
