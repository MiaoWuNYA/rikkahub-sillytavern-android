package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid

@Serializable
data class Persona(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val title: String = "",
    val description: String = "",
    val position: PersonaInjectionPosition = PersonaInjectionPosition.IN_PROMPT,
    val depth: Int = 2,
    val role: MessageRole = MessageRole.SYSTEM,
    val avatar: Avatar = Avatar.Dummy,
    val enabled: Boolean = true,
    val lockedCharacterIds: List<Uuid> = emptyList(),
)

@Serializable
enum class PersonaInjectionPosition {
    IN_PROMPT,
    TOP_OF_CHAT,
    BOTTOM_OF_CHAT,
    AT_DEPTH,
    NONE,
}
