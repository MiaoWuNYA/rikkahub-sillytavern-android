package me.rerere.rikkahub.data.st.macro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 宏变量存储 —— 内存实现。
 *
 * 行为对齐 SillyTavern 1.18.0 `public/scripts/variables.js`：
 * - get：数字字符串归一化为数值形式（"3.0" → "3"）；非数字返回原字符串；
 *        空白串按 falsy 处理；不存在返回 null（对应 JS undefined）。
 * - add：JSON 数组 push；数值相加；否则字符串拼接（含 "0" / 空值 falsy 特例）。
 * - inc / dec = add(±1)。
 *
 * 持久化（会话级 / 应用级）由批次 B 的外层存储实现，本类作为默认与测试实现。
 */
class InMemoryMacroVariableStore : MacroVariableStore {
    private val map = ConcurrentHashMap<String, String>()

    override fun get(name: String): String? = map[name]?.let { JsNumberNormalizer.getNormalized(it) }

    override fun has(name: String): Boolean = map.containsKey(name)

    override fun set(name: String, value: String) {
        map[name] = value
    }

    override fun del(name: String) {
        map.remove(name)
    }

    override fun inc(name: String): String = addImpl(name, "1")

    override fun dec(name: String): String = addImpl(name, "-1")

    override fun add(name: String, value: String) {
        addImpl(name, value)
    }

    override fun getAtKey(name: String, key: String): String? = MacroVarKeyOps.getAtKey(map[name], key)

    override fun setAtKey(name: String, key: String, value: String) {
        map[name] = MacroVarKeyOps.setAtKey(map[name], key, value)
    }

    /** 供测试与持久化导出：原始存储视图（含未归一化值） */
    fun snapshot(): Map<String, String> = map.toMap()

    /** 供测试与持久化导入 */
    fun restore(data: Map<String, String>) {
        map.clear()
        map.putAll(data)
    }

    /**
     * 对应 ST addLocalVariable / addGlobalVariable。
     * 返回新值的字符串形式（数组 → JSON）。
     */
    private fun addImpl(name: String, value: String): String {
        // const currentValue = getLocalVariable(name) || 0;
        val rawCurrent = map[name]
        val currentValue: String = run {
            val normalized = rawCurrent?.let { JsNumberNormalizer.getNormalized(it) }
            if (normalized.isNullOrEmpty()) "0" else normalized
        }

        // try { const parsedValue = JSON.parse(currentValue); if (Array.isArray(...)) { push; return } } catch {}
        val parsedArray = runCatching {
            (Json.parseToJsonElement(currentValue) as? JsonArray)
        }.getOrNull()
        if (parsedArray != null) {
            val updated = JsonArray(parsedArray + JsonPrimitive(value))
            val serialized = updated.toString()
            map[name] = serialized
            return serialized
        }

        val increment = JsNumberNormalizer.parse(value)
        if (increment == null || JsNumberNormalizer.parse(currentValue) == null) {
            // 字符串拼接分支：String(currentValue || '') + value
            val base = falsyBase(currentValue)
            val stringValue = base + value
            map[name] = stringValue
            return stringValue
        }

        val newValue = JsNumberNormalizer.parse(currentValue)!! + increment
        val formatted = JsNumberNormalizer.format(newValue)
        map[name] = formatted
        return formatted
    }

    /**
     * 模拟 JS `currentValue || ''`：
     * - 数值 0 / 空串 → ''
     * - 其他数值 → 格式化数字串
     * - 非数字字符串 → 原样（注意 "0" 经 get 归一化后已是数字 0，会落回 ''）
     */
    private fun falsyBase(currentValue: String): String {
        if (currentValue.isEmpty()) return ""
        val n = JsNumberNormalizer.parse(currentValue)
        return when {
            n != null -> if (n == 0.0) "" else JsNumberNormalizer.format(n)
            else -> currentValue
        }
    }
}

/**
 * JS Number / String 数字语义的简化移植：
 * - parse：模拟 `Number(value)`（trim 后解析，失败为 null / NaN）
 * - format：模拟 `String(number)` 的常见形态（整数不带小数点）
 * - getNormalized：模拟 variables.js get 的归一化返回
 */
internal object JsNumberNormalizer {
    fun parse(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty()) return 0.0 // Number('') === 0
        t.toDoubleOrNull()?.let { return it }
        // 常见 JS 扩展：Infinity
        return when (t) {
            "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> null
        }
    }

    fun format(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value == Double.POSITIVE_INFINITY) return "Infinity"
        if (value == Double.NEGATIVE_INFINITY) return "-Infinity"
        return if (value % 1.0 == 0.0 && abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    /**
     * 对齐 ST：
     * `(v?.trim?.() === '' || isNaN(Number(v))) ? (v || '') : Number(v)`
     * 输出统一为字符串；不存在（null）由调用方处理。
     */
    fun getNormalized(raw: String): String {
        val trimmedEmpty = raw.trim().isEmpty()
        val number = parse(raw)
        return if (trimmedEmpty || number == null) {
            if (raw.isEmpty()) "" else raw
        } else {
            format(number)
        }
    }
}

/**
 * varkey 系宏的存储无关 JSON 树读写（对齐 ST variables.js get/set 的 index 分支）。
 *
 * - 读：根为对象按 key 取；根为数组按数字下标取（越界或字符串 key 返回 null）；
 *   取出对象或数组时序列化为 JSON 串；原始类型返回 content；根缺失或解析失败返回 null。
 * - 写：根缺失时数字 key 建数组、其余建对象；数组加数字 key 原位写或末位追加；
 *   数组加字符串 key 降级为数字下标键对象（对齐 JS 数组命名属性语义）；
 *   根为字符串等原始类型时 JS 语义下不可写，静默忽略并返回原值。
 */
internal object MacroVarKeyOps {
    private fun decodeNode(node: JsonElement?): String? = when (node) {
        null -> null
        is JsonPrimitive -> node.content
        else -> node.toString()
    }

    fun getAtKey(rootRaw: String?, key: String): String? {
        if (rootRaw == null) return null
        val parsed = runCatching { Json.parseToJsonElement(rootRaw) }.getOrNull() ?: return null
        val numKey = key.trim().toLongOrNull()
        return when (parsed) {
            is JsonObject -> decodeNode(parsed[key])
            is JsonArray -> {
                if (numKey != null && numKey >= 0 && numKey < parsed.size) decodeNode(parsed[numKey.toInt()]) else null
            }
            else -> null
        }
    }

    fun setAtKey(rootRaw: String?, key: String, value: String): String {
        val root = if (rootRaw.isNullOrBlank()) null
            else runCatching { Json.parseToJsonElement(rootRaw) }.getOrNull()
        val numKey = key.trim().toLongOrNull()
        val newPrimitive = JsonPrimitive(value)
        return when (root) {
            is JsonObject -> {
                val m = root.toMutableMap()
                m[key] = newPrimitive
                JsonObject(m).toString()
            }
            is JsonArray -> {
                if (numKey != null) {
                    val list = root.toMutableList()
                    val idx = numKey.toInt()
                    if (idx in 0 until list.size) list[idx] = newPrimitive
                    else if (idx == list.size) list.add(newPrimitive)
                    JsonArray(list).toString()
                } else {
                    val m = LinkedHashMap<String, JsonElement>()
                    root.forEachIndexed { i, el -> m[i.toString()] = el }
                    m[key] = newPrimitive
                    JsonObject(m).toString()
                }
            }
            is JsonNull -> {
                if (numKey != null) JsonArray(mutableListOf(newPrimitive)).toString()
                else JsonObject(linkedMapOf(key to newPrimitive)).toString()
            }
            null -> {
                if (numKey != null) JsonArray(mutableListOf(newPrimitive)).toString()
                else JsonObject(linkedMapOf(key to newPrimitive)).toString()
            }
            else -> rootRaw ?: ""
        }
    }
}