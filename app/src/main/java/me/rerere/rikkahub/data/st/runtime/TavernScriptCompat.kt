package me.rerere.rikkahub.data.st.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.Assistant

/**
 * 酒馆助手（JS-Slash-Runner，下称 JSR）角色卡脚本存储兼容层。
 *
 * JSR 当前把角色卡脚本写在 `data.extensions.tavern_helper.scripts`，但它自身仍兼容更早版本导出的卡：
 * - 旧字段：`data.extensions.TavernHelper_scripts` + `TavernHelper_characterScriptVariables`；
 * - 旧 ScriptTree：`{type:"script", value:{...}}` / `{type:"folder", value:[...]}`；
 * - `tavern_helper` 可能被序列化成 `Object.fromEntries` 形式的 `[[key,value],...]` 数组。
 *
 * RikkaHub 的 UI 和 WebView 运行时如果只读 `tavern_helper.scripts`，上述存量卡会显示成
 * 「无卡内脚本」，JSR 自己的脚本库也会读不到。本对象把这些历史形态统一归一化为当前 JSR 形状；
 * 它只做纯 JSON 变换，不依赖 Android，便于 JVM/Node 侧回归测试。
 */
internal object TavernScriptCompat {

    /**
     * `Assistant.tavernData` → ST `data.extensions` 对象。
     *
     * 新数据以 [me.rerere.rikkahub.data.model.TavernCharacterData.extensionsRaw] 为准；
     * 旧版本持久化的助手可能只有 `extensions: Map<String, String>`（嵌套值被 stringify），
     * 此时从 Map 重建，避免升级后卡内脚本丢失。
     */
    fun cardExtensions(assistant: Assistant?): JsonObject {
        val tav = assistant?.tavernData ?: return JsonObject(emptyMap())
        val out = linkedMapOf<String, JsonElement>()
        tav.extensions.forEach { (key, value) ->
            if (key.isNotBlank()) out[key] = parseMaybeJson(value)
        }
        val raw = tav.extensionsRaw.takeIf { it.isNotBlank() }
        if (raw != null) {
            val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
            if (parsed is JsonObject) {
                // 原始 JSON 是权威来源；Map 只补齐旧数据中缺失的键。
                parsed.forEach { (key, value) -> out[key] = value }
            }
        }
        return JsonObject(out)
    }

    /**
     * 从 `data.extensions` 提取归一化后的 `tavern_helper` 对象。
     * 新字段缺失时回退到 JSR 3.2.3 及更早版本的 `TavernHelper_scripts`。
     */
    fun tavernHelper(root: JsonObject): JsonObject? {
        val helperEl = root["tavern_helper"]
        if (helperEl != null && helperEl !is JsonNull) {
            normalizeHelperValue(helperEl)?.let { return it }
        }
        val legacy = legacyScripts(root["TavernHelper_scripts"]) ?: return null
        val variables = normalizeVariables(root["TavernHelper_characterScriptVariables"])
        return buildJsonObject {
            put("scripts", JsonArray(normalizeScripts(legacy)))
            put("variables", variables)
        }
    }

    /** 把一个 ScriptTree/脚本对象归一化为当前 JSR 形状；非对象返回 null。 */
    fun normalizeScript(item: JsonElement): JsonElement? {
        val obj = item as? JsonObject ?: return null
        val type = obj.str("type").lowercase()
        val scriptChildren = obj["scripts"] as? JsonArray
        val legacyChildren = obj["value"] as? JsonArray
        // 当前 folder 用 scripts，旧 folder 用 value；type 缺失时按「有子数组且无 content」推断。
        val isFolder = type == "folder" ||
            (type != "script" && obj["content"] == null && (scriptChildren != null || legacyChildren != null))
        if (isFolder) {
            val children = scriptChildren ?: legacyChildren ?: JsonArray(emptyList())
            return buildJsonObject {
                obj.forEach { (key, value) ->
                    if (key != "scripts" && key != "value") put(key, value)
                }
                put("type", JsonPrimitive("folder"))
                put("scripts", JsonArray(normalizeScripts(children.toList())))
            }
        }
        val payload = obj["value"] as? JsonObject
        val base = if (payload != null) mergeScriptWrapper(obj, payload) else obj
        return buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            put("type", JsonPrimitive("script"))
            if (base["button"] == null) {
                val legacyButtons = base["buttons"] as? JsonArray
                if (legacyButtons != null) {
                    put(
                        "button",
                        buildJsonObject {
                            put("enabled", JsonPrimitive(true))
                            put("buttons", legacyButtons)
                        },
                    )
                }
            }
        }
    }

    /** 批量归一化（兼容旧文件夹 value / 旧 script value 包装）。 */
    fun normalizeScripts(items: List<JsonElement>): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        for (item in items) {
            when (item) {
                is JsonObject -> normalizeScript(item)?.let { out += it }
                is JsonArray -> item.forEach { nested ->
                    if (nested is JsonObject) normalizeScript(nested)?.let { out += it }
                }
                else -> Unit
            }
        }
        return out
    }

    /**
     * 在 ScriptTree 列表里递归更新指定 id 的 enabled；返回 (归一化后的新列表, 是否找到)。
     * 写入时顺带完成旧格式 → 当前 JSR 格式迁移。
     */
    fun updateEnabled(
        items: List<JsonElement>,
        scriptId: String,
        enabled: Boolean,
    ): Pair<List<JsonElement>, Boolean> {
        var found = false
        fun update(item: JsonElement): JsonElement? {
            val normalized = normalizeScript(item) as? JsonObject ?: return null
            if (normalized.str("type") == "folder") {
                val children = (normalized["scripts"] as? JsonArray)?.mapNotNull { update(it) }.orEmpty()
                return normalized.withField("scripts", JsonArray(children))
            }
            if (normalized.str("id") == scriptId) {
                found = true
                return normalized.withField("enabled", JsonPrimitive(enabled))
            }
            return normalized
        }
        val next = items.mapNotNull { update(it) }
        return next to found
    }

    /**
     * 解析任意「酒馆助手脚本」JSON 元素，兼容：
     * `[script,...]` / `{"scripts":[...]}` / `{"tavern_helper":{...}}` /
     * `{"tavern_helper":[[key,value],...]}` / `{"TavernHelper_scripts":[...]}` / 单个脚本对象。
     */
    fun extractScripts(element: JsonElement): List<JsonElement> = when (element) {
        is JsonArray -> normalizeScripts(element.toList())
        is JsonObject -> {
            val helperScripts = normalizeHelperValue(element["tavern_helper"])
                ?.get("scripts") as? JsonArray
            val directScripts = element["scripts"] as? JsonArray
            val legacy = legacyScripts(element["TavernHelper_scripts"])
            when {
                helperScripts != null -> normalizeScripts(helperScripts.toList())
                directScripts != null -> normalizeScripts(directScripts.toList())
                legacy != null -> normalizeScripts(legacy)
                element["type"] != null || element["content"] != null || element["value"] is JsonObject ->
                    normalizeScripts(listOf(element))
                else -> emptyList()
            }
        }
        else -> emptyList()
    }

    // ---------------------------------------------------------------- internal

    private fun normalizeHelperValue(element: JsonElement?): JsonObject? = when (element) {
        is JsonObject -> normalizeHelperObject(element)
        is JsonArray -> objectFromPairs(element)?.let(::normalizeHelperObject)
        else -> null
    }

    private fun normalizeHelperObject(obj: JsonObject): JsonObject {
        val scripts = when (val raw = obj["scripts"]) {
            is JsonArray -> normalizeScripts(raw.toList())
            is JsonObject -> normalizeScripts(raw.values.toList())
            else -> emptyList()
        }
        val variables = normalizeVariables(obj["variables"])
        return buildJsonObject {
            obj.forEach { (key, value) ->
                if (key != "scripts" && key != "variables") put(key, value)
            }
            put("scripts", JsonArray(scripts))
            put("variables", variables)
        }
    }

    private fun normalizeVariables(element: JsonElement?): JsonObject = when (element) {
        is JsonObject -> element
        is JsonArray -> objectFromPairs(element) ?: JsonObject(emptyMap())
        else -> JsonObject(emptyMap())
    }

    private fun legacyScripts(element: JsonElement?): List<JsonElement>? = when (element) {
        is JsonArray -> element.toList()
        is JsonObject -> when (val scripts = element["scripts"] ?: element["value"]) {
            is JsonArray -> scripts.toList()
            is JsonObject -> scripts.values.toList()
            else -> null
        }
        else -> null
    }

    /** `[[key,value],...]` / `[{key,value},...]` → JsonObject（Object.fromEntries 兼容）。 */
    private fun objectFromPairs(array: JsonArray): JsonObject? {
        val out = linkedMapOf<String, JsonElement>()
        for (item in array) {
            when (item) {
                is JsonArray -> {
                    if (item.size < 2) continue
                    val key = (item[0] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: continue
                    out[key] = item[1]
                }
                is JsonObject -> {
                    val key = item.str("key").ifBlank { item.str("name") }
                    if (key.isBlank()) continue
                    val value = item["value"] ?: continue
                    out[key] = value
                }
                else -> Unit
            }
        }
        return if (out.isEmpty()) null else JsonObject(out)
    }

    /** `{type:"script", value:{实际脚本字段}}` 旧包装 → 实际脚本对象。 */
    private fun mergeScriptWrapper(wrapper: JsonObject, payload: JsonObject): JsonObject {
        val merged = payload.toMutableMap()
        for (key in listOf("id", "name", "enabled", "info", "content", "buttons")) {
            if (merged[key] == null) wrapper[key]?.let { merged[key] = it }
        }
        return JsonObject(merged)
    }

    /** 旧持久化 `extensions` Map 的 value 可能是 JSON 文本，也可能是普通字符串。 */
    private fun parseMaybeJson(raw: String): JsonElement {
        val text = raw.trim()
        if (text.isEmpty()) return JsonPrimitive(raw)
        return runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(raw) }
    }

    private fun JsonObject.withField(key: String, value: JsonElement): JsonObject {
        val map = toMutableMap()
        map[key] = value
        return JsonObject(map)
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

