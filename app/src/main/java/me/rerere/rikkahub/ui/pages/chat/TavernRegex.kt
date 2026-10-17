package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import kotlin.uuid.Uuid

// 酒馆助手 TavernRegex <-> RikkaHub AssistantRegex。
// 字段对齐 @types/function/tavern_regex.d.ts。

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

fun AssistantRegex.toTavernRegexJson(scope: String): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id.toString()))
    put("script_name", JsonPrimitive(name))
    put("enabled", JsonPrimitive(enabled))
    put("run_on_edit", JsonPrimitive(false))
    put("scope", JsonPrimitive(scope))
    put("find_regex", JsonPrimitive(findRegex))
    put("trim_strings", JsonPrimitive(""))
    put("replace_string", JsonPrimitive(replaceString))
    put(
        "source",
        buildJsonObject {
            put("user_input", JsonPrimitive(AssistantAffectScope.USER in affectingScope))
            put("ai_output", JsonPrimitive(AssistantAffectScope.ASSISTANT in affectingScope))
            put("slash_command", JsonPrimitive(false))
            put("world_info", JsonPrimitive(false))
            put("reasoning", JsonPrimitive(false))
        },
    )
    put(
        "destination",
        buildJsonObject {
            put("display", JsonPrimitive(!promptOnly))
            put("prompt", JsonPrimitive(!visualOnly))
        },
    )
    put("min_depth", if (minDepth > 0) JsonPrimitive(minDepth) else kotlinx.serialization.json.JsonNull)
    put("max_depth", if (maxDepth > 0) JsonPrimitive(maxDepth) else kotlinx.serialization.json.JsonNull)
}

fun jsonToAssistantRegex(obj: JsonObject, fallback: AssistantRegex? = null): AssistantRegex {
    val base = fallback ?: AssistantRegex(id = Uuid.random())
    val source = obj["source"] as? JsonObject
    val destination = obj["destination"] as? JsonObject
    val scopes = buildSet {
        if (source?.bool("user_input") == true) add(AssistantAffectScope.USER)
        if (source?.bool("ai_output") == true) add(AssistantAffectScope.ASSISTANT)
    }
    val display = destination?.bool("display") ?: true
    val prompt = destination?.bool("prompt") ?: true
    val id = obj.str("id")?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: base.id
    return base.copy(
        id = id,
        name = obj.str("script_name") ?: base.name,
        enabled = obj.bool("enabled") ?: base.enabled,
        findRegex = obj.str("find_regex") ?: base.findRegex,
        replaceString = obj.str("replace_string") ?: base.replaceString,
        affectingScope = if (source == null) base.affectingScope else scopes,
        visualOnly = display && !prompt,
        promptOnly = prompt && !display,
        minDepth = (obj["min_depth"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
        maxDepth = (obj["max_depth"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
    )
}

fun tavernRegexesToJson(regexes: List<AssistantRegex>, scope: String): JsonArray = buildJsonArray {
    regexes.forEach { add(it.toTavernRegexJson(scope)) }
}

fun jsonToAssistantRegexes(element: JsonElement?): List<AssistantRegex> =
    (element as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { obj -> jsonToAssistantRegex(obj) } }.orEmpty()
