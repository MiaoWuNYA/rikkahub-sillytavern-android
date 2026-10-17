package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SelectiveLogic

// 酒馆助手世界书条目 <-> RikkaHub RegexInjection 的互转。
// 字段命名严格对齐 @types/function/worldbook.d.ts 的 WorldbookEntry。

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonPrimitive.intOrNull(): Int? =
    if (this is JsonNull) null else content.toIntOrNull()

private fun JsonElement?.nullableInt(): Int? =
    (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toIntOrNull()

private fun positionTypeOf(position: InjectionPosition): String = when (position) {
    InjectionPosition.BEFORE_CHARACTER -> "before_character_definition"
    InjectionPosition.AFTER_CHARACTER -> "after_character_definition"
    InjectionPosition.EM_TOP -> "before_example_messages"
    InjectionPosition.EM_BOTTOM -> "after_example_messages"
    InjectionPosition.AUTHOR_NOTE -> "before_author_note"
    InjectionPosition.AT_DEPTH -> "at_depth"
    else -> "after_character_definition"
}

private fun positionOf(type: String?): InjectionPosition = when (type) {
    "before_character_definition" -> InjectionPosition.BEFORE_CHARACTER
    "after_character_definition" -> InjectionPosition.AFTER_CHARACTER
    "before_example_messages" -> InjectionPosition.EM_TOP
    "after_example_messages" -> InjectionPosition.EM_BOTTOM
    "before_author_note", "after_author_note" -> InjectionPosition.AUTHOR_NOTE
    "at_depth" -> InjectionPosition.AT_DEPTH
    else -> InjectionPosition.AFTER_CHARACTER
}

private fun logicOf(logic: SelectiveLogic): String = when (logic) {
    SelectiveLogic.AND_ALL -> "and_all"
    SelectiveLogic.NOT_ANY -> "not_any"
    SelectiveLogic.NOT_ALL -> "not_all"
    else -> "and_any"
}

private fun logicFrom(logic: String?): SelectiveLogic = when (logic) {
    "and_all" -> SelectiveLogic.AND_ALL
    "not_any" -> SelectiveLogic.NOT_ANY
    "not_all" -> SelectiveLogic.NOT_ALL
    "or_any" -> SelectiveLogic.OR_ANY
    else -> SelectiveLogic.AND_ANY
}

private fun roleOf(role: MessageRole): String = when (role) {
    MessageRole.USER -> "user"
    MessageRole.SYSTEM -> "system"
    else -> "assistant"
}

private fun messageRoleOf(role: String?): MessageRole = when (role) {
    "user" -> MessageRole.USER
    "system" -> MessageRole.SYSTEM
    else -> MessageRole.ASSISTANT
}

private fun keysOf(element: JsonElement?): List<String> =
    (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

// RegexInjection -> WorldbookEntry
fun lorebookEntryToJson(entry: PromptInjection.RegexInjection, uid: Int): JsonObject = buildJsonObject {
    put("uid", JsonPrimitive(uid))
    put("name", JsonPrimitive(entry.name))
    put("enabled", JsonPrimitive(entry.enabled))
    put("content", JsonPrimitive(entry.content))
    val strategyType = when {
        entry.vectorized -> "vectorized"
        entry.constantActive -> "constant"
        else -> "selective"
    }
    put(
        "strategy",
        buildJsonObject {
            put("type", JsonPrimitive(strategyType))
            put("keys", buildJsonArray { entry.keywords.forEach { add(JsonPrimitive(it)) } })
            put(
                "keys_secondary",
                buildJsonObject {
                    put("logic", JsonPrimitive(logicOf(entry.selectiveLogic)))
                    put("keys", buildJsonArray { entry.secondaryKeys.forEach { add(JsonPrimitive(it)) } })
                },
            )
            put(
                "scan_depth",
                entry.scanDepth?.let { JsonPrimitive(it) } ?: JsonPrimitive("same_as_global"),
            )
        },
    )
    put(
        "position",
        buildJsonObject {
            put("type", JsonPrimitive(positionTypeOf(entry.position)))
            put("role", JsonPrimitive(roleOf(entry.role)))
            put("depth", JsonPrimitive(entry.injectDepth))
            put("order", JsonPrimitive(entry.priority))
        },
    )
    put("probability", JsonPrimitive(entry.probability))
    put(
        "recursion",
        buildJsonObject {
            put("prevent_incoming", JsonPrimitive(entry.preventRecursion))
            put("prevent_outgoing", JsonPrimitive(entry.excludeRecursion))
            put("delay_until", entry.delayUntilRecursion.takeIf { it > 0 }?.let { JsonPrimitive(it) } ?: JsonNull)
        },
    )
    put(
        "effect",
        buildJsonObject {
            put("sticky", entry.sticky.takeIf { it > 0 }?.let { JsonPrimitive(it) } ?: JsonNull)
            put("cooldown", entry.cooldown.takeIf { it > 0 }?.let { JsonPrimitive(it) } ?: JsonNull)
            put("delay", entry.delay.takeIf { it > 0 }?.let { JsonPrimitive(it) } ?: JsonNull)
        },
    )
    put("extra", buildJsonObject { })
}

// WorldbookEntry(partial) -> RegexInjection。未给的字段保留 fallback（新建时用默认值）。
fun jsonToLorebookEntry(obj: JsonObject, fallback: PromptInjection.RegexInjection? = null): PromptInjection.RegexInjection {
    val base = fallback ?: PromptInjection.RegexInjection()
    val strategy = obj["strategy"] as? JsonObject
    val secondary = strategy?.get("keys_secondary") as? JsonObject
    val position = obj["position"] as? JsonObject
    val recursion = obj["recursion"] as? JsonObject
    val effect = obj["effect"] as? JsonObject
    val type = strategy?.str("type")
    val scanDepthRaw = strategy?.get("scan_depth")
    val scanDepth = if (scanDepthRaw == null || scanDepthRaw is JsonPrimitive && scanDepthRaw.content.toIntOrNull() == null) {
        null
    } else {
        scanDepthRaw.nullableInt()
    }
    return base.copy(
        name = obj.str("name") ?: base.name,
        enabled = obj.bool("enabled") ?: base.enabled,
        content = obj.str("content") ?: base.content,
        keywords = if (strategy == null) base.keywords else keysOf(strategy["keys"]),
        secondaryKeys = if (secondary == null) base.secondaryKeys else keysOf(secondary["keys"]),
        selectiveLogic = logicFrom(secondary?.str("logic")),
        selective = true,
        constantActive = type == "constant" || (type == null && base.constantActive),
        vectorized = type == "vectorized" || (type == null && base.vectorized),
        scanDepth = scanDepth,
        position = if (position == null) base.position else positionOf(position.str("type")),
        role = if (position?.str("role") == null) base.role else messageRoleOf(position.str("role")),
        injectDepth = position?.int("depth") ?: base.injectDepth,
        priority = position?.int("order") ?: base.priority,
        probability = obj.int("probability") ?: base.probability,
        preventRecursion = recursion?.bool("prevent_incoming") ?: base.preventRecursion,
        excludeRecursion = recursion?.bool("prevent_outgoing") ?: base.excludeRecursion,
        delayUntilRecursion = recursion?.get("delay_until").nullableInt() ?: 0,
        sticky = effect?.get("sticky").nullableInt() ?: 0,
        cooldown = effect?.get("cooldown").nullableInt() ?: 0,
        delay = effect?.get("delay").nullableInt() ?: 0,
    )
}

// 世界书条目数组 -> JSON 数组（uid 按脚下标）
fun lorebookEntriesToJson(entries: List<PromptInjection.RegexInjection>): JsonArray = buildJsonArray {
    entries.forEachIndexed { index, entry -> add(lorebookEntryToJson(entry, index)) }
}
