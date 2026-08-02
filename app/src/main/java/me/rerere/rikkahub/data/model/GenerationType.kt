package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 生成类型 — 对齐官方 SillyTavern GENERATION_TYPE_TRIGGERS
 * 用于世界书条目 triggers 过滤
 */
@Serializable
enum class GenerationType(val value: String) {
    @SerialName("normal")
    NORMAL("normal"),

    @SerialName("continue")
    CONTINUE("continue"),

    @SerialName("impersonate")
    IMPERSONATE("impersonate"),

    @SerialName("swipe")
    SWIPE("swipe"),

    @SerialName("regenerate")
    REGENERATE("regenerate"),

    @SerialName("quiet")
    QUIET("quiet"),
}
