package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Persona
import me.rerere.rikkahub.data.model.PersonaInjectionPosition

// 酒馆助手用户人设 Persona <-> RikkaHub Persona 互转。
// 字段命名对齐 @types/function/persona.d.ts。

private fun positionNumber(position: PersonaInjectionPosition): Int = when (position) {
    PersonaInjectionPosition.IN_PROMPT -> 0
    PersonaInjectionPosition.TOP_OF_CHAT -> 1
    PersonaInjectionPosition.BOTTOM_OF_CHAT -> 2
    PersonaInjectionPosition.AT_DEPTH -> 3
    PersonaInjectionPosition.NONE -> 4
}

private fun positionOf(number: Int?): PersonaInjectionPosition = when (number) {
    1 -> PersonaInjectionPosition.TOP_OF_CHAT
    2 -> PersonaInjectionPosition.BOTTOM_OF_CHAT
    3 -> PersonaInjectionPosition.AT_DEPTH
    4 -> PersonaInjectionPosition.NONE
    else -> PersonaInjectionPosition.IN_PROMPT
}

private fun roleNumber(role: MessageRole): Int = when (role) {
    MessageRole.SYSTEM -> 0
    MessageRole.USER -> 1
    else -> 2
}

private fun messageRoleOf(number: Int?): MessageRole = when (number) {
    1 -> MessageRole.USER
    2 -> MessageRole.ASSISTANT
    else -> MessageRole.SYSTEM
}

private fun avatarPath(avatar: Avatar): String = when (avatar) {
    is Avatar.Image -> avatar.url
    else -> ""
}

fun personaToJson(persona: Persona): JsonObject = buildJsonObject {
    put("avatar_id", JsonPrimitive(persona.id.toString()))
    put("avatar", JsonPrimitive(avatarPath(persona.avatar)))
    put("name", JsonPrimitive(persona.name))
    put("title", JsonPrimitive(persona.title))
    put("description", JsonPrimitive(persona.description))
    put("position", JsonPrimitive(positionNumber(persona.position)))
    put("depth", JsonPrimitive(persona.depth))
    put("role", JsonPrimitive(roleNumber(persona.role)))
    put("lorebook", JsonPrimitive(""))
    put("connections", buildJsonArray { })
    put("is_default", JsonPrimitive(false))
}

// PartialDeep<Persona> -> Persona；未给的字段沿用 fallback。
fun jsonToPersona(obj: JsonObject, fallback: Persona = Persona()): Persona {
    fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content
    fun int(key: String): Int? = (obj[key] as? JsonPrimitive)?.content?.toIntOrNull()
    val avatar = str("avatar")?.takeIf { it.isNotBlank() }
    return fallback.copy(
        name = str("name") ?: fallback.name,
        title = str("title") ?: fallback.title,
        description = str("description") ?: fallback.description,
        position = if (obj["position"] == null) fallback.position else positionOf(int("position")),
        depth = int("depth") ?: fallback.depth,
        role = if (obj["role"] == null) fallback.role else messageRoleOf(int("role")),
        avatar = avatar?.let { Avatar.Image(it) } ?: fallback.avatar,
    )
}
