package me.rerere.rikkahub.data.st.import

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.RegexGroup
import me.rerere.rikkahub.data.model.RegexGroupSource
import me.rerere.rikkahub.data.st.preset.StCardParseCompat
import me.rerere.rikkahub.data.st.preset.parseStPresetBundleCompat

/** 参考 data.export.PresetImportBundle 的最小等价物：宿主没有 presetScripts / unsupportedExtensions 落点。 */
internal data class PresetImportBundle(
    val presetGroup: String,
    val injections: List<PromptInjection.ModeInjection>,
    val rawPresetJson: String?,
    val regexScripts: List<AssistantRegex>,
)

private val stImportJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** 一次性解析预设文件：注入 + 原始 JSON + 内嵌正则（宿主无 Settings.regexScripts，正则改走 PRESET 分组）。 */
internal fun importPresetBundleFromString(json: String, fileName: String?): Result<PresetImportBundle> =
    runCatching {
        val parsed = parseStPresetBundleCompat(json, fileName)
            ?: error("Unsupported preset format")
        val root = runCatching { stImportJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        PresetImportBundle(
            presetGroup = parsed.name,
            injections = parsed.injections,
            rawPresetJson = if (root?.get("prompts") is JsonArray) json else null,
            regexScripts = parsed.regexScripts,
        )
    }

/** 原子合并：注入按名字复用旧 id，正则进同名 PRESET 分组，原始预设写回 tavernPresets。 */
internal fun applyPresetBundleToSettings(
    current: Settings,
    bundle: PresetImportBundle,
): Pair<Settings, Int> {
    val bundleNames = bundle.injections.map { it.name }.toSet()
    val oldEntries = current.modeInjections.filter { it.name in bundleNames }
    val oldIdByName = oldEntries.associate { it.name to it.id }
    val oldIds = oldEntries.map { it.id }.toSet()
    val reconciled = bundle.injections.map { injection ->
        val existingId = oldIdByName[injection.name]
        if (existingId != null) injection.copy(id = existingId) else injection
    }
    val newIds = reconciled.map { it.id }.toSet()
    val nextInjections = current.modeInjections.filterNot { it.name in bundleNames } + reconciled
    // 宿主 tavernPresets 在 huadengSettings 上（Settings.kt:52 → HuaDengSettings:247），类型为 Map<String, JsonElement>
    val currentPresets = current.huadengSettings.tavernPresets
    val nextPresets = if (bundle.rawPresetJson != null) {
        val element = runCatching {
            stImportJson.parseToJsonElement(bundle.rawPresetJson)
        }.getOrNull()
        if (element != null) currentPresets + (bundle.presetGroup to element) else currentPresets
    } else {
        currentPresets
    }
    val nextRegexGroups = if (bundle.regexScripts.isEmpty()) {
        current.regexGroups
    } else {
        val existing = current.regexGroups.firstOrNull {
            it.source == RegexGroupSource.PRESET && it.name == bundle.presetGroup
        }
        if (existing != null) {
            current.regexGroups.map {
                if (it.id == existing.id) it.copy(regexes = bundle.regexScripts) else it
            }
        } else {
            current.regexGroups + RegexGroup(
                name = bundle.presetGroup,
                source = RegexGroupSource.PRESET,
                regexes = bundle.regexScripts,
            )
        }
    }
    val nextAssistants = if (oldIds.isEmpty()) {
        current.assistants
    } else {
        current.assistants.map { assistant ->
            if (assistant.modeInjectionIds.any { it in oldIds }) {
                assistant.copy(
                    modeInjectionIds = (assistant.modeInjectionIds - oldIds) + newIds,
                )
            } else {
                assistant
            }
        }
    }
    return current.copy(
        modeInjections = nextInjections,
        huadengSettings = current.huadengSettings.copy(tavernPresets = nextPresets),
        regexGroups = nextRegexGroups,
        assistants = nextAssistants,
    ) to bundle.regexScripts.size
}

/** 参考 ui.pages.assistant.detail.parseCardFromUri 的薄包装：IO 线程读 Uri 后走纯解析。 */
internal suspend fun parseCardFromUri(
    context: Context,
    uri: Uri,
): Pair<Assistant, List<Lorebook>>? = withContext(Dispatchers.IO) {
    StCardParseCompat.parseCardFromUri(context, uri)
}
