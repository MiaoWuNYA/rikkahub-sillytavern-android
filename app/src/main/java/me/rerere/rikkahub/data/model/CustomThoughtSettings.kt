package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** 全局显示/请求偏好；不写入消息或原生 Reasoning。 */
@Serializable
data class CustomThoughtSettings(
    val enabled: Boolean = false,
    val excludeFromRequests: Boolean = false,
    val rules: List<CustomThoughtRule> = emptyList(),
)

@Serializable
data class CustomThoughtRule(
    val id: String = Uuid.random().toString(),
    val enabled: Boolean = true,
    val startTag: String = "",
    val endTag: String = "",
)
