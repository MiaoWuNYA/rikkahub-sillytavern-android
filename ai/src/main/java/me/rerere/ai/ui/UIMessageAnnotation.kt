package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()

    /** 角色卡 mes_example 解析出的示例消息标记（EMTop/EMBottom 锚点用） */
    @Serializable
    @SerialName("example_message")
    data object ExampleMessage : UIMessageAnnotation()

    /** 角色卡字段消息标记（官方独立 system 消息，世界书 before/after char 锚点用） */
    @Serializable
    @SerialName("character_card")
    data object CharacterCardData : UIMessageAnnotation()
}
