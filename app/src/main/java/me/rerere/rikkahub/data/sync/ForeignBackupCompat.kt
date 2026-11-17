package me.rerere.rikkahub.data.sync

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRProviderSetting
import me.rerere.tts.provider.TTSProviderSetting

/**
 * 导入别的发行版导出的备份（例如上游 rikkahub）时，把本地读不懂的 JSON 片段丢掉。
 *
 * 上游和本地的密封类子类集合长期会分叉：上游新加的 [UIMessagePart.Notice]、TTS/ASR 的
 * deepseek / volcengine 提供商，本地根本没有对应类型。这些片段不会让数据库结构出错，
 * 但会让整份 settings.json 或整条消息的 `decodeFromString` 抛 SerializationException，
 * 于是「恢复失败」或者「某个会话打不开」。
 *
 * 这里不做硬编码名单，而是拿本地序列化器逐个试探解码：读得懂就留，读不懂就丢。
 * 上游以后再加新类型也自动兼容，而本地不要的数据（比如上游独有的提供商）本来也用不上。
 */
internal object ForeignBackupCompat {
    private const val TAG = "ForeignBackupCompat"

    /** settings.json 里随发行版增长的元素列表，以及「这个元素本地读得懂吗」的判定。 */
    private val SETTINGS_LISTS = listOf<Pair<String, (Json, JsonElement) -> Boolean>>(
        "ttsProviders" to { json, element ->
            runCatching { json.decodeFromJsonElement(TTSProviderSetting.serializer(), element) }.isSuccess
        },
        "asrProviders" to { json, element ->
            runCatching { json.decodeFromJsonElement(ASRProviderSetting.serializer(), element) }.isSuccess
        },
    )

    /**
     * 过滤 settings.json 里本地读不懂的提供商，顺带清理 assistants 里预设消息的部件。
     *
     * 没有需要清理的内容时返回原始字符串，便于调用方逐字比对。
     */
    fun sanitizeSettingsJson(encoded: String, json: Json): String {
        return runCatching {
            val root = json.parseToJsonElement(encoded).jsonObject.toMutableMap()
            var changed = false

            for ((field, readable) in SETTINGS_LISTS) {
                val value = root[field] as? JsonArray ?: continue
                val kept = value.filter { readable(json, it) }
                if (kept.size == value.size) continue
                Log.w(TAG, "settings.$field 丢掉 ${value.size - kept.size} 个本地读不懂的条目")
                root[field] = JsonArray(kept)
                changed = true
            }

            val assistants = root["assistants"] as? JsonArray
            if (assistants != null) {
                val sanitized = JsonArray(
                    assistants.map { assistant ->
                        val obj = assistant as? JsonObject ?: return@map assistant
                        val preset = obj["presetMessages"] as? JsonArray ?: return@map assistant
                        val next = sanitizeMessages(json, preset)
                        if (next == preset) assistant else JsonObject(obj + ("presetMessages" to next))
                    }
                )
                if (sanitized != assistants) {
                    root["assistants"] = sanitized
                    changed = true
                }
            }

            if (changed) json.encodeToString(JsonObject.serializer(), JsonObject(root)) else encoded
        }.onFailure {
            Log.e(TAG, "清理 settings.json 失败，保持原样", it)
        }.getOrDefault(encoded)
    }

    /**
     * 过滤消息数组里本地读不懂的部件与注解；没有变化时返回 null。
     *
     * 上游写进消息里的 Notice 之类部件本地没有对应类型，留着会让整条消息（乃至整个会话）
     * 反序列化失败，所以按部件粒度丢。
     */
    fun sanitizeMessagesJson(encoded: String, json: Json): String? {
        return runCatching {
            val messages = json.parseToJsonElement(encoded).jsonArray
            val sanitized = sanitizeMessages(json, messages)
            if (sanitized == messages) null else json.encodeToString(JsonArray.serializer(), JsonArray(sanitized))
        }.onFailure {
            Log.e(TAG, "清理消息部件失败，保持原样", it)
        }.getOrNull()
    }

    /**
     * 读消息时用的宽松解码：先按原样解，解不动就清理部件再解，最后逐个消息丢弃。
     *
     * 恢复阶段已经清理过一遍结构，这里是兜底：任何来历不明的消息都不该让整个会话打不开。
     */
    fun decodeMessages(encoded: String, json: Json): List<UIMessage> {
        runCatching { json.decodeFromString<List<UIMessage>>(encoded) }.getOrNull()?.let { return it }
        val sanitized = sanitizeMessagesJson(encoded, json) ?: encoded
        runCatching { json.decodeFromString<List<UIMessage>>(sanitized) }.getOrNull()?.let { return it }
        val array = runCatching { json.parseToJsonElement(sanitized).jsonArray }.getOrNull() ?: return emptyList()
        Log.w(TAG, "有 ${array.size} 条消息本地读不懂，逐条丢弃后继续")
        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(UIMessage.serializer(), element) }.getOrNull()
        }
    }

    private fun sanitizeMessages(json: Json, messages: JsonArray): JsonArray {
        val sanitized = messages.map { sanitizeMessage(json, it) }
        return if (sanitized == messages.toList()) messages else JsonArray(sanitized)
    }

    private fun sanitizeMessage(json: Json, element: JsonElement): JsonElement {
        val message = element as? JsonObject ?: return element
        val parts = message["parts"] as? JsonArray
        val annotations = message["annotations"] as? JsonArray
        val nextParts = parts?.let { sanitizeParts(json, it) }
        val nextAnnotations = annotations?.let { sanitizeAnnotations(json, it) }
        if (nextParts == parts && nextAnnotations == annotations) return element
        return JsonObject(
            buildMap {
                putAll(message)
                nextParts?.let { put("parts", it) }
                nextAnnotations?.let { put("annotations", it) }
            }
        )
    }

    /** 工具部件的 output 也是一层部件列表，先递归清理它，再判断整个部件读不读得懂。 */
    private fun sanitizeParts(json: Json, parts: JsonArray): JsonArray {
        val rewritten = parts.map { element ->
            val part = element as? JsonObject ?: return@map element
            val output = part["output"] as? JsonArray ?: return@map element
            val next = sanitizeParts(json, output)
            if (next == output) element else JsonObject(part + ("output" to next))
        }
        val kept = rewritten.filter { element ->
            runCatching { json.decodeFromJsonElement(UIMessagePart.serializer(), element) }.isSuccess
        }
        return if (kept == parts.toList()) parts else JsonArray(kept)
    }

    private fun sanitizeAnnotations(json: Json, annotations: JsonArray): JsonArray {
        val kept = annotations.filter { element ->
            runCatching { json.decodeFromJsonElement(UIMessageAnnotation.serializer(), element) }.isSuccess
        }
        return if (kept == annotations.toList()) annotations else JsonArray(kept)
    }
}
