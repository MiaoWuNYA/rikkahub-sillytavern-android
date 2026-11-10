package me.rerere.rikkahub.data.st.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SelectiveLogic
import me.rerere.rikkahub.data.model.TavernBookEntry
import me.rerere.rikkahub.data.model.TavernEmbeddedBook
import me.rerere.ai.core.MessageRole
import kotlinx.serialization.json.intOrNull

/**
 * 「酒馆助手」（JS-Slash-Runner）脚本桥 + 三源脚本合并（B6）。
 *
 * 数据流（对齐 JSR 三来源语义）：
 * - global（全局脚本）：`Settings.tavernGlobalScripts`（JSON 数组字符串）+ 总开关 `tavernGlobalScriptsEnabled`；
 * - character（角色卡脚本）：`Assistant.tavernData.extensionsRaw`（"data.extensions" 原始 JSON）内 `tavern_helper.scripts`；
 * - preset（预设脚本）：`Settings.tavernPresetScripts`（{"预设组名": [script, ...]}），
 *   仅当当前助手已启用该预设组（`modeInjectionIds` 含该组条目）时装载。
 *
 * 合并结果经 [TavernRuntimeManager.TavernJsBridge.getTavernScriptsJson] 透传给运行时；
 * runtime.js 按 JSR 约定把 `type == "script" && enabled` 的脚本装载到 `TH-script--<name>--<id>` iframe。
 */

/** 脚本简要信息（UI 展示模型，folder 已展开）。 */
data class TavernScriptBrief(
    val id: String,
    val name: String,
    val type: String,
    val enabled: Boolean,
    val info: String,
    val content: String,
)

// ============================================================
// 装载：三源合并（runtime.js 消费）
// ============================================================

fun buildTavernScriptsJson(assistant: Assistant?, settings: Settings?): String {
    val helper = cardHelper(assistant)
    val merged = buildJsonArray {
        // 1) 全局脚本（JSR global；总开关关闭则跳过）
        if (TavernScriptStore.globalScriptsEnabled) {
            flattenTree(parseScriptItems(TavernScriptStore.globalScripts)).forEach { add(it) }
        }
        // 2) 卡内脚本（JSR character）
        (helper?.get("scripts") as? JsonArray)?.let { arr ->
            flattenTree(arr.toList()).forEach { add(it) }
        }
        // 3) 预设脚本（JSR preset；仅装载「当前助手已启用预设组」旗下的）
        val groups = enabledPresetGroups(assistant, settings)
        if (groups.isNotEmpty()) {
            val buckets = parsePresetBucketsRaw(TavernScriptStore.presetScriptsJson())
            groups.forEach { g ->
                flattenTree(buckets[g].orEmpty()).forEach { add(it) }
            }
        }
    }
    return buildJsonObject {
        put(
            "tavern_helper",
            buildJsonObject {
                put("scripts", merged)
                put("variables", (helper?.get("variables") as? JsonObject) ?: JsonObject(emptyMap()))
            },
        )
    }.toString()
}

/** 当前助手已启用的预设组集合（来自 modeInjectionIds → presetGroup 推导）。 */
fun enabledPresetGroups(
    assistant: Assistant?,
    settings: Settings?,
    extraModeInjectionIds: Set<kotlin.uuid.Uuid> = emptySet(),
): Set<String> {
    if (assistant == null || settings == null) return emptySet()
    val ids = assistant.modeInjectionIds + extraModeInjectionIds
    val enabledIds = settings.modeInjections.filter { it.id in ids && it.enabled }.map { it.id }.toSet()
    return TavernScriptStore.presetGroupsOf(enabledIds)
}

// ============================================================
// UI 列表
// ============================================================

fun listCardScripts(assistant: Assistant?): List<TavernScriptBrief> {
    val helper = cardHelper(assistant) ?: return emptyList()
    val scripts = helper["scripts"] as? JsonArray ?: return emptyList()
    return briefsOf(scripts.toList())
}

fun listGlobalScripts(scriptsJson: String?): List<TavernScriptBrief> =
    briefsOf(parseScriptItems(scriptsJson))

fun listPresetScriptBuckets(scriptsJson: String?): Map<String, List<TavernScriptBrief>> =
    parsePresetBucketsRaw(scriptsJson).mapValues { (_, v) -> briefsOf(v) }

// ============================================================
// 写回辅助（UI 开关 / 导入 / 删除）
// ============================================================

/** 卡内脚本开关：返回新的 extensionsRaw；解析失败或未找到返回 null。 */
fun setCardScriptEnabled(extensionsRaw: String?, scriptId: String, enabled: Boolean): String? {
    if (extensionsRaw.isNullOrBlank() || scriptId.isBlank()) return null
    return try {
        val root = Json.parseToJsonElement(extensionsRaw) as? JsonObject ?: return null
        val helper = TavernScriptCompat.tavernHelper(root) ?: return null
        val scripts = helper["scripts"] as? JsonArray ?: return null
        val (newList, found) = TavernScriptCompat.updateEnabled(scripts.toList(), scriptId, enabled)
        if (!found) return null
        // 写回统一落到新字段 tavern_helper；旧 TavernHelper_* 字段保留不动，便于无损回滚/再导出。
        root.withField("tavern_helper", helper.withField("scripts", JsonArray(newList))).toString()
    } catch (_: Exception) {
        null
    }
}

/**
 * 卡内脚本开关（完整助手版，推荐 UI 使用）：
 * 先通过 [TavernScriptCompat.cardExtensions] 合并 `extensionsRaw` 与旧
 * `TavernCharacterData.extensions: Map<String,String>`，因此旧版本持久化、`extensionsRaw` 为空的卡
 * 也能正常显示并写回开关；写回结果统一落到新字段 `tavern_helper`，旧字段保留做无损回滚。
 */
fun setCardScriptEnabled(assistant: Assistant?, scriptId: String, enabled: Boolean): String? {
    if (scriptId.isBlank()) return null
    val root = TavernScriptCompat.cardExtensions(assistant)
    if (root.isEmpty()) return null
    val helper = TavernScriptCompat.tavernHelper(root) ?: return null
    val scripts = helper["scripts"] as? JsonArray ?: return null
    val (newList, found) = TavernScriptCompat.updateEnabled(scripts.toList(), scriptId, enabled)
    if (!found) return null
    return root.withField("tavern_helper", helper.withField("scripts", JsonArray(newList))).toString()
}
/** 全局脚本开关：返回新的全局脚本 JSON；未找到返回 null。 */
fun setGlobalScriptEnabled(globalJson: String?, scriptId: String, enabled: Boolean): String? {
    if (scriptId.isBlank()) return null
    val items = parseScriptItems(globalJson)
    val (newList, found) = updateEnabledInItems(items, scriptId, enabled)
    if (!found) return null
    return JsonArray(newList).toString()
}

/** 预设脚本开关：返回新的预设脚本 JSON；未找到返回 null。 */
fun setPresetScriptEnabled(presetJson: String?, group: String, scriptId: String, enabled: Boolean): String? {
    if (group.isBlank() || scriptId.isBlank()) return null
    return try {
        val root = (presetJson?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) } as? JsonObject)
            ?: return null
        val bucket = root[group] ?: return null
        val items = when (bucket) {
            is JsonArray -> bucket.toList()
            is JsonObject -> (bucket["scripts"] as? JsonArray)?.toList().orEmpty()
            else -> return null
        }
        val (newList, found) = updateEnabledInItems(items, scriptId, enabled)
        if (!found) return null
        root.withField(group, JsonArray(newList)).toString()
    } catch (_: Exception) {
        null
    }
}

/** 追加全局脚本（按 id 去重）；返回 (新 JSON, 新增数量)。 */
fun appendGlobalScripts(globalJson: String?, imported: List<JsonElement>): Pair<String, Int> {
    val existing = parseScriptItems(globalJson).toMutableList()
    val existingIds = existing
        .mapNotNull { (it as? JsonObject)?.str("id")?.takeIf { s -> s.isNotBlank() } }
        .toSet()
    var added = 0
    TavernScriptCompat.normalizeScripts(imported).forEach { el ->
        val obj = el as? JsonObject ?: return@forEach
        val id = obj.str("id")
        if (id.isNotBlank() && id in existingIds) return@forEach
        existing += el
        added++
    }
    return JsonArray(existing).toString() to added
}

/** 删除一个全局脚本（按 id）；未找到返回 null。 */
fun removeGlobalScript(globalJson: String?, scriptId: String): String? {
    if (scriptId.isBlank()) return null
    val items = parseScriptItems(globalJson)
    val newList = items.filterNot { (it as? JsonObject)?.str("id") == scriptId }
    if (newList.size == items.size) return null
    return JsonArray(newList).toString()
}

/** 合并一个预设组下的脚本（同名组整体替换；当 scripts 为空且 removeWhenEmpty=true 时清除该预设组的旧脚本残留）。 */
fun mergePresetScripts(
    presetJson: String?,
    group: String,
    scripts: List<JsonElement>,
    removeWhenEmpty: Boolean = true,
): String {
    if (group.isBlank()) return presetJson ?: "{}"
    val root = try {
        (presetJson?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) } as? JsonObject)
            ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
    if (scripts.isEmpty() && removeWhenEmpty) {
        val mutable = root.toMutableMap()
        mutable.remove(group)
        return JsonObject(mutable).toString()
    }
    return root.withField(group, JsonArray(TavernScriptCompat.normalizeScripts(scripts))).toString()
}

/** 预设重命名或删除时，同步迁移或清理 `tavernPresetScripts` 中的分组键。 */
fun renameOrRemovePresetScriptsGroup(
    presetJson: String?,
    oldGroup: String,
    newGroup: String?,
): String {
    if (oldGroup.isBlank()) return presetJson ?: "{}"
    val root = try {
        (presetJson?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) } as? JsonObject)
            ?: return presetJson ?: "{}"
    } catch (_: Exception) {
        return presetJson ?: "{}"
    }
    if (!root.containsKey(oldGroup)) return presetJson ?: "{}"
    val mutable = root.toMutableMap()
    val existing = mutable.remove(oldGroup)
    if (!newGroup.isNullOrBlank() && existing != null) {
        mutable[newGroup] = existing
    }
    return JsonObject(mutable).toString()
}

/**
 * 从任意「酒馆助手脚本」JSON 文本中提取脚本列表，兼容格式：
 * `[script, ...]` / `{"scripts":[...]}` / `{"tavern_helper":{"scripts":[...]}}` / 单个脚本对象。
 */
fun extractScriptsFromJson(text: String): List<JsonElement> {
    if (text.isBlank()) return emptyList()
    return try {
        TavernScriptCompat.extractScripts(Json.parseToJsonElement(text))
    } catch (_: Exception) {
        emptyList()
    }
}

// ============================================================
// 内部工具
// ============================================================

private fun cardHelper(assistant: Assistant?): JsonObject? =
    TavernScriptCompat.tavernHelper(TavernScriptCompat.cardExtensions(assistant))

/** 解析脚本数组：兼容 `[...]` 与 `{"scripts":[...]}` 两种形态。 */
private fun parseScriptItems(json: String?): List<JsonElement> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        TavernScriptCompat.extractScripts(Json.parseToJsonElement(json))
    } catch (_: Exception) {
        emptyList()
    }
}

/** 解析预设脚本桶：{"组名": [script, ...]}。 */
private fun parsePresetBucketsRaw(json: String?): Map<String, List<JsonElement>> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: return emptyMap()
        root.mapValues { (_, v) ->
            when (v) {
                is JsonArray -> v.toList()
                is JsonObject -> (v["scripts"] as? JsonArray)?.toList().orEmpty()
                else -> emptyList()
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}

/** ScriptTree[] → 平铺脚本（folder 展开；仅合并/展示时进行，存储保持原样）。 */
private fun flattenTree(items: List<JsonElement>): List<JsonObject> {
    val out = mutableListOf<JsonObject>()
    fun take(item: JsonElement) {
        val obj = TavernScriptCompat.normalizeScript(item) as? JsonObject ?: return
        when (obj.str("type")) {
            "folder" -> (obj["scripts"] as? JsonArray)?.forEach { take(it) }
            else -> out += obj
        }
    }
    items.forEach { take(it) }
    return out
}

private fun briefsOf(items: List<JsonElement>): List<TavernScriptBrief> =
    flattenTree(items).map { o ->
        TavernScriptBrief(
            id = o.str("id"),
            name = o.str("name"),
            type = o.str("type").ifBlank { "script" },
            enabled = o.boolOrTrue("enabled"),
            info = o.str("info"),
            content = o.str("content"),
        )
    }

/** 在 ScriptTree 列表里递归更新指定 id 的 enabled；返回 (新列表, 是否找到)。 */
private fun updateEnabledInItems(
    items: List<JsonElement>,
    scriptId: String,
    enabled: Boolean,
): Pair<List<JsonElement>, Boolean> = TavernScriptCompat.updateEnabled(items, scriptId, enabled)

private fun JsonObject.withField(key: String, value: JsonElement): JsonObject {
    val map = toMutableMap()
    map[key] = value
    return JsonObject(map)
}

private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

/** 宽松布尔：与 runtime.js `scriptEnabled` 语义一致（缺失/未知按 true，显式 false/0/"false" 为 false）。 */
private fun JsonObject.boolOrTrue(key: String): Boolean {
    val p = this[key] as? JsonPrimitive ?: return true
    p.booleanOrNull?.let { return it }
    return when (p.contentOrNull?.lowercase()) {
        "false", "0", "no", "off" -> false
        else -> true
    }
}

// ============================================================
// B4：世界书桥 + 角色桥（JSR `loadWorldInfo` / `characters[i]` 语义）
// ============================================================

/** 取 `data.extensions.world`（角色绑定的世界书名）。 */
fun extractWorldName(extensionsRaw: String?): String? {
    if (extensionsRaw.isNullOrBlank()) return null
    return try {
        val root = Json.parseToJsonElement(extensionsRaw) as? JsonObject ?: return null
        (root["world"] as? JsonPrimitive)?.contentOrNull
    } catch (_: Exception) {
        null
    }
}

/**
 * [v216] 世界书名匹配：精确优先 → 双侧 trim 兜底。
 *
 * 存量全局库里存在书名带尾随空格的历史数据（导入时用文件名做 name，如「… 0.96 世界书 」）；
 * 卡侧 getGlobalWorldbookNames() 原样下发 → getWorldbook(name) 里 JS/Kotlin 都 trim 了
 * 「请求名」，但存量 name 未 trim → 精确比对不命中 → 返回 "null" → 卡抛错。见交接文档 §11.6。
 */
internal fun lorebookNameMatches(stored: String?, requested: String): Boolean {
    if (stored.isNullOrBlank()) return false
    return stored == requested || stored.trim() == requested.trim()
}

/**
 * [v216] 全局世界书查找：精确优先 → 双侧 trim 兜底（避免脏名/同名书时选错）。
 * @return 命中下标；未命中返回 -1。
 */
internal fun findLorebookIndexByName(books: List<Lorebook>, requested: String): Int {
    val exact = books.indexOfFirst { it.name == requested }
    if (exact >= 0) return exact
    return books.indexOfFirst { it.name.trim() == requested.trim() }
}

/** [v216] 世界书请求的命中结果（读面与可解析性判定共用，避免两处匹配逻辑漂移）。 */
private sealed interface LorebookHit {
    data class Embedded(val book: TavernEmbeddedBook, val bookName: String) : LorebookHit
    data class Global(val book: Lorebook, val trimmedFallback: Boolean) : LorebookHit
}

/**
 * [v216] 解析世界书请求（卡内嵌书优先，其次全局库；精确优先 → trim 兜底）；未命中返回 null。
 *
 * embeddedBook / worldName 由调用方预算好传入：批量判定（buildWorldNamesJson）时避免重复解析
 * 卡 extensionsRaw（千纱卡那份可达数百 KB）。
 */
private fun matchLorebook(
    embeddedBook: TavernEmbeddedBook?,
    worldName: String?,
    requestedRaw: String,
    settings: Settings?,
): LorebookHit? {
    val requested = requestedRaw.trim()
    // ST 语义：空名 = 未指定书（loadWorldInfo 无参返回 undefined），不能当成 "current" 去命中卡内书
    if (requested.isEmpty() || requested == "null" || requested == "undefined") return null

    // ① 卡内嵌书（原样优先 → 双侧 trim 兜底）
    if (embeddedBook != null) {
        val bookName = embeddedBook.name.ifBlank { worldName ?: "" }
        if (requested == "current" || lorebookNameMatches(bookName, requestedRaw) || lorebookNameMatches(worldName, requestedRaw)) {
            return LorebookHit.Embedded(embeddedBook, bookName)
        }
    }

    // ② [v214] 全局世界书库（Settings.lorebooks）；[v216] 精确优先（原样）→ 双侧 trim 兜底
    val books = settings?.lorebooks.orEmpty()
    val index = findLorebookIndexByName(books, requestedRaw)
    if (index >= 0) {
        return LorebookHit.Global(books[index], trimmedFallback = books[index].name != requestedRaw)
    }
    return null
}

/**
 * 世界书 → ST 风格 world info JSON（JSR `ctx.loadWorldInfo(name)` 语义）。
 *
 * 数据源优先级（与 [buildWorldNamesJson] 一致）：
 * 1. 内存中的卡内嵌书 [me.rerere.rikkahub.data.model.TavernCharacterData.embeddedBook]；
 * 2. [Settings.lorebooks] 宿主全局世界书库（[Lorebook.entries] 为 [PromptInjection.RegexInjection]）。
 *
 * [v208] P0-1：未命中返回 JSON "null"（ST loadWorldInfo 未命中返回 null/undefined）。
 * 此前返回 "{}" —— 真值！ST-PT worldinfo.ts:216-222 先 `if (!lorebook) return []` 再
 * `Object.values(lorebook.entries)`，把 {} 当有效书 → 对 undefined.entries 取 values → TypeError。
 *
 * [v216] 全局书分支改为「精确优先 → 双侧 trim 兜底」（存量书名可能带尾随空格，见 §11.6）。
 * @param onTrimFallback 命中 trim 兜底时回调 (requested, stored)；纯函数不直接打日志，由宿主决定。
 */
fun buildLorebookJson(
    assistant: Assistant?,
    name: String,
    settings: Settings? = null,
    onTrimFallback: ((requested: String, stored: String) -> Unit)? = null,
): String {
    val tav = assistant?.tavernData
    val hit = matchLorebook(tav?.embeddedBook, extractWorldName(tav?.extensionsRaw), name, settings)
    return when (hit) {
        is LorebookHit.Embedded -> embeddedBookToStJson(hit.book, hit.bookName)
        is LorebookHit.Global -> {
            if (hit.trimmedFallback) onTrimFallback?.invoke(name, hit.book.name)
            lorebookToStJson(hit.book)
        }
        null -> "null"
    }
}

/** 卡内嵌书 → ST world info JSON（entries 以 [TavernBookEntry.id] 为 uid）。 */
private fun embeddedBookToStJson(book: TavernEmbeddedBook, bookName: String): String = buildJsonObject {
    put("name", JsonPrimitive(bookName))
    put(
        "entries",
        buildJsonObject {
            book.entries.forEach { e ->
                put(e.id.toString(), entryToStJson(e))
            }
        },
    )
}.toString()

/**
 * [v214] 全局世界书 → ST world info JSON。
 *
 * [Lorebook.entries] 是 [PromptInjection.RegexInjection]（Uuid 主键），而 ST `uid` 契约是数字：
 * 这里用条目在书内的下标作为 uid（与 [applyLorebookDisablePatch] 写回规则一致）。
 */
fun lorebookToStJson(book: Lorebook): String = buildJsonObject {
    put("name", JsonPrimitive(book.name))
    put(
        "entries",
        buildJsonObject {
            book.entries.forEachIndexed { index, entry ->
                put(index.toString(), entryToStJson(globalInjectionToTavernEntry(entry, index)))
            }
        },
    )
}.toString()

/** [PromptInjection.RegexInjection] → [TavernBookEntry]（uid 用下标），复用 [entryToStJson] 保证 41 键同形。 */
private fun globalInjectionToTavernEntry(entry: PromptInjection.RegexInjection, uid: Int): TavernBookEntry =
    TavernBookEntry(
        id = uid,
        keys = entry.keywords,
        secondaryKeys = entry.secondaryKeys,
        comment = entry.name,
        content = entry.content,
        constant = entry.constantActive,
        selective = entry.selective,
        selectiveLogic = selectiveLogicToStNumber(entry.selectiveLogic),
        group = entry.group,
        position = injectionPositionToStNumber(entry.position),
        priority = entry.priority,
        disable = !entry.enabled,
        caseSensitive = entry.caseSensitive,
        matchWholeWords = entry.matchWholeWords,
        useRegex = entry.useRegex,
        probability = entry.probability,
        sticky = entry.sticky,
        cooldown = entry.cooldown,
        depth = entry.injectDepth,
        scanDepth = entry.scanDepth,
        role = when (entry.role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            else -> "system"
        },
        groupWeight = entry.groupWeight,
        groupOverride = entry.groupOverride,
        delay = entry.delay,
        excludeRecursion = entry.excludeRecursion,
        preventRecursion = entry.preventRecursion,
        delayUntilRecursion = entry.delayUntilRecursion,
        useProbability = entry.useProbability,
        inclusionGroup = entry.inclusionGroup,
        useGroupScoring = entry.useGroupScoring,
        groupPriority = entry.groupPriority,
        automationId = entry.automationId,
        displayIndex = entry.displayIndex,
        displayPosition = entry.displayPosition,
        triggers = entry.triggers,
        matchPersonaDescription = entry.matchPersonaDescription,
        matchCharacterDescription = entry.matchCharacterDescription,
        matchCharacterPersonality = entry.matchCharacterPersonality,
        matchCharacterDepthPrompt = entry.matchCharacterDepthPrompt,
        matchScenario = entry.matchScenario,
        matchCreatorNotes = entry.matchCreatorNotes,
        ignoreBudget = entry.ignoreBudget,
        outletName = entry.outletName,
    )

/** 官方 world_info_logic：0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL（OR_ANY 为本地遗留 → 0）。 */
private fun selectiveLogicToStNumber(logic: SelectiveLogic): Int = when (logic) {
    SelectiveLogic.AND_ANY -> 0
    SelectiveLogic.NOT_ALL -> 1
    SelectiveLogic.NOT_ANY -> 2
    SelectiveLogic.AND_ALL -> 3
    SelectiveLogic.OR_ANY -> 0
}

/** 官方 world_info_position：0=before_char 1=after_char 2=ANTop 3=ANBottom 4=atDepth 5=EMTop 6=EMBottom 7=outlet。 */
private fun injectionPositionToStNumber(pos: InjectionPosition): Int = when (pos) {
    InjectionPosition.BEFORE_CHARACTER -> 0
    InjectionPosition.AFTER_CHARACTER -> 1
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> 0
    InjectionPosition.AFTER_SYSTEM_PROMPT -> 1
    InjectionPosition.TOP_OF_CHAT -> 2
    InjectionPosition.BOTTOM_OF_CHAT -> 3
    InjectionPosition.AT_DEPTH -> 4
    InjectionPosition.AUTHOR_NOTE, InjectionPosition.BEFORE_AUTHOR_NOTE -> 2
    InjectionPosition.AFTER_AUTHOR_NOTE -> 3
    InjectionPosition.ANTAGONIZE, InjectionPosition.AFTER_DIALOG -> 7
    InjectionPosition.EM_TOP -> 5
    InjectionPosition.EM_BOTTOM -> 6
    InjectionPosition.OUTLET -> 7
}

/** 世界书写回补丁项：uid + 目标 disable 状态。 */
data class LorebookDisablePatch(val uid: Int, val disable: Boolean)

/**
 * [v214] 解析 JS `RikkaBridge.updateLorebookEntries` 的整本书补丁：
 * `[{"uid":<number>,"disable":<bool>}, ...]`；非法/空返回 null。
 */
fun parseLorebookDisablePatch(patchJson: String): List<LorebookDisablePatch>? {
    if (patchJson.isBlank()) return null
    return try {
        val arr = Json.parseToJsonElement(patchJson) as? JsonArray ?: return null
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val uid = (obj["uid"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val disable = (obj["disable"] as? JsonPrimitive)?.booleanOrNull ?: return@mapNotNull null
            LorebookDisablePatch(uid, disable)
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * [v214] 卡内嵌书写回：按 [TavernBookEntry.id] == uid 改写 disable。
 * @return 有实际变更的新书；空补丁 / 无 uid 命中 / 状态未变返回 null。
 */
fun applyLorebookDisablePatch(book: TavernEmbeddedBook, patchJson: String): TavernEmbeddedBook? {
    val patches = parseLorebookDisablePatch(patchJson) ?: return null
    if (patches.isEmpty()) return null
    val byUid = patches.associateBy { it.uid }
    var changed = false
    val entries = book.entries.map { e ->
        val patch = byUid[e.id] ?: return@map e
        if (e.disable == patch.disable) return@map e
        changed = true
        e.copy(disable = patch.disable)
    }
    return if (changed) book.copy(entries = entries) else null
}

/**
 * [v214] 全局书写回：按条目下标（= [lorebookToStJson] 下发的 uid）改写 enabled = !disable。
 * @return 有实际变更的新书；空补丁 / 无 uid 命中 / 状态未变返回 null。
 */
fun applyLorebookDisablePatch(book: Lorebook, patchJson: String): Lorebook? {
    val patches = parseLorebookDisablePatch(patchJson) ?: return null
    if (patches.isEmpty()) return null
    val byUid = patches.associateBy { it.uid }
    var changed = false
    val entries = book.entries.mapIndexed { index, e ->
        val patch = byUid[index] ?: return@mapIndexed e
        val enabled = !patch.disable
        if (e.enabled == enabled) return@mapIndexed e
        changed = true
        e.copy(enabled = enabled)
    }
    return if (changed) book.copy(entries = entries) else null
}

/**
 * [v243 BUGFIX A3] JSR 世界书写入载荷：`{ upsert:[WorldbookEntry...], delete:[uid...], replaceAll:boolean }`。
 */
data class LorebookUpsertPayload(
    val upsert: List<JsonObject>,
    val delete: List<Int>,
    val replaceAll: Boolean,
)

/** 解析 JS `RikkaBridge.upsertLorebookEntries` 的载荷；空/非法返回 null。 */
fun parseLorebookUpsertPayload(json: String): LorebookUpsertPayload? {
    if (json.isBlank()) return null
    return try {
        val obj = Json.parseToJsonElement(json) as? JsonObject ?: return null
        val upsert = (obj["upsert"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val delete = (obj["delete"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
        val replaceAll = (obj["replaceAll"] as? JsonPrimitive)?.booleanOrNull == true
        if (upsert.isEmpty() && delete.isEmpty() && !replaceAll) return null
        LorebookUpsertPayload(upsert, delete, replaceAll)
    } catch (_: Exception) {
        null
    }
}

/** JSR `position.type` → 官方 world_info_position 数值。 */
private fun jsrPositionTypeToStNumber(type: String?): Int = when ((type ?: "").lowercase()) {
    "before_character_definition" -> 0
    "after_character_definition" -> 1
    "before_authors_note" -> 2
    "after_authors_note" -> 3
    "at_depth" -> 4
    "before_example_messages" -> 5
    "after_example_messages" -> 6
    "outlet" -> 7
    else -> 1
}

/** JSR `strategy.keys_secondary.logic` → 官方 world_info_logic 数值。 */
private fun jsrLogicToStNumber(logic: String?): Int = when ((logic ?: "").lowercase()) {
    "not_all" -> 1
    "not_any" -> 2
    "and_all" -> 3
    else -> 0
}

/** JSR 字符串数组（元素可能是 `/regex/` 串）→ 普通字符串列表。 */
private fun jsrKeyList(el: JsonElement?): List<String> {
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { k -> k.isNotEmpty() } }
}

/**
 * [v243 BUGFIX A3] JSR `WorldbookEntry` → [TavernBookEntry]（ST 扁平条目）。
 *
 * 只映射「卡脚本真的会写、且 `lorebook-shims.js:toLorebookEntry` 会回读校验」的字段：
 * name / content / enabled / strategy(type, keys, keys_secondary, scan_depth) /
 * position(type, role, depth, order) / probability。
 *
 * 为什么必须能回读一致：卡写完会立刻 `getWorldbook()` 再比对
 *   `strategy.type === 'constant' && position.type === 'after_character_definition' && position.order === 25`
 * 不一致就报「写入后回读的条目设置与预期不一致」。所以这几个键的往返必须无损。
 *
 * @param fallbackId 未带 uid 时使用的 id（由调用方分配 max+1）
 */
private fun jsrEntryToTavernBookEntry(obj: JsonObject, fallbackId: Int): TavernBookEntry? {
    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: ""
    val content = (obj["content"] as? JsonPrimitive)?.contentOrNull
    if (content == null && name.isEmpty()) return null

    val strategy = obj["strategy"] as? JsonObject
    val strategyType = ((strategy?.get("type") as? JsonPrimitive)?.contentOrNull ?: "selective").lowercase()
    val secondary = strategy?.get("keys_secondary") as? JsonObject
    val position = obj["position"] as? JsonObject
    val rawScanDepth = strategy?.get("scan_depth")
    val scanDepth = if (rawScanDepth is JsonPrimitive) rawScanDepth.intOrNull else null
    val rawUid = (obj["uid"] as? JsonPrimitive)?.intOrNull ?: -1
    val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true

    return TavernBookEntry(
        id = if (rawUid >= 0) rawUid else fallbackId,
        keys = jsrKeyList(strategy?.get("keys")),
        secondaryKeys = jsrKeyList(secondary?.get("keys")),
        comment = name,
        content = content ?: "",
        // JSR strategy.type：constant / selective / vectorized
        constant = strategyType == "constant",
        selective = strategyType != "constant",
        selectiveLogic = jsrLogicToStNumber((secondary?.get("logic") as? JsonPrimitive)?.contentOrNull),
        position = jsrPositionTypeToStNumber((position?.get("type") as? JsonPrimitive)?.contentOrNull),
        priority = (position?.get("order") as? JsonPrimitive)?.intOrNull ?: 100,
        disable = !enabled,
        depth = (position?.get("depth") as? JsonPrimitive)?.intOrNull ?: 4,
        scanDepth = scanDepth,
        role = ((position?.get("role") as? JsonPrimitive)?.contentOrNull ?: "system").lowercase(),
        probability = (obj["probability"] as? JsonPrimitive)?.intOrNull ?: 100,
        useProbability = true,
    )
}

/** JSR `position.type` → [InjectionPosition]（必须是 [injectionPositionToStNumber] 的逆，保证回读一致）。 */
private fun jsrPositionTypeToInjectionPosition(type: String?): InjectionPosition = when ((type ?: "").lowercase()) {
    "before_character_definition" -> InjectionPosition.BEFORE_CHARACTER
    "after_character_definition" -> InjectionPosition.AFTER_CHARACTER
    "before_authors_note" -> InjectionPosition.BEFORE_AUTHOR_NOTE
    "after_authors_note" -> InjectionPosition.AFTER_AUTHOR_NOTE
    "at_depth" -> InjectionPosition.AT_DEPTH
    "before_example_messages" -> InjectionPosition.EM_TOP
    "after_example_messages" -> InjectionPosition.EM_BOTTOM
    "outlet" -> InjectionPosition.OUTLET
    else -> InjectionPosition.AFTER_CHARACTER
}

/** JSR `strategy.keys_secondary.logic` → [SelectiveLogic]。 */
private fun jsrLogicToSelectiveLogic(logic: String?): SelectiveLogic = when ((logic ?: "").lowercase()) {
    "not_all" -> SelectiveLogic.NOT_ALL
    "not_any" -> SelectiveLogic.NOT_ANY
    "and_all" -> SelectiveLogic.AND_ALL
    else -> SelectiveLogic.AND_ANY
}

/** JSR `position.role` → [MessageRole]。 */
private fun jsrRoleToMessageRole(role: String?): MessageRole = when ((role ?: "system").lowercase()) {
    "user" -> MessageRole.USER
    "assistant" -> MessageRole.ASSISTANT
    else -> MessageRole.SYSTEM
}

/**
 * [v243 BUGFIX A3b] JSR `WorldbookEntry` → [PromptInjection.RegexInjection]（宿主全局世界书库的条目形态）。
 *
 * 字段选择与 [jsrEntryToTavernBookEntry] 完全一致，保证「写进去 → getWorldbook 回读」无损
 * （回读路径 = globalInjectionToTavernEntry → entryToStJson → JS toLorebookEntry）。
 */
private fun jsrEntryToRegexInjection(obj: JsonObject): PromptInjection.RegexInjection? {
    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: ""
    val content = (obj["content"] as? JsonPrimitive)?.contentOrNull
    if (content == null && name.isEmpty()) return null

    val strategy = obj["strategy"] as? JsonObject
    val strategyType = ((strategy?.get("type") as? JsonPrimitive)?.contentOrNull ?: "selective").lowercase()
    val secondary = strategy?.get("keys_secondary") as? JsonObject
    val position = obj["position"] as? JsonObject
    val rawScanDepth = strategy?.get("scan_depth")
    val scanDepth = if (rawScanDepth is JsonPrimitive) rawScanDepth.intOrNull else null
    val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true

    return PromptInjection.RegexInjection(
        name = name,
        enabled = enabled,
        content = content ?: "",
        priority = (position?.get("order") as? JsonPrimitive)?.intOrNull ?: 100,
        position = jsrPositionTypeToInjectionPosition((position?.get("type") as? JsonPrimitive)?.contentOrNull),
        injectDepth = (position?.get("depth") as? JsonPrimitive)?.intOrNull ?: 4,
        role = jsrRoleToMessageRole((position?.get("role") as? JsonPrimitive)?.contentOrNull),
        keywords = jsrKeyList(strategy?.get("keys")),
        secondaryKeys = jsrKeyList(secondary?.get("keys")),
        constantActive = strategyType == "constant",
        selective = strategyType != "constant",
        selectiveLogic = jsrLogicToSelectiveLogic((secondary?.get("logic") as? JsonPrimitive)?.contentOrNull),
        scanDepth = scanDepth,
        probability = (obj["probability"] as? JsonPrimitive)?.intOrNull ?: 100,
        useProbability = true,
    )
}

/**
 * [v243 BUGFIX A3b] **全局世界书库**（[Settings.lorebooks]）条目 upsert。
 *
 * uid 契约：全局书的 uid 就是条目**下标**（见 [lorebookToStJson] / [applyLorebookDisablePatch]）。
 * 因此这里的语义是：
 * - `replaceAll` → 整本替换；
 * - 带 `uid`（合法下标）→ 替换该下标；否则**追加**到末尾；
 * - `delete` → 按下标删除（从大到小删，避免下标漂移）。
 *
 * @return 有实际变更的新书；无变更返回 null。
 */
fun applyLorebookUpsertPatch(book: Lorebook, payloadJson: String): Lorebook? {
    val payload = parseLorebookUpsertPayload(payloadJson) ?: return null

    val entries = if (payload.replaceAll) mutableListOf() else book.entries.toMutableList()
    payload.upsert.forEach { obj ->
        val converted = jsrEntryToRegexInjection(obj) ?: return@forEach
        val uid = (obj["uid"] as? JsonPrimitive)?.intOrNull ?: -1
        if (uid in entries.indices) {
            // [v245 BUGFIX G1-KT] 更新既有条目：保留 JSR 投影读不到、也写不回的那批字段。
            // [v245 BUGFIX G3] 再按 presence 覆盖 effect / recursion 里真的带了的键。
            entries[uid] = converted.keepingUnmappedFrom(entries[uid]).withJsrEffectAndRecursion(obj)
        } else {
            entries.add(converted)
        }
    }
    payload.delete
        .filter { it in entries.indices }
        .sortedDescending()
        .forEach { entries.removeAt(it) }

    if (entries == book.entries) return null
    return book.copy(entries = entries)
}

/**
 * [v243 BUGFIX A3] 卡内嵌世界书条目 upsert（新增/覆盖/删除）。
 *
 * - `replaceAll = true` → 整本替换（JSR `replaceLorebookEntries` / `replaceWorldbook`）；
 * - 否则按 `uid` 合并：同 id 覆盖、新 id 追加；`delete` 里的 uid 删除。
 *
 * @return 有实际变更的新书；无变更返回 null。
 */
fun applyLorebookUpsertPatch(book: TavernEmbeddedBook, payloadJson: String): TavernEmbeddedBook? {
    val payload = parseLorebookUpsertPayload(payloadJson) ?: return null

    val map = LinkedHashMap<Int, TavernBookEntry>()
    if (!payload.replaceAll) book.entries.forEach { map[it.id] = it }
    var nextId = (map.keys.maxOrNull() ?: -1) + 1
    payload.upsert.forEach { obj ->
        val rawUid = (obj["uid"] as? JsonPrimitive)?.intOrNull ?: -1
        val target = if (rawUid >= 0) rawUid else nextId++
        // [v245 BUGFIX G1-KT] 更新既有条目：保留 JSR 投影读不到、也写不回的那批字段。
        jsrEntryToTavernBookEntry(obj, target)?.let {
            // [v245 BUGFIX G3] 先保未映射字段，再按 presence 覆盖 effect / recursion 里**真的带了**的键。
            map[target] = it.keepingUnmappedFrom(map[target]).withJsrEffectAndRecursion(obj)
        }
    }
    payload.delete.forEach { map.remove(it) }

    val result = map.values.toList()
    if (result == book.entries) return null
    return book.copy(entries = result)
}

/**
 * [v245 BUGFIX G1-KT] 保留「JSR 投影读不到、也写不回」的那批字段。
 *
 * 背景：`lorebook-shims.js` 的 `getWorldbook` 是**有损投影**——`toLorebookEntry` 只产出
 * name / content / enabled / strategy / position / probability / recursion / effect 这几个键。
 * 卡走 `updateWorldbookWith` 时会把读到的条目原样回传，宿主侧若**整条目替换**，
 * 下面这批字段就会被静默打回默认值（对 357 条的大卡是数据损毁级）：
 *   group / caseSensitive / matchWholeWords / useRegex / sticky / cooldown / groupWeight /
 *   groupOverride / delay / excludeRecursion / preventRecursion / delayUntilRecursion /
 *   inclusionGroup / useGroupScoring / groupPriority / automationId / displayIndex /
 *   displayPosition / triggers / extensionsRaw / match* / ignoreBudget / outletName
 *
 * 修法：更新既有条目时**以上字段沿用旧值**，只覆盖 `jsrEntryToTavernBookEntry` 真正映射的那批。
 * 已知局限（保守取向，宁可少写不可错写）：卡若通过 `updateWorldbookWith` 修改
 * `effect.sticky` / `recursion.*` 这类「投影能读、但本次不覆盖」的字段，改动不会落盘，
 * 但旧值不会被破坏。要放开需先把这批字段纳入映射并做「presence 感知」解析。
 */
internal fun TavernBookEntry.keepingUnmappedFrom(old: TavernBookEntry?): TavernBookEntry {
    if (old == null) return this
    return copy(
        group = old.group,
        caseSensitive = old.caseSensitive,
        matchWholeWords = old.matchWholeWords,
        useRegex = old.useRegex,
        sticky = old.sticky,
        cooldown = old.cooldown,
        groupWeight = old.groupWeight,
        groupOverride = old.groupOverride,
        delay = old.delay,
        excludeRecursion = old.excludeRecursion,
        preventRecursion = old.preventRecursion,
        delayUntilRecursion = old.delayUntilRecursion,
        inclusionGroup = old.inclusionGroup,
        useGroupScoring = old.useGroupScoring,
        groupPriority = old.groupPriority,
        automationId = old.automationId,
        displayIndex = old.displayIndex,
        displayPosition = old.displayPosition,
        triggers = old.triggers,
        extensionsRaw = old.extensionsRaw,
        matchPersonaDescription = old.matchPersonaDescription,
        matchCharacterDescription = old.matchCharacterDescription,
        matchCharacterPersonality = old.matchCharacterPersonality,
        matchCharacterDepthPrompt = old.matchCharacterDepthPrompt,
        matchScenario = old.matchScenario,
        matchCreatorNotes = old.matchCreatorNotes,
        ignoreBudget = old.ignoreBudget,
        outletName = old.outletName,
    )
}

/**
 * [v245 BUGFIX G3] JSR `effect` / `recursion` 的 **presence 感知**写回（[TavernBookEntry]）。
 *
 * 为什么要单独一步：`lorebook-shims.js:toLorebookEntry` **会读**这两个对象
 *   effect   → sticky / cooldown / delay
 *   recursion→ excludeRecursion(prev_incoming) / preventRecursion(prev_outgoing) / delayUntilRecursion(delay_until)
 * 但 `jsrEntryToTavernBookEntry` **不写**它们 ⇒ 卡通过 `updateWorldbookWith` 改这几个字段会静默丢失。
 *
 * 为什么必须 presence 感知而不是无条件覆盖：卡也常手工构造**局部**条目
 * （例如只给 `{uid, content}`），无条件覆盖会把没提到的子键打成默认值（= 数据损毁）。
 * 所以规则是：**只有 JSON 里真的出现的子键才覆盖**，其余沿用（此时已由
 * [keepingUnmappedFrom] 保留成旧值）。
 *
 * 上游语义出处：JSR `WorldbookEntry` 的 `effect` / `recursion`（JS-Slash-Runner worldbook 类型），
 * 与 ST 扁平条目的 extensions.sticky / cooldown / delay / exclude_recursion / prevent_recursion /
 * delay_until_recursion 一一对应。
 */
internal fun TavernBookEntry.withJsrEffectAndRecursion(obj: kotlinx.serialization.json.JsonObject): TavernBookEntry {
    var out = this
    (obj["effect"] as? kotlinx.serialization.json.JsonObject)?.let { eff ->
        out = out.copy(
            sticky = (eff["sticky"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: out.sticky,
            cooldown = (eff["cooldown"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: out.cooldown,
            delay = (eff["delay"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: out.delay,
        )
    }
    (obj["recursion"] as? kotlinx.serialization.json.JsonObject)?.let { rec ->
        out = out.copy(
            excludeRecursion = (rec["prevent_incoming"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: out.excludeRecursion,
            preventRecursion = (rec["prevent_outgoing"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: out.preventRecursion,
            delayUntilRecursion = (rec["delay_until"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: out.delayUntilRecursion,
        )
    }
    return out
}

/** [v245 BUGFIX G3] 同 [TavernBookEntry.withJsrEffectAndRecursion]（全局世界书库条目）。 */
internal fun PromptInjection.RegexInjection.withJsrEffectAndRecursion(obj: kotlinx.serialization.json.JsonObject): PromptInjection.RegexInjection {
    var out = this
    (obj["effect"] as? kotlinx.serialization.json.JsonObject)?.let { eff ->
        out = out.copy(
            sticky = (eff["sticky"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: out.sticky,
            cooldown = (eff["cooldown"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: out.cooldown,
            delay = (eff["delay"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: out.delay,
        )
    }
    (obj["recursion"] as? kotlinx.serialization.json.JsonObject)?.let { rec ->
        out = out.copy(
            excludeRecursion = (rec["prevent_incoming"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: out.excludeRecursion,
            preventRecursion = (rec["prevent_outgoing"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: out.preventRecursion,
            delayUntilRecursion = (rec["delay_until"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: out.delayUntilRecursion,
        )
    }
    return out
}

/**
 * [v245 BUGFIX G1-KT] 同 [TavernBookEntry.keepingUnmappedFrom]（全局世界书库条目）。
 *
 * 额外保留 `id`：`jsrEntryToRegexInjection` 不解析 uid，默认走 `Uuid.random()`，
 * 整条目替换会让同一个条目每次都换主键（引用它的地方会错位）。
 */
internal fun PromptInjection.RegexInjection.keepingUnmappedFrom(
    old: PromptInjection.RegexInjection?,
): PromptInjection.RegexInjection {
    if (old == null) return this
    return copy(
        id = old.id,
        useRegex = old.useRegex,
        caseSensitive = old.caseSensitive,
        matchWholeWords = old.matchWholeWords,
        group = old.group,
        groupWeight = old.groupWeight,
        groupOverride = old.groupOverride,
        sticky = old.sticky,
        cooldown = old.cooldown,
        delay = old.delay,
        excludeRecursion = old.excludeRecursion,
        preventRecursion = old.preventRecursion,
        delayUntilRecursion = old.delayUntilRecursion,
        inclusionGroup = old.inclusionGroup,
        useGroupScoring = old.useGroupScoring,
        groupPriority = old.groupPriority,
        automationId = old.automationId,
        displayIndex = old.displayIndex,
        displayPosition = old.displayPosition,
        triggers = old.triggers,
        outletName = old.outletName,
        matchPersonaDescription = old.matchPersonaDescription,
        matchCharacterDescription = old.matchCharacterDescription,
        matchCharacterPersonality = old.matchCharacterPersonality,
        matchCharacterDepthPrompt = old.matchCharacterDepthPrompt,
        matchScenario = old.matchScenario,
        matchCreatorNotes = old.matchCreatorNotes,
        ignoreBudget = old.ignoreBudget,
    )
}

/** [TavernBookEntry] → ST 扁平条目的字段名（与 JSR lorebook-shims `toLorebookEntry` 读取的字段对齐）。 */
/** ST `extension_prompt_roles`：0=system 1=user 2=assistant（ST 4117 / script.js:63 / JSR trace.ts:134）。 */
private fun roleToStNumber(role: String?): Int = when (role?.lowercase()) {
    "user" -> 1
    "assistant" -> 2
    "1" -> 1
    "2" -> 2
    else -> 0
}

private fun entryToStJson(e: TavernBookEntry): JsonObject = buildJsonObject {
    put("uid", e.id)
    put("comment", e.comment)
    put("content", e.content)
    put("disable", e.disable)
    put("constant", e.constant)
    put("vectorized", false)
    // ---- [v208] 以下 20 键此前缺失（审计 notes/audit-world-info-shim.md §3.2）----
    // selective：ST-PT worldinfo.ts:390 `data.selective && ...` —— 缺失 = 二级关键词全废（P1-1）
    put("selective", e.selective)
    // group：ST-PT worldinfo.ts:438 `_.groupBy(..., d => d.group)` —— 缺失 = 全部命中条目并成一组只随机回 1 条（P1-2）
    put("group", e.group)
    put("groupOverride", e.groupOverride)
    put("groupWeight", e.groupWeight)
    put("useGroupScoring", e.useGroupScoring)
    // useProbability：ST-PT worldinfo.ts:359 `if (data.useProbability && ...)` —— 缺失 = 概率过滤恒跳过（P1-4）
    put("useProbability", e.useProbability)
    put("addMemo", e.comment.isNotEmpty())
    put("ignoreBudget", e.ignoreBudget)
    put("outletName", e.outletName)
    put("automationId", e.automationId)
    put("displayIndex", if (e.displayIndex != 0) e.displayIndex else e.id)
    put("triggers", buildJsonArray { e.triggers.forEach { add(JsonPrimitive(it)) } })
    put("caseSensitive", e.caseSensitive)
    put("matchWholeWords", e.matchWholeWords)
    put("matchPersonaDescription", e.matchPersonaDescription)
    put("matchCharacterDescription", e.matchCharacterDescription)
    put("matchCharacterPersonality", e.matchCharacterPersonality)
    put("matchCharacterDepthPrompt", e.matchCharacterDepthPrompt)
    put("matchScenario", e.matchScenario)
    put("matchCreatorNotes", e.matchCreatorNotes)
    put("key", buildJsonArray { e.keys.forEach { add(JsonPrimitive(it)) } })
    put("keysecondary", buildJsonArray { e.secondaryKeys.forEach { add(JsonPrimitive(it)) } })
    put("selectiveLogic", e.selectiveLogic)
    put("position", e.position)
    // [v208] role 数值化：ST 契约是 0/1/2（此前下发字符串 "system" → JSR Number("system")=NaN，depth 桶配不上，P1-6）
    put("role", roleToStNumber(e.role))
    put("depth", e.depth)
    put("order", e.priority)
    put("probability", e.probability)
    if (e.scanDepth != null) put("scanDepth", e.scanDepth) else put("scanDepth", JsonNull)
    put("excludeRecursion", e.excludeRecursion)
    put("preventRecursion", e.preventRecursion)
    put("delayUntilRecursion", e.delayUntilRecursion)
    put("sticky", e.sticky)
    put("cooldown", e.cooldown)
    put("delay", e.delay)
}

/** `data.extensions` 原始 JSON → JsonObject（空/非法/非对象 → 空对象）。 */
private fun parseExtensionsObject(raw: String?): JsonObject {
    if (raw.isNullOrBlank()) return JsonObject(emptyMap())
    return try {
        Json.parseToJsonElement(raw) as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}

/**
 * [v211] UIMessage 列表 ↔ ST Chat Completion messages（[{role, content}]）互转。
 *
 * ST 契约（openai.js:1616-1623）：生成前把最终 messages 交给扩展改写后继续使用。
 * 只转文本 part（与 ST 的 chat completion 语义一致）；role 映射 system/user/assistant。
 * 注意：size 必须守恒 —— Kotlin 侧以此判断改写是否可信，不等长直接拒绝（防止扩展丢消息）。
 */
fun buildStMessagesJson(messages: List<me.rerere.ai.ui.UIMessage>): String = buildJsonArray {
    messages.forEach { m ->
        add(buildJsonObject {
            put("role", m.role.name.lowercase())
            put("content", m.toText())
            if (!m.name.isNullOrBlank()) put("name", m.name)
        })
    }
}.toString()

/**
 * [ST 1.19.0] 生成前钩子（generate_interceptor + PROMPT_READY + SETTINGS_READY）返回结构。
 * 支持可变长消息数组改写（ST-Prompt-Template 插入消息、st-memory-enhancement 注入表格）以及 abort 终止。
 */
data class StGenerationHookResult(
    val messages: List<me.rerere.ai.ui.UIMessage>,
    val aborted: Boolean = false,
)

/** 解析生成钩子返回结果（兼容 `[...]` 数组与 `{"messages":[...],"aborted":true}` 对象两种形状）。 */
fun parseStGenerationHookResult(json: String): StGenerationHookResult? {
    return runCatching {
        val root = Json.parseToJsonElement(json)
        val aborted = (root as? JsonObject)?.get("aborted")?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } ?: false
        val arr = when (root) {
            is JsonArray -> root
            is JsonObject -> root["messages"] as? JsonArray ?: return null
            else -> return null
        }
        val msgs = arr.map { el ->
            val obj = el as? JsonObject ?: return null
            val roleRaw = (obj["role"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "user"
            val role = when (roleRaw) {
                "system" -> me.rerere.ai.core.MessageRole.SYSTEM
                "user", "tool" -> me.rerere.ai.core.MessageRole.USER
                "assistant" -> me.rerere.ai.core.MessageRole.ASSISTANT
                else -> return null
            }
            val content = (obj["content"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["mes"] as? JsonPrimitive)?.contentOrNull
                ?: return null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            me.rerere.ai.ui.UIMessage(
                role = role,
                parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(content)),
                name = name,
            )
        }
        StGenerationHookResult(messages = msgs, aborted = aborted)
    }.getOrNull()
}

/** 反序列化改写结果；字段缺失/role 非法时返回 null（调用方保留原文）。 */
fun parseStMessagesJson(json: String): List<me.rerere.ai.ui.UIMessage>? =
    parseStGenerationHookResult(json)?.messages

/**
 * [v208] P0-3：世界书名列表 + 全局启用书（ST `world_names` / `selected_world_info` / `world_info`）。
 *
 * 此前 JS 侧三者恒为空：
 *  - ST-PT worldinfo.ts:660 `results.filter(e => e && world_names.includes(e))` 把所有书过滤光（WI 全灭）；
 *  - ST-PT worldinfo.ts:597-618/630-655 依赖 selected_world_info，空数组 → 全局/角色附加书全漏；
 *  - JSR lorebook.ts:191-193 setLorebookSettings 对非空书单 throw Error。
 *
 * 数据源：Settings.lorebooks（RikkaHub 自己的世界书库，含 isCharacterBook 标记）+ 卡内嵌书名。
 */
fun buildWorldNamesJson(settings: Settings?, assistant: Assistant?): String {
    val names = LinkedHashSet<String>()
    val selected = LinkedHashSet<String>()
    // ① 宿主世界书库（全局世界书 enabled 的进 selected_world_info；角色卡专属世界书仅在绑定给当前助手时进 selected）
    if (settings != null) {
        val boundIds = assistant?.lorebookIds.orEmpty()
        settings.lorebooks.forEach { book ->
            if (book.name.isNotBlank()) {
                names.add(book.name)
                if (book.enabled && (!book.isCharacterBook || book.id in boundIds)) {
                    selected.add(book.name)
                }
            }
        }
    }
    // ② 角色绑定的主世界书（data.extensions.world）+ 卡内嵌书名：算可用，也并入全局启用（ST 行为）
    val tav = assistant?.tavernData
    val embeddedBook = tav?.embeddedBook
    val worldName = (TavernScriptCompat.cardExtensions(assistant)["world"] as? JsonPrimitive)
        ?.contentOrNull?.takeIf { it.isNotBlank() }
    if (tav != null) {
        worldName?.let { if (it.isNotBlank()) { names.add(it); selected.add(it) } }
        embeddedBook?.name?.takeIf { it.isNotBlank() }?.let { names.add(it); selected.add(it) }
    }
    // [v216] 只下发「可解析」的书名：卡侧 M() 用 Promise.all 且无 per-book 容错，
    // 用户全局库里任何一本坏书（含脏名）都会让整个开关面板报废（见交接文档 §11.6）。
    fun resolvable(n: String): Boolean = matchLorebook(embeddedBook, worldName, n, settings) != null
    return buildJsonObject {
        put("world_names", buildJsonArray {
            names.filter { resolvable(it) }.forEach { add(JsonPrimitive(it)) }
        })
        put("selected", buildJsonArray {
            selected.filter { resolvable(it) }.forEach { add(JsonPrimitive(it)) }
        })
        // charLore：按文件名的角色附加书（ST world_info.charLore: [{name, extraBooks}]），宿主暂无此概念 → 空数组保形
        put("charLore", buildJsonArray {})
    }.toString()
}

/**
 * 当前角色卡 → ST `characters[i]`（char-data.js 的 v1CharData）**完整形态** JSON。
 *
 * 消费方：
 *  - ST-Prompt-Template：`getCharacterDefine()`（src/function/characters.ts:92）第一句就是 `char.mes_example.trim()`；
 *  - JSR：`characters[i].avatar`（per-character store 的键）、`data.extensions.world`、`data.alternate_greetings`、`data.mes_example`；
 *  - EJS 模板：`getCharData()` / `getCharacterDefine()`。
 *
 * ⚠️ v204 真机事故（交接文档 §5.A）：本函数早期只输出 `{name, world}`，
 * 于是 `char.mes_example` 是 undefined → 扩展在 `.trim()` 处抛
 * `TypeError: Cannot read properties of undefined (reading 'trim')`；又因为抛在
 * `handlePreloadWorldInfo()` 的 try 里，真机日志顶着
 * `[Prompt Template] Error processing world info:` 的壳 —— **世界书背了黑锅**。
 * 结论（项目第一原则）：**形态必须与 ST 真源同构，一个键都不能少**。
 */
fun buildCharacterJson(assistant: Assistant?): String {
    val tav = assistant?.tavernData ?: return "{}"
    val name = tav.name.ifBlank { assistant?.name ?: "" }
    // data.extensions 是 ST 真源位置（world / tavern_helper / regex_scripts 都挂在这里）。
    // 走兼容层：旧持久化 Map、旧 TavernHelper_scripts、旧 ScriptTree 都要能还原给 JSR 自身 UI。
    val extensionMap = TavernScriptCompat.cardExtensions(assistant).toMutableMap()
    val world = (extensionMap["world"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    if (world != null) extensionMap["world"] = JsonPrimitive(world)
    TavernScriptCompat.tavernHelper(JsonObject(extensionMap))?.let { helper ->
        extensionMap["tavern_helper"] = helper
    }
    val extensions = JsonObject(extensionMap)

    val tagsArray = buildJsonArray { tav.tags.forEach { add(JsonPrimitive(it)) } }
    val book = tav.embeddedBook
    val characterBook: JsonElement = if (book == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("name", book.name)
            put("description", book.description)
            put("entries", buildJsonArray { book.entries.forEach { add(entryToStJson(it)) } })
        }
    }

    val data = buildJsonObject {
        put("name", name)
        put("description", tav.description)
        put("personality", tav.personality)
        put("scenario", tav.scenario)
        put("first_mes", tav.firstMessage)
        put("mes_example", tav.mesExample)
        put("creator_notes", tav.creatorNotes)
        put("system_prompt", tav.systemPrompt)
        put("post_history_instructions", tav.postHistoryInstructions)
        put("alternate_greetings", buildJsonArray { tav.alternateGreetings.forEach { add(JsonPrimitive(it)) } })
        put("character_book", characterBook)
        put("tags", tagsArray)
        put("creator", tav.creator)
        put("character_version", tav.characterVersion)
        put("extensions", extensions)
        put("group_only_greetings", buildJsonArray { tav.groupOnlyGreetings.forEach { add(JsonPrimitive(it)) } })
        put("depth_prompt", buildJsonObject {
            put("prompt", tav.depthPrompt)
            put("depth", tav.depthPromptDepth)
            put("role", tav.depthPromptRole)
        })
    }

    return buildJsonObject {
        put("name", name)
        // ST 拿「角色文件名」当 avatar（JSR 用它做 per-character store 的键、还会 .replace() 它）：
        // 宿主没有这个概念 → 用助手 id 派生一个稳定标识，保证扩展永远拿到字符串而非 undefined。
        put("avatar", (assistant?.id?.toString() ?: "character") + ".png")
        put("description", tav.description)
        put("personality", tav.personality)
        put("scenario", tav.scenario)
        put("first_mes", tav.firstMessage)
        put("mes_example", tav.mesExample)
        put("creatorcomment", tav.creatorNotes) // ST 兼容旧字段（等价 data.creator_notes）
        put("chat", "")
        put("talkativeness", (assistant?.talkativeness ?: 0.5f).toString())
        put("fav", false)
        put("tags", tagsArray)
        put("spec", tav.spec)
        put("spec_version", tav.specVersion)
        put("data", data)
    }.toString()
}


/**
 * 将 JSR `createOrReplaceWorldbook(name, entries)` / `createWorldbook(name, entries)` 传入的条目数组 JSON
 * 转换为宿主 [Lorebook]。若传入 `existing` 则保留其 id 与 enabled 状态。
 */
fun buildLorebookFromJsrEntries(
    name: String,
    entriesJson: String,
    existing: Lorebook? = null,
): Lorebook {
    val parsedEntries: List<PromptInjection.RegexInjection> = runCatching {
        if (entriesJson.isBlank()) {
            emptyList()
        } else {
            when (val el = Json.parseToJsonElement(entriesJson)) {
                is JsonArray -> el.mapNotNull { (it as? JsonObject)?.let(::jsrEntryToRegexInjection) }
                is JsonObject -> {
                    val arr = (el["upsert"] as? JsonArray) ?: (el["entries"] as? JsonArray)
                    arr?.mapNotNull { (it as? JsonObject)?.let(::jsrEntryToRegexInjection) }.orEmpty()
                }
                else -> emptyList()
            }
        }
    }.getOrDefault(emptyList())
    return existing?.copy(name = name.trim(), entries = parsedEntries)
        ?: Lorebook(
            name = name.trim(),
            enabled = true,
            entries = parsedEntries,
        )
}

/** 更新角色卡 `extensionsRaw` JSON 中的 `world` 字段（绑定主世界书名）。 */
fun updateWorldNameInExtensionsRaw(extensionsRaw: String?, worldName: String?): String {
    val map = parseExtensionsObject(extensionsRaw).toMutableMap()
    if (worldName.isNullOrBlank()) {
        map.remove("world")
    } else {
        map["world"] = JsonPrimitive(worldName.trim())
    }
    return JsonObject(map).toString()
}

