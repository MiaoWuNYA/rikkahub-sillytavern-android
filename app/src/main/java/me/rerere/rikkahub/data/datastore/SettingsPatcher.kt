package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.utils.JsonInstant

/** 设置路径读写：a.b[0].c、assistants[id=xxx].temperature */
object SettingsPatcher {
    private val PATH_TOKEN = Regex("""([^.\[]+)|\[([^\]]*)]""")

    fun tokens(path: String): List<String> {
        val result = PATH_TOKEN.findAll(path).map { m ->
            m.groupValues[1].takeIf { it.isNotEmpty() } ?: "[${m.groupValues[2]}]"
        }.toList()
        require(result.isNotEmpty()) { "empty path" }
        return result
    }

    fun encode(settings: Settings): JsonObject =
        JsonInstant.encodeToJsonElement(Settings.serializer(), settings) as JsonObject

    fun decode(tree: JsonObject): Settings =
        JsonInstant.decodeFromJsonElement(Settings.serializer(), tree)

    fun read(root: JsonElement, path: String): JsonElement = readTokens(root, tokens(path))

    fun readTokens(element: JsonElement, tokens: List<String>): JsonElement {
        if (tokens.isEmpty()) return element
        val head = tokens.first()
        val rest = tokens.drop(1)
        if (head.startsWith("[")) {
            val array = element as? JsonArray ?: error("$head is not an array index")
            val selector = head.removePrefix("[").removeSuffix("]")
            val index = selector.toIntOrNull() ?: indexOf(array, selector)
            if (index !in array.indices) error("not found: $selector")
            return readTokens(array[index], rest)
        }
        val obj = element as? JsonObject ?: error("cannot read $head")
        val child = obj[head] ?: error("key not found: $head")
        return readTokens(child, rest)
    }

    fun write(root: JsonElement, path: String, value: JsonElement): JsonElement =
        writeTokens(root, tokens(path), value)

    fun writeTokens(element: JsonElement, tokens: List<String>, value: JsonElement): JsonElement {
        if (tokens.isEmpty()) return value
        val head = tokens.first()
        val rest = tokens.drop(1)
        if (head.startsWith("[")) {
            val array = element as? JsonArray ?: error("$head is not an array index")
            val selector = head.removePrefix("[").removeSuffix("]")
            val index = selector.toIntOrNull() ?: indexOf(array, selector)
            if (index !in array.indices) error("not found: $selector")
            return JsonArray(array.mapIndexed { i, item -> if (i == index) writeTokens(item, rest, value) else item })
        }
        val obj = element as? JsonObject ?: error("cannot write $head")
        val child = obj[head] ?: JsonNull
        return JsonObject(obj + (head to writeTokens(child, rest, value)))
    }

    fun indexOf(array: JsonArray, selector: String): Int = array.indexOfFirst { item ->
        val obj = item as? JsonObject
        obj?.get("id")?.let { (it as? JsonPrimitive)?.content } == selector ||
            obj?.get("name")?.let { (it as? JsonPrimitive)?.content } == selector
    }

    fun coerce(current: JsonElement?, raw: String): JsonElement {
        val trimmed = raw.trim()
        if (current is JsonPrimitive && current.isString) return JsonPrimitive(raw)
        val literal = trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed == "null" ||
            trimmed == "true" || trimmed == "false" || trimmed.matches(Regex("-?\\d+(\\.\\d+)?"))
        if (!literal) return JsonPrimitive(raw)
        return runCatching { JsonInstant.parseToJsonElement(trimmed) }.getOrElse { JsonPrimitive(raw) }
    }
}
