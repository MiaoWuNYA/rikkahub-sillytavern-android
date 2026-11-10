package me.rerere.rikkahub.data.st.preset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.export.SillyTavernRegexImporter
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.RegexGroup
import me.rerere.rikkahub.data.model.RegexGroupSource

/**
 * 最小等价实现（等待 data/export 正式 PresetImportBundle 交付后替换）：
 * ST「OpenAI Settings」预设 → 模式注入 + 全局正则分组。
 */
internal data class StPresetBundleCompat(
    val name: String,
    val injections: List<PromptInjection.ModeInjection>,
    val regexScripts: List<AssistantRegex>,
)

private val stPresetJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun parseStPresetBundleCompat(json: String, fileName: String?): StPresetBundleCompat? {
    val root = runCatching { stPresetJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
    val name = fileName?.takeIf { it.isNotBlank() }
        ?: str(root["name"])?.takeIf { it.isNotBlank() }
        ?: return null
    val prompts = root["prompts"] as? JsonArray
    val injections = prompts.orEmpty().mapNotNull { element ->
        val prompt = element as? JsonObject ?: return@mapNotNull null
        val content = str(prompt["content"]).orEmpty()
        if (content.isBlank()) return@mapNotNull null
        PromptInjection.ModeInjection(
            name = str(prompt["name"]).orEmpty(),
            enabled = bool(prompt["enabled"]) ?: true,
            position = if ((int(prompt["injection_position"]) ?: 0) == 1) {
                InjectionPosition.TOP_OF_CHAT
            } else {
                InjectionPosition.AFTER_SYSTEM_PROMPT
            },
            content = content,
            injectDepth = int(prompt["injection_depth"]) ?: 4,
            role = when (str(prompt["role"])?.lowercase()) {
                "system" -> MessageRole.SYSTEM
                "assistant" -> MessageRole.ASSISTANT
                else -> MessageRole.USER
            },
        )
    }
    val regexScripts = SillyTavernRegexImporter.parseFromPreset(json)
    return StPresetBundleCompat(name = name, injections = injections, regexScripts = regexScripts)
}

/** 把预设落到设置上：注入追加，正则按预设名收进 PRESET 分组（同名覆盖）。 */
internal fun applyStPresetBundleCompat(
    current: Settings,
    bundle: StPresetBundleCompat,
): Pair<Settings, Int> {
    var next = current.copy(modeInjections = current.modeInjections + bundle.injections)
    var addedRegex = 0
    if (bundle.regexScripts.isNotEmpty()) {
        val existing = next.regexGroups.firstOrNull {
            it.source == RegexGroupSource.PRESET && it.name == bundle.name
        }
        next = next.copy(
            regexGroups = if (existing != null) {
                next.regexGroups.map {
                    if (it.id == existing.id) it.copy(regexes = bundle.regexScripts) else it
                }
            } else {
                next.regexGroups + RegexGroup(
                    name = bundle.name,
                    source = RegexGroupSource.PRESET,
                    regexes = bundle.regexScripts,
                )
            },
        )
        addedRegex = bundle.regexScripts.size
    }
    return next to addedRegex
}

private fun str(element: kotlinx.serialization.json.JsonElement?): String? =
    (element as? JsonPrimitive)?.contentOrNull

private fun bool(element: kotlinx.serialization.json.JsonElement?): Boolean? =
    (element as? JsonPrimitive)?.booleanOrNull

private fun int(element: kotlinx.serialization.json.JsonElement?): Int? =
    (element as? JsonPrimitive)?.intOrNull
