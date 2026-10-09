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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.Link2
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Lucide
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import android.util.Log
import kotlinx.coroutines.CoroutineScope
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
import me.rerere.rikkahub.BuildConfig
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
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.export.SillyTavernRegexImporter
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.common.http.await
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.compose.koinInject
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * 酒馆导入结果
 */
data class TavernImportResult(
    val assistant: Assistant,
    val newLorebooks: List<Lorebook> = emptyList(),  // 保留字段：当前导入不再产生外置书（内嵌书随卡存储）
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
    // 复用应用内已配置的 OkHttpClient（含用户设置的代理），仅收紧总超时
    val httpClient: OkHttpClient = koinInject()
    val downloadClient = remember(httpClient) {
        httpClient.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
    }
    // 多个开场白时，合并为同一对话的多条消息（部分卡片把开场白拆成连续多条）
    var mergeGreetings by remember { mutableStateOf(false) }
    // URL 导入对话框
    var showUrlDialog by remember { mutableStateOf(false) }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importFile(context, uri, onImport, filesManager, toaster, scope, mergeGreetings) { isLoading = it } }
    }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importFile(context, uri, onImport, filesManager, toaster, scope, mergeGreetings) { isLoading = it } }
    }

    // 春水 AI 的对话导出走同一套 importFile——格式判定放在
    // importFromJson 里（靠 looksLikeConversationExport 自动识别），
    // 这样 URL 导入和拖文件两条路也能吃到它，不用各写一遍。
    val chunshuiPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importFile(context, uri, onImport, filesManager, toaster, scope, mergeGreetings) { isLoading = it } }
    }

    val fengyuePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importFile(context, uri, onImport, filesManager, toaster, scope, mergeGreetings) { isLoading = it } }
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
        // 春水 AI 的导出是「一段对话」而不是角色卡，字段结构完全不同，
        // 混在「导入酒馆角色卡 (JSON)」里会走到 V1 分支去读顶层 name 而报错。
        // 单独给一个入口，让报错信息能对症。
        //
        // 不给额外的说明文字：这一行按钮旁边挂一段介绍会显得啰嗦，
        // 而「导入角色卡」这个动作本身用户已经会了，不需要教。
        OutlinedButton(
            onClick = { chunshuiPickerLauncher.launch(arrayOf("application/json")) },
            enabled = !isLoading
        ) {
            Icon(
                imageVector = Lucide.MessageSquare,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(if (isLoading) stringResource(R.string.assistant_importer_importing)
                 else stringResource(R.string.assistant_importer_import_chunshui))
        }
        OutlinedButton(
            onClick = { fengyuePickerLauncher.launch(arrayOf("application/json")) },
            enabled = !isLoading
        ) {
            Icon(
                imageVector = Lucide.Sparkles,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(if (isLoading) stringResource(R.string.assistant_importer_importing)
                 else stringResource(R.string.assistant_importer_import_fengyue))
        }
        OutlinedButton(
            onClick = { showUrlDialog = true },
            enabled = !isLoading
        ) {
            Icon(
                imageVector = Lucide.Link2,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(stringResource(R.string.assistant_importer_import_from_url))
        }
        if (showUrlDialog) {
            UrlImportDialog(
                isLoading = isLoading,
                onDismiss = { showUrlDialog = false },
                onConfirm = { url ->
                    runImport(scope, { isLoading = it }, { e ->
                        e.printStackTrace()
                        toaster.show(e.message ?: context.getString(R.string.assistant_importer_download_failed, ""))
                    }) {
                        importFromUrl(context, url, downloadClient, filesManager, onImport, toaster, mergeGreetings)
                    }
                },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Checkbox(
                checked = mergeGreetings,
                onCheckedChange = { mergeGreetings = it },
            )
            Text(
                text = stringResource(R.string.assistant_importer_merge_greetings),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** URL 导入对话框：输入链接 → 确定后下载（进度条由按钮上的 loading 态体现） */
@Composable
private fun UrlImportDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var urlInput by remember { mutableStateOf("") }

    fun submit() {
        val value = urlInput.trim()
        if (value.isNotEmpty() && !isLoading) {
            onConfirm(value)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_importer_import_from_url)) },
        text = {
            Column {
                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.assistant_importer_downloading))
                    }
                }
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    enabled = !isLoading,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_importer_url_hint)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !isLoading && urlInput.isNotBlank()) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

/** 统一的导入执行包装：文件选择器导入与 URL 导入共用，负责 loading 状态与错误提示 */
private fun runImport(
    scope: CoroutineScope,
    setLoading: (Boolean) -> Unit,
    onError: (Throwable) -> Unit,
    block: suspend () -> Unit,
) {
    setLoading(true)
    scope.launch {
        try {
            runCatching { block() }.onFailure { e -> onError(e) }
        } finally {
            setLoading(false)
        }
    }
}

private fun importFile(
    context: Context, uri: Uri,
    onImport: (TavernImportResult) -> Unit,
    filesManager: FilesManager, toaster: ToasterState,
    scope: CoroutineScope,
    mergeGreetings: Boolean = false,
    setLoading: (Boolean) -> Unit
) {
    runImport(scope, setLoading, { e ->
        e.printStackTrace()
        toaster.show(e.message ?: context.getString(R.string.assistant_importer_import_failed))
    }) {
        importFromUri(context, uri, filesManager, onImport, toaster, mergeGreetings)
    }
}

private suspend fun importFromUri(
    context: Context, uri: Uri, filesManager: FilesManager,
    onImport: (TavernImportResult) -> Unit, toaster: ToasterState,
    mergeGreetings: Boolean = false,
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
    importFromString(context, jsonString, backgroundStr, avatarUri, onImport, toaster, mergeGreetings)
}

/** 角色卡下载体积上限，防止误贴大文件链接耗尽内存 */
private const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024

private fun isPngBytes(bytes: ByteArray): Boolean =
    bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()

/** 仅接受 http/https 链接；其余协议（file://、content:// 等）一律拒绝 */
private fun parseCardUrl(context: Context, raw: String): HttpUrl {
    val url = raw.trim().toHttpUrlOrNull()
        ?: error(context.getString(R.string.assistant_importer_invalid_url))
    if (url.scheme != "http" && url.scheme != "https") {
        error(context.getString(R.string.assistant_importer_invalid_url))
    }
    return url
}

/**
 * 从 URL 下载角色卡并导入，PNG 与 JSON 均可
 *
 * 类型判定按权威性排序：Content-Type → URL 后缀 → PNG 魔数兜底
 * （很多图床/CDN 直链返回 application/octet-stream 或 text/plain）
 */
private suspend fun importFromUrl(
    context: Context,
    rawUrl: String,
    client: OkHttpClient,
    filesManager: FilesManager,
    onImport: (TavernImportResult) -> Unit,
    toaster: ToasterState,
    mergeGreetings: Boolean = false,
) {
    val url = parseCardUrl(context, rawUrl)
    val (bytes, isPng) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("User-Agent", "RikkaHub/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                error(context.getString(R.string.assistant_importer_download_failed, "${response.code}"))
            }
            val declaredLength = response.body?.contentLength() ?: -1L
            if (declaredLength > MAX_DOWNLOAD_BYTES) {
                error(context.getString(R.string.assistant_importer_file_too_large, MAX_DOWNLOAD_BYTES / 1024 / 1024))
            }
            val data = response.body?.bytes()
                ?: error(context.getString(R.string.assistant_importer_download_failed, "empty body"))
            if (data.size > MAX_DOWNLOAD_BYTES) {
                error(context.getString(R.string.assistant_importer_file_too_large, MAX_DOWNLOAD_BYTES / 1024 / 1024))
            }

            val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            val path = url.encodedPath.lowercase()
            // HTML 错误页/过期链接嗅探：明显是网页就直接报错，不给解析器猜
            if (!isPngBytes(data) &&
                (contentType?.contains("text/html") == true ||
                    String(data.copyOfRange(0, minOf(256, data.size)), Charsets.UTF_8)
                        .trimStart().lowercase().startsWith("<!doctype html"))
            ) {
                error(context.getString(R.string.assistant_importer_not_character_card))
            }
            // 魔数最可靠，其次是明确的 Content-Type / 后缀；都没有时按 JSON 尝试
            val png = isPngBytes(data) ||
                contentType == "image/png" ||
                (contentType == null && path.endsWith(".png"))
            if (!png && contentType != null &&
                contentType !in setOf(
                    "application/json", "text/json", "text/plain",
                    "application/octet-stream", "binary/octet-stream",
                ) && !path.endsWith(".json")
            ) {
                error(context.getString(R.string.assistant_importer_not_character_card))
            }
            data to png
        }
    }

    if (isPng) {
        val jsonString = runCatching {
            val base64Data = ImageUtils.getTavernCharacterMetaFromBytes(bytes).getOrThrow()
            String(Base64.decode(base64Data, Base64.DEFAULT))
        }.getOrElse { e ->
            Log.w(TAG, "PNG 角色卡解析失败", e)
            error(context.getString(R.string.assistant_importer_not_character_card))
        }
        // 下载的 PNG 本身既是背景源也是头像
        val savedUris = withContext(Dispatchers.IO) { filesManager.createChatFilesByByteArrays(listOf(bytes)) }
        val saved = savedUris.firstOrNull()?.toString()
        importFromString(context, jsonString, saved, saved, onImport, toaster, mergeGreetings)
    } else {
        val jsonString = String(bytes, Charsets.UTF_8)
        try {
            importFromString(context, jsonString, null, null, onImport, toaster, mergeGreetings)
        } catch (e: Exception) {
            // 网页错误页 / 非角色卡 JSON 在这里暴露，给出可读提示而非解析器内部异常
            Log.w(TAG, "URL 内容不是有效的角色卡", e)
            error(context.getString(R.string.assistant_importer_not_character_card))
        }
    }
}

private const val TAG = "AssistantImporter"

/**
 * 解析角色卡 JSON 字符串并回调导入结果
 * 文件导入与 URL 导入共用此入口
 */
private suspend fun importFromString(
    context: Context,
    jsonString: String,
    backgroundStr: String?,
    avatarUri: String?,
    onImport: (TavernImportResult) -> Unit,
    toaster: ToasterState,
    mergeGreetings: Boolean = false,
) {
    val json = Json.parseToJsonElement(jsonString).jsonObject

    // 先认风月 AI 的角色卡。
    //
    // 它有自己一整套字段（ttl / desc / pre_pt / world_book），既没有
    // spec、也没有 data，掉到 V1 分支会被当平铺卡读 name 而报错。
    // 判定放在最前面：它的 messages 字段不存在，不会被上一条误判。
    if (looksLikeFengyueCard(json)) {
        val (assistant, lorebooks) = parseFengyueCard(
            context = context,
            json = json,
            avatarUri = avatarUri,
        )
        toaster.show(context.getString(R.string.assistant_importer_import_success))
        onImport(TavernImportResult(assistant = assistant, newLorebooks = lorebooks))
        return
    }

    // 先认「对话导出」格式。
    //
    // 这种 JSON 没有 spec、也没有 data，特征是有 messages 数组加
    // character_name / conversation_id（春水 AI 等应用的导出）。它其实
    // 是「一段已经聊过的对话」而不是角色卡：人物设定写在 messages[0]
    // 那条 user 消息里，首条 assistant 是开场白。
    //
    // 不先认的话它会掉进 V1 分支，被当成平铺卡去读顶层 name 字段，
    // 读不到就报错——用户手上明明有一份完好的设定，却导不进来。
    if (looksLikeConversationExport(json)) {
        val (assistant, lorebooks) = parseConversationExport(
            context = context,
            json = json,
            avatarUri = avatarUri,
            mergeGreetings = mergeGreetings,
        )
        toaster.show(context.getString(R.string.assistant_importer_import_success))
        onImport(TavernImportResult(assistant = assistant, newLorebooks = lorebooks))
        return
    }

    // spec 缺失时按数据结构推断（V1 卡及部分社区导出没有 spec 字段）：
    // 有 data 对象视为 V2 结构，否则视为 V1 平铺结构
    val spec = json["spec"]?.jsonPrimitive?.contentOrNull
        ?: if (json["data"]?.jsonObjectOrNull != null) "chara_card_v2" else "chara_card_v1"

    val (assistant, lorebooks) = when (spec) {
        "chara_card_v2" -> parseV2Card(context, json, backgroundStr, avatarUri, mergeGreetings)
        "chara_card_v3" -> parseV3Card(context, json, backgroundStr, avatarUri, mergeGreetings)
        // V1 卡：字段平铺在顶层，等价映射到 V2 解析器
        "chara_card_v1", "chara_card" -> parseV2Card(context, flattenV1Card(json), backgroundStr, avatarUri, mergeGreetings)
        else -> error(context.getString(R.string.assistant_importer_unsupported_spec, spec))
    }

    toaster.show(context.getString(R.string.assistant_importer_import_success))
    onImport(
        TavernImportResult(
            assistant = assistant,
            newLorebooks = lorebooks,
        )
    )
}

// ==================== 风月 AI 角色卡 ====================

/**
 * 判断是不是风月 AI 的角色卡。
 *
 * 特征字段（同时具备才算）：ttl（名称）+ desc（HTML 界面）+
 * pre_pt（前置提示词）。这三个是它的核心结构，普通酒馆卡不会有。
 *
 * 不靠 type 字段：那个值是 1，含义不明确，拿它当判据太脆。
 */
private fun looksLikeFengyueCard(json: JsonObject): Boolean {
    if (json["spec"] != null || json["data"] != null) return false
    val hasTtl = json["ttl"]?.jsonPrimitiveOrNull?.contentOrNull?.isNotBlank() == true
    val hasDesc = json["desc"]?.jsonPrimitiveOrNull?.contentOrNull?.isNotBlank() == true
    return hasTtl && hasDesc && json["pre_pt"] != null
}

/**
 * 把风月 AI 角色卡转成助手。
 *
 * 字段映射：
 *   · ttl       -> 助手名
 *   · pre_pt    -> 系统提示词（它的「前置提示词」，即角色设定与规则）
 *   · desc      -> 前端界面（HTML），作为开场白保留
 *   · world_book-> 世界书条目
 *
 * **desc 是完整 HTML 网页，原样保留。** 这是前端卡，界面由应用内的
 * 卡片渲染器（HtmlCardDocument + CardHostBridge）绘制；把标签剥掉
 * 等于把卡片毁了。
 *
 * 该卡没有独立的开场白字段（greet_st 只是 UI 上的提示文字），
 * 所以开场白取 desc —— 它本身就是一整个可交互界面。
 */
private fun parseFengyueCard(
    context: Context,
    json: JsonObject,
    avatarUri: String?,
): Pair<Assistant, List<Lorebook>> {
    fun str(key: String): String =
        json[key]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()

    val name = str("ttl").takeIf { it.isNotBlank() }
        ?: context.getString(R.string.assistant_importer_unnamed_character)

    // pre_pt 是角色设定与规则，整段进系统提示词。
    // desc 是界面（HTML），进开场白 —— 两者职责不同，不能混。
    val systemPrompt = str("pre_pt")
    val greetingHtml = str("desc")

    val presetMessages = if (greetingHtml.isBlank()) {
        emptyList()
    } else {
        listOf(UIMessage.assistant(prompt = greetingHtml))
    }

    val lorebooks = parseFengyueWorldBook(json)

    val assistant = Assistant(
        name = name,
        avatar = if (avatarUri != null) Avatar.Image(avatarUri) else Avatar.Dummy,
        systemPrompt = systemPrompt,
        presetMessages = presetMessages,
    )
    // 世界书不经 Assistant 携带，随 TavernImportResult 一起交给调用方——
    // 和其它解析器保持一致（V2/V3 卡也走这条路）。
    return assistant to lorebooks
}

/**
 * 风月的世界书 -> Lorebook。
 *
 * 条目结构（实测）：
 *   key            触发关键词，多个用 @wb@ 分隔，前缀 _or_ 表示任一命中
 *   value          注入内容
 *   enable         是否启用
 *   probability    触发概率
 *   depth          扫描深度
 *   match_type     匹配方式（2 = 关键词）
 *   value_region   注入位置
 *
 * 解析失败一律跳过该条而不是整个导入失败：世界书条目是补充内容，
 * 少一条不影响角色能用，但整个导入失败会让用户什么都拿不到。
 */
private fun parseFengyueWorldBook(json: JsonObject): List<Lorebook> {
    val raw = json["world_book"] as? JsonArray ?: return emptyList()
    if (raw.isEmpty()) return emptyList()

    val entries = raw.mapNotNull { element ->
        val obj = element.jsonObjectOrNull ?: return@mapNotNull null
        val content = obj["value"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
        if (content.isBlank()) return@mapNotNull null

        val rawKey = obj["key"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
        // _or_ 前缀表示「任一命中」；@wb@ 是它自己的分隔符
        val keywords = rawKey
            .removePrefix("_or_")
            .split("@wb@")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val enabled = obj["enable"]?.jsonPrimitiveOrNull?.contentOrNull != "false"
        val probability = obj["probability"]?.jsonPrimitiveOrNull?.contentOrNull
            ?.toIntOrNull()?.coerceIn(0, 100) ?: 100
        val depth = obj["depth"]?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull()
        // 没有关键词的条目当作常驻，否则它永远不会被触发
        val constant = keywords.isEmpty()

        PromptInjection.RegexInjection(
            name = keywords.firstOrNull().orEmpty(),
            enabled = enabled,
            content = content,
            keywords = keywords,
            scanDepth = depth,
            constantActive = constant,
            probability = probability,
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        )
    }

    if (entries.isEmpty()) return emptyList()
    return listOf(
        Lorebook(
            name = "风月世界书",
            enabled = true,
            entries = entries,
            isCharacterBook = true,
        )
    )
}

// ==================== 春水 AI 对话导出 ====================

/**
 * 判断是不是「对话导出」格式。
 *
 * 特征：没有 spec、没有 data，但有 messages 数组，且带 character_name
 * 或 conversation_id 里至少一个。春水 AI 等应用的导出都长这样。
 *
 * 之所以要和角色卡区分开：它其实是「一段已经聊过的对话」。人物设定
 * 写在第一条 user 消息里（用户自己贴进去的），首条 assistant 是开场白。
 * 直接当 V1 平铺卡解析会读不到顶层的 name 字段而报错——用户手上
 * 明明有一份完好的设定，却导不进来。
 */
private fun looksLikeConversationExport(json: JsonObject): Boolean {
    if (json["spec"] != null || json["data"] != null) return false
    val messages = json["messages"] as? JsonArray ?: return false
    if (messages.isEmpty()) return false
    return json["character_name"] != null || json["conversation_id"] != null
}

/**
 * 把对话导出转成助手。
 *
 * 映射关系：
 *   · 第一条 user 消息        -> 角色设定（systemPrompt 的一部分）
 *   · 第一条 assistant 消息   -> 开场白（presetMessage）
 *   · title / character_name  -> 助手名
 *
 * 之所以把首条 user 当设定而不是当普通对话：那是这类导出的固定用法，
 * 用户把人物卡内容贴在开场之前，让模型先读到。当成历史消息塞进
 * presetMessages 反而会让每次新对话都重复一遍这段设定。
 */
private fun parseConversationExport(
    context: Context,
    json: JsonObject,
    avatarUri: String?,
    mergeGreetings: Boolean = false,
): Pair<Assistant, List<Lorebook>> {
    val messages = json["messages"] as? JsonArray ?: JsonArray(emptyList())

    fun textOf(element: JsonElement?): String =
        element?.jsonObjectOrNull?.get("content")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()

    fun roleOf(element: JsonElement?): String =
        element?.jsonObjectOrNull?.get("role")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()

    // 首条 user = 人物设定。若首条不是 user（有的导出直接以 assistant 开场），
    // 那就不当设定，避免把开场白误当成人设。
    val firstIsUser = messages.firstOrNull()?.let { roleOf(it) == "user" } == true
    // 同样保留原文：设定里也可能带样式标记，而且它是要给模型读的，
    // 擅自删改用户写好的内容没有道理。
    val setting = if (firstIsUser) textOf(messages.first()) else ""

    // 第一条 assistant = 开场白。首条是 user 时取第二条，否则取第一条。
    //
    // **原文保留，不做任何清理。** 这类导出常常是「前端卡」——
    // 开场白本身就是一段 HTML，由应用内的卡片渲染器（HtmlCardDocument
    // + CardHostBridge）画成界面。把标签剥掉等于把卡片毁了，只剩一堆
    // 散落的文字。
    val greetingIndex = if (firstIsUser) 1 else 0
    val greeting = messages.getOrNull(greetingIndex)
        ?.takeIf { roleOf(it) == "assistant" }
        ?.let { textOf(it) }
        .orEmpty()

    val name = json["character_name"]?.jsonPrimitiveOrNull?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: json["title"]?.jsonPrimitiveOrNull?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.assistant_importer_unnamed_character)

    // 设定整段作为系统提示词。
    //
    // 不额外拼模板：这种导出里的设定往往已经是完整的角色描述，
    // 外面再套一层「你是……」的壳只会干扰原文的语气。
    val systemPrompt = setting.ifBlank { "" }

    val presetMessages = if (greeting.isBlank()) {
        emptyList()
    } else {
        listOf(UIMessage.assistant(prompt = greeting))
    }

    val assistant = Assistant(
        name = name,
        avatar = if (avatarUri != null) Avatar.Image(avatarUri) else Avatar.Dummy,
        systemPrompt = systemPrompt,
        presetMessages = presetMessages,
    )
    return assistant to emptyList()
}

/**
 * 决定导入后的头像。
 *
 * 优先级：
 *   1. 本地 PNG 卡解析出的 avatarUri —— 那是从卡里抽出来的真实图片文件
 *   2. `data.avatar` 里的远程 URL —— chub 等平台导出时带的是 CDN 地址
 *   3. 默认头像
 *
 * 第 2 条是后加的。chub（charhub.io）的卡在 data.avatar 放的是
 * `https://avatars.charhub.io/.../chara_card_v2.png`，原来这个字段
 * 完全没被读，导入后头像是灰的——功能上不报错，但用户会以为卡没导全。
 *
 * 只在看起来像 URL 时才用：本地卡里偶尔有相对路径或空串，
 * 那些塞进 Avatar.Image 会得到一个加载不出来的头像，比默认头像更糟。
 */
private fun resolveCardAvatar(avatarUri: String?, remoteAvatar: String?): Avatar = when {
    !avatarUri.isNullOrBlank() -> Avatar.Image(avatarUri)
    !remoteAvatar.isNullOrBlank() &&
        (remoteAvatar.startsWith("http://") || remoteAvatar.startsWith("https://")) ->
        Avatar.Image(remoteAvatar)
    else -> Avatar.Dummy
}

/**
 * 安全的对象取值。
 *
 * Kotlin 里 `element?.jsonObject` 只挡住了「字段不存在」，
 * **挡不住「字段存在但值是 JSON null」**——后者会执行
 * `JsonNull.jsonObject`，抛：
 *
 *     Element class kotlinx.serialization.json.JsonNull is not a JsonObject
 *
 * 这在角色卡里是常态而非例外：chub 导出的卡 `character_book` 就是
 * 一个显式的 JSON null（不是缺字段）。因此整份卡导入失败，
 * 用户看到的是一句莫名其妙的序列化报错，而卡本身完全正常。
 *
 * 下面这个扩展把两种情况都收敛成 null。
 */
private fun JsonElement?.jsonObjectSafe(): JsonObject? =
    (this as? JsonObject)

// ==================== V2 Parser ====================

/**
 * V1 卡（chara_card_v1 / 无 spec）：字段平铺在顶层，包装成 V2 的 data 结构。
 * V1 的 embeddings 也归一为 character_book 以复用解析。
 */
private fun flattenV1Card(json: JsonObject): JsonObject {
    val fields = listOf(
        "name", "description", "personality", "scenario", "first_mes",
        "mes_example", "system_prompt", "creator", "creator_notes",
        "character_version", "tags", "post_history_instructions",
    )
    val data = buildMap<String, JsonElement> {
        fields.forEach { f -> json[f]?.let { put(f, it) } }
        json["alternate_greetings"]?.let { put("alternate_greetings", it) }
        // V1 世界书字段名是 character_embdings，归一为 V2 的 character_book
        (json["character_book"] ?: json["character_embdings"])?.let { put("character_book", it) }
        json["extensions"]?.let { put("extensions", it) }
    }
    return JsonObject(json.toMap() + ("data" to JsonObject(data)))
}

private fun parseV2Card(context: Context, json: JsonObject, background: String?, avatarUri: String?, mergeGreetings: Boolean = false): Pair<Assistant, List<Lorebook>> {
    val data = json["data"]?.jsonObjectSafe() ?: error(context.getString(R.string.assistant_importer_missing_data_field))
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
        depthPrompt = parseDepthPromptText(data["extensions"]?.jsonObjectSafe()),
        depthPromptDepth = parseDepthPromptDepth(data["extensions"]?.jsonObjectSafe()),
        depthPromptRole = parseDepthPromptRole(data["extensions"]?.jsonObjectSafe()),
        embeddedBook = parseEmbeddedBook(data["character_book"]?.jsonObjectSafe()),
    )

    val systemPrompt = buildTavernSystemPrompt(tavData)
    val presetMessages = buildPresetMessages(tavData, mergeGreetings)

    val assistant = Assistant(
        name = name,
        // data.avatar 可能是 chub 这类平台的远程地址，本地卡则是 null
        avatar = resolveCardAvatar(avatarUri, data["avatar"]?.jsonPrimitiveOrNull?.contentOrNull),
        systemPrompt = systemPrompt,
        presetMessages = presetMessages,
        background = background,
        tavernData = tavData,
        // 前端卡（HTML 卡）靠 extensions.regex_scripts 在渲染时把占位标签
        // （<zd_status>、<StatusPlaceHolderImpl/> 等）替换成整段 HTML 文档。
        // 不导入这些脚本，卡只会显示成标签文字。复用全局正则脚本的导入实现，
        // 避免两套语义分叉。
        regexes = extractCardRegexScripts(tavData),
    )

    // 内嵌世界书不再物化成独立外置书：官方模型里它就是卡的一部分，
    // 由 PromptInjectionTransformer 在注入时直接从卡片构建，避免与全局书互相覆盖
    return assistant to emptyList()
}

// ==================== V3 Parser ====================

private fun parseV3Card(context: Context, json: JsonObject, background: String?, avatarUri: String?, mergeGreetings: Boolean = false): Pair<Assistant, List<Lorebook>> {
    val data = json["data"]?.jsonObjectSafe() ?: error(context.getString(R.string.assistant_importer_missing_data_field))
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
        extensions = parseExtensions(data["extensions"]?.jsonObjectSafe()),
        extensionsRaw = data["extensions"]?.toString() ?: "",
        assets = parseAssets(data["assets"]?.jsonArray),
        groupOnlyGreetings = parseStringArray(data["group_only_greetings"]),
        nickname = data["nickname"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        creatorNotesMultilingual = data["creator_notes_multilingual"]?.toString() ?: "",
        source = parseStringArray(data["source"]),
        creationDate = data["creation_date"]?.toString() ?: "",
        modificationDate = data["modification_date"]?.toString() ?: "",
        depthPrompt = parseDepthPromptText(data["extensions"]?.jsonObjectSafe()),
        depthPromptDepth = parseDepthPromptDepth(data["extensions"]?.jsonObjectSafe()),
        depthPromptRole = parseDepthPromptRole(data["extensions"]?.jsonObjectSafe()),
        embeddedBook = parseEmbeddedBook(data["character_book"]?.jsonObjectSafe()),
    )

    val systemPrompt = buildTavernSystemPrompt(tavData)
    val presetMessages = buildPresetMessages(tavData, mergeGreetings)

    val assistant = Assistant(
        name = name,
        // data.avatar 可能是 chub 这类平台的远程地址，本地卡则是 null
        avatar = resolveCardAvatar(avatarUri, data["avatar"]?.jsonPrimitiveOrNull?.contentOrNull),
        systemPrompt = systemPrompt,
        presetMessages = presetMessages,
        background = background,
        tavernData = tavData,
        // 前端卡（HTML 卡）靠 extensions.regex_scripts 在渲染时把占位标签
        // （<zd_status>、<StatusPlaceHolderImpl/> 等）替换成整段 HTML 文档。
        // 不导入这些脚本，卡只会显示成标签文字。复用全局正则脚本的导入实现，
        // 避免两套语义分叉。
        regexes = extractCardRegexScripts(tavData),
    )

    // 内嵌世界书不再物化成独立外置书：官方模型里它就是卡的一部分，
    // 由 PromptInjectionTransformer 在注入时直接从卡片构建，避免与全局书互相覆盖
    return assistant to emptyList()
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
        extensions = parseExtensions(obj["extensions"]?.jsonObjectSafe()),
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
                    ?: e["insertion_order"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: e["priority"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100,
                disable = e["disable"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                caseSensitive = e["caseSensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                useRegex = e["useRegex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: e["use_regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                probability = e["probability"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100,
                sticky = parseStickyInt(e["sticky"]),
                cooldown = e["cooldown"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                depth = e["depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 4,
                // 官方语义：条目未写 scan_depth 时为 null → 注入时用全局默认（world_info_depth=2）
                scanDepth = e["scan_depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
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
                    ?: e["insertion_order"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: e["priority"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100,
                disable = e["disable"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                caseSensitive = e["caseSensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                useRegex = e["useRegex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: e["use_regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                probability = e["probability"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100,
                sticky = parseStickyInt(e["sticky"]),
                cooldown = e["cooldown"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                depth = e["depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 4,
                // 官方语义：条目未写 scan_depth 时为 null → 注入时用全局默认（world_info_depth=2）
                scanDepth = e["scan_depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
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
            // 官方 delay_until_recursion 可为 true 或数字层级（1/2/3…），extensions 优先，顶层兜底
            delayUntilRecursion = parseDelayUntilRecursionInt(extensions["delay_until_recursion"])
                ?: parseDelayUntilRecursionInt(e["delayUntilRecursion"])
                ?: entry.delayUntilRecursion,
            excludeRecursion = extensions["exclude_recursion"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: entry.excludeRecursion,
            caseSensitive = extensions["case_sensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: entry.caseSensitive,
            selectiveLogic = extensions["selectiveLogic"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: entry.selectiveLogic,
            probability = extensions["probability"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.probability,
            useProbability = extensions["useProbability"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: extensions["use_probability"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                // 旧卡只写 probability 没写开关时，按“有概率值即启用”兜底
                ?: (extensions["probability"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() != null || entry.useProbability),
            sticky = extensions["sticky"]?.let { parseStickyInt(it) }?.takeIf { it > 0 } ?: entry.sticky,
            cooldown = extensions["cooldown"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.cooldown,
            delay = extensions["delay"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.delay,
            scanDepth = extensions["scan_depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.scanDepth,
            priority = extensions["order"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.priority,
            position = extensions["position"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.position,
            depth = extensions["depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: entry.depth,
            group = extensions["group"]?.jsonPrimitive?.contentOrNull ?: entry.group,
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
 * 官方 delay_until_recursion：true=层级1，数字=层级 N，字符串同理解析。
 * 返回 null 表示字段不存在/无法解析。
 */
internal fun parseDelayUntilRecursionInt(element: JsonElement?): Int? {
    if (element == null) return null
    val content = try {
        element.jsonPrimitive.contentOrNull
    } catch (_: Exception) {
        return null
    } ?: return null
    content.toBooleanStrictOrNull()?.let { return if (it) 1 else 0 }
    return content.toIntOrNull()
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
 * 将内嵌世界书条目转为Rikkahub的RegexInjection（用于导出/反向同步，故 id 随机）
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
    "user", "1" -> me.rerere.ai.core.MessageRole.USER
    "assistant", "2" -> me.rerere.ai.core.MessageRole.ASSISTANT
    else -> me.rerere.ai.core.MessageRole.SYSTEM   // 官方世界书/深度提示默认 system
}

/** 映射酒馆 selectiveLogic Int 到 SelectiveLogic 枚举（官方 world_info_logic：0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL） */
internal fun mapSelectiveLogic(logic: Int): SelectiveLogic = when (logic) {
    0 -> SelectiveLogic.AND_ANY
    1 -> SelectiveLogic.NOT_ALL
    2 -> SelectiveLogic.NOT_ANY
    3 -> SelectiveLogic.AND_ALL
    else -> SelectiveLogic.AND_ANY
}

/** 反向转换：RegexInjection → TavernBookEntry（内嵌书编辑/导出用） */
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
            SelectiveLogic.NOT_ALL -> 1
            SelectiveLogic.NOT_ANY -> 2
            SelectiveLogic.AND_ALL -> 3
            // 官方无 OR_ANY；本地遗留条目导出时按最接近的 AND_ANY 处理
            SelectiveLogic.OR_ANY -> 0
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
    // 本地扩展位置写入官方枚举时落到最接近的出口（outlet=7），避免产生官方不存在的 8
    InjectionPosition.ANTAGONIZE, InjectionPosition.AFTER_DIALOG -> 7
    InjectionPosition.EM_TOP -> 5
    InjectionPosition.EM_BOTTOM -> 6
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
 * 构建 presetMessages — 默认只使用 first_mes，alternate_greetings 由角色卡 UI 选择
 * 用户在角色卡页面点「使用此开场白」时替换 presetMessages
 * mergeGreetings 为 true 时（导入勾选「合并开场白」），把 first_mes 与所有
 * alternate_greetings 作为同一对话的连续多条消息导入
 */
private fun buildPresetMessages(d: TavernCharacterData, mergeGreetings: Boolean = false): List<UIMessage> {
    val messages = mutableListOf<UIMessage>()
    if (d.firstMessage.isNotBlank()) {
        messages.add(UIMessage.assistant(d.firstMessage))
    }
    if (mergeGreetings) {
        d.alternateGreetings.filter { it.isNotBlank() }.forEach { greeting ->
            messages.add(UIMessage.assistant(greeting))
        }
    }
    return messages
}

/**
 * 从角色卡 extensions.regex_scripts 提取正则脚本。
 *
 * 官方把前端卡的 HTML 挂在正则脚本的 replaceString 上，由渲染阶段执行；
 * 解析复用 [SillyTavernRegexImporter]（与用户手动导入正则脚本走同一条路径）。
 */
private fun extractCardRegexScripts(tavData: TavernCharacterData): List<AssistantRegex> {
    val raw = tavData.extensionsRaw.ifBlank { return emptyList() }
    val extensions = runCatching {
        Json.parseToJsonElement(raw) as? JsonObject
    }.getOrNull() ?: return emptyList()
    val scripts = extensions["regex_scripts"] as? JsonArray ?: return emptyList()
    if (scripts.isEmpty()) return emptyList()
    return SillyTavernRegexImporter.parse(scripts.toString())
}
