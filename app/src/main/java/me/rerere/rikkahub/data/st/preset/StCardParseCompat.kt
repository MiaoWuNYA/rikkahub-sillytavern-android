package me.rerere.rikkahub.data.st.preset

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.TavernCharacterData

/**
 * 最小等价实现（等待 ui/pages/assistant/detail 正式 parseCardFromUri 交付后替换）：
 * 只做 v1/v2/v3 JSON、PNG 内嵌 chara、charx/byaf 的 card.json 解析。
 *
 * 已知缺口：角色卡头像/背景落盘、内嵌世界书（character_book）转 Lorebook 未移植，books 恒为空。
 */
internal object StCardParseCompat {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun parseCardFromUri(context: Context, uri: Uri): Pair<Assistant, List<Lorebook>>? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val text = extractCardJson(bytes) ?: return null
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val payload = root["data"] as? JsonObject ?: root
        val data = toCharacterData(root, payload)
        val assistant = Assistant(
            name = data.name.ifBlank { "Unnamed" },
            tavernData = data,
        )
        return assistant to emptyList()
    }

    private fun extractCardJson(bytes: ByteArray): String? {
        if (bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            return extractPngCardText(bytes)
        }
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return extractZipCardText(bytes)
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /** PNG：遍历 tEXt 块，取 keyword=chara/ccv3 的 base64 负载。 */
    private fun extractPngCardText(bytes: ByteArray): String? {
        var offset = 8
        while (offset + 8 <= bytes.size) {
            val length = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            if (length < 0 || offset + 12 + length > bytes.size) return null
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (type == "tEXt") {
                val payload = String(bytes, offset + 8, length, Charsets.ISO_8859_1)
                val keyword = payload.substringBefore('\u0000', "")
                val value = payload.substringAfter('\u0000', "")
                if (keyword == "chara" || keyword == "ccv3") {
                    return decodeBase64(value)
                }
            }
            offset += 12 + length
        }
        return null
    }

    /** charx/byaf：ZIP 内的 card.json（缺省退回第一个含 spec 字段的 json）。 */
    private fun extractZipCardText(bytes: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var fallback: String? = null
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".json")) {
                    val text = zin.readBytes().toString(Charsets.UTF_8)
                    if (entry.name.substringAfterLast('/').equals("card.json", ignoreCase = true)) return text
                    if (fallback == null && (text.contains("\"spec\"") || text.contains("character_book"))) {
                        fallback = text
                    }
                }
                entry = zin.nextEntry
            }
            return fallback
        }
    }

    private fun decodeBase64(value: String): String? = runCatching {
        String(java.util.Base64.getDecoder().decode(value.trim()), Charsets.UTF_8)
    }.getOrNull()

    private fun toCharacterData(root: JsonObject, payload: JsonObject): TavernCharacterData {
        return TavernCharacterData(
            spec = str(root["spec"]).orEmpty(),
            specVersion = str(root["spec_version"]).orEmpty(),
            name = str(payload["name"]).orEmpty(),
            description = str(payload["description"]).orEmpty(),
            personality = str(payload["personality"]).orEmpty(),
            scenario = str(payload["scenario"]).orEmpty(),
            firstMessage = str(payload["first_mes"]).orEmpty(),
            alternateGreetings = strList(payload["alternate_greetings"]),
            mesExample = str(payload["mes_example"]).orEmpty(),
            systemPrompt = str(payload["system_prompt"]).orEmpty(),
            creator = str(payload["creator"]).orEmpty(),
            creatorNotes = str(payload["creator_notes"]).orEmpty(),
            characterVersion = str(payload["character_version"]).orEmpty(),
            tags = strList(payload["tags"]),
            postHistoryInstructions = str(payload["post_history_instructions"]).orEmpty(),
            extensions = extensionPairs(payload["extensions"]),
            groupOnlyGreetings = strList(payload["group_only_greetings"]),
            nickname = str(payload["nickname"]).orEmpty(),
        )
    }

    private fun str(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    private fun int(element: JsonElement?): Int? = (element as? JsonPrimitive)?.intOrNull

    private fun bool(element: JsonElement?): Boolean? = (element as? JsonPrimitive)?.booleanOrNull

    private fun strList(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    private fun extensionPairs(element: JsonElement?): Map<String, String> {
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }.toMap()
    }
}
