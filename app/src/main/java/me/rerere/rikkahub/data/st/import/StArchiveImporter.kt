package me.rerere.rikkahub.data.st.import

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.export.ExportSerializer
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.st.expressions.SpriteRepository
import me.rerere.rikkahub.data.st.regex.RegexScript
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * SillyTavern 备份/数据目录 ZIP 条目分类。
 * 按路径段匹配（前缀或中缀），兼容任意顶层目录嵌套（如 data/default-user/、SillyTavern-x.x.x/）。
 */
internal enum class StArchiveEntryKind {
    CHARACTER, WORLD, PRESET, QUICK_REPLIES, SETTINGS, OTHER
}

internal fun classifyStArchiveEntry(path: String): StArchiveEntryKind {
    val p = path.replace('\\', '/')
    if (p.isBlank() || p.endsWith("/")) return StArchiveEntryKind.OTHER
    val name = p.substringAfterLast('/')
    if (name.startsWith("._") || p.contains("__MACOSX")) return StArchiveEntryKind.OTHER
    val lower = name.lowercase()
    fun inDir(dir: String) = p.contains("/$dir/") || p.startsWith("$dir/")
    return when {
        inDir("characters") && (lower.endsWith(".png") || lower.endsWith(".json") || lower.endsWith(".charx") || lower.endsWith(".byaf")) -> StArchiveEntryKind.CHARACTER
        inDir("worlds") && lower.endsWith(".json") -> StArchiveEntryKind.WORLD
        inDir("OpenAI Settings") && lower.endsWith(".json") -> StArchiveEntryKind.PRESET
        inDir("QuickReplies") && lower.endsWith(".json") -> StArchiveEntryKind.QUICK_REPLIES
        name.equals("settings.json", ignoreCase = true) -> StArchiveEntryKind.SETTINGS
        else -> StArchiveEntryKind.OTHER
    }
}

/**
 * 解析 ST settings.json 中的全局正则脚本（extension_settings.regex）。
 * 字段与 ST RegexScriptData 完全对齐，可直接反序列化；失败返回空列表。
 */
internal fun parseStSettingsRegex(json: String): List<RegexScript> {
    return runCatching {
        val root = ExportSerializer.DefaultJson.parseToJsonElement(json) as? JsonObject
            ?: return@runCatching emptyList()
        val ext = root["extension_settings"] as? JsonObject ?: return@runCatching emptyList()
        val arr = ext["regex"] as? JsonArray ?: return@runCatching emptyList()
        ExportSerializer.DefaultJson.decodeFromJsonElement(ListSerializer(RegexScript.serializer()), arr)
    }.getOrDefault(emptyList())
}

/**
 * 解析 ST QuickReplies 文件（{ version, qrList: [{label, value}] }）。
 * v1 取顶层 label/value；contextMenu 等嵌套结构忽略。
 */
internal fun parseStQuickReplies(json: String): List<QuickMessage> {
    return runCatching {
        val root = ExportSerializer.DefaultJson.parseToJsonElement(json) as? JsonObject
            ?: return@runCatching emptyList()
        val arr = root["qrList"] as? JsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val label = o["label"]?.jsonPrimitive?.contentOrNull
                ?: o["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val value = o["message"]?.jsonPrimitive?.contentOrNull
                ?: o["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (label.isBlank() && value.isBlank()) {
                null
            } else {
                // 移植缺口：宿主 QuickMessage 只有 title/content，ST 的执行触发/分组/自动化字段未落地
                QuickMessage(
                    title = label,
                    content = value,
                )
            }
        }
    }.getOrDefault(emptyList())
}

/**
 * 「从 SillyTavern 备份全量导入」：解析酒馆备份（或数据目录打包）ZIP，分发导入：
 * - characters 目录（PNG 内嵌元数据、.json 文件）→ 助理（含内嵌世界书、背景/头像文件）
 * - worlds 目录下的 .json → 世界书
 * - OpenAI Settings 目录下的 .json → 模式注入预设
 * - QuickReplies 目录下的 .json → 快速回复
 * - settings.json（extension_settings.regex）→ 正则脚本
 *
 * 其余条目计入 skipped；单条解析失败计入 errors 并继续处理后续条目。
 */
class StArchiveImporter(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager,
) {
    data class Summary(
        val characters: Int = 0,
        val worldbooks: Int = 0,
        val presets: Int = 0,
        val quickReplies: Int = 0,
        val regexScripts: Int = 0,
        val skipped: Int = 0,
        val errors: Int = 0,
    )

    suspend fun import(zipFile: File): Summary = withContext(Dispatchers.IO) {
        val newAssistants = mutableListOf<me.rerere.rikkahub.data.model.Assistant>()
        val newLorebooks = mutableListOf<Lorebook>()
        val newInjections = mutableListOf<PromptInjection.ModeInjection>()
        val newPresetBundles = mutableListOf<PresetImportBundle>()
        val newQuickMessages = mutableListOf<QuickMessage>()
        var settingsRegexCount = 0
        var worldCount = 0
        var presetCount = 0
        var skipped = 0
        var errors = 0

        val tmpDir = File(context.cacheDir, "st_import_tmp").apply { mkdirs() }
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zin ->
                var entry = zin.nextEntry
                var cardIndex = 0
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val kind = classifyStArchiveEntry(entry.name)
                        val baseName = entry.name.replace('\\', '/')
                            .substringAfterLast('/')
                            .substringBeforeLast('.')
                        try {
                            when (kind) {
                                StArchiveEntryKind.CHARACTER -> {
                                    val lowerEntryName = entry.name.lowercase()
                                    val ext = when {
                                        lowerEntryName.endsWith(".png") -> "png"
                                        lowerEntryName.endsWith(".charx") -> "charx"
                                        lowerEntryName.endsWith(".byaf") -> "byaf"
                                        else -> "json"
                                    }
                                    val tmp = File(tmpDir, "card_${cardIndex++}.$ext")
                                    tmp.outputStream().use { out -> zin.copyTo(out) }
                                    runCatching {
                                        val (assistant, books) =
                                            parseCardFromUri(context, Uri.fromFile(tmp))
                                                ?: error("Unsupported card format")
                                        // [v240 W3] 归档里的角色卡内嵌立绘同样落盘（best-effort）
                                        runCatching {
                                            SpriteRepository.syncFromCardAssets(
                                                context = context,
                                                assistantId = assistant.id,
                                                assets = assistant.tavernData?.assets.orEmpty(),
                                            )
                                        }
                                        newAssistants += assistant.copy(
                                            lorebookIds = assistant.lorebookIds + books.map { it.id }
                                        )
                                        newLorebooks += books
                                    }.onFailure {
                                        errors++
                                    }
                                    tmp.delete()
                                }

                                StArchiveEntryKind.WORLD -> {
                                    val text = zin.readBytes().toString(Charsets.UTF_8)
                                    val book = runCatching {
                                        LorebookSerializer.importFromJson(text, baseName)
                                    }.getOrNull()
                                    if (book != null) {
                                        newLorebooks += book
                                        worldCount++
                                    } else {
                                        errors++
                                    }
                                }

                                StArchiveEntryKind.PRESET -> {
                                    val text = zin.readBytes().toString(Charsets.UTF_8)
                                    val bundle = importPresetBundleFromString(text, baseName).getOrNull()
                                    if (bundle != null && bundle.injections.isNotEmpty()) {
                                        newPresetBundles += bundle
                                        newInjections += bundle.injections
                                        presetCount++
                                    } else {
                                        errors++
                                    }
                                }

                                StArchiveEntryKind.QUICK_REPLIES -> {
                                    val text = zin.readBytes().toString(Charsets.UTF_8)
                                    newQuickMessages += parseStQuickReplies(text)
                                }

                                StArchiveEntryKind.SETTINGS -> {
                                    val text = zin.readBytes().toString(Charsets.UTF_8)
                                    // [v236 C3] 全量设置导入：power_user / oai_settings /
                                    // extension_settings（含 regex / variables.global）/ world_info_settings。
                                    // 正则脚本由 StSettingsImporter 权威写入，不再单独二次合并。
                                    val settingsSummary =
                                        StSettingsImporter(context, settingsStore).import(text)
                                    settingsRegexCount += settingsSummary.regexScripts
                                }

                                StArchiveEntryKind.OTHER -> skipped++
                            }
                        } catch (e: Exception) {
                            errors++
                        }
                    }
                    entry = zin.nextEntry
                }
            }
        } finally {
            tmpDir.deleteRecursively()
        }

        // 一次性写入设置（快速回复按内容去重，避免重复导入产生副本）。
        // 正则脚本已由 StSettingsImporter 在 SETTINGS 分支写入（权威导入），此处不再二次合并。
        settingsStore.update { s ->
            var next = s.copy(
                assistants = s.assistants + newAssistants,
                lorebooks = s.lorebooks + newLorebooks,
                quickMessages = (s.quickMessages + newQuickMessages).distinctBy { it.title to it.content },
            )
            for (bundle in newPresetBundles) {
                val (afterBundle, addedRx) = applyPresetBundleToSettings(next, bundle)
                next = afterBundle
                settingsRegexCount += addedRx
            }
            next
        }

        Summary(
            characters = newAssistants.size,
            worldbooks = worldCount,
            presets = presetCount,
            quickReplies = newQuickMessages.size,
            regexScripts = settingsRegexCount,
            skipped = skipped,
            errors = errors,
        )
    }
}