package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import kotlin.uuid.Uuid

// 酒馆助手角色卡 Character <-> RikkaHub Assistant（含 tavernData）。
// 字段命名对齐 @types/function/character.d.ts。

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content

private fun AssistantRegex.toRegexScriptJson(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id.toString()))
    put("scriptName", JsonPrimitive(name))
    put("findRegex", JsonPrimitive(findRegex))
    put("replaceString", JsonPrimitive(replaceString))
    put("trimStrings", buildJsonArray { })
    put("placement", buildJsonArray {
        if (AssistantAffectScope.USER in affectingScope) add(JsonPrimitive(1))
        if (AssistantAffectScope.ASSISTANT in affectingScope) add(JsonPrimitive(2))
    })
    put("disabled", JsonPrimitive(!enabled))
    put("markdownOnly", JsonPrimitive(visualOnly))
    put("promptOnly", JsonPrimitive(promptOnly))
    put("runOnEdit", JsonPrimitive(false))
    put("substituteRegex", JsonPrimitive(0))
    put("minDepth", JsonPrimitive(minDepth))
    put("maxDepth", JsonPrimitive(maxDepth))
}

private fun JsonObject.toAssistantRegex(): AssistantRegex {
    val placements = (this["placement"] as? JsonArray)?.mapNotNull { it.toString().toIntOrNull() } ?: emptyList()
    val scopes = buildSet {
        if (1 in placements) add(AssistantAffectScope.USER)
        if (2 in placements) add(AssistantAffectScope.ASSISTANT)
    }
    val id = str("id")?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: Uuid.random()
    return AssistantRegex(
        id = id,
        name = str("scriptName") ?: "",
        enabled = !(this["disabled"]?.toString() == "true"),
        findRegex = str("findRegex") ?: "",
        replaceString = str("replaceString") ?: "",
        affectingScope = scopes,
        visualOnly = this["markdownOnly"]?.toString() == "true",
        promptOnly = this["promptOnly"]?.toString() == "true",
        minDepth = this["minDepth"]?.toString()?.toIntOrNull() ?: 0,
        maxDepth = this["maxDepth"]?.toString()?.toIntOrNull() ?: 0,
    )
}

fun assistantToCharacterJson(assistant: Assistant, worldbookName: String?, characterVariables: JsonElement): JsonObject {
    val tav = assistant.tavernData
    val firstMessages = buildJsonArray {
        val primary = tav?.firstMessage.orEmpty()
        if (primary.isNotBlank()) add(JsonPrimitive(primary))
        tav?.alternateGreetings?.forEach { if (it.isNotBlank()) add(JsonPrimitive(it)) }
    }
    return buildJsonObject {
        put("name", JsonPrimitive(assistant.name))
        put("version", JsonPrimitive(tav?.characterVersion.orEmpty()))
        put("creator", JsonPrimitive(tav?.creator.orEmpty()))
        put("creator_notes", JsonPrimitive(tav?.creatorNotes.orEmpty()))
        put("worldbook", worldbookName?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        put("description", JsonPrimitive(tav?.description ?: assistant.systemPrompt))
        put("first_messages", firstMessages)
        put("personality", JsonPrimitive(tav?.personality.orEmpty()))
        put("scenario", JsonPrimitive(tav?.scenario.orEmpty()))
        put("mes_example", JsonPrimitive(tav?.mesExample.orEmpty()))
        put("system_prompt", JsonPrimitive(tav?.systemPrompt.orEmpty()))
        put("post_history_instructions", JsonPrimitive(tav?.postHistoryInstructions.orEmpty()))
        put("tags", buildJsonArray { tav?.tags?.forEach { add(JsonPrimitive(it)) } })
        put(
            "extensions",
            buildJsonObject {
                put(
                    "regex_scripts",
                    buildJsonArray { assistant.regexes.forEach { add(it.toRegexScriptJson()) } },
                )
                put(
                    "tavern_helper",
                    buildJsonObject {
                        put("scripts", buildJsonArray { })
                        put("variables", characterVariables)
                    },
                )
            },
        )
    }
}

// PartialDeep<Character> -> Assistant；只覆盖给出的字段。
fun applyCharacterJson(assistant: Assistant, obj: JsonObject): Assistant {
    val tav = assistant.tavernData
    fun jsonArrayOf(key: String): List<String>? =
        (obj[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
    val firstMessages = jsonArrayOf("first_messages")
    val extensions = obj["extensions"] as? JsonObject
    val regexScripts = (extensions?.get("regex_scripts") as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.toAssistantRegex() }
    val updatedTav = if (tav == null) {
        me.rerere.rikkahub.data.model.TavernCharacterData(
            name = obj.str("name") ?: assistant.name,
            description = obj.str("description").orEmpty(),
            personality = obj.str("personality").orEmpty(),
            scenario = obj.str("scenario").orEmpty(),
            firstMessage = firstMessages?.firstOrNull().orEmpty(),
            alternateGreetings = firstMessages?.drop(1).orEmpty(),
            mesExample = obj.str("mes_example").orEmpty(),
            systemPrompt = obj.str("system_prompt").orEmpty(),
            creator = obj.str("creator").orEmpty(),
            creatorNotes = obj.str("creator_notes").orEmpty(),
            characterVersion = obj.str("version").orEmpty(),
            postHistoryInstructions = obj.str("post_history_instructions").orEmpty(),
            tags = jsonArrayOf("tags").orEmpty(),
        )
    } else {
        tav.copy(
            description = obj.str("description") ?: tav.description,
            personality = obj.str("personality") ?: tav.personality,
            scenario = obj.str("scenario") ?: tav.scenario,
            firstMessage = firstMessages?.firstOrNull() ?: tav.firstMessage,
            alternateGreetings = firstMessages?.drop(1) ?: tav.alternateGreetings,
            mesExample = obj.str("mes_example") ?: tav.mesExample,
            systemPrompt = obj.str("system_prompt") ?: tav.systemPrompt,
            creator = obj.str("creator") ?: tav.creator,
            creatorNotes = obj.str("creator_notes") ?: tav.creatorNotes,
            characterVersion = obj.str("version") ?: tav.characterVersion,
            postHistoryInstructions = obj.str("post_history_instructions") ?: tav.postHistoryInstructions,
            tags = jsonArrayOf("tags") ?: tav.tags,
        )
    }
    return assistant.copy(
        name = obj.str("name") ?: assistant.name,
        tavernData = updatedTav,
        regexes = regexScripts ?: assistant.regexes,
    )
}
