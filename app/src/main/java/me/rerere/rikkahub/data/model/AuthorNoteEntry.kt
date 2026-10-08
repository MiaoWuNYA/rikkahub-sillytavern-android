package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid

/** 一条可独立控制注入位置与节奏的导演备注。 */
@Serializable
data class AuthorNoteEntry(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val content: String = "",
    val enabled: Boolean = true,
    val position: AuthorNotePosition = AuthorNotePosition.IN_CHAT,
    val depth: Int = 4,
    val role: MessageRole = MessageRole.SYSTEM,
    val interval: Int = 1,
)

const val LEGACY_AUTHOR_NOTE_ENTRY_ID = "legacy-author-note"
