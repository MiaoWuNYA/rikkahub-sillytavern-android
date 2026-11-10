package me.rerere.rikkahub.data.st.import

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.export.SillyTavernRegexImporter
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.RegexGroup
import me.rerere.rikkahub.data.model.RegexGroupSource
import me.rerere.rikkahub.data.st.preset.TavernVariableStoreCompat

/**
 * [v236 C3] ST settings.json 导入器：power_user / oai_settings / extension_settings
 * 三段，另附 world_info_settings 段（宿主 `Settings.worldInfo*` 已是一等公民）。
 *
 * ## 真源（ST 1.19.0，只读参考，逐字段对齐字段名/默认值/语义，不逐行复制实现）
 * - 默认对象：`public/scripts/power-user.js` 的 `power_user`；
 * - 默认对象：`public/scripts/openai.js` 的 `default_settings`（= `oai_settings` 初值）；
 * - 默认对象：`public/scripts/extensions.js` 的 `extension_settings`；
 * - 世界书设置：`public/scripts/world-info.js` 的顶层 `world_info_*` 变量。
 *
 * ## 分层
 * - 纯函数层（无 Android，可 JVM 单测）：[mapStSettings] / [applyStSettingsPatch] / [exportStSettings]；
 * - 落库层：[StSettingsImporter]（SettingsStore + TavernVariableStore）。
 *
 * ## 落点速查
 * | ST | 宿主 |
 * | --- | --- |
 * | power_user 显示类字段 | `Settings.displaySetting.*` |
 * | oai_settings.enable_web_search | `Settings.enableWebSearch` |
 * | oai_settings.show_thoughts | `Settings.displaySetting.showThinkingContent` |
 * | extension_settings.regex | `Settings.regexScripts` |
 * | extension_settings.note.default | `Settings.authorNote` |
 * | extension_settings.disabledExtensions | `Settings.tavernThirdPartyDisabled` |
 * | extension_settings（除 variables） | `Settings.tavernExtensionSettings`（整块透传 JSON） |
 * | extension_settings.variables.global | `TavernVariableStore`（vars.json，ST 全局变量作用域） |
 * | world_info_settings.* | `Settings.worldInfo*` |
 *
 * 逐字段对照表（含暂不映射原因）见 `notes/c3-st-settings-import.md`。
 */
internal val StSettingsJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/** settings.json 里本导入器认得的顶层段。 */
internal val ST_SETTINGS_SECTIONS = setOf(
    "power_user",
    "oai_settings",
    "extension_settings",
    "world_info_settings",
)

/** 显示类补丁（null = 本次 settings.json 未提供该字段，落库时保持宿主现值）。 */
data class StDisplayPatch(
    val sendOnEnter: Boolean? = null,
    val showDateTimeInMessage: Boolean? = null,
    val showTokenUsage: Boolean? = null,
    val enableAutoScroll: Boolean? = null,
    val fontSizeRatio: Float? = null,
    val italicsColor: String? = null,
    val showThinkingContent: Boolean? = null,
)

/** 世界书设置补丁。 */
data class StWorldInfoPatch(
    val budget: Int? = null,
    val budgetCap: Int? = null,
    val minActivations: Int? = null,
    val minActivationsDepthMax: Int? = null,
    val recursive: Boolean? = null,
    val maxRecursionSteps: Int? = null,
    val depth: Int? = null,
    val characterStrategy: Int? = null,
    val overflowAlert: Boolean? = null,
    val useGroupScoring: Boolean? = null,
)

/** 一次 settings.json 解析出的宿主补丁（只含能映射的字段）。 */
data class StSettingsPatch(
    val display: StDisplayPatch = StDisplayPatch(),
    val worldInfo: StWorldInfoPatch = StWorldInfoPatch(),
    val authorNote: String? = null,
    val regexScripts: List<AssistantRegex>? = null,
    val enableWebSearch: Boolean? = null,
    val thirdPartyDisabled: Set<String>? = null,
    val thirdPartySettingsJson: String? = null,
    val globalVariables: JsonObject? = null,
)

/** 解析结果：补丁 + 逐字段登记（mapped / unmapped / 不认识的顶层键）。 */
data class StSettingsMapResult(
    val patch: StSettingsPatch,
    val mappedPaths: List<String>,
    val unmappedPaths: List<String>,
    val unknownTopLevelKeys: List<String>,
)

/**
 * 解析 ST settings.json 为宿主补丁（纯函数，可 JVM 单测）。
 *
 * 只把「有明确宿主落点」的字段写进补丁；其余字段计入 [StSettingsMapResult.unmappedPaths]，
 * 让调用方（与文档）能如实登记剩余面，而不是静默丢弃。
 */
internal fun mapStSettings(json: String): StSettingsMapResult {
    val root = runCatching { StSettingsJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        ?: return StSettingsMapResult(StSettingsPatch(), emptyList(), emptyList(), emptyList())

    val mapped = linkedSetOf<String>()
    val unmapped = linkedSetOf<String>()

    var sendOnEnter: Boolean? = null
    var showDateTime: Boolean? = null
    var showTokenUsage: Boolean? = null
    var autoScroll: Boolean? = null
    var fontScale: Float? = null
    var italicsColor: String? = null
    var showThinking: Boolean? = null

    root.obj("power_user")?.let { pu ->
        val produced = mutableSetOf<String>()
        sendOnEnter = pu.takeInt("send_on_enter", produced)?.let { it == 1 }
        showDateTime = pu.takeBool("timestamps_enabled", produced)
        showTokenUsage = pu.takeBool("message_token_count_enabled", produced)
        autoScroll = pu.takeBool("auto_scroll_chat_to_bottom", produced)
        fontScale = pu.takeDouble("font_scale", produced)?.toFloat()?.coerceIn(0.5f, 3.0f)
        italicsColor = pu.takeHexColor("italics_text_color", produced)
        reportSection("power_user", pu, produced, mapped, unmapped)
    }

    var enableWebSearch: Boolean? = null
    root.obj("oai_settings")?.let { oai ->
        val produced = mutableSetOf<String>()
        enableWebSearch = oai.takeBool("enable_web_search", produced)
        showThinking = oai.takeBool("show_thoughts", produced)
        reportSection("oai_settings", oai, produced, mapped, unmapped)
    }

    var authorNote: String? = null
    var regexScripts: List<AssistantRegex>? = null
    var disabled: Set<String>? = null
    var thirdPartyJson: String? = null
    var globals: JsonObject? = null
    root.obj("extension_settings")?.let { ext ->
        val produced = mutableSetOf<String>()
        ext.array("regex")?.let { arr ->
            // 宿主没有 Settings.regexScripts：ST 脚本按 ST→宿主正则规则转换后进 PRESET 分组
            val parsed = SillyTavernRegexImporter.parse(arr.toString())
            if (parsed.isNotEmpty()) {
                regexScripts = parsed
                produced += "regex"
            }
        }
        ext.obj("variables")?.let { vars ->
            (vars["global"] as? JsonObject)?.let {
                globals = it
                produced += "variables"
            }
        }
        ext.obj("note")?.str("default")?.let {
            authorNote = it
            produced += "note"
        }
        ext.array("disabledExtensions")?.let { arr ->
            disabled = arr.mapNotNull { el -> (el as? JsonPrimitive)?.contentOrNull }.toSet()
            produced += "disabledExtensions"
        }
        // 整块透传（ST 第三方扩展设置）。按宿主既有约定剔除 variables（变量走 TavernVariableStore）。
        thirdPartyJson = JsonObject(ext.filterKeys { it != "variables" }).toString()
        ext.keys.forEach { if (it != "variables") produced += it }
        reportSection("extension_settings", ext, produced, mapped, unmapped)
    }

    var world: StWorldInfoPatch? = null
    root.obj("world_info_settings")?.let { wi ->
        val produced = mutableSetOf<String>()
        world = StWorldInfoPatch(
            budget = wi.takeInt("world_info_budget", produced),
            budgetCap = wi.takeInt("world_info_budget_cap", produced),
            minActivations = wi.takeInt("world_info_min_activations", produced),
            minActivationsDepthMax = wi.takeInt("world_info_min_activations_depth_max", produced),
            recursive = wi.takeBool("world_info_recursive", produced),
            maxRecursionSteps = wi.takeInt("world_info_max_recursion_steps", produced),
            depth = wi.takeInt("world_info_depth", produced),
            characterStrategy = wi.takeInt("world_info_character_strategy", produced),
            overflowAlert = wi.takeBool("world_info_overflow_alert", produced),
            useGroupScoring = wi.takeBool("world_info_use_group_scoring", produced),
        )
        reportSection("world_info_settings", wi, produced, mapped, unmapped)
    }

    val patch = StSettingsPatch(
        display = StDisplayPatch(
            sendOnEnter = sendOnEnter,
            showDateTimeInMessage = showDateTime,
            showTokenUsage = showTokenUsage,
            enableAutoScroll = autoScroll,
            fontSizeRatio = fontScale,
            italicsColor = italicsColor,
            showThinkingContent = showThinking,
        ),
        worldInfo = world ?: StWorldInfoPatch(),
        authorNote = authorNote,
        regexScripts = regexScripts,
        enableWebSearch = enableWebSearch,
        thirdPartyDisabled = disabled,
        thirdPartySettingsJson = thirdPartyJson,
        globalVariables = globals,
    )

    return StSettingsMapResult(
        patch = patch,
        mappedPaths = mapped.sorted(),
        unmappedPaths = unmapped.sorted(),
        unknownTopLevelKeys = root.keys.filterNot { it in ST_SETTINGS_SECTIONS }.sorted(),
    )
}

/** 把补丁合并到宿主 [Settings]（纯函数，null 字段保持宿主现值）。 */
internal fun applyStSettingsPatch(current: Settings, patch: StSettingsPatch): Settings {
    var s = current
    val d = patch.display
    s = s.copy(
        displaySetting = s.displaySetting.copy(
            sendOnEnter = d.sendOnEnter ?: s.displaySetting.sendOnEnter,
            showDateTimeInMessage = d.showDateTimeInMessage ?: s.displaySetting.showDateTimeInMessage,
            showTokenUsage = d.showTokenUsage ?: s.displaySetting.showTokenUsage,
            enableAutoScroll = d.enableAutoScroll ?: s.displaySetting.enableAutoScroll,
            fontSizeRatio = d.fontSizeRatio ?: s.displaySetting.fontSizeRatio,
            italicsColor = d.italicsColor ?: s.displaySetting.italicsColor,
            showThinkingContent = d.showThinkingContent ?: s.displaySetting.showThinkingContent,
        ),
    )
    val w = patch.worldInfo
    s = s.copy(
        worldInfoBudget = w.budget ?: s.worldInfoBudget,
        worldInfoBudgetCap = w.budgetCap ?: s.worldInfoBudgetCap,
        worldInfoMinActivations = w.minActivations ?: s.worldInfoMinActivations,
        worldInfoMinActivationsDepthMax = w.minActivationsDepthMax ?: s.worldInfoMinActivationsDepthMax,
        worldInfoRecursive = w.recursive ?: s.worldInfoRecursive,
        worldInfoMaxRecursionSteps = w.maxRecursionSteps ?: s.worldInfoMaxRecursionSteps,
        worldInfoDepth = w.depth ?: s.worldInfoDepth,
        worldInfoCharacterStrategy = w.characterStrategy ?: s.worldInfoCharacterStrategy,
        worldInfoOverflowAlert = w.overflowAlert ?: s.worldInfoOverflowAlert,
        worldInfoUseGroupScoring = w.useGroupScoring ?: s.worldInfoUseGroupScoring,
    )
    patch.authorNote?.let { s = s.copy(authorNote = it) }
    patch.regexScripts?.let { importedGlobal ->
        // 宿主 Settings 没有 regexScripts：全局正则按 ST settings.json 收进一个 PRESET 分组
        val existing = s.regexGroups.firstOrNull {
            it.source == RegexGroupSource.PRESET && it.name == ST_SETTINGS_REGEX_GROUP
        }
        s = s.copy(
            regexGroups = if (existing != null) {
                s.regexGroups.map {
                    if (it.id == existing.id) it.copy(regexes = importedGlobal) else it
                }
            } else {
                s.regexGroups + RegexGroup(
                    name = ST_SETTINGS_REGEX_GROUP,
                    source = RegexGroupSource.PRESET,
                    regexes = importedGlobal,
                )
            },
        )
    }
    patch.enableWebSearch?.let { s = s.copy(enableWebSearch = it) }
    // 移植缺口：宿主 Settings 无 tavernThirdPartyDisabled / tavernExtensionSettings 落点，仅保留在补丁里
    return s
}

/**
 * 把宿主 [Settings] 反向导出为 ST settings.json 形状（round-trip 单测用；也可作为「回读」取证）。
 *
 * 只导出能映射的字段；[thirdPartySettings] 会并回 extension_settings 整块。
 */
internal fun exportStSettings(
    settings: Settings,
    globalVariables: JsonObject = JsonObject(emptyMap()),
    thirdPartySettings: JsonObject = JsonObject(emptyMap()),
    thirdPartyDisabled: Set<String> = emptySet(),
): String {
    val root = buildJsonObject {
        put(
            "power_user",
            buildJsonObject {
                put("send_on_enter", if (settings.displaySetting.sendOnEnter) 1 else 0)
                put("timestamps_enabled", settings.displaySetting.showDateTimeInMessage)
                put("message_token_count_enabled", settings.displaySetting.showTokenUsage)
                put("auto_scroll_chat_to_bottom", settings.displaySetting.enableAutoScroll)
                put("font_scale", settings.displaySetting.fontSizeRatio)
                put("italics_text_color", settings.displaySetting.italicsColor)
            },
        )
        put(
            "oai_settings",
            buildJsonObject {
                put("enable_web_search", settings.enableWebSearch)
                put("show_thoughts", settings.displaySetting.showThinkingContent)
            },
        )
        put(
            "world_info_settings",
            buildJsonObject {
                put("world_info_budget", settings.worldInfoBudget)
                put("world_info_budget_cap", settings.worldInfoBudgetCap)
                put("world_info_min_activations", settings.worldInfoMinActivations)
                put("world_info_min_activations_depth_max", settings.worldInfoMinActivationsDepthMax)
                put("world_info_recursive", settings.worldInfoRecursive)
                put("world_info_max_recursion_steps", settings.worldInfoMaxRecursionSteps)
                put("world_info_depth", settings.worldInfoDepth)
                put("world_info_character_strategy", settings.worldInfoCharacterStrategy)
                put("world_info_overflow_alert", settings.worldInfoOverflowAlert)
                put("world_info_use_group_scoring", settings.worldInfoUseGroupScoring)
            },
        )
        put(
            "extension_settings",
            buildJsonObject {
                put("regex", stRegexArray(settings.globalRegexes))
                put("note", buildJsonObject { put("default", settings.authorNote) })
                put(
                    "disabledExtensions",
                    buildJsonArray { thirdPartyDisabled.forEach { add(JsonPrimitive(it)) } },
                )
                put("variables", buildJsonObject { put("global", globalVariables) })
                thirdPartySettings.forEach { (k, v) ->
                    if (k !in EXTENSION_BLOB_RESERVED) put(k, v)
                }
            },
        )
    }
    return StSettingsJson.encodeToString(JsonObject.serializer(), root)
}

private val EXTENSION_BLOB_RESERVED = setOf("regex", "note", "disabledExtensions", "variables")

/** settings.json 导入的全局正则落点分组名。 */
private const val ST_SETTINGS_REGEX_GROUP = "SillyTavern settings"

/** 宿主正则 → ST RegexScriptData 形状（round-trip 单测/回读用）。 */
private fun stRegexArray(regexes: List<AssistantRegex>): JsonArray = buildJsonArray {
    regexes.forEach { regex ->
        add(
            buildJsonObject {
                put("id", regex.id.toString())
                put("scriptName", regex.name)
                put("findRegex", regex.findRegex)
                put("replaceString", regex.replaceString)
                put("trimStrings", buildJsonArray { })
                put(
                    "placement",
                    buildJsonArray {
                        if (AssistantAffectScope.USER in regex.affectingScope) add(JsonPrimitive(1))
                        if (AssistantAffectScope.ASSISTANT in regex.affectingScope) add(JsonPrimitive(2))
                    },
                )
                put("disabled", !regex.enabled)
                put("markdownOnly", regex.visualOnly)
                put("promptOnly", regex.promptOnly)
                put("substituteRegex", 0)
                put("minDepth", regex.minDepth)
                put("maxDepth", regex.maxDepth)
            },
        )
    }
}

/**
 * settings.json 落库器：把 [mapStSettings] 的补丁写进宿主 datastore。
 *
 * - Settings 字段：`SettingsStore.update { applyStSettingsPatch(it, patch) }`（唯一全量写入口，事件差分自动生效）；
 * - 全局变量：`TavernVariableStore.replaceGlobal`（对齐 ST `extension_settings.variables.global`，落 vars.json）。
 *
 * 已知落盘缺口（登记在 notes）：`Settings.tavernExtensionSettings` / `tavernThirdPartyDisabled`
 * 在本地 PreferencesStore.update 里没有对应 key（仅随 S3/WebDAV 整体序列化），重启会丢；
 * 修复需改 `data/datastore/PreferencesStore.kt`（本轮作用域外）。
 */
class StSettingsImporter(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    data class Summary(
        val mappedFields: Int,
        val unmappedFields: Int,
        val unknownTopLevelKeys: Int,
        val regexScripts: Int,
        val globalVariables: Int,
        val authorNoteImported: Boolean,
        val thirdPartySettingsImported: Boolean,
    )

    suspend fun import(json: String): Summary = withContext(Dispatchers.IO) {
        val result = mapStSettings(json)
        settingsStore.update { current -> applyStSettingsPatch(current, result.patch) }
        result.patch.globalVariables?.let { globals ->
            runCatching { TavernVariableStoreCompat.ensureInitialized(context) }
            TavernVariableStoreCompat.replaceGlobal(globals)
        }
        Summary(
            mappedFields = result.mappedPaths.size,
            unmappedFields = result.unmappedPaths.size,
            unknownTopLevelKeys = result.unknownTopLevelKeys.size,
            regexScripts = result.patch.regexScripts?.size ?: 0,
            globalVariables = result.patch.globalVariables?.size ?: 0,
            authorNoteImported = result.patch.authorNote != null,
            thirdPartySettingsImported = result.patch.thirdPartySettingsJson != null,
        )
    }

    suspend fun import(file: File): Summary = import(file.readText(Charsets.UTF_8))
}

private fun reportSection(
    section: String,
    source: JsonObject,
    producedKeys: Set<String>,
    mapped: MutableSet<String>,
    unmapped: MutableSet<String>,
) {
    source.keys.sorted().forEach { key ->
        val path = "$section.$key"
        if (key in producedKeys) mapped += path else unmapped += path
    }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
private fun JsonObject.str(key: String): String? =
    primitive(key)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.booleanOrNull
private fun JsonObject.int(key: String): Int? = primitive(key)?.intOrNull
private fun JsonObject.double(key: String): Double? = primitive(key)?.doubleOrNull

private fun JsonObject.takeBool(key: String, produced: MutableSet<String>): Boolean? =
    bool(key)?.also { produced += key }

private fun JsonObject.takeInt(key: String, produced: MutableSet<String>): Int? =
    int(key)?.also { produced += key }

private fun JsonObject.takeDouble(key: String, produced: MutableSet<String>): Double? =
    double(key)?.also { produced += key }

private val ST_HEX_COLOR = Regex("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

private fun JsonObject.takeHexColor(key: String, produced: MutableSet<String>): String? {
    val raw = str(key)?.trim() ?: return null
    if (raw.isEmpty() || ST_HEX_COLOR.matches(raw)) {
        produced += key
        return raw
    }
    return null
}
