package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.TavernCharacterData
import me.rerere.rikkahub.data.model.TavernEmbeddedBook
import me.rerere.rikkahub.data.model.TavernBookEntry
import me.rerere.rikkahub.data.model.TavernAsset
import me.rerere.rikkahub.data.model.SelectiveLogic
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 酒馆导入结果
 */
data class TavernImportResult(
    val assistant: Assistant,
    val newLorebooks: List<Lorebook> = emptyList(),  // 从内嵌世界书创建的新Lorebook
    val importedBookSettings: ImportedBookSettings = ImportedBookSettings(),
)

/** 角色卡内嵌世界书自带的激活设置（酒馆旧字段），导入时同步到全局设置 */
data class ImportedBookSettings(
    val recursiveScanning: Boolean? = null,
    val maxRecursionSteps: Int? = null,
    val minActivations: Int? = null,
    val tokenBudget: Int? = null,
)

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    onImport: (TavernImportResult) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(onImport = onImport)
    }
}

@Composable
private fun SillyTavernImporter(
    onImport: (TavernImportResult) -> Unit
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importFile(context, uri, onImport, filesManager, toaster, scope) { isLoading = it } }
    }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importFile(context, uri, onImport, filesManager, toaster, scope) { isLoading = it } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { pngPickerLauncher.launch(arrayOf("image/png")) },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(if (isLoading) stringResource(R.string.assistant_importer_importing)
                 else stringResource(R.string.assistant_importer_import_tavern_png))
        }
        OutlinedButton(
            onClick = { jsonPickerLauncher.launch(arrayOf("application/json")) },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(if (isLoading) stringResource(R.string.assistant_importer_importing)
                 else stringResource(R.string.assistant_importer_import_tavern_json))
        }
    }
}

private fun importFile(
    context: Context, uri: Uri,
    onImport: (TavernImportResult) -> Unit,
    filesManager: FilesManager, toaster: ToasterState,
    scope: kotlinx.coroutines.CoroutineScope,
    setLoading: (Boolean) -> Unit
) {
    setLoading(true)
    scope.launch {
        try {
            runCatching {
                importFromUri(context, uri, filesManager, onImport, toaster)
            }.onFailure { e ->
                e.printStackTrace()
                toaster.show(e.message ?: context.getString(R.string.assistant_importer_import_failed))
            }
        } finally { setLoading(false) }
    }
}

private suspend fun importFromUri(
    context: Context, uri: Uri, filesManager: FilesManager,
    onImport: (TavernImportResult) -> Unit, toaster: ToasterState
) {
    val mime = withContext(Dispatchers.IO) { filesManager.getFileMimeType(uri) }
    val (jsonString, backgroundStr, avatarUri) = withContext(Dispatchers.IO) {
        when (mime) {
            "image/png" -> {
                val result = ImageUtils.getTavernCharacterMeta(context, uri)
                result.map { base64Data ->
                    val json = String(Base64.decode(base64Data, Base64.DEFAULT))
                    // PNG本身既是背景源也是头像
                    val savedUris = filesManager.createChatFilesByContents(listOf(uri))
                    val bg = savedUris.first().toString()
                    val avatar = savedUris.first().toString()
                    Triple(json, bg, avatar)
                }.getOrElse { throw it }
            }
            "application/json" -> {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                    .use { it?.readText() }
                    ?: error(context.getString(R.string.assistant_importer_read_json_failed))
                Triple(json, null, null)
            }
            else -> error(context.getString(R.string.assistant_importer_unsupported_file_type, mime ?: "unknown"))
        }
    }
    val json = Json.parseToJsonElement(jsonString).jsonObject
    val spec = json["spec"]?.jsonPrimitive?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_spec_field))

    val (assistant, lorebooks) = when (spec) {
        "chara_card_v2" -> parseV2Card(context, json, backgroundStr, avatarUri)
        "chara_card_v3" -> parseV3Card(context, json, backgroundStr, avatarUri)
        else -> error(context.getString(R.string.assistant_importer_unsupported_spec, spec))
    }

    toaster.show(context.getString(R.string.app_name, assistant.name))
    onImport(
        TavernImportResult(
            assistant = assistant,
            newLorebooks = lorebooks,
            importedBookSettings = buildImportedBookSettings(assistant.tavernData?.embeddedBook),
        )
    )
}

/** 从内嵌世界书提取导入时要同步到全局设置的字段（只保留显式提供的值） */
private fun buildImportedBookSettings(book: TavernEmbeddedBook?): ImportedBookSettings {
    if (book == null) return ImportedBookSettings()
    return ImportedBookSettings(
        recursiveScanning = book.recursiveScanning,
        maxRecursionSteps = book.maxRecursionSteps?.let { if (it <= 0) 0 else it },
        minActivations = book.minActivations?.coerceAtLeast(0),
        tokenBudget = book.tokenBudget?.takeIf { it > 0 },
    )
}

// ==================== V2 Parser ====================

private fun parseV2Card(context: Context, json: JsonObject, background: String?, avatarUri: String?): Pair<Assistant, List<Lorebook>> {
    val data = json["data"]?.jsonObject ?: error(context.getString(R.string.assistant_importer_missing_data_field))
    val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_name_field))

    // 官方兼容：V2 顶层 talkativeness/fav 归入 data.extensions（官方导入同样处理），
    // 原始 extensions 结构保持无损
    val extensionsObj = data["extensions"]?.jsonObjectOrNull?.let { JsonObject(it.toMap()) }
        ?: JsonObject(emptyMap())
    val mergedExtensions = extensionsObj.toMutableMap()
    (json["talkativeness"]?.jsonPrimitiveOrNull)?.let { mergedExtensions.putIfAbsent("talkativeness", it) }
    (json["fav"]?.jsonPrimitiveOrNull)?.let { mergedExtensions.putIfAbsent("fav", it) }
    val mergedExtensionsRaw = if (mergedExtensions.isEmpty()) "" else JsonObject(mergedExtensions).toString()

    val tavData = TavernCharacterData(
        spec = "chara_card_v2",
        specVersion = json["spec_version"]?.jsonPrimitive?.contentOrNull ?: "",
        name = name,
        description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        alternateGreetings = parseStringArray(data["alternate_greetings"]),
        mesExample = data["mes_example"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        systemPrompt = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        creator = data["creator"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        creatorNotes = data["creator_notes"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        characterVersion = data["character_version"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        tags = parseStringArray(data["tags"]),
        postHistoryInstructions = data["post_history_instructions"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        extensions = parseExtensions(if (mergedExtensions.isEmpty()) null else JsonObject(mergedExtensions)),
        extensionsRaw = mergedExtensionsRaw,
        depthPrompt = parseDepthPromptText(data["extensions"]?.jsonObject),
        depthPromptDepth = parseDepthPromptDepth(data["extensions"]?.jsonObject),
        depthPromptRole = parseDepthPromptRole(data["extensions"]?.jsonObject),
        embeddedBook = parseEmbeddedBook(data["character_book"]?.jsonObject),
    )

    val systemPrompt = buildTavernSystemPrompt(tavData)
    val presetMessages = buildPresetMessages(tavData)
    val lorebooks = buildEmbeddedLorebooks(tavData)

    val assistant = Assistant(
        name = name,
        avatar = if (avatarUri != null) Avatar.Image(avatarUri) else Avatar.Dummy,
        systemPrompt = systemPrompt,
        presetMessages = presetMessages,
        background = background,
        tavernData = tavData,
    )

    return assistant to lorebooks
}

// ==================== V3 Parser ====================

private fun parseV3Card(context: Context, json: JsonObject, background: String?, avatarUri: String?): Pair<Assistant, List<Lorebook>> {
    val data = json["data"]?.jsonObject ?: error(context.getString(R.string.assistant_importer_missing_data_field))
    val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_name_field))

    val tavData = TavernCharacterData(
        spec = "chara_card_v3",
        specVersion = json["spec_version"]?.jsonPrimitive?.contentOrNull ?: "",
        name = name,
        description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        alternateGreetings = parseStringArray(data["alternate_greetings"]),
        mesExample = data["mes_example"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        systemPrompt = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        creator = data["creator"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        creatorNotes = data["creator_notes"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        characterVersion = data["character_version"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        tags = parseStringArray(data["tags"]),
        postHistoryInstructions = data["post_history_instructions"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        extensions = parseExtensions(data["extensions"]?.jsonObject),
        extensionsRaw = data["extensions"]?.toString() ?: "",
        assets = parseAssets(data["assets"]?.jsonArray),
        groupOnlyGreetings = parseStringArray(data["group_only_greetings"]),
        nickname = data["nickname"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        creatorNotesMultilingual = data["creator_notes_multilingual"]?.toString() ?: "",
        source = parseStringArray(data["source"]),
        creationDate = data["creation_date"]?.toString() ?: "",
        modificationDate = data["modification_date"]?.toString() ?: "",
        depthPrompt = parseDepthPromptText(data["extensions"]?.jsonObject),
        depthPromptDepth = parseDepthPromptDepth(data["extensions"]?.jsonObject),
        depthPromptRole = parseDepthPromptRole(data["extensions"]?.jsonObject),
        embeddedBook = parseEmbeddedBook(data["character_book"]?.jsonObject),
    )

    val systemPrompt = buildTavernSystemPrompt(tavData)
    val presetMessages = buildPresetMessages(tavData)
    val lorebooks = buildEmbeddedLorebooks(tavData)

    val assistant = Assistant(
        name = name,
        avatar = if (avatarUri != null) Avatar.Image(avatarUri) else Avatar.Dummy,
        systemPrompt = systemPrompt,
        presetMessages = presetMessages,
        background = background,
        tavernData = tavData,
    )

    return assistant to lorebooks
}

// ==================== Helpers ====================

private fun parseStringArray(element: kotlinx.serialization.json.JsonElement?): List<String> {
    if (element == null) return emptyList()
    return try {
        element.jsonArray.map { it.jsonPrimitive.contentOrNull ?: "" }.filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() }
}

private fun parseExtensions(obj: JsonObject?): Map<String, String> {
    if (obj == null) return emptyMap()
    return obj.entries.associate { (k, v) -> k to (v.jsonPrimitiveOrNull?.contentOrNull ?: v.toString()) }
}

/** 官方深度提示（extensions.depth_prompt）解析 */
private fun parseDepthPrompt(obj: JsonObject?): JsonObject? {
    if (obj == null) return null
    return try {
        (obj["depth_prompt"] as? JsonObject) ?: runCatching {
            (obj["depth_prompt"]?.jsonPrimitiveOrNull?.contentOrNull?.let {
                kotlinx.serialization.json.Json.parseToJsonElement(it)
            } as? JsonObject)
        }.getOrNull()
    } catch (_: Exception) { null }
}

private fun parseDepthPromptText(obj: JsonObject?): String =
    parseDepthPrompt(obj)?.get("prompt")?.jsonPrimitiveOrNull?.contentOrNull ?: ""

private fun parseDepthPromptDepth(obj: JsonObject?): Int =
    parseDepthPrompt(obj)?.get("depth")?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull() ?: 4

private fun parseDepthPromptRole(obj: JsonObject?): String =
    parseDepthPrompt(obj)?.get("role")?.jsonPrimitiveOrNull?.contentOrNull?.lowercase() ?: "system"

private fun parseAssets(arr: kotlinx.serialization.json.JsonArray?): List<TavernAsset> {
    if (arr == null) return emptyList()
    return arr.mapNotNull { el ->
        try {
            val obj = el.jsonObject
            TavernAsset(
                type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "",
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                uri = obj["uri"]?.jsonPrimitive?.contentOrNull ?: "",
                ext = obj["ext"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        } catch (_: Exception) { null }
    }
}

private fun parseEmbeddedBook(obj: JsonObject?): TavernEmbeddedBook? {
    if (obj == null) return null
    val entries = try {
        val entriesJson = obj["entries"]
        when {
            entriesJson == null -> emptyList()
            entriesJson is kotlinx.serialization.json.JsonArray -> parseEntriesArray(entriesJson)
            else -> parseEntriesMap(entriesJson.jsonObject)
        }
    } catch (_: Exception) { emptyList() }

    if (entries.isEmpty()) return null

    return TavernEmbeddedBook(
        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
        description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
        scanDepth = obj["scan_depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        tokenBudget = obj["token_budget"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        recursiveScanning = obj["recursive_scanning"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
        maxRecursionSteps = obj["max_recursion_steps"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        minActivations = obj["min_activations"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        extensions = parseExtensions(obj["extensions"]?.jsonObject),
        extensionsRaw = obj["extensions"]?.toString() ?: "",
        entries = entries,
    )
}

private fun parseEntriesArray(arr: kotlinx.serialization.json.JsonArray): List<TavernBookEntry> {
    return arr.mapNotNull { el ->
        try {
            val e = el.jsonObject
            applyEntryExtensions(TavernBookEntry(
                id = e["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                keys = parseStringArray(e["keys"]) + parseStringArray(e["key"]),
                secondaryKeys = parseStringArray(e["secondary_keys"]),
                comment = e["comment"]?.jsonPrimitive?.contentOrNull ?: "",
                content = e["content"]?.jsonPrimitive?.contentOrNull ?: "",
                constant = e["constant"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                selective = e["selective"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                selectiveLogic = e["selectiveLogic"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                group = e["group"]?.jsonPrimitive?.contentOrNull ?: "",
                position = parseEntryPosition(e),
                priority = e["order"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: e["priority"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100,
                disable = e["disable"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                caseSensitive = e["caseSensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                useRegex = e["useRegex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                probability = e["probability"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100,
                sticky = parseStickyInt(e["sticky"]),
                cooldown = e["cooldown"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                depth = e["depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 4,
                scanDepth = e["scan_depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1000,
                role = parseEntryRole(e),
                groupWeight = e["group_weight"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100,
                groupOverride = e["group_override"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                delay = e["delay"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                useProbability = e["useProbability"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: e["use_probability"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                extensionsRaw = e["extensions"]?.toString() ?: "",
            ), e)
        } catch (_: Exception) { null }
    }
}

private fun parseEntriesMap(obj: JsonObject): List<TavernBookEntry> {
    return obj.entries.mapNotNull { (idStr, el) ->
        try {
            val e = el.jsonObject
            applyEntryExtensions(TavernBookEntry(
                id = idStr.toIntOrNull() ?: 0,
                keys = parseStringArray(e["keys"]) + parseStringArray(e["key"]),
                secondaryKeys = parseStringArray(e["secondary_keys"]),
                comment = e["comment"]?.jsonPrimitive?.contentOrNull ?: "",
                content = e["content"]?.jsonPrimitive?.contentOrNull ?: "",
                constant = e["constant"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                selective = e["selective"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                selectiveLogic = e["selectiveLogic"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                group = e["group"]?.jsonPrimitive?.contentOrNull ?: "",
                position = parseEntryPosition(e),
                priority = e["order"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: e["priority"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100,
                disable = e["disable"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                caseSensitive = e["caseSensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                useRegex = e["useRegex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                probability = e["probability"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100,
                sticky = parseStickyInt(e["sticky"]),
                cooldown = e["cooldown"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                depth = e["depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 4,
                scanDepth = e["scan_depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1000,
                role = parseEntryRole(e),
                groupWeight = e["group_weight"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100,
                groupOverride = e["group_override"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                delay = e["delay"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                useProbability = e["useProbability"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: e["use_probability"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                extensionsRaw = e["extensions"]?.toString() ?: "",
            ), e)
        } catch (_: Exception) { null }
    }
}

/**
 * 应用酒馆条目 extensions 里的新字段（整词匹配/递归控制/概率/权重等）。
 * 旧版顶层字段优先保留，只有 extensions 显式提供时才覆盖。
 */
private fun applyEntryExtensions(entry: TavernBookEntry, e: JsonObject?): TavernBookEntry {
    if (e == null) return entry
    val extensions = e["extensions"] as? JsonObject
    if (extensions == null) return entry
    return try {
        entry.copy(
            matchWholeWords = extensions["match_whole_words"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: entry.matchWholeWords,
            preventRecursion = extBool(extensions["prevent_recursion"]) || entry.preventRecursion,
            delayUntilRecursion = extBool(extensions["delay_until_recursion"]) || entry.delayUntilRecursion,
            excludeRecursion = extensions["exclude_recursion"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: entry.excludeRecursion,
            caseSensitive = extensions["case_sensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: entry.caseSensitive,
            probability = extensions["probability"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.probability,
            useProbability = extensions["probability"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() != null
                || entry.useProbability,
            sticky = extensions["sticky"]?.let { parseStickyInt(it) }?.takeIf { it > 0 } ?: entry.sticky,
            cooldown = extensions["cooldown"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.cooldown,
            delay = extensions["delay"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.delay,
            scanDepth = extensions["scan_depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.scanDepth,
            priority = extensions["order"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.priority,
            position = extensions["position"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.position,
            groupWeight = extensions["group_weight"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.groupWeight,
            groupOverride = extensions["group_priority"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: entry.groupOverride,
            inclusionGroup = (extensions?.let { parseInclusionGroup(it["inclusion_group"]) } ?: "")
                .ifBlank { parseInclusionGroup(e["inclusion_group"]) },
            useGroupScoring = extensions?.get("use_group_scoring")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: e["use_group_scoring"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: entry.useGroupScoring,
            groupPriority = extensions?.get("group_priority")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: e["group_priority"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: entry.groupPriority,
            automationId = extensions?.get("automation_id")?.jsonPrimitive?.contentOrNull
                ?: e["automation_id"]?.jsonPrimitive?.contentOrNull ?: entry.automationId,
            displayIndex = extensions?.get("display_index")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: e["display_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.displayIndex,
            displayPosition = extensions?.get("display_position")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: e["display_position"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.displayPosition,
            triggers = (extensions?.let { parseStringArray(it["triggers"]) }.orEmpty() +
                parseStringArray(e["triggers"])).distinct(),
            matchPersonaDescription = extensions?.get("match_persona_description")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: e["match_persona_description"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: entry.matchPersonaDescription,
            matchCharacterDescription = extensions?.get("match_character_description")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: e["match_character_description"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: entry.matchCharacterDescription,
            matchCharacterPersonality = extensions?.get("match_character_personality")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: e["match_character_personality"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: entry.matchCharacterPersonality,
            matchCharacterDepthPrompt = extensions?.get("match_character_depth_prompt")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: e["match_character_depth_prompt"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: entry.matchCharacterDepthPrompt,
            matchScenario = extensions?.get("match_scenario")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: e["match_scenario"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: entry.matchScenario,
            matchCreatorNotes = extensions?.get("match_creator_notes")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: e["match_creator_notes"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: entry.matchCreatorNotes,
            ignoreBudget = extensions?.get("ignore_budget")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: e["ignore_budget"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: entry.ignoreBudget,
        )
    } catch (_: Exception) { entry }
}

/** inclusion_group 可为逗号分隔字符串或数组，统一转逗号分隔字符串 */
private fun parseInclusionGroup(element: JsonElement?): String = when {
    element == null -> ""
    element is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString(",")
    else -> element.jsonPrimitive?.contentOrNull ?: ""
}

/** extensions 里的递归控制可为布尔、数字（延迟层级）或 uid 数组，统一转布尔 */
private fun extBool(element: JsonElement?): Boolean = when {
    element == null -> false
    element is JsonArray -> element.isNotEmpty()
    else -> try {
        element.jsonPrimitive.contentOrNull?.let { str ->
            str.toBooleanStrictOrNull() ?: (str.toIntOrNull()?.let { it > 0 } ?: false)
        } ?: false
    } catch (_: Exception) { false }
}

/**
 * 解析 role 字段：酒馆 JSON 可能用 0/1/2 数字或 "system"/"user"/"assistant" 字符串
 */
private fun parseRoleString(element: kotlinx.serialization.json.JsonElement?): String {
    if (element == null) return "system"
    return try {
        element.jsonPrimitive.contentOrNull?.let { str ->
            when (str.lowercase()) {
                "system" -> "system"
                "user" -> "user"
                "assistant" -> "assistant"
                "0" -> "system"
                "1" -> "user"
                "2" -> "assistant"
                else -> "system"
            }
        } ?: when (element.jsonPrimitive.content.toIntOrNull()) {
            0 -> "system"
            1 -> "user"
            2 -> "assistant"
            else -> "system"
        }
    } catch (_: Exception) { "system" }
}

/**
 * 解析世界书条目 position（官方 V2 spec 字符串 + 新版 extensions.position 数字优先）：
 * 0=before_char 1=after_char 2=ANTop 3=ANBottom 4=atDepth 5=EMTop 6=EMBottom 7=outlet
 */
private fun parseEntryPosition(e: JsonObject): Int {
    val base = e["position"]?.jsonPrimitiveOrNull?.contentOrNull?.let { raw ->
        when (raw.trim().lowercase()) {
            "before_char", "before" -> 0
            "after_char", "after" -> 1
            else -> raw.trim().toIntOrNull()
        }
    } ?: 1
    // 官方新版 UI：扩展位置数字存 extensions.position，优先于旧字段
    return e["extensions"]?.jsonObjectOrNull
        ?.get("position")?.jsonPrimitiveOrNull?.contentOrNull?.trim()?.toIntOrNull()
        ?: base
}

/** 解析世界书条目 role（官方 extensions.role 数字/字符串优先） */
private fun parseEntryRole(e: JsonObject): String {
    val fromExtensions = e["extensions"]?.jsonObjectOrNull?.get("role")
    return parseRoleString(fromExtensions ?: e["role"])
}

/** 解析 sticky：兼容数字和布尔值（酒馆旧格式） */
private fun parseStickyInt(element: kotlinx.serialization.json.JsonElement?): Int {
    if (element == null) return 0
    return try {
        element.jsonPrimitive.contentOrNull?.let { str ->
            str.toIntOrNull() ?: if (str.toBooleanStrictOrNull() == true) 1 else 0
        } ?: 0
    } catch (_: Exception) { 0 }
}

/**
 * 将内嵌世界书条目转为Rikkahub的RegexInjection
 */
private fun tavernEntryToInjection(entry: TavernBookEntry): PromptInjection.RegexInjection {
    return PromptInjection.RegexInjection(
        id = Uuid.random(),
        name = entry.comment.ifEmpty { entry.keys.firstOrNull() ?: "Entry ${entry.id}" },
        enabled = !entry.disable,
        priority = entry.priority,
        position = mapTavernPosition(entry.position),
        injectDepth = entry.depth,
        content = entry.content,
        role = mapTavernRole(entry.role),
        keywords = entry.keys,
        secondaryKeys = entry.secondaryKeys,
        useRegex = entry.useRegex,
        caseSensitive = entry.caseSensitive,
        matchWholeWords = entry.matchWholeWords,
        excludeRecursion = entry.excludeRecursion,
        preventRecursion = entry.preventRecursion,
        delayUntilRecursion = entry.delayUntilRecursion,
        scanDepth = entry.scanDepth,
        constantActive = entry.constant,
        selective = entry.selective,
        selectiveLogic = mapSelectiveLogic(entry.selectiveLogic),
        group = entry.group,
        probability = entry.probability,
        sticky = entry.sticky,
        cooldown = entry.cooldown,
        delay = entry.delay,
        groupWeight = entry.groupWeight,
        groupOverride = entry.groupOverride,
        useProbability = entry.useProbability,
        inclusionGroup = entry.inclusionGroup,
        useGroupScoring = entry.useGroupScoring,
        groupPriority = entry.groupPriority,
        automationId = entry.automationId,
        displayIndex = entry.displayIndex,
        displayPosition = entry.displayPosition,
        triggers = entry.triggers,
        matchPersonaDescription = entry.matchPersonaDescription,
        matchCharacterDescription = entry.matchCharacterDescription,
        matchCharacterPersonality = entry.matchCharacterPersonality,
        matchCharacterDepthPrompt = entry.matchCharacterDepthPrompt,
        matchScenario = entry.matchScenario,
        matchCreatorNotes = entry.matchCreatorNotes,
        ignoreBudget = entry.ignoreBudget,
    )
}

internal fun mapTavernPosition(pos: Int): InjectionPosition = when (pos) {
    0 -> InjectionPosition.BEFORE_CHARACTER    // 官方 before_char：主提示之后、角色卡之前
    1 -> InjectionPosition.AFTER_CHARACTER     // 官方 after_char：角色卡之后
    2 -> InjectionPosition.AUTHOR_NOTE        // 跟随用户 AN 位置设置
    3 -> InjectionPosition.AUTHOR_NOTE        // 官方 ANBottom（作者备注下方），本地跟随 AN 位置
    4 -> InjectionPosition.AT_DEPTH
    5 -> InjectionPosition.EM_TOP             // 官方 EMTop：示例消息之前
    6 -> InjectionPosition.EM_BOTTOM          // 官方 EMBottom：示例消息之后
    else -> InjectionPosition.AFTER_CHARACTER // 官方 outlet 等暂不支持，落到角色卡后
}

internal fun mapTavernRole(role: String): me.rerere.ai.core.MessageRole = when (role.lowercase()) {
    "user" -> me.rerere.ai.core.MessageRole.USER
    "assistant" -> me.rerere.ai.core.MessageRole.ASSISTANT
    else -> me.rerere.ai.core.MessageRole.SYSTEM   // 官方世界书/深度提示默认 system
}

/**
 * 外置世界书 → 内嵌世界书同步：
 * 编辑外置世界书后，把绑定该世界书的角色卡内嵌世界书条目一并更新（最后修改生效）
 */
internal fun syncExternalToEmbedded(
    assistants: List<me.rerere.rikkahub.data.model.Assistant>,
    lorebooks: List<me.rerere.rikkahub.data.model.Lorebook>,
): List<me.rerere.rikkahub.data.model.Assistant> {
    return assistants.map { assistant ->
        val tav = assistant.tavernData ?: return@map assistant
        val book = tav.embeddedBook ?: return@map assistant
        val boundBook = lorebooks.firstOrNull { lb -> lb.id in assistant.lorebookIds }
            ?: return@map assistant
        val newEntries = boundBook.entries.mapIndexed { index, injection ->
            val template = book.entries.getOrNull(index)
                ?: book.entries.firstOrNull()
                ?: TavernBookEntry()
            injectionToTavernEntry(injection, template)
        }
        assistant.copy(tavernData = tav.copy(embeddedBook = book.copy(entries = newEntries)))
    }
}

/** 映射酒馆 selectiveLogic Int 到 SelectiveLogic 枚举 */
private fun mapSelectiveLogic(logic: Int): SelectiveLogic = when (logic) {
    0 -> SelectiveLogic.AND_ANY
    1 -> SelectiveLogic.OR_ANY
    2 -> SelectiveLogic.NOT_ANY
    3 -> SelectiveLogic.NOT_ALL
    else -> SelectiveLogic.AND_ANY
}

/** 反向转换：RegexInjection → TavernBookEntry（用于外置世界书→内嵌同步） */
internal fun injectionToTavernEntry(
    injection: PromptInjection.RegexInjection,
    template: TavernBookEntry,
): TavernBookEntry {
    return template.copy(
        keys = injection.keywords,
        secondaryKeys = injection.secondaryKeys,
        content = injection.content,
        comment = injection.name,
        constant = injection.constantActive,
        selective = injection.selective,
        selectiveLogic = when (injection.selectiveLogic) {
            SelectiveLogic.AND_ANY -> 0
            SelectiveLogic.AND_ALL -> 1
            SelectiveLogic.OR_ANY -> 2
            SelectiveLogic.NOT_ANY -> 3
            SelectiveLogic.NOT_ALL -> 4
        },
        group = injection.group,
        position = mapInjectionToPosition(injection.position),
        priority = injection.priority,
        disable = !injection.enabled,
        caseSensitive = injection.caseSensitive,
        matchWholeWords = injection.matchWholeWords,
        excludeRecursion = injection.excludeRecursion,
        preventRecursion = injection.preventRecursion,
        delayUntilRecursion = injection.delayUntilRecursion,
        useRegex = injection.useRegex,
        probability = injection.probability,
        sticky = injection.sticky,
        cooldown = injection.cooldown,
        delay = injection.delay,
        depth = injection.injectDepth,
        scanDepth = injection.scanDepth,
        role = when (injection.role) {
            me.rerere.ai.core.MessageRole.USER -> "user"
            me.rerere.ai.core.MessageRole.ASSISTANT -> "assistant"
            else -> "system"
        },
        groupWeight = injection.groupWeight,
        groupOverride = injection.groupOverride,
        useProbability = injection.useProbability,
        inclusionGroup = injection.inclusionGroup,
        useGroupScoring = injection.useGroupScoring,
        groupPriority = injection.groupPriority,
        automationId = injection.automationId,
        displayIndex = injection.displayIndex,
        displayPosition = injection.displayPosition,
        triggers = injection.triggers,
        matchPersonaDescription = injection.matchPersonaDescription,
        matchCharacterDescription = injection.matchCharacterDescription,
        matchCharacterPersonality = injection.matchCharacterPersonality,
        matchCharacterDepthPrompt = injection.matchCharacterDepthPrompt,
        matchScenario = injection.matchScenario,
        matchCreatorNotes = injection.matchCreatorNotes,
        ignoreBudget = injection.ignoreBudget,
    )
}

/** 反向映射 InjectionPosition → 酒馆 position 数字 */
private fun mapInjectionToPosition(pos: InjectionPosition): Int = when (pos) {
    InjectionPosition.BEFORE_CHARACTER -> 0
    InjectionPosition.AFTER_CHARACTER -> 1
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> 0
    InjectionPosition.AFTER_SYSTEM_PROMPT -> 1
    InjectionPosition.TOP_OF_CHAT -> 2
    InjectionPosition.BOTTOM_OF_CHAT -> 3
    InjectionPosition.AT_DEPTH -> 4
    InjectionPosition.AUTHOR_NOTE -> 2
    InjectionPosition.ANTAGONIZE -> 7
    InjectionPosition.AFTER_DIALOG -> 8
    InjectionPosition.EM_TOP -> 5
    InjectionPosition.EM_BOTTOM -> 6
}

/**
 * 从内嵌世界书 + PHI + creator_notes 构建 Lorebook 列表
 */
private fun buildEmbeddedLorebooks(tavData: TavernCharacterData): List<Lorebook> {
    val entries = mutableListOf<PromptInjection.RegexInjection>()

    // 内嵌世界书条目
    tavData.embeddedBook?.let { book ->
        entries.addAll(book.entries.map { tavernEntryToInjection(it) })
    }

    // PHI（post_history_instructions）→ 官方行为：聊天历史末尾之后追加（user 消息）
    if (tavData.postHistoryInstructions.isNotBlank()) {
        entries.add(PromptInjection.RegexInjection(
            id = Uuid.random(),
            name = "历史后续指令",
            enabled = true,
            priority = 0,
            position = InjectionPosition.AFTER_DIALOG,
            content = tavData.postHistoryInstructions,
            constantActive = true,
        ))
    }

    // 官方深度提示（extensions.depth_prompt）→ 按深度/角色注入对话（默认深度4、system）
    if (tavData.depthPrompt.isNotBlank()) {
        entries.add(PromptInjection.RegexInjection(
            id = Uuid.random(),
            name = "深度提示",
            enabled = true,
            priority = 0,
            position = InjectionPosition.AT_DEPTH,
            injectDepth = tavData.depthPromptDepth,
            content = tavData.depthPrompt,
            constantActive = true,
            role = mapTavernRole(tavData.depthPromptRole),
        ))
    }

    if (entries.isEmpty()) return emptyList()

    return listOf(
        Lorebook(
            id = Uuid.random(),
            name = tavData.embeddedBook?.name?.ifEmpty { "${tavData.name}的世界书" } ?: "${tavData.name}的世界书",
            description = tavData.embeddedBook?.description ?: "",
            enabled = true,
            entries = entries,
        )
    )
}

/**
 * 构建 system prompt — 只使用卡片原始的 system_prompt 字段，不拍平其他字段
 * 其他字段（description/personality/scenario/mes_example）由上下文模板展开
 * phi/creator_notes 由独立的注入系统处理
 */
private fun buildTavernSystemPrompt(d: TavernCharacterData): String {
    return d.systemPrompt.ifBlank { "" }
}

/**
 * 构建 presetMessages — 只使用 first_mes，alternate_greetings 由角色卡 UI 选择
 * 用户在角色卡页面点「使用此开场白」时替换 presetMessages
 */
private fun buildPresetMessages(d: TavernCharacterData): List<UIMessage> {
    val messages = mutableListOf<UIMessage>()
    if (d.firstMessage.isNotBlank()) {
        messages.add(UIMessage.assistant(d.firstMessage))
    }
    return messages
}
