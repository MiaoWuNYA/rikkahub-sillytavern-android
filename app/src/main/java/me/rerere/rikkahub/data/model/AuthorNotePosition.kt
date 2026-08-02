package me.rerere.rikkahub.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 导演备注（Author's Note）注入位置，对齐酒馆官方 authors-note.js：
 *
 * - IN_PROMPT：After Main Prompt / Story String（官方值 0）
 * - IN_CHAT：In-chat @ Depth（官方值 1，默认；配合 depth 与 role）
 * - BEFORE_PROMPT：Before Main Prompt / Story String（官方值 2）
 */
@Serializable(with = AuthorNotePositionSerializer::class)
enum class AuthorNotePosition {
    IN_PROMPT,
    IN_CHAT,
    BEFORE_PROMPT,
}

object AuthorNotePositionSerializer : KSerializer<AuthorNotePosition> {
    override val descriptor = PrimitiveSerialDescriptor("AuthorNotePosition", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AuthorNotePosition) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): AuthorNotePosition {
        return parseAuthorNotePosition(decoder.decodeString())
    }
}

/** 读取持久化值：只认官方三档，未知值回退官方默认 IN_CHAT */
fun parseAuthorNotePosition(value: String?): AuthorNotePosition {
    return when (value) {
        "IN_PROMPT" -> AuthorNotePosition.IN_PROMPT
        "IN_CHAT" -> AuthorNotePosition.IN_CHAT
        "BEFORE_PROMPT" -> AuthorNotePosition.BEFORE_PROMPT
        else -> AuthorNotePosition.IN_CHAT
    }
}
