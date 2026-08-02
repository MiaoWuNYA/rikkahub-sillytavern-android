package me.rerere.rikkahub.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * Persona — 用户人设
 * 对齐酒馆 Persona 功能：定义用户角色、外观、系统提示词注入
 */
@Serializable
data class Persona(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val title: String = "",                 // 短标题（展示用）
    val description: String = "",            // 外观/背景描述
    val position: PersonaInjectionPosition = PersonaInjectionPosition.IN_PROMPT,
    val depth: Int = 2,                      // AT_DEPTH 时的插入深度（官方默认 2）
    val role: MessageRole = MessageRole.SYSTEM, // 独立消息注入时的角色（官方默认 SYSTEM）
    val avatar: Avatar = Avatar.Dummy,
    val enabled: Boolean = true,
    val lockedCharacterIds: List<Uuid> = emptyList(), // 绑定到特定角色
)

@Serializable(with = PersonaInjectionPositionSerializer::class)
enum class PersonaInjectionPosition {
    IN_PROMPT,       // 官方 IN_PROMPT：嵌入系统提示词（官方默认）
    TOP_OF_CHAT,     // 官方 TOP_AN：对话顶部
    BOTTOM_OF_CHAT,  // 官方 BOTTOM_AN：对话底部
    AT_DEPTH,        // 官方 AT_DEPTH：从最新消息往前指定深度插入
    NONE,            // 官方 NONE：不注入
}

/**
 * 位置序列化：旧版保存的 BEFORE_SYSTEM / AFTER_SYSTEM 统一映射为官方默认 IN_PROMPT，
 * 其余未知值同样回退 IN_PROMPT，保证旧数据无损读取。
 */
object PersonaInjectionPositionSerializer : KSerializer<PersonaInjectionPosition> {
    override val descriptor = PrimitiveSerialDescriptor("PersonaInjectionPosition", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PersonaInjectionPosition) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): PersonaInjectionPosition {
        return when (decoder.decodeString()) {
            "TOP_OF_CHAT" -> PersonaInjectionPosition.TOP_OF_CHAT
            "BOTTOM_OF_CHAT" -> PersonaInjectionPosition.BOTTOM_OF_CHAT
            "AT_DEPTH" -> PersonaInjectionPosition.AT_DEPTH
            "NONE" -> PersonaInjectionPosition.NONE
            else -> PersonaInjectionPosition.IN_PROMPT
        }
    }
}
