package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
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
            val fileName = getUriFileName(context, uri)?.removeSuffix(".json")
            // 首先尝试解析为自己的格式
            tryImportNative(json)
            // 然后尝试解析为 SillyTavern 世界书
                ?: tryImportSillyTavern(json, fileName)
                // 酒馆 Chat Completion 预设（main_prompt/jailbreak_prompt 等）→ 转成 constant 条目的世界书
                ?: tryImportSillyTavernPreset(json, fileName)
                // 正则脚本不在这里导入，给出明确指引而不是静默生成空世界书
                ?: throwSillyTavernRegexScriptError(json)
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
            // entries 有默认值，预设/正则脚本等其他 JSON 会"成功"解码成空世界书，
            // 必须判空让后续格式识别接管
            if (stLorebook.entries.isEmpty()) return null
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
                        // 官方 v2 世界书条目的顶层 vectorized 字段（向量检索激活）
                        vectorized = entry.vectorized ?: false,
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

    /**
     * 酒馆 Chat Completion 预设 → constant 条目的世界书。
     * 两种格式：
     * - 新版：prompts 数组 + prompt_order（character_id 100001 是当前生效顺序），
     *   自定义条目 injection_position=0 为相对深度注入 → AT_DEPTH 条目
     * - 旧版：main_prompt/nsfw_prompt/jailbreak_prompt 平铺字段
     * 采样参数（temperature 等）本地由模型设置管理，忽略。
     */
    private fun tryImportSillyTavernPreset(json: String, fileName: String?): Lorebook? {
        return runCatching {
            val entries = tryImportPresetOrdered(json) ?: tryImportPresetFlat(json)
            if (entries == null || entries.isEmpty()) return null
            Lorebook(
                id = Uuid.random(),
                name = fileName ?: LocalDateTime.now().toLocalString(),
                description = "SillyTavern 预设导入",
                enabled = true,
                entries = entries,
            )
        }.getOrNull()
    }

    /** 新版预设（prompts + prompt_order）解析 */
    private fun tryImportPresetOrdered(json: String): List<PromptInjection.RegexInjection>? {
        val obj = runCatching { ExportSerializer.DefaultJson.parseToJsonElement(json) }.getOrNull() as? JsonObject
        val promptsArray = obj?.get("prompts") as? kotlinx.serialization.json.JsonArray ?: return null
        if (promptsArray.isEmpty()) return null
        val preset = ExportSerializer.DefaultJson.decodeFromJsonElement(SillyTavernPresetOrdered.serializer(), obj)
        val promptsById = preset.prompts.associateBy { it.identifier }
        // 官方用 dummy character 100001 的顺序作为当前生效列表；缺失时取条目最多的
        val order = preset.promptOrder.firstOrNull { it.characterId == 100001L }?.order
            ?: preset.promptOrder.maxByOrNull { it.order.size }?.order
            ?: return null

        // chatHistory 标记位置：绝对定位条目在它之前/之后决定落点
        val chatHistoryIndex = order.indexOfFirst { promptsById[it.identifier]?.marker == true && it.identifier == "chatHistory" }

        return order.mapIndexedNotNull { index, entry ->
            val prompt = promptsById[entry.identifier] ?: return@mapIndexedNotNull null
            if (prompt.marker) return@mapIndexedNotNull null
            if (!entry.enabled) return@mapIndexedNotNull null
            if (prompt.content.isBlank()) return@mapIndexedNotNull null
            val role = when (prompt.role?.lowercase()) {
                "user", "1" -> me.rerere.ai.core.MessageRole.USER
                "assistant", "2" -> me.rerere.ai.core.MessageRole.ASSISTANT
                else -> me.rerere.ai.core.MessageRole.SYSTEM
            }
            val position = when {
                prompt.identifier == "main" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
                prompt.identifier == "nsfw" -> InjectionPosition.AFTER_SYSTEM_PROMPT
                prompt.identifier == "jailbreak" -> InjectionPosition.BOTTOM_OF_CHAT
                // 相对深度注入：injection_depth=0 表示聊天最末尾，与本地 AT_DEPTH 语义一致
                prompt.injectionPosition == 0 -> InjectionPosition.AT_DEPTH
                // 绝对定位：chatHistory 之前 → 角色卡区，之后 → 聊天末尾
                chatHistoryIndex < 0 || index < chatHistoryIndex -> InjectionPosition.AFTER_SYSTEM_PROMPT
                else -> InjectionPosition.BOTTOM_OF_CHAT
            }
            PromptInjection.RegexInjection(
                id = Uuid.random(),
                name = prompt.name.ifBlank { prompt.identifier },
                enabled = true,
                position = position,
                injectDepth = prompt.injectionDepth ?: 4,
                content = prompt.content,
                role = role,
                constantActive = true, // 预设条目无条件注入
            )
        }
    }

    /** 旧版预设（main_prompt 平铺字段）解析 */
    private fun tryImportPresetFlat(json: String): List<PromptInjection.RegexInjection>? {
        val preset = runCatching {
            ExportSerializer.DefaultJson.decodeFromString(SillyTavernPresetFlat.serializer(), json)
        }.getOrNull() ?: return null
        if (preset.mainPrompt.isBlank() && preset.nsfwPrompt.isBlank() && preset.jailbreakPrompt.isBlank()) return null
        fun presetEntry(
            name: String,
            content: String,
            position: InjectionPosition,
            role: me.rerere.ai.core.MessageRole,
            enabled: Boolean = true,
        ) = PromptInjection.RegexInjection(
            id = Uuid.random(),
            name = name,
            enabled = enabled,
            position = position,
            content = content,
            role = role,
            constantActive = true,
        )
        return buildList {
                preset.mainPrompt.takeIf { it.isNotBlank() }?.let {
                    add(presetEntry("Main Prompt", it, InjectionPosition.BEFORE_SYSTEM_PROMPT, me.rerere.ai.core.MessageRole.SYSTEM))
                }
                preset.nsfwPrompt.takeIf { it.isNotBlank() }?.let {
                    // nsfw_toggle=false 时导入但默认关闭，用户可手动开
                    add(
                        presetEntry(
                            "NSFW Prompt",
                            it,
                            if (preset.nsfwFirst) InjectionPosition.BEFORE_SYSTEM_PROMPT else InjectionPosition.AFTER_SYSTEM_PROMPT,
                            me.rerere.ai.core.MessageRole.SYSTEM,
                            enabled = preset.nsfwToggle,
                        )
                    )
                }
                preset.jailbreakPrompt.takeIf { it.isNotBlank() }?.let {
                    add(
                        presetEntry(
                            "Jailbreak",
                            it,
                            InjectionPosition.BOTTOM_OF_CHAT, // 官方 post-history：聊天末尾
                            if (preset.jailbreakSystem) me.rerere.ai.core.MessageRole.SYSTEM else me.rerere.ai.core.MessageRole.USER,
                        )
                    )
                }
        }
    }

    /** 酒馆正则脚本走助手详情-消息正则导入，这里明确报错而不是生成空世界书 */
    private fun throwSillyTavernRegexScriptError(json: String): Lorebook? {
        val obj = runCatching { ExportSerializer.DefaultJson.parseToJsonElement(json) }.getOrNull() as? JsonObject
        if (obj != null && obj.containsKey("scriptName") && obj.containsKey("findRegex")) {
            throw IllegalArgumentException("检测到酒馆正则脚本，请在 助手详情 → 消息正则 区域导入")
        }
        return null
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

/** 酒馆新版 Chat Completion 预设（prompts 数组 + prompt_order） */
@Serializable
private data class SillyTavernPresetOrdered(
    val prompts: List<StPresetPrompt> = emptyList(),
    @SerialName("prompt_order") val promptOrder: List<StPromptOrder> = emptyList(),
)

@Serializable
private data class StPresetPrompt(
    val identifier: String = "",
    val name: String = "",
    val content: String = "",
    val marker: Boolean = false,
    val role: String? = null,
    @SerialName("injection_position") val injectionPosition: Int? = null,
    @SerialName("injection_depth") val injectionDepth: Int? = null,
)

@Serializable
private data class StPromptOrder(
    @SerialName("character_id") val characterId: Long = 0,
    val order: List<StOrderEntry> = emptyList(),
)

@Serializable
private data class StOrderEntry(
    val identifier: String = "",
    val enabled: Boolean = false,
)

/** 酒馆旧版 Chat Completion 预设（main_prompt 平铺字段） */
@Serializable
private data class SillyTavernPresetFlat(
    @SerialName("main_prompt") val mainPrompt: String = "",
    @SerialName("nsfw_prompt") val nsfwPrompt: String = "",
    @SerialName("nsfw_toggle") val nsfwToggle: Boolean = false,
    @SerialName("nsfw_first") val nsfwFirst: Boolean = false,
    @SerialName("jailbreak_prompt") val jailbreakPrompt: String = "",
    @SerialName("jailbreak_system") val jailbreakSystem: Boolean = true,
)

/**
 * 酒馆正则脚本（Regex Script）解析：
 * 单对象或数组（酒馆"导出全部"为数组），映射为 AssistantRegex。
 * placement：1=用户输入 2=AI输出 3=Slash命令 4=世界信息 5=推理（3-5 本地不适用，忽略）；
 * markdownOnly=仅影响显示 → visualOnly；promptOnly=仅影响发送给 AI 的提示。
 */
object SillyTavernRegexImporter {
    fun parse(json: String): List<AssistantRegex> = runCatching {
        when (val element = kotlinx.serialization.json.Json.parseToJsonElement(json)) {
            is kotlinx.serialization.json.JsonArray -> element.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(element)
            else -> emptyList()
        }.mapNotNull(::parseRegex)
    }.getOrDefault(emptyList())

    private fun parseRegex(obj: JsonObject): AssistantRegex? {
        val name = obj["scriptName"]?.jsonPrimitive?.contentOrNull ?: return null
        val findRegex = obj["findRegex"]?.jsonPrimitive?.contentOrNull ?: return null
        if (findRegex.isBlank()) return null
        val placement = obj["placement"]?.let {
            (it as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { p -> (p as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull }
                .orEmpty()
        }.orEmpty()
        // 官方未勾选任何位置时不生效；导入时保守地映射为两者，用户可在 UI 里再收窄
        val scopes = buildSet {
            if (placement.isEmpty() || 1 in placement) add(AssistantAffectScope.USER)
            if (placement.isEmpty() || 2 in placement) add(AssistantAffectScope.ASSISTANT)
        }
        val markdownOnly = obj["markdownOnly"]?.jsonPrimitive?.booleanOrNull ?: false
        val promptOnly = obj["promptOnly"]?.jsonPrimitive?.booleanOrNull ?: false
        // 官方深度过滤：minDepth/maxDepth 为 null 时不限制（本地 0 表示不限制）
        val minDepth = obj["minDepth"]?.jsonPrimitive?.intOrNull ?: 0
        val maxDepth = obj["maxDepth"]?.jsonPrimitive?.intOrNull ?: 0
        return AssistantRegex(
            id = Uuid.random(),
            name = name,
            enabled = !(obj["disabled"]?.jsonPrimitive?.booleanOrNull ?: false),
            findRegex = findRegex,
            replaceString = obj["replaceString"]?.jsonPrimitive?.contentOrNull ?: "",
            affectingScope = scopes,
            visualOnly = markdownOnly && !promptOnly,
            minDepth = minDepth,
            maxDepth = maxDepth,
        )
    }
}

/**
 * 酒馆快速回复（Quick Replies）导入：
 * - v2 格式：{"version":2,"name":"...","qrList":[{"label":"...","message":"...",...}]}
 * - v1 格式：{"name":"...","quickReplies":[...]} 或直接 [{"label":"...","message":"..."}]
 * 占位符参数（{{arg:1}} 等）由宏引擎在发送时处理，导入不展开。
 */
object SillyTavernQuickRepliesImporter {
    fun parse(json: String): List<QuickMessage> = runCatching {
        val element = Json.parseToJsonElement(json)
        val qrArray: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["qrList"] ?: element["quickReplies"]) as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        qrArray.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (message.isBlank()) return@mapNotNull null
            QuickMessage(
                title = obj["label"]?.jsonPrimitive?.contentOrNull ?: "",
                content = message,
            )
        }
    }.getOrDefault(emptyList())
}

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
    val vectorized: Boolean? = null,
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
