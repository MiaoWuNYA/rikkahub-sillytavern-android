@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Wrench01
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.pages.assistant.detail.TavernCharacterCard
import me.rerere.rikkahub.utils.CardExporter
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import me.rerere.rikkahub.data.model.Tag as DataTag

@Composable
fun AssistantDetailPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    // 头部滚出屏幕后，名字改在顶栏里显示
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
    var showExport by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(
                        visible = showTitle,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { it / 2 },
                    ) {
                        Text(
                            text = name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AssistantHeader(
                    assistant = assistant,
                    name = name,
                    tags = tags.filter { it.id in assistant.tags },
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp)
                )
            }

            // 酒馆角色卡结构化信息
            if (assistant.tavernData != null) {
                item {
                    TavernCharacterCard(
                        assistant = assistant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        onAssistantUpdate = { updated -> vm.update(updated) },
                        settings = settings,
                        onSettingsUpdate = { updated -> vm.updateSettings(updated) },
                        onExport = { showExport = true },
                    )
                }

                // 开场白选择
                item {
                    GreetingSelectorCard(
                        assistant = assistant,
                        onSelectGreeting = { greeting ->
                            vm.update(assistant.copy(
                                presetMessages = listOf(UIMessage.assistant(greeting))
                            ))
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            item {
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup {
                    item(
                        onClick = { navController.navigate(Screen.AssistantBasic(id)) },
                        leadingContent = { CardGroupIcon(HugeIcons.Settings03, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.assistant_detail_basic_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_basic)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.AssistantPrompt(id)) },
                        leadingContent = { CardGroupIcon(HugeIcons.Message02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.assistant_detail_prompt_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_prompt)) },
                    )
                }
            }

            item {
                val containerColor = MaterialTheme.colorScheme.tertiaryContainer
                val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                CardGroup {
                    item(
                        onClick = { navController.navigate(Screen.AssistantInjections(id)) },
                        leadingContent = { CardGroupIcon(HugeIcons.Puzzle, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.assistant_detail_extensions_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_extensions)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.AssistantMemory(id)) },
                        leadingContent = { CardGroupIcon(HugeIcons.Brain02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.assistant_detail_memory_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_memory)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.AssistantMcp(id)) },
                        leadingContent = { CardGroupIcon(HugeIcons.McpServer, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.assistant_detail_mcp_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_mcp)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.AssistantLocalTool(id)) },
                        leadingContent = { CardGroupIcon(HugeIcons.Wrench01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.assistant_detail_local_tools_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_local_tools)) },
                    )
                }
            }

            item {
                CardGroup {
                    item(
                        onClick = { navController.navigate(Screen.AssistantRequest(id)) },
                        leadingContent = { CardGroupIcon(HugeIcons.Code) },
                        supportingContent = { Text(stringResource(R.string.assistant_detail_request_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_request)) },
                    )

                }
            }
        }
    }

    if (showExport) {
        ExportCardDialog(
            assistant = assistant,
            onDismiss = { showExport = false },
        )
    }
}

@Composable
private fun AssistantHeader(
    assistant: Assistant,
    name: String,
    tags: List<DataTag>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        UIAvatar(
            value = assistant.avatar,
            name = name,
            shape = MaterialShapes.Cookie12Sided.toShape(),
            onUpdate = null,
            modifier = Modifier
                .size(136.dp)
                .heroAnimation("assistant_${assistant.id}")
        )

        Text(
            text = name,
            // 名字长短不一，放不下两行时自动缩小字号
            autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 32.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineLargeEmphasized.copy(
                fontWeight = FontWeight.Black,
                lineHeight = 1.2.em,
                lineBreak = LineBreak.Heading,
            ),
        )

        if (tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.fastForEach { tag ->
                    HeaderChip(text = tag.name)
                }
            }
        }

        if (assistant.systemPrompt.isNotBlank()) {
            Text(
                text = assistant.systemPrompt.take(100),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 开场白选择器卡片 — 显示当前开场白，点击弹出底部选单
 */
@Composable
private fun GreetingSelectorCard(
    assistant: Assistant,
    onSelectGreeting: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tav = assistant.tavernData ?: return
    val allGreetings = listOfNotNull(tav.firstMessage.takeIf { it.isNotBlank() }) +
        tav.alternateGreetings.filter { it.isNotBlank() }
    if (allGreetings.isEmpty()) return

    val currentGreeting = assistant.presetMessages
        .firstOrNull { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
        ?.toText() ?: ""

    var showSheet by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().clickable { showSheet = true },
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HugeIcons.Message02,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "开场白",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (currentGreeting.isNotBlank())
                        currentGreeting.replace("\n", " ").take(80) +
                            (if (currentGreeting.length > 80) "..." else "")
                    else "未选择 · ${allGreetings.size}个可选",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "切换",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }

    if (showSheet) {
        GreetingPickerSheet(
            allGreetings = allGreetings,
            currentGreeting = currentGreeting,
            onSelect = { greeting ->
                onSelectGreeting(greeting)
                showSheet = false
            },
            onDismiss = { showSheet = false },
        )
    }
}

/**
 * 开场白选择底部弹窗
 */
@Composable
private fun GreetingPickerSheet(
    allGreetings: List<String>,
    currentGreeting: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CustomColors.listItemColors.containerColor,
        dragHandle = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "选择开场白",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }) {
                    Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(allGreetings) { index, greeting ->
                val isSelected = greeting == currentGreeting

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSelected) { onSelect(greeting) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        // 编号标识
                        Text(
                            text = "G${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .width(28.dp),
                        )

                        // 开场白内容
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        // 选中状态
                        if (isSelected) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "当前",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // 使用提示
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "点击开场白即可使用，新建对话时将以此开场",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ExportCardDialog(
    assistant: Assistant,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    // 独立于对话框的 scope（对话框关闭后仍可执行导出）
    val exportScope = remember { CoroutineScope(Dispatchers.Main + SupervisorJob()) }
    var downloadingAvatar by remember { mutableStateOf(false) }
    val avatarUrl = (assistant.avatar as? Avatar.Image)?.url

    // 头像选取（当没有头像时手动选）
    val pngImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { pickedUri: Uri? ->
        if (pickedUri == null) return@rememberLauncherForActivityResult
        onDismiss()
        doPngExportInternal(context, exportScope, toaster, assistant, pickedUri)
    }

    // 已设头像的 URI（仅本地 content/file 可用于 PNG 嵌入；网络 URL 走自动下载）
    val avatarUri = runCatching {
        val uri = avatarUrl?.toUri() ?: return@runCatching null
        val scheme = uri.scheme
        if (scheme == "content" || scheme == "file") uri else null
    }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tavern_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Icon(
                        HugeIcons.Folder01,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.tavern_export_download_dir),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CardGroup {
                    item(
                        onClick = {
                            onDismiss()
                            exportScope.launch {
                                try {
                                    val json = CardExporter.buildV3CardJson(assistant)
                                    val fileName = "RikkaHub_${assistant.name.replace(" ", "_")}_${System.currentTimeMillis()}.json"
                                    saveToDownloads(context, fileName, "application/json", json.toByteArray())
                                    toaster.show(context.getString(R.string.tavern_export_json_success, "Download/$fileName"))
                                } catch (e: Exception) {
                                    toaster.show(context.getString(R.string.tavern_export_failed, e.message.orEmpty()))
                                }
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.tavern_export_json)) },
                        supportingContent = { Text(stringResource(R.string.tavern_export_json_desc)) },
                        leadingContent = {
                            Icon(HugeIcons.File01, contentDescription = null)
                        },
                    )
                    item(
                        onClick = {
                            when {
                                avatarUri != null -> {
                                    onDismiss()
                                    doPngExportInternal(context, exportScope, toaster, assistant, avatarUri)
                                }
                                avatarUrl != null && (avatarUrl.startsWith("http://") || avatarUrl.startsWith("https://")) -> {
                                    // 网络头像：自动下载到缓存后合并导出，失败再落回图片选择器
                                    downloadingAvatar = true
                                    exportScope.launch {
                                        val downloaded = downloadUrlAvatarToCache(context, avatarUrl)
                                        downloadingAvatar = false
                                        onDismiss()
                                        if (downloaded != null) {
                                            doPngExportInternal(context, exportScope, toaster, assistant, downloaded)
                                        } else {
                                            toaster.show(context.getString(R.string.tavern_export_png_download_failed))
                                            pngImagePicker.launch("image/*")
                                        }
                                    }
                                }
                                else -> {
                                    pngImagePicker.launch("image/*")
                                }
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.tavern_export_png)) },
                        supportingContent = {
                            Text(
                                when {
                                    downloadingAvatar -> context.getString(R.string.tavern_export_png_downloading)
                                    avatarUri != null -> context.getString(R.string.tavern_export_png_desc_avatar)
                                    avatarUrl != null && (avatarUrl.startsWith("http://") || avatarUrl.startsWith("https://")) ->
                                        context.getString(R.string.tavern_export_png_desc_network)
                                    else -> context.getString(R.string.tavern_export_png_desc_pick)
                                }
                            )
                        },
                        leadingContent = {
                            if (downloadingAvatar) {
                                CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                Icon(HugeIcons.Image02, contentDescription = null)
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tavern_export_close)) }
        }
    )
}

private fun doPngExportInternal(
    context: Context,
    scope: CoroutineScope,
    toaster: com.dokar.sonner.ToasterState,
    assistant: Assistant,
    imageUri: Uri,
) {
    scope.launch {
        try {
            val json = CardExporter.buildV3CardJson(assistant)
            val pngBytes = CardExporter.embedCardToPng(imageUri, context, json)
                ?: error("嵌入PNG失败，请确认选择的图片是PNG格式")
            val fileName = "RikkaHub_${assistant.name.replace(" ", "_")}_${System.currentTimeMillis()}.png"
            saveToDownloads(context, fileName, "image/png", pngBytes)
            toaster.show("已导出 PNG: Download/$fileName")
        } catch (e: Exception) {
            toaster.show(context.getString(R.string.tavern_export_failed, e.message.orEmpty()))
        }
    }
}

/**
 * 下载网络头像到应用缓存目录，返回 file:// URI；下载失败返回 null
 */
private suspend fun downloadUrlAvatarToCache(context: Context, url: String): Uri? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RikkaHub")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    return@runCatching null
                }
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) {
                    return@runCatching null
                }
                val file = java.io.File(
                    context.cacheDir,
                    "avatar_download_${System.currentTimeMillis()}.img"
                )
                file.writeBytes(bytes)
                Uri.fromFile(file)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}

/**
 * 通过 MediaStore 将字节写入「下载」文件夹
 */
private suspend fun saveToDownloads(context: Context, fileName: String, mimeType: String, data: ByteArray) {
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { output ->
                    output.write(data)
                }
            } ?: error("无法创建下载文件")
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = java.io.File(downloadsDir, fileName)
            file.writeBytes(data)
        }
    }
}

@Composable
private fun HeaderChip(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
