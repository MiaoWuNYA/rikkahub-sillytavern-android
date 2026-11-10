package me.rerere.rikkahub.data.st.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * MVU `<UpdateVariable>` 解析器（JSON Patch on JSON 树，JSON Pointer 支持多层路径）。
 *
 * 支持两种块内形态：
 * 1. 裸 JSON Patch 数组：`[{"op":"replace","path":"/hp","value":5}, …]`
 * 2. MVU shell：`<Analysis>…</Analysis><JSONPatch>[…]</JSONPatch>`
 *
 * 语义：成功 → 剥离块并返回新文本 + 更新后的树；
 * 失败 / 不完整（流式未闭合）→ 原文与树原样返回（applied = false）。
 * 参考实现：RikkaRs #188 + SillyTavern MVU 生态实际输出。
 */
object UpdateVariableParser {
    private val blockRegex = Regex(
        """<UpdateVariable>([\s\S]*?)</UpdateVariable>""",
        RegexOption.IGNORE_CASE,
    )
    private val openRegex = Regex("""<UpdateVariable\b""", RegexOption.IGNORE_CASE)
    private val closeRegex = Regex("""</UpdateVariable>""", RegexOption.IGNORE_CASE)
    private val jsonPatchXmlRegex = Regex(
        """<JSONPatch\b[^>]*>([\s\S]*?)</JSONPatch>""",
        RegexOption.IGNORE_CASE,
    )
    private val strictJson = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    data class Result(
        val text: String,
        val variables: JsonObject,
        val applied: Boolean,
    )

    fun apply(text: String, current: JsonObject): Result {
        if (text.isEmpty() || !text.contains("UpdateVariable", ignoreCase = true)) {
            return Result(text = text, variables = current, applied = false)
        }
        // 流式未闭合：保持原样（调用方可能稍后缓冲重试）。
        if (hasOpenUnclosedBlock(text)) {
            return Result(text = text, variables = current, applied = false)
        }
        val matches = blockRegex.findAll(text).toList()
        if (matches.isEmpty()) {
            return Result(text = text, variables = current, applied = false)
        }
        var root: JsonObject = current
        var anyApplied = false
        val rangesToStrip = mutableListOf<IntRange>()
        for (match in matches) {
            val body = match.groupValues[1].trim()
            val ops = parseOps(body) ?: return Result(text = text, variables = current, applied = false)
            val next = applyOps(root, ops) ?: return Result(text = text, variables = current, applied = false)
            root = next
            anyApplied = true
            rangesToStrip += match.range
        }
        if (!anyApplied) {
            return Result(text = text, variables = current, applied = false)
        }
        val stripped = stripRanges(text, rangesToStrip).trim()
        return Result(text = stripped, variables = root, applied = true)
    }

    private fun hasOpenUnclosedBlock(text: String): Boolean {
        val openCount = openRegex.findAll(text).count()
        val closeCount = closeRegex.findAll(text).count()
        return openCount > closeCount
    }

    // ==================== 解析 ====================

    private fun parseOps(body: String): List<JsonObject>? {
        parseJsonOps(body)?.let { return it }
        val xmlInner = jsonPatchXmlRegex.find(body)?.groupValues?.get(1)?.trim()
        if (!xmlInner.isNullOrEmpty()) {
            return parseJsonOps(xmlInner)
        }
        return null
    }

    private fun parseJsonOps(payload: String): List<JsonObject>? {
        val element = runCatching { strictJson.parseToJsonElement(payload) }.getOrNull() ?: return null
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> {
                val nested = element["JSONPatch"]
                    ?: element["jsonPatch"]
                    ?: element["ops"]
                    ?: element["patch"]
                nested as? JsonArray ?: return null
            }
            else -> return null
        }
        return array.mapNotNull { it as? JsonObject }
    }

    // ==================== 应用 ====================

    private fun applyOps(root: JsonObject, ops: List<JsonObject>): JsonObject? {
        if (ops.isEmpty()) return root
        var current: JsonObject = root
        for (opObj in ops) {
            val op = (opObj["op"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return null
            val path = (opObj["path"] as? JsonPrimitive)?.contentOrNull ?: return null
            val segments = parsePointer(path) ?: return null
            current = when (op) {
                "add", "replace" -> {
                    val value = opObj["value"] ?: return null
                    (applySet(current, segments, value) as? JsonObject) ?: return null
                }
                "remove" -> {
                    (applyRemove(current, segments) as? JsonObject) ?: return null
                }
                else -> return null
            }
        }
        return current
    }

    /** JSON Pointer 解析（RFC 6901 的 ~1→/、~0→~ 转义）。 */
    private fun parsePointer(path: String): List<String>? {
        if (!path.startsWith("/")) return null
        if (path == "/") return listOf("")
        return path.removePrefix("/").split("/").map { it.replace("~1", "/").replace("~0", "~") }
    }

    private fun applySet(node: JsonElement?, segments: List<String>, value: JsonElement): JsonElement? {
        if (segments.isEmpty()) return value
        val head = segments.first()
        val rest = segments.drop(1)
        return when (node) {
            is JsonArray -> {
                val idx = head.toIntOrNull() ?: return null
                val list = node.toMutableList()
                if (rest.isEmpty()) {
                    when {
                        idx < list.size -> list[idx] = value
                        idx == list.size -> list.add(value)
                        else -> return null
                    }
                } else {
                    if (idx !in list.indices) return null
                    list[idx] = applySet(list[idx], rest, value) ?: return null
                }
                JsonArray(list)
            }
            is JsonObject -> {
                val map = node.toMutableMap()
                if (rest.isEmpty()) {
                    map[head] = value
                } else {
                    map[head] = applySet(map[head], rest, value) ?: return null
                }
                JsonObject(map)
            }
            else -> {
                // 缺失节点/标量：新建对象容器继续深入
                val map = linkedMapOf<String, JsonElement>()
                if (rest.isEmpty()) {
                    map[head] = value
                } else {
                    map[head] = applySet(null, rest, value) ?: return null
                }
                JsonObject(map)
            }
        }
    }

    private fun applyRemove(node: JsonElement, segments: List<String>): JsonElement? {
        if (segments.isEmpty()) return null
        val head = segments.first()
        val rest = segments.drop(1)
        return when (node) {
            is JsonArray -> {
                val idx = head.toIntOrNull() ?: return node
                val list = node.toMutableList()
                if (rest.isEmpty()) {
                    if (idx in list.indices) list.removeAt(idx)
                } else {
                    if (idx !in list.indices) return node
                    list[idx] = applyRemove(list[idx], rest) ?: return null
                }
                JsonArray(list)
            }
            is JsonObject -> {
                val map = node.toMutableMap()
                if (rest.isEmpty()) {
                    map.remove(head)
                } else {
                    val child = map[head] ?: return node
                    map[head] = applyRemove(child, rest) ?: return null
                }
                JsonObject(map)
            }
            else -> return node
        }
    }

    private fun stripRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val sorted = ranges.sortedByDescending { it.first }
        var result = text
        for (range in sorted) {
            result = result.removeRange(range)
        }
        // Collapse leftover double blank lines from stripped blocks.
        return result.replace(Regex("\\n{3,}"), "\n\n")
    }
}