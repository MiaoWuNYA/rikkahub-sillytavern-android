package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.ui.pages.assistant.detail.mapSelectiveLogic
import me.rerere.rikkahub.ui.pages.assistant.detail.parseDelayUntilRecursionInt
import me.rerere.rikkahub.ui.pages.assistant.detail.mapTavernRole
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDateTime
import kotlin.uuid.Uuid

@Serializable
data class ExportData(
    val version: Int = 1,
    val type: String,
    val data: JsonElement
)

interface ExportSerializer<T> {
    val type: String

    fun export(data: T): ExportData
    fun import(context: Context, uri: Uri): Result<T>

    // 获取导出文件名
    fun getExportFileName(data: T): String = "${type}.json"

    // 便捷方法：直接导出为 JSON 字符串
    fun exportToJson(data: T, json: Json = DefaultJson): String {
        return json.encodeToString(ExportData.serializer(), export(data))
    }

    // 读取 URI 内容的便捷方法
    fun readUri(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Failed to read file")
    }

    fun getUriFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex) else null
            } else null
        }
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}

object ModeInjectionSerializer : ExportSerializer<PromptInjection.ModeInjection> {
    override val type = "mode_injection"

    override fun getExportFileName(data: PromptInjection.ModeInjection): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: PromptInjection.ModeInjection): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<PromptInjection.ModeInjection> {
        return runCatching {
            val json = readUri(context, uri)
            // 首先尝试解析为自己的格式
            tryImportNative(json)
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): PromptInjection.ModeInjection? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<PromptInjection.ModeInjection>(exportData.data)
                .copy(id = Uuid.random())
        }.getOrNull()
    }
}

object LorebookSerializer : ExportSerializer<Lorebook> {
    override val type = "lorebook"

    override fun getExportFileName(data: Lorebook): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: Lorebook): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<Lorebook> {
        return runCatching {
            val json = readUri(context, uri)
            // 首先尝试解析为自己的格式
            tryImportNative(json)
            // 然后尝试解析为 SillyTavern 格式
                ?: tryImportSillyTavern(json, getUriFileName(context, uri)?.removeSuffix(".json"))
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): Lorebook? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<Lorebook>(exportData.data)
                .copy(
                    id = Uuid.random(),
                    entries = ExportSerializer.DefaultJson
                        .decodeFromJsonElement<Lorebook>(exportData.data)
                        .entries.map { it.copy(id = Uuid.random()) }
                )
        }.getOrNull()
    }

    private fun tryImportSillyTavern(json: String, fileName: String?): Lorebook? {
        return runCatching {
            val stLorebook = ExportSerializer.DefaultJson.decodeFromString(
                SillyTavernLorebook.serializer(),
                json
            )
            Lorebook(
                id = Uuid.random(),
                name = fileName ?: LocalDateTime.now().toLocalString(),
                description = "",
                enabled = true,
                entries = stLorebook.entries.values.map { entry ->
                    PromptInjection.RegexInjection(
                        id = Uuid.random(),
                        name = entry.comment.orEmpty().ifEmpty { entry.key.firstOrNull().orEmpty() },
                        enabled = !entry.disable,
                        priority = entry.order,
                        position = mapSillyTavernPosition(entry.position),
                        injectDepth = entry.depth,
                        content = entry.content,
                        keywords = entry.key,
                        secondaryKeys = entry.keysecondary,
                        useRegex = false, // 官方键始终支持 /regex/ 语法，keyMatches 会自动识别
                        caseSensitive = entry.caseSensitive ?: false,
                        matchWholeWords = entry.matchWholeWords ?: extBool(entry.extensions, "match_whole_words"),
                        excludeRecursion = entry.excludeRecursion ?: extBool(entry.extensions, "exclude_recursion"),
                        preventRecursion = entry.preventRecursion ?: extBool(entry.extensions, "prevent_recursion"),
                        delayUntilRecursion = parseDelayUntilRecursionInt(entry.delayUntilRecursion)
                            ?: parseDelayUntilRecursionInt(
                                entry.extensions?.jsonObject?.get("delay_until_recursion")
                            ) ?: 0,
                        scanDepth = entry.scanDepth,
                        constantActive = entry.constant,
                        selective = entry.selective,
                        selectiveLogic = mapSelectiveLogic(entry.selectiveLogic),
                        probability = entry.probability ?: 100,
                        useProbability = entry.useProbability ?: true,
                        group = entry.group.orEmpty(),
                        groupWeight = entry.groupWeight ?: 100,
                        groupOverride = entry.groupOverride ?: false,
                        role = mapTavernRole((entry.role as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "0"),
                        sticky = entry.sticky ?: 0,
                        cooldown = entry.cooldown ?: 0,
                        delay = entry.delay ?: 0,
                        automationId = extString(entry.extensions, "automation_id"),
                        displayIndex = extInt(entry.extensions, "display_index"),
                        displayPosition = extInt(entry.extensions, "display_position"),
                        useGroupScoring = extBool(entry.extensions, "use_group_scoring"),
                        ignoreBudget = extBool(entry.extensions, "ignore_budget"),
                        triggers = extStringArray(entry.extensions, "triggers"),
                        matchPersonaDescription = extBool(entry.extensions, "match_persona_description"),
                        matchCharacterDescription = extBool(entry.extensions, "match_character_description"),
                        matchCharacterPersonality = extBool(entry.extensions, "match_character_personality"),
                        matchCharacterDepthPrompt = extBool(entry.extensions, "match_character_depth_prompt"),
                        matchScenario = extBool(entry.extensions, "match_scenario"),
                        matchCreatorNotes = extBool(entry.extensions, "match_creator_notes"),
                    )
                }
            )
        }.getOrNull()
    }

    /** 官方 world_info_position：0=before 1=after 2=ANTop 3=ANBottom 4=atDepth 5=EMTop 6=EMBottom 7=outlet */
    private fun mapSillyTavernPosition(position: Int): InjectionPosition {
        return when (position) {
            0 -> InjectionPosition.BEFORE_CHARACTER
            1 -> InjectionPosition.AFTER_CHARACTER
            2 -> InjectionPosition.AUTHOR_NOTE   // ANTop
            3 -> InjectionPosition.AUTHOR_NOTE   // ANBottom
            4 -> InjectionPosition.AT_DEPTH
            5 -> InjectionPosition.EM_TOP
            6 -> InjectionPosition.EM_BOTTOM
            else -> InjectionPosition.AFTER_CHARACTER // outlet 暂不支持，落回角色卡后
        }
    }

    private fun extBool(extensions: JsonElement?, key: String): Boolean =
        extensions?.jsonObject?.get(key)?.jsonPrimitive?.booleanOrNull ?: false

    private fun extInt(extensions: JsonElement?, key: String): Int =
        extensions?.jsonObject?.get(key)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

    private fun extString(extensions: JsonElement?, key: String): String =
        extensions?.jsonObject?.get(key)?.jsonPrimitive?.contentOrNull ?: ""

    private fun extStringArray(extensions: JsonElement?, key: String): List<String> {
        val element = extensions?.jsonObject?.get(key) ?: return emptyList()
        return if (element is kotlinx.serialization.json.JsonArray) {
            element.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        } else {
            (element as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        }
    }
}

@Serializable
private data class SillyTavernLorebook(
    val entries: Map<String, SillyTavernEntry> = emptyMap(),
)

@Serializable
private data class SillyTavernEntry(
    val key: List<String> = emptyList(),
    val keysecondary: List<String> = emptyList(),
    val content: String = "",
    val comment: String? = null,
    val constant: Boolean = false,
    val position: Int = 0,
    val order: Int = 100,
    val disable: Boolean = false,
    val depth: Int = 4,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
    val selective: Boolean = false,
    val selectiveLogic: Int = 0,
    val probability: Int? = 100,
    val useProbability: Boolean? = true,
    val group: String? = null,
    val groupWeight: Int? = 100,
    val groupOverride: Boolean? = null,
    val role: JsonElement? = null,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val delay: Int? = null,
    val excludeRecursion: Boolean? = null,
    val preventRecursion: Boolean? = null,
    val delayUntilRecursion: JsonElement? = null,
    val matchWholeWords: Boolean? = null,
    val extensions: JsonElement? = null,
)
