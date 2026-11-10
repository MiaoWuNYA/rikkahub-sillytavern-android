package me.rerere.rikkahub.data.st.regex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.globalRegexScripts
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.st.runtime.enabledPresetGroups
import me.rerere.rikkahub.data.st.script.enabledPresetGroupsCompat

// 合并全局 → 已启用预设内嵌 → 角色卡内嵌三源正则（对齐 ST getRegexScripts({allowedOnly:true})）
fun mergeActiveRegexScripts(
    settings: Settings,
    assistant: Assistant?,
    extraModeInjectionIds: Set<kotlin.uuid.Uuid> = emptySet(),
    global: List<RegexScript>? = null,
): List<RegexScript> {
    val activeGroups = enabledPresetGroupsCompat(assistant, settings, extraModeInjectionIds) +
        enabledPresetGroups(assistant, settings, extraModeInjectionIds) +
        presetGroupsFromModeInjections(settings, assistant, extraModeInjectionIds)
    val presetScriptsByGroup = LinkedHashMap<String, List<RegexScript>>()
    for (group in activeGroups) {
        val rawPreset = when (val el = settings.huadengSettings.tavernPresets[group]) {
            is JsonPrimitive -> el.contentOrNull
            is JsonObject -> el.toString()
            else -> null
        }
        val scripts = if (rawPreset.isNullOrBlank()) {
            emptyList()
        } else {
            parsePresetRegexScripts(rawPreset, group)
        }
        if (scripts.isNotEmpty()) {
            presetScriptsByGroup[group] = scripts
        }
    }
    val flatPresetScripts = presetScriptsByGroup.values.flatten()
    return mergeRegexScripts(
        global = global ?: settings.globalRegexScripts(),
        card = parseCardRegexScripts(cardExtensionsObject(assistant)),
        preset = flatPresetScripts,
        presetByGroup = presetScriptsByGroup,
    )
}

// ModeInjection.presetGroup 是预设归属的持久来源，不依赖导入期的内存映射
private fun presetGroupsFromModeInjections(
    settings: Settings,
    assistant: Assistant?,
    extraModeInjectionIds: Set<Uuid>,
): Set<String> {
    val ids = assistant?.modeInjectionIds.orEmpty() + extraModeInjectionIds
    if (ids.isEmpty()) return emptySet()
    return settings.modeInjections
        .filter { it.id in ids && it.enabled }
        .mapNotNull { it.presetGroup?.takeIf { group -> group.isNotBlank() } }
        .toSet()
}

// 全局 → 预设 → 角色卡，按来源标记隔离去重
fun mergeRegexScripts(
    global: List<RegexScript>,
    card: List<RegexScript>?,
    preset: List<RegexScript> = emptyList(),
    presetByGroup: Map<String, List<RegexScript>> = emptyMap(),
): List<RegexScript> {
    val cardList = card.orEmpty()
    if (cardList.isEmpty() && preset.isEmpty() && presetByGroup.isEmpty()) return global
    val seenKeys = HashSet<String>(global.size + preset.size + cardList.size)
    return buildList(global.size + preset.size + cardList.size) {
        for (script in global) {
            if (seenKeys.add(dedupKey("global", script))) {
                add(script)
            }
        }
        if (presetByGroup.isNotEmpty()) {
            for ((group, scripts) in presetByGroup) {
                val tag = "preset:$group"
                for (script in scripts) {
                    if (seenKeys.add(dedupKey(tag, script))) {
                        add(script)
                    }
                }
            }
        } else {
            for (script in preset) {
                if (seenKeys.add(dedupKey("preset", script))) {
                    add(script)
                }
            }
        }
        for (script in cardList) {
            if (seenKeys.add(dedupKey("card", script))) {
                add(script)
            }
        }
    }
}

private fun dedupKey(sourceTag: String, script: RegexScript): String {
    val normalizedId = script.id.trim()
    return if (normalizedId.isNotEmpty()) {
        "$sourceTag#id:$normalizedId#${script.findRegex.hashCode()}#${script.replaceString.hashCode()}"
    } else {
        "$sourceTag#sig:${script.scriptName}#${script.findRegex}#${script.replaceString}#${script.placement.joinToString(",")}"
    }
}

// 角色卡内嵌正则：从 tavernData.extensionsRaw 的 regex_scripts 解析
fun cardExtensionsObject(assistant: Assistant?): JsonObject? {
    val raw = assistant?.tavernData?.extensionsRaw
    if (raw.isNullOrBlank()) return null
    return runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

// 预设内嵌正则：解析后打上 sourcePresetGroup 标记
fun parsePresetRegexScripts(rawPreset: String, group: String): List<RegexScript> {
    val root = runCatching {
        (Json.parseToJsonElement(rawPreset) as? JsonObject)
            ?.get("extensions") as? JsonObject
    }.getOrNull() ?: return emptyList()
    return parseCardRegexScripts(root)
        .map { it.copy(sourcePresetGroup = group) }
}

// 宽容解析角色卡 data.extensions.regex_scripts（id 数字、placement 单值、布尔 0/1 等历史形态）
fun parseCardRegexScripts(extensionsObj: JsonObject?): List<RegexScript> {
    val raw = extensionsObj?.get("regex_scripts")
        ?: extensionsObj?.get("regexScripts")
        ?: return emptyList()
    val arr = raw as? JsonArray ?: return emptyList()
    return arr.mapNotNull { parseRegexScript(it) }
        .map { if (it.id.isBlank()) it.copy(id = Uuid.random().toString()) else it }
        .filter { it.findRegex.isNotBlank() }
}

// 单条内嵌正则的宽容解析；解析失败返回 null
internal fun parseRegexScript(element: JsonElement): RegexScript? {
    val obj = element as? JsonObject ?: return null

    fun raw(key: String, alt: String? = null): JsonElement? = obj[key] ?: alt?.let { obj[it] }

    fun primitive(key: String, alt: String? = null): JsonPrimitive? = raw(key, alt) as? JsonPrimitive

    fun stringOf(key: String, alt: String? = null, default: String = ""): String =
        primitive(key, alt)?.contentOrNull ?: default

    fun intOf(key: String, alt: String? = null): Int? {
        val text = primitive(key, alt)?.contentOrNull?.trim() ?: return null
        if (text.isEmpty()) return null
        return text.toIntOrNull() ?: text.toDoubleOrNull()?.toInt()
    }

    fun intOrBoolOf(key: String, alt: String? = null): Int? {
        intOf(key, alt)?.let { return it }
        return when (primitive(key, alt)?.contentOrNull?.trim()?.lowercase()) {
            "true" -> 1
            "false" -> 0
            else -> null
        }
    }

    fun boolOf(key: String, alt: String? = null, default: Boolean = false): Boolean {
        val text = primitive(key, alt)?.contentOrNull?.trim()?.lowercase() ?: return default
        return text == "true" || text == "1" || text == "yes" || text == "on"
    }

    fun intListOf(key: String): List<Int> = when (val el = obj[key]) {
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull() }
        is JsonPrimitive -> listOfNotNull(el.contentOrNull?.trim()?.toIntOrNull())
        else -> emptyList()
    }

    fun stringListOf(key: String, alt: String? = null): List<String> = when (val el = raw(key, alt)) {
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(el.contentOrNull?.takeIf { it.isNotBlank() })
        else -> emptyList()
    }

    val disabled = when {
        primitive("disabled") != null -> boolOf("disabled")
        primitive("enabled") != null -> !boolOf("enabled", default = true)
        else -> false
    }

    return RegexScript(
        id = stringOf("id"),
        scriptName = stringOf("scriptName", "script_name", "name"),
        findRegex = stringOf("findRegex", "find_regex"),
        replaceString = stringOf("replaceString", "replace_string"),
        trimStrings = stringListOf("trimStrings", "trim_strings"),
        placement = intListOf("placement"),
        disabled = disabled,
        markdownOnly = boolOf("markdownOnly", "markdown_only"),
        promptOnly = boolOf("promptOnly", "prompt_only"),
        runOnEdit = boolOf("runOnEdit", "run_on_edit"),
        substituteRegex = intOrBoolOf("substituteRegex", "substitute_regex") ?: 0,
        minDepth = intOf("minDepth", "min_depth"),
        maxDepth = intOf("maxDepth", "max_depth"),
    )
}
