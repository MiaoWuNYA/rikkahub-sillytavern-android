package me.rerere.rikkahub.tavern.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/** 酒馆前端的角色 / 会话 / 设置类只读接口，未列出的路径一律返回 null 交给其他片段。 */
class TavernDataApi(
    private val settingsProvider: () -> Settings,
    private val conversations: ConversationRepository?,
    private val scope: CoroutineScope,
) : TavernApi {

    override suspend fun handle(request: TavernRequest): TavernReply? {
        return when (request.path.trim('/')) {
            "api/settings/get" -> TavernReply.Json(settingsJson(settingsProvider()))
            "api/settings/save" -> TavernReply.Json("{}")
            "api/characters/all" -> TavernReply.Json(charactersJson(settingsProvider()))
            "api/characters/get" -> TavernReply.Json(characterJson(request, settingsProvider()))
            "api/characters/create",
            "api/characters/edit",
            "api/characters/delete",
            "api/characters/rename",
            "api/chats/save",
            "api/chats/delete",
            "api/chats/rename",
            "api/chats/group/get" -> TavernReply.Json("{}")
            "api/chats/search" -> TavernReply.Json("[]")
            "api/chats/recent" -> TavernReply.Json(recentChatsJson())
            "api/chats/get" -> TavernReply.Json(chatMessagesJson(request))
            else -> null
        }
    }

    private fun settingsJson(settings: Settings): String {
        val persona = settings.personas.firstOrNull { it.id == settings.activePersonaId }
        return buildJsonObject {
            put("username", persona?.name?.ifBlank { DEFAULT_USER_NAME } ?: DEFAULT_USER_NAME)
            put("user_avatar", avatarName(persona?.avatar, "user"))
            put("power_user", buildJsonObject {})
            put("extension_settings", buildJsonObject {})
            put("world_names", buildJsonArray { settings.lorebooks.forEach { add(it.name) } })
        }.toString()
    }

    private fun charactersJson(settings: Settings): String = buildJsonArray {
        settings.assistants
            .sortedByDescending { if (it.tavernData != null) 1 else 0 }
            .forEach { add(it.toCharacterJson()) }
    }.toString()

    private fun characterJson(request: TavernRequest, settings: Settings): String {
        val body = parseBody(request)
        val wanted = listOf("avatar_url", "ch_name", "name")
            .mapNotNull { body.field(it) ?: request.queryValue(it)?.takeIf { v -> v.isNotBlank() } }
        val found = settings.assistants.firstOrNull { assistant ->
            val json = assistant.toCharacterJson()
            wanted.any { want ->
                val avatar = (json["avatar"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val name = (json["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                want == name || want == avatar || want == avatar + ".png" || want.substringAfterLast('/') == avatar
            }
        }
        return (found?.toCharacterJson() ?: emptyCharacterJson(wanted.lastOrNull().orEmpty())).toString()
    }

    private fun emptyCharacterJson(name: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", "")
        put("personality", "")
        put("scenario", "")
        put("first_mes", "")
        put("mes_example", "")
        put("avatar", "")
        put("chat", "")
        put("talkativeness", 0.5)
        put("fav", false)
        put("data", buildJsonObject {})
    }

    private fun Assistant.toCharacterJson(): JsonObject = buildJsonObject {
        val tavern = tavernData
        put("name", tavern?.name?.ifBlank { name } ?: name)
        put("description", tavern?.description?.ifBlank { systemPrompt } ?: systemPrompt)
        put("personality", tavern?.personality.orEmpty())
        put("scenario", tavern?.scenario.orEmpty())
        put("first_mes", tavern?.firstMessage.orEmpty())
        put("mes_example", tavern?.mesExample.orEmpty())
        put("avatar", avatarName(avatar, name))
        put("chat", "")
        put("talkativeness", talkativeness.toDouble())
        put("fav", false)
        put("data", buildJsonObject {})
    }

    private suspend fun recentChatsJson(): String {
        val repo = conversations ?: return "[]"
        val settings = settingsProvider()
        val rows = ArrayList<Pair<Assistant, Conversation>>()
        for (assistant in settings.assistants) {
            val recent = runCatching { repo.getRecentConversations(assistant.id, RECENT_PER_ASSISTANT) }
                .getOrNull().orEmpty()
            recent.forEach { rows.add(assistant to it) }
        }
        return buildJsonArray {
            rows.sortedByDescending { it.second.updateAt }
                .take(RECENT_LIMIT)
                .forEach { (assistant, conversation) ->
                    add(buildJsonObject {
                        put("file_name", conversation.id.toString() + CHAT_FILE_SUFFIX)
                        put("file_id", conversation.id.toString())
                        put("mes", lastText(conversation).take(MESSAGE_PREVIEW_CHARS))
                        put("last_mes", conversation.updateAt.toEpochMilli())
                        put("char_name", assistant.tavernData?.name?.ifBlank { assistant.name } ?: assistant.name)
                        put("avatar", avatarName(assistant.avatar, assistant.name))
                    })
                }
        }.toString()
    }

    private suspend fun chatMessagesJson(request: TavernRequest): String {
        val repo = conversations ?: return "[]"
        val body = parseBody(request)
        val raw = listOf("file_name", "file_id", "conversation_id", "chat_id")
            .mapNotNull { body.field(it) ?: request.queryValue(it)?.takeIf { v -> v.isNotBlank() } }
            .firstOrNull() ?: return "[]"
        val id = runCatching {
            Uuid.parse(raw.removeSuffix(CHAT_FILE_SUFFIX).removeSuffix(".json"))
        }.getOrNull() ?: return "[]"
        val conversation = runCatching { repo.getConversationById(id) }.getOrNull() ?: return "[]"
        val settings = settingsProvider()
        val assistant = settings.assistants.firstOrNull { it.id == conversation.assistantId }
        val charName = assistant?.tavernData?.name?.ifBlank { assistant.name } ?: assistant?.name.orEmpty()
        val userName = settings.personas.firstOrNull { it.id == settings.activePersonaId }
            ?.name?.ifBlank { DEFAULT_USER_NAME } ?: DEFAULT_USER_NAME
        return buildJsonArray {
            conversation.currentMessages.forEach { message ->
                add(buildJsonObject {
                    put("name", if (message.role == MessageRole.USER) userName else charName)
                    put("is_user", message.role == MessageRole.USER)
                    put("is_system", message.role == MessageRole.SYSTEM)
                    put("send_date", message.createdAt.toString())
                    put("mes", message.plainText())
                    put("extra", buildJsonObject {})
                })
            }
        }.toString()
    }

    private fun lastText(conversation: Conversation): String =
        conversation.currentMessages.lastOrNull { it.plainText().isNotBlank() }?.plainText().orEmpty()

    private fun UIMessage.plainText(): String =
        parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    private fun avatarName(avatar: Avatar?, fallback: String): String = when (avatar) {
        is Avatar.Image -> avatar.url.substringAfterLast('/').ifBlank { fallback }
        is Avatar.Emoji -> avatar.content
        else -> fallback
    }

    private fun parseBody(request: TavernRequest): JsonObject? =
        runCatching {
            Json.parseToJsonElement(request.bodyText()) as? JsonObject
        }.getOrNull()

    private fun JsonObject?.field(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private companion object {
        const val DEFAULT_USER_NAME = "User"
        const val CHAT_FILE_SUFFIX = ".jsonl"
        const val RECENT_PER_ASSISTANT = 10
        const val RECENT_LIMIT = 30
        const val MESSAGE_PREVIEW_CHARS = 200
    }
}
