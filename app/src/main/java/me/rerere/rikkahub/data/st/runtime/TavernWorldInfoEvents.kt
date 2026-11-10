package me.rerere.rikkahub.data.st.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection

/**
 * [v228 S5] ST 世界书事件载荷构造（纯函数 -> 可单测）。
 *
 * 真源（`D:\\rikkaST-refs\\B-sillytavern\\SillyTavern__SillyTavern\\public\\scripts\\world-info.js`）：
 * - `WORLDINFO_ENTRIES_LOADED`（`events.js:97`）在 `:4604` 发，载荷 `{globalLore, characterLore, chatLore, personaLore}`
 *   —— 全部世界书**加载完成、扫描开始之前**；四个数组里的元素是官方 WorldInfoEntry 对象。
 * - `WORLDINFO_SCAN_DONE`（`events.js:98`）在 `:5149-5175` 发（注释原文「after each scan loop」），载荷 `args`
 *   形状见 `:5150-5174`：state{current,next,loopCount} / new{all,successful} / activated{entries,text} /
 *   sortedEntries / recursionDelay{availableLevels,currentLevel} / budget{current,overflowed} / timedEffects。
 *
 * ## 本地映射与已知差异（绝不编默认值）
 * - 条目字段只映射**语义等价**的 ST 同名字段（key/keysecondary/content/comment/disable/order/...）；
 *   ST 的 `uid`（number）本地没有，用「本次载荷内的下标」稳定映射（同一份载荷内唯一、可索引）；
 *   `position`/`depth` 等 ST 数值枚举本地语义不同（我们用 InjectionPosition 枚举）-> **不发**。
 * - `timedEffects` 本地未跟踪 -> 发空对象（消费方按字段存在性读）。
 * - `state.current/next` 用官方 `scan_state` 数值（world-info.js:43-51：NONE=0 / INITIAL=1 / RECURSION=2 /
 *   MIN_ACTIVATIONS=3）；本地循环的 0/1/2 在 [mapState] 里换算，`-1`（本地「无下一步」）= 官方 NONE=0。
 * - 载荷是 `fireEvent`（Kotlin -> JS，单向）：ST 允许监听者改写 `args`，本地这一条**不回读**（已登记）。
 *
 * ⚠️ 用 kotlinx.serialization（而不是 org.json）：本文件要能被 **JVM 单测**直接跑真值断言
 *   （org.json 在 unit test 里是 android.jar 桩，`JSONObject.put` 会抛 "not mocked"）。
 */
internal object TavernWorldInfoEvents {

    const val EVENT_ENTRIES_LOADED = "worldinfo_entries_loaded" // ST events.js:97
    const val EVENT_SCAN_DONE = "worldinfo_scan_done"           // ST events.js:98

    private val json = Json

    /** 本地扫描状态 -> 官方 `scan_state` 数值（world-info.js:43-51）。 */
    fun mapState(local: Int): Int = when (local) {
        0 -> 1   // INITIAL
        1 -> 2   // RECURSION
        2 -> 3   // MIN_ACTIVATIONS
        else -> 0 // NONE（本地 -1 = 无下一步）
    }

    /** 单条目 -> 官方 WorldInfoEntry 的**同名字段子集**（映射见 KDoc；不映射的字段不发）。 */
    fun entryJson(entry: PromptInjection.RegexInjection, uid: Int): JsonObject = buildJsonObject {
        put("uid", uid)
        putJsonArray("key") { entry.keywords.forEach { add(it) } }
        putJsonArray("keysecondary") { entry.secondaryKeys.forEach { add(it) } }
        put("content", entry.content)
        put("comment", entry.name)
        put("disable", !entry.enabled)          // ST disable = !本地 enabled
        put("constant", entry.constantActive)
        put("selective", entry.selective)
        put("order", entry.priority)            // ST order = 本地 priority
        put("probability", entry.probability)
        put("useProbability", entry.useProbability)
        put("group", entry.group)
        put("group_weight", entry.groupWeight)
        put("sticky", entry.sticky)
        put("cooldown", entry.cooldown)
        put("delay", entry.delay)
        put("excludeRecursion", entry.excludeRecursion)
        put("preventRecursion", entry.preventRecursion)
        put("delayUntilRecursion", entry.delayUntilRecursion)
        put("scanDepth", entry.scanDepth)       // Int? -> null 时写 JsonNull
        put("caseSensitive", entry.caseSensitive)
        put("matchWholeWords", entry.matchWholeWords)
        put("automationId", entry.automationId)
    }

    fun entryArray(entries: List<PromptInjection.RegexInjection>): JsonArray =
        buildJsonArray { entries.forEachIndexed { index, entry -> add(entryJson(entry, index)) } }

    /** `WORLDINFO_ENTRIES_LOADED`（world-info.js:4604）。 */
    fun entriesLoadedPayload(
        globalLore: List<PromptInjection.RegexInjection>,
        characterLore: List<PromptInjection.RegexInjection>,
        chatLore: List<PromptInjection.RegexInjection> = emptyList(),
        personaLore: List<PromptInjection.RegexInjection> = emptyList(),
    ): String = buildJsonObject {
        put("globalLore", entryArray(globalLore))
        put("characterLore", entryArray(characterLore))
        put("chatLore", entryArray(chatLore))
        put("personaLore", entryArray(personaLore))
    }.toString()

    /** `WORLDINFO_SCAN_DONE`（world-info.js:5150-5175）。 */
    fun scanDonePayload(
        loopCount: Int,
        currentState: Int,
        nextState: Int,
        newAll: List<PromptInjection.RegexInjection>,
        newSuccessful: List<PromptInjection.RegexInjection>,
        activatedEntries: List<PromptInjection.RegexInjection>,
        activatedText: String,
        sortedEntriesJson: JsonArray,
        recursionLevel: Int,
        availableLevels: List<Int>,
        budget: Int,
        overflowed: Boolean,
    ): String = buildJsonObject {
        putJsonObject("state") {
            put("current", mapState(currentState))
            put("next", mapState(nextState))
            put("loopCount", loopCount)
        }
        putJsonObject("new") {
            put("all", entryArray(newAll))
            put("successful", entryArray(newSuccessful))
        }
        putJsonObject("activated") {
            put("entries", entryArray(activatedEntries))
            put("text", activatedText)
        }
        put("sortedEntries", sortedEntriesJson)
        putJsonObject("recursionDelay") {
            putJsonArray("availableLevels") { availableLevels.forEach { add(it) } }
            put("currentLevel", recursionLevel)
        }
        putJsonObject("budget") {
            put("current", budget)
            put("overflowed", overflowed)
        }
        // 本地未跟踪 timedEffects（ST 里是可选子集；发空对象而不是编字段）
        putJsonObject("timedEffects") { }
    }.toString()

    /** 排序条目数组（uid = 数组下标）；只在真的有人订阅时才构造（见 collectInjections）。 */
    fun sortedEntriesJson(entries: List<Pair<Lorebook, PromptInjection.RegexInjection>>): JsonArray =
        buildJsonArray { entries.forEachIndexed { index, (_, entry) -> add(entryJson(entry, index)) } }

    /** 供测试/诊断：把 JSON 字符串解析回对象。 */
    fun parse(payload: String): JsonObject = json.parseToJsonElement(payload).let { it as JsonObject }
}
