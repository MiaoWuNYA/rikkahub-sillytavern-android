package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * 角色卡导出工具 — PNG tEXt chunk 嵌入 / JSON 导出
 * 对齐 SillyTavern V3 spec
 */
object CardExporter {

    /**
     * 将角色卡 JSON 嵌入到已有图片的 PNG tEXt chunk 中
     * 支持传入任意格式图片（JPEG/WebP/BMP等），自动转PNG
     */
    fun embedCardToPng(imageUri: Uri, context: Context, cardJson: String): ByteArray? {
        val originalBytes = try {
            context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
        } catch (_: Exception) { null } ?: return null

        // 检查是否为有效 PNG
        val isPng = originalBytes.size >= 8 &&
            originalBytes[0] == 0x89.toByte() &&
            originalBytes[1] == 0x50.toByte()

        val pngBytes = if (isPng) {
            originalBytes
        } else {
            // 非PNG格式 → 转PNG
            val bitmap = try {
                BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
            } catch (_: Exception) { null } ?: return null
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }

        // 官方双 chunk 写法：chara=V2 兼容格式，ccv3=V3 格式（导入时 ccv3 优先）
        val parsed = try {
            Json.parseToJsonElement(cardJson).jsonObject
        } catch (_: Exception) { null } ?: return null

        val v2Json = JsonObject(parsed.toMap() + mapOf(
            "spec" to JsonPrimitive("chara_card_v2"),
            "spec_version" to JsonPrimitive("2.0")
        )).toString()
        val charaB64 = Base64.encodeToString(v2Json.toByteArray(), Base64.NO_WRAP)
        val result = injectTextChunk(pngBytes, "chara", charaB64)

        // ccv3 chunk：与官方一致，强制 spec=chara_card_v3 / spec_version=3.0
        val v3Json = JsonObject(parsed.toMap() + mapOf(
            "spec" to JsonPrimitive("chara_card_v3"),
            "spec_version" to JsonPrimitive("3.0")
        )).toString()
        val ccv3B64 = Base64.encodeToString(v3Json.toByteArray(), Base64.NO_WRAP)
        return injectTextChunk(result, "ccv3", ccv3B64)
    }

    /**
     * 构建 V3 spec JSON — 对齐 SillyTavern chara_card_v3 官方格式
     */
    fun buildV3CardJson(assistant: me.rerere.rikkahub.data.model.Assistant): String {
        val tav = assistant.tavernData
        return buildJsonObject {
            put("spec", "chara_card_v3")
            // 官方 ccv3 与 V3 JSON 导出均强制 3.0，不沿用导入时的旧版本号
            put("spec_version", "3.0")
            putJsonObject("data") {
                put("name", tav?.name ?: assistant.name)
                put("description", tav?.description ?: "")
                put("personality", tav?.personality ?: "")
                put("scenario", tav?.scenario ?: "")
                put("first_mes", tav?.firstMessage ?: "")
                put("mes_example", tav?.mesExample ?: "")
                put("system_prompt", tav?.systemPrompt ?: assistant.systemPrompt)
                put("creator", tav?.creator ?: "")
                put("creator_notes", tav?.creatorNotes ?: "")
                put("character_version", tav?.characterVersion ?: "")
                put("post_history_instructions", tav?.postHistoryInstructions ?: "")
                putJsonArray("tags") { tav?.tags?.forEach { add(it) } }
                putJsonArray("alternate_greetings") { tav?.alternateGreetings?.forEach { add(it) } }
                putJsonArray("group_only_greetings") { tav?.groupOnlyGreetings?.forEach { add(it) } }
                if (tav?.assets?.isNotEmpty() == true) {
                    putJsonArray("assets") {
                        tav.assets.forEach { asset ->
                            addJsonObject {
                                put("type", asset.type)
                                put("name", asset.name)
                                put("uri", asset.uri)
                                put("ext", asset.ext)
                            }
                        }
                    }
                }
                if (tav?.nickname?.isNotBlank() == true) {
                    put("nickname", tav.nickname)
                }
                if (!tav?.creatorNotesMultilingual.isNullOrBlank()) {
                    put("creator_notes_multilingual", kotlinx.serialization.json.Json.parseToJsonElement(tav!!.creatorNotesMultilingual))
                }
                if (tav?.source?.isNotEmpty() == true) {
                    putJsonArray("source") { tav.source.forEach { add(it) } }
                }
                if (!tav?.creationDate.isNullOrBlank()) {
                    put("creation_date", kotlinx.serialization.json.Json.parseToJsonElement(tav!!.creationDate))
                }
                if (!tav?.modificationDate.isNullOrBlank()) {
                    put("modification_date", kotlinx.serialization.json.Json.parseToJsonElement(tav!!.modificationDate))
                }
                if (!tav?.extensionsRaw.isNullOrBlank()) {
                    // 有原始 JSON 时原样带回（无损）
                    put("extensions", kotlinx.serialization.json.Json.parseToJsonElement(tav!!.extensionsRaw))
                } else if (tav?.extensions?.isNotEmpty() == true) {
                    putJsonObject("extensions") {
                        tav.extensions.forEach { (k, v) -> put(k, v) }
                    }
                }
                if (tav?.embeddedBook != null) {
                    putJsonObject("character_book") {
                        put("name", tav.embeddedBook.name)
                        put("description", tav.embeddedBook.description)
                        if (tav.embeddedBook.scanDepth != null) put("scan_depth", tav.embeddedBook.scanDepth)
                        if (tav.embeddedBook.tokenBudget != null) put("token_budget", tav.embeddedBook.tokenBudget)
                        if (tav.embeddedBook.recursiveScanning != null) put("recursive_scanning", tav.embeddedBook.recursiveScanning)
                        if (tav.embeddedBook.maxRecursionSteps != null) put("max_recursion_steps", tav.embeddedBook.maxRecursionSteps)
                        if (tav.embeddedBook.minActivations != null) put("min_activations", tav.embeddedBook.minActivations)
                        if (!tav.embeddedBook.extensionsRaw.isNullOrBlank()) {
                            put("extensions", kotlinx.serialization.json.Json.parseToJsonElement(tav.embeddedBook.extensionsRaw))
                        } else if (tav.embeddedBook.extensions.isNotEmpty()) {
                            putJsonObject("extensions") {
                                tav.embeddedBook.extensions.forEach { (k, v) -> put(k, v) }
                            }
                        }
                        putJsonObject("entries") {
                            tav.embeddedBook.entries.forEach { entry ->
                                put(entry.id.toString(), buildJsonObject {
                                    putJsonArray("keys") { entry.keys.forEach { add(it) } }
                                    putJsonArray("secondary_keys") { entry.secondaryKeys.forEach { add(it) } }
                                    put("content", entry.content)
                                    put("comment", entry.comment)
                                    put("constant", entry.constant)
                                    put("selective", entry.selective)
                                    put("selectiveLogic", entry.selectiveLogic)
                                    // 官方 convertWorldInfoToCharacterBook：顶层 position 只写 before_char/after_char 字符串，
                                    // 具体数字位置（@Depth/EM/AN/outlet）一律走 extensions.position
                                    put("position", if (entry.position == 0) "before_char" else "after_char")
                                    put("order", entry.priority)
                                    // 官方 V2/V3 character_book 规范字段是 insertion_order（world-info.js: order: entry.insertion_order）
                                    put("insertion_order", entry.priority)
                                    put("disable", entry.disable)
                                    put("caseSensitive", entry.caseSensitive)
                                    put("useRegex", entry.useRegex)
                                    // 官方 V2 规范字段名（convertCharacterBook 不读，但 spec 与第三方工具按此识别）
                                    put("use_regex", true)
                                    put("probability", entry.probability)
                                    put("sticky", entry.sticky)
                                    put("cooldown", entry.cooldown)
                                    put("delay", entry.delay)
                                    put("scan_depth", entry.scanDepth)
                                    put("role", entry.role)
                                    put("group", entry.group)
                                    put("group_weight", entry.groupWeight)
                                    put("group_override", entry.groupOverride)
                                    put("depth", entry.depth)
                                    put("matchWholeWords", entry.matchWholeWords)
                                    put("excludeRecursion", entry.excludeRecursion)
                                    put("preventRecursion", entry.preventRecursion)
                                    put("delayUntilRecursion", entry.delayUntilRecursion)
                                    put("useProbability", entry.useProbability)
                                    // 原始 extensions 未知字段无损保留，官方字段用当前值覆盖（用户修改不丢）
                                    val extMap = if (!entry.extensionsRaw.isNullOrBlank()) {
                                        try {
                                            kotlinx.serialization.json.Json.parseToJsonElement(entry.extensionsRaw)
                                                .jsonObject.toMutableMap()
                                        } catch (_: Exception) { mutableMapOf() }
                                    } else {
                                        mutableMapOf()
                                    }
                                    // 官方 convertCharacterBook 只从 extensions 读取这些字段（顶层写了也会被忽略）
                                    extMap["position"] = JsonPrimitive(entry.position)
                                    extMap["depth"] = JsonPrimitive(entry.depth)
                                    extMap["selectiveLogic"] = JsonPrimitive(entry.selectiveLogic)
                                    extMap["role"] = JsonPrimitive(entry.role)
                                    extMap["group"] = JsonPrimitive(entry.group)
                                    extMap["sticky"] = JsonPrimitive(entry.sticky)
                                    extMap["cooldown"] = JsonPrimitive(entry.cooldown)
                                    extMap["delay"] = JsonPrimitive(entry.delay)
                                    extMap["match_whole_words"] = JsonPrimitive(entry.matchWholeWords)
                                    extMap["case_sensitive"] = JsonPrimitive(entry.caseSensitive)
                                    extMap["exclude_recursion"] = JsonPrimitive(entry.excludeRecursion)
                                    extMap["prevent_recursion"] = JsonPrimitive(entry.preventRecursion)
                                    extMap["delay_until_recursion"] = JsonPrimitive(entry.delayUntilRecursion)
                                    if (entry.scanDepth != null) extMap["scan_depth"] = JsonPrimitive(entry.scanDepth!!)
                                    else extMap.remove("scan_depth")
                                    extMap["group_weight"] = JsonPrimitive(entry.groupWeight)
                                    extMap["group_override"] = JsonPrimitive(entry.groupOverride)
                                    extMap["use_group_scoring"] = JsonPrimitive(entry.useGroupScoring)
                                    extMap["group_priority"] = JsonPrimitive(entry.groupPriority)
                                    extMap["match_persona_description"] = JsonPrimitive(entry.matchPersonaDescription)
                                    extMap["match_character_description"] = JsonPrimitive(entry.matchCharacterDescription)
                                    extMap["match_character_personality"] = JsonPrimitive(entry.matchCharacterPersonality)
                                    extMap["match_character_depth_prompt"] = JsonPrimitive(entry.matchCharacterDepthPrompt)
                                    extMap["match_scenario"] = JsonPrimitive(entry.matchScenario)
                                    extMap["match_creator_notes"] = JsonPrimitive(entry.matchCreatorNotes)
                                    extMap["ignore_budget"] = JsonPrimitive(entry.ignoreBudget)
                                    if (entry.inclusionGroup.isBlank()) extMap.remove("inclusion_group")
                                    else extMap["inclusion_group"] = JsonPrimitive(entry.inclusionGroup)
                                    if (entry.automationId.isBlank()) extMap.remove("automation_id")
                                    else extMap["automation_id"] = JsonPrimitive(entry.automationId)
                                    if (entry.displayIndex != 0) extMap["display_index"] = JsonPrimitive(entry.displayIndex)
                                    else extMap.remove("display_index")
                                    if (entry.displayPosition != 0) extMap["display_position"] = JsonPrimitive(entry.displayPosition)
                                    else extMap.remove("display_position")
                                    if (entry.triggers.isNotEmpty()) {
                                        extMap["triggers"] = JsonArray(entry.triggers.map { JsonPrimitive(it) })
                                    } else {
                                        extMap.remove("triggers")
                                    }
                                    // 官方 convertWorldInfoToCharacterBook：probability 与 useProbability 都写进 extensions
                                    extMap["probability"] = JsonPrimitive(entry.probability)
                                    extMap["useProbability"] = JsonPrimitive(entry.useProbability)
                                    put("extensions", JsonObject(extMap))
                                })
                            }
                        }
                    }
                }
            }
        }.toString()
    }

    /**
     * 在 PNG 字节流中注入 tEXt chunk
     * PNG 结构: [IHDR][...chunks...][tEXt][IEND]
     */
    private fun injectTextChunk(pngBytes: ByteArray, keyword: String, text: String): ByteArray {
        val output = ByteArrayOutputStream(pngBytes.size + keyword.length + text.length + 256)

        // 写入 PNG 签名
        output.write(pngBytes, 0, 8)

        // 找 IEND chunk 位置，在此之前插入 tEXt
        var pos = 8
        val iendPos = findIendChunk(pngBytes)
        val keywordBytes = keyword.toByteArray(Charsets.ISO_8859_1)
        val textBytes = text.toByteArray(Charsets.ISO_8859_1)

        // 复制 IEND 之前的所有 chunk
        while (pos < iendPos) {
            val chunkLen = readInt32BE(pngBytes, pos)
            output.write(pngBytes, pos, chunkLen + 12)
            pos += chunkLen + 12
        }

        // 写入 tEXt chunk
        val tEXtData = ByteArray(keywordBytes.size + 1 + textBytes.size)
        System.arraycopy(keywordBytes, 0, tEXtData, 0, keywordBytes.size)
        tEXtData[keywordBytes.size] = 0 // null separator
        System.arraycopy(textBytes, 0, tEXtData, keywordBytes.size + 1, textBytes.size)

        // Chunk length (4 bytes, big-endian)
        output.write(byteArrayOf(
            ((tEXtData.size shr 24) and 0xFF).toByte(),
            ((tEXtData.size shr 16) and 0xFF).toByte(),
            ((tEXtData.size shr 8) and 0xFF).toByte(),
            (tEXtData.size and 0xFF).toByte(),
        ))

        // Chunk type: "tEXt"
        output.write("tEXt".toByteArray())

        // Chunk data
        output.write(tEXtData)

        // CRC32 of type + data
        val crc = CRC32()
        crc.update("tEXt".toByteArray())
        crc.update(tEXtData)
        val crcVal = crc.value
        output.write(byteArrayOf(
            ((crcVal shr 24) and 0xFF).toByte(),
            ((crcVal shr 16) and 0xFF).toByte(),
            ((crcVal shr 8) and 0xFF).toByte(),
            (crcVal and 0xFF).toByte(),
        ))

        // 复制剩余的 IEND + 之后
        output.write(pngBytes, iendPos, pngBytes.size - iendPos)

        return output.toByteArray()
    }

    private fun findIendChunk(pngBytes: ByteArray): Int {
        var pos = 8
        while (pos + 12 <= pngBytes.size) {
            val len = readInt32BE(pngBytes, pos)
            val type = String(pngBytes, pos + 4, 4)
            if (type == "IEND") return pos
            pos += len + 12
        }
        return pngBytes.size - 12 // fallback
    }

    private fun readInt32BE(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
               ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
               ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
               (bytes[offset + 3].toInt() and 0xFF)
    }
}
