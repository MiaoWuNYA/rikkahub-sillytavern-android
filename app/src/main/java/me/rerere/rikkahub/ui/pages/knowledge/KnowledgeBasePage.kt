package me.rerere.rikkahub.ui.pages.knowledge

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AddCircleHalfDot
import me.rerere.hugeicons.stroke.BookOpen02
import me.rerere.hugeicons.stroke.CloudDownload
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder02
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Note
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.db.entity.KnowledgeSourceEntity
import me.rerere.rikkahub.data.knowledge.KnowledgeBaseService
import me.rerere.rikkahub.data.model.KnowledgeSourceType
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

private fun formatTime(millis: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBasePage() {
    val kbService: KnowledgeBaseService = koinInject()
    val sources by kbService.getAllSourcesFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val importProgress by kbService.importProgress.collectAsStateWithLifecycle()
    val embeddingProgress by kbService.embeddingProgress.collectAsStateWithLifecycle()

    var showImportDialog by remember { mutableStateOf(false) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var embeddingId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<KnowledgeBaseService.SearchResultUi>?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var simulateResults by remember { mutableStateOf<List<KnowledgeBaseService.SearchResultUi>?>(null) }
    var isSimulating by remember { mutableStateOf(false) }
    var detailSourceId by remember { mutableStateOf<String?>(null) }
    var assistantFilter by remember { mutableStateOf<String?>(null) } // null=全部, "global"=全局

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("知识库") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(HugeIcons.AddCircleHalfDot, contentDescription = "导入")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            LargeFloatingActionButton(
                onClick = { showImportDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(HugeIcons.AddCircleHalfDot, contentDescription = "导入")
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (sources.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        HugeIcons.BookOpen02,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "知识库为空",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点击右下角按钮导入文件或聊天记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        } else {
            // 在Composable域计算filteredSources，不能在LazyColumn的LazyListScope里用remember
            val filteredSources = remember(sources, assistantFilter) {
                val af = assistantFilter // 本地捕获，enable smart cast
                sources.filter { source ->
                    when (af) {
                        null -> true
                        "global" -> source.assistantId == null
                        else -> source.assistantId == af
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                // 进度条
                if (importProgress != null) {
                    item {
                        val p = importProgress!!
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("批量导入中...", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(progress = { if (p.total > 0) p.completed.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth())
                                Text("${p.currentFileName}（${p.completed}/${p.total}）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (embeddingProgress != null) {
                    item {
                        val p = embeddingProgress!!
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("向量化中 ${p.completed}/${p.total}...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                // 自动注入开关
                item {
                    val kbSettings = settings.kbInjectionSettings
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("自动注入", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (kbSettings.enabled) "已开启 · ${kbSettings.chunkCount}条 · ${kbSettings.tokenBudget}tokens"
                                        else "生成时自动检索知识库",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = kbSettings.enabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            settingsStore.update { s ->
                                                s.copy(kbInjectionSettings = kbSettings.copy(enabled = enabled))
                                            }
                                        }
                                    }
                                )
                            }
                            AnimatedVisibility(visible = kbSettings.enabled) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Spacer(Modifier.height(2.dp))

                                    // 注入条数
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("注入条数", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.width(64.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Slider(
                                            value = kbSettings.chunkCount.toFloat(),
                                            onValueChange = { v ->
                                                scope.launch {
                                                    settingsStore.update { s ->
                                                        s.copy(kbInjectionSettings = kbSettings.copy(chunkCount = v.toInt()))
                                                    }
                                                }
                                            },
                                            valueRange = 1f..10f,
                                            steps = 8,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text("${kbSettings.chunkCount}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(24.dp))
                                    }

                                    // Token预算
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Token预算", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.width(64.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Slider(
                                            value = kbSettings.tokenBudget.toFloat(),
                                            onValueChange = { v ->
                                                scope.launch {
                                                    settingsStore.update { s ->
                                                        s.copy(kbInjectionSettings = kbSettings.copy(tokenBudget = v.toInt()))
                                                    }
                                                }
                                            },
                                            valueRange = 256f..4096f,
                                            steps = 14,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text("${kbSettings.tokenBudget}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(36.dp))
                                    }

                                    // 相似度阈值
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("相似度阈值", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.width(64.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Slider(
                                            value = kbSettings.scoreThreshold,
                                            onValueChange = { v ->
                                                scope.launch {
                                                    settingsStore.update { s ->
                                                        s.copy(kbInjectionSettings = kbSettings.copy(scoreThreshold = v))
                                                    }
                                                }
                                            },
                                            valueRange = 0.05f..0.95f,
                                            steps = 17,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text("%.2f".format(kbSettings.scoreThreshold), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(36.dp))
                                    }

                                    // Embedding 模型选择
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Embedding 模型", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                            Text("用于向量化知识库内容", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        ModelSelector(
                                            modelId = settings.embeddingModelId,
                                            providers = settings.providers,
                                            type = ModelType.EMBEDDING,
                                            allowClear = true,
                                            onSelect = { model ->
                                                scope.launch {
                                                    settingsStore.update { s ->
                                                        s.copy(embeddingModelId = if (model.modelId.isBlank()) null else model.id)
                                                    }
                                                }
                                            },
                                        )
                                    }

                                    Divider(Modifier.padding(vertical = 2.dp))

                                    // 混合搜索
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("混合搜索", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                            Text("FTS5 + 向量语义同时搜索", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(
                                            checked = kbSettings.useHybridSearch,
                                            onCheckedChange = { v ->
                                                scope.launch {
                                                    settingsStore.update { s ->
                                                        s.copy(kbInjectionSettings = kbSettings.copy(useHybridSearch = v))
                                                    }
                                                }
                                            }
                                        )
                                    }

                                    // Query Rewrite
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Query Rewrite", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                            Text("自动生成搜索变体提高召回率", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(
                                            checked = kbSettings.useQueryRewrite,
                                            onCheckedChange = { v ->
                                                scope.launch {
                                                    settingsStore.update { s ->
                                                        s.copy(kbInjectionSettings = kbSettings.copy(useQueryRewrite = v))
                                                    }
                                                }
                                            }
                                        )
                                    }

                                    // 跨轮去重
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("跨轮去重", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                            Text("避免多轮对话中重复注入相同内容", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(
                                            checked = kbSettings.enableDedup,
                                            onCheckedChange = { v ->
                                                scope.launch {
                                                    settingsStore.update { s ->
                                                        s.copy(kbInjectionSettings = kbSettings.copy(enableDedup = v))
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // 搜索栏 + 模拟注入按钮
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it; searchResults = null; simulateResults = null },
                            placeholder = { Text("搜索知识库...") },
                            leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                            trailingIcon = {
                                if (isSearching) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSearch = {
                                    if (searchQuery.isNotBlank()) {
                                        isSearching = true
                                        scope.launch {
                                            searchResults = kbService.searchForUi(searchQuery, settings)
                                            isSearching = false
                                        }
                                    }
                                }
                            ),
                        )
                        Spacer(Modifier.width(4.dp))
                        // 模拟注入按钮
                        IconButton(
                            enabled = searchQuery.isNotBlank() && !isSimulating,
                            onClick = {
                                isSimulating = true
                                simulateResults = null
                                scope.launch {
                                    val injectResults = kbService.searchForInjection(searchQuery, settings = settings)
                                    simulateResults = injectResults.map { r ->
                                        KnowledgeBaseService.SearchResultUi(
                                            chunkId = r.chunk.id,
                                            text = r.chunk.text.take(300),
                                            sourceName = r.source.name,
                                            score = r.score,
                                            matchType = r.matchType.name,
                                        )
                                    }
                                    isSimulating = false
                                }
                            },
                        ) {
                            if (isSimulating) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(HugeIcons.AiMagic, contentDescription = "模拟注入")
                            }
                        }
                    }
                }

                // 搜索结果或来源列表
                if (searchResults != null) {
                    item {
                        Text(
                            "搜索结果（${searchResults?.size}条）",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(searchResults ?: emptyList(), key = { it.chunkId }) { result ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = when (result.matchType) {
                                        "FTS" -> "📖 关键字匹配"
                                        "EMBEDDING" -> "🧠 语义匹配"
                                        "HYBRID" -> "🎯 双通道匹配"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = result.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 5,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "来源: ${result.sourceName} · 匹配度: ${"%.0f".format(result.score * 100)}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else if (simulateResults != null) {
                    item {
                        Text(
                            "模拟注入结果（${simulateResults?.size}条 · 使用注入参数）",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    items(simulateResults ?: emptyList(), key = { it.chunkId }) { result ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = when (result.matchType) {
                                        "FTS" -> "📖 关键字匹配"
                                        "EMBEDDING" -> "🧠 语义匹配"
                                        "HYBRID" -> "🎯 双通道匹配"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = result.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 5,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "来源: ${result.sourceName} · 匹配度: ${"%.0f".format(result.score * 100)}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    // 助理筛选行
                    item {
                        val allAssistants = settings.assistants.filter { it.name.isNotBlank() }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            item {
                                FilterChip(
                                    selected = assistantFilter == null,
                                    onClick = { assistantFilter = null },
                                    label = { Text("全部", style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(8.dp),
                                )
                            }
                            item {
                                FilterChip(
                                    selected = assistantFilter == "global",
                                    onClick = { assistantFilter = "global" },
                                    label = { Text("全局", style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(8.dp),
                                )
                            }
                            items(allAssistants.take(20), key = { it.id.toString() }) { asst ->
                                FilterChip(
                                    selected = assistantFilter == asst.id.toString(),
                                    onClick = { assistantFilter = asst.id.toString() },
                                    label = { Text(asst.name, style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(8.dp),
                                )
                            }
                        }
                    }
                    // 过滤后的来源列表
                    items(filteredSources, key = { it.id }) { source ->
                        val toaster = LocalToaster.current
                        KnowledgeSourceCard(
                            source = source,
                            isDeleting = deletingId == source.id,
                            onClick = { detailSourceId = source.id },
                            onDelete = {
                                deletingId = source.id
                                scope.launch {
                                    kbService.deleteSource(source.id)
                                    deletingId = null
                                }
                            },
                            onRename = {
                                renamingId = source.id
                                renameText = source.name
                            },
                            onEmbed = {
                                scope.launch {
                                    embeddingId = source.id
                                    val err = kbService.embedSource(source.id, settings)
                                    embeddingId = null
                                    if (err != null) {
                                        toaster.show(err, type = com.dokar.sonner.ToastType.Error)
                                    }
                                }
                            },
                            isEmbedding = embeddingId == source.id,
                        )
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        KnowledgeImportDialog(
            knowledgeBaseService = kbService,
            onDismiss = { showImportDialog = false },
            scope = scope,
        )
    }

    // 重命名对话框
    if (renamingId != null) {
        val currentSource = sources.find { it.id == renamingId }
        var tagText by remember(renamingId) { mutableStateOf(currentSource?.tags ?: "") }
        AlertDialog(
            onDismissRequest = { renamingId = null },
            title = { Text("编辑知识源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = tagText,
                        onValueChange = { tagText = it },
                        label = { Text("标签（逗号分隔）") },
                        placeholder = { Text("如: 技术, 文档, AI") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renamingId?.let { id ->
                            scope.launch {
                                kbService.renameSource(id, renameText.trim())
                                kbService.editSourceTags(id, tagText.trim())
                                renamingId = null
                            }
                        }
                    },
                    enabled = renameText.isNotBlank(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingId = null }) {
                    Text("取消")
                }
            },
        )

        // 详情 BottomSheet
        if (detailSourceId != null) {
            val detailSource = sources.find { it.id == detailSourceId }
            ModalBottomSheet(
                onDismissRequest = { detailSourceId = null },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                    if (detailSource != null) {
                        Text(detailSource.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))

                        // 基本信息
                        Text(
                            buildString {
                                append(when (detailSource.type) {
                                    "FILE" -> "📄 文件"
                                    "CHAT" -> "💬 聊天记录"
                                    "TEXT" -> "📝 笔记"
                                    "BATCH" -> "📁 批量"
                                    else -> detailSource.type
                                })
                                append(" · ${detailSource.chunkCount} 段")
                                if (detailSource.fileSize > 0) {
                                    append(" · ${"%.0f".format(detailSource.fileSize / 1024.0)} KB")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "创建于 ${formatTime(detailSource.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // 标签
                        if (detailSource.tags.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                detailSource.tags.split(",").filter { it.isNotBlank() }.forEach { tag ->
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Text(
                                            text = tag.trim(),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }

                        // 预览
                        if (detailSource.filePath != null) {
                            Spacer(Modifier.height(12.dp))
                            Text("预览", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            var preview by remember { mutableStateOf<String?>(null) }
                            LaunchedEffect(detailSource) {
                                preview = kbService.previewDocument(Uri.parse(detailSource.filePath))
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Text(
                                    text = preview ?: "加载中...",
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeSourceCard(
    source: KnowledgeSourceEntity,
    isDeleting: Boolean,
    isEmbedding: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onEmbed: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (source.type) {
                    "FILE" -> HugeIcons.File02
                    "CHAT" -> HugeIcons.BubbleChatQuestion
                    "TEXT" -> HugeIcons.Note
                    "BATCH" -> HugeIcons.Folder02
                    else -> HugeIcons.File02
                },
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(when (source.type) {
                            "FILE" -> "文件"
                            "CHAT" -> "聊天记录"
                            "TEXT" -> "笔记"
                            "URL" -> "网页"
                            else -> source.type
                        })
                        append(" · ")
                        append(formatTime(source.createdAt))
                    if (source.chunkCount > 0) {
                        append(" · ${source.chunkCount} 段")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 标签
            if (source.tags.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    source.tags.split(",").filter { it.isNotBlank() }.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = tag.trim(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }
        if (isDeleting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        HugeIcons.Delete02,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onRename) {
                Icon(
                    HugeIcons.PencilEdit01,
                    contentDescription = "重命名",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isEmbedding) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onEmbed) {
                    Icon(
                        HugeIcons.CloudDownload,
                        contentDescription = "向量化",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    // 删除确认弹窗
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除知识源") },
            text = { Text("确定删除「${source.name}」及其所有分块？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun KnowledgeImportDialog(
    knowledgeBaseService: KnowledgeBaseService,
    onDismiss: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var selectedType by remember { mutableStateOf<ImportType>(ImportType.File) }

    // 自动向量化开关
    val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    var autoEmbed by remember { mutableStateOf(settings.displaySetting.autoEmbedOnImport) }
    var embeddingEnabled by remember { mutableStateOf(settings.displaySetting.embeddingEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入知识") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 自动向量化开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("导入后自动向量化", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "调API计算语义向量，可离线搜索但首次需要联网",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoEmbed,
                        onCheckedChange = {
                            autoEmbed = it
                            scope.launch {
                                settingsStore.update { s ->
                                    s.copy(displaySetting = s.displaySetting.copy(autoEmbedOnImport = it))
                                }
                            }
                        },
                    )
                }
                // 向量搜索总开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用向量搜索", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "关则仅使用文本搜索，不调API、不产生向量费用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = embeddingEnabled,
                        onCheckedChange = {
                            embeddingEnabled = it
                            scope.launch {
                                settingsStore.update { s ->
                                    s.copy(displaySetting = s.displaySetting.copy(embeddingEnabled = it))
                                }
                            }
                        },
                    )
                }
                Divider()
                // 选择导入类型（用 FlowRow 防止溢出）
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ImportTypeChip(
                        selected = selectedType is ImportType.File,
                        icon = HugeIcons.File02,
                        label = "文件",
                        onClick = { selectedType = ImportType.File },
                    )
                    ImportTypeChip(
                        selected = selectedType is ImportType.ChatHistory,
                        icon = HugeIcons.BubbleChatQuestion,
                        label = "聊天记录",
                        onClick = { selectedType = ImportType.ChatHistory },
                    )
                    ImportTypeChip(
                        selected = selectedType is ImportType.TextNote,
                        icon = HugeIcons.Note,
                        label = "笔记",
                        onClick = { selectedType = ImportType.TextNote },
                    )
                    ImportTypeChip(
                        selected = selectedType is ImportType.BatchFolder,
                        icon = HugeIcons.Folder02,
                        label = "批量",
                        onClick = { selectedType = ImportType.BatchFolder },
                    )
                }

                Spacer(Modifier.height(8.dp))

                when (selectedType) {
                    is ImportType.File -> FileImportContent(knowledgeBaseService, scope, onDismiss)
                    is ImportType.ChatHistory -> ChatHistoryImportContent(knowledgeBaseService, scope, onDismiss)
                    is ImportType.TextNote -> TextNoteImportContent(knowledgeBaseService, scope, onDismiss)
                    is ImportType.BatchFolder -> BatchFolderImportContent(knowledgeBaseService, scope, onDismiss, null)
                }

                Divider()
                Text(
                    "提示：导入的知识源默认对所有助理可见，可在助理设置中单独按助理启用知识库",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun ImportTypeChip(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
    )
}

// ---- 文件导入 ----

@Composable
private fun FileImportContent(
    kbService: KnowledgeBaseService,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit,
) {
    var stage by remember { mutableStateOf(0) } // 0=选择, 1=预览, 2=导入中
    var status by remember { mutableStateOf("") }
    var previewText by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "未知文件"
                fileName = name
                selectedUri = uri
                previewText = kbService.previewDocument(uri) ?: "(无法预览)"
                stage = 1
            }
        }
    }

    Column {
        if (stage == 0) {
            Text("支持 PDF、DOCX、EPUB、PPTX、TXT 格式", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { filePicker.launch(arrayOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/epub+zip",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "text/plain",
                )) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.File02, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("选择文件")
            }
        } else if (stage == 1) {
            Text(fileName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = previewText,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    stage = 2
                    status = "正在导入..."
                    scope.launch {
                        val id = kbService.importFile(selectedUri!!, fileName)
                        if (id != null) {
                            status = "导入完成"
                            onDone()
                        } else {
                            status = "导入失败：无法读取文件内容"
                            stage = 1
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (status.startsWith("导入失败")) status else "确认导入")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---- 聊天记录导入 ----

@Composable
private fun ChatHistoryImportContent(
    kbService: KnowledgeBaseService,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit,
) {
    val conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository = koinInject()
    val conversations by conversationRepo.searchConversationsLight("")
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    if (conversations.isEmpty()) {
        Text("没有聊天记录可导入", style = MaterialTheme.typography.bodyMedium)
        return
    }

    LazyColumn(
        modifier = Modifier.heightIn(max = 300.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(conversations.take(20), key = { it.id.toString() }) { conv ->
            Surface(
                onClick = {
                    if (!importing) {
                        importing = true
                        status = "正在导入..."
                        scope.launch {
                            val fullConv = conversationRepo.getConversationById(conv.id)
                            val id = if (fullConv != null) {
                                kbService.importChatHistory(fullConv, null)
                            } else null
                            importing = false
                            if (id != null) {
                                status = "导入完成"
                                onDone()
                            } else {
                                status = "导入失败：聊天记录为空"
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(HugeIcons.BubbleChatQuestion, contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = conv.title.ifBlank { "未命名对话" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${conv.messageNodes.size} 条消息",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (importing) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---- 笔记导入 ----

@Composable
private fun TextNoteImportContent(
    kbService: KnowledgeBaseService,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Column {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("标题") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text("内容") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            maxLines = 10,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (content.isNotBlank()) {
                    importing = true
                    status = "正在导入..."
                    scope.launch {
                        val id = kbService.importText(
                            title = title.ifBlank { "笔记" },
                            text = content,
                        )
                        importing = false
                        if (id != null) {
                            status = "导入完成"
                            onDone()
                        } else {
                            status = "导入失败：内容为空"
                        }
                    }
                }
            },
            enabled = content.isNotBlank() && !importing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (importing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Icon(HugeIcons.Note, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (importing) status else "保存")
        }
    }
}

// ---- 批量文件夹导入 ----

@Composable
private fun BatchFolderImportContent(
    kbService: KnowledgeBaseService,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit,
    assistantId: String?,
) {
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var importedCount by remember { mutableStateOf(0) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            importing = true
            status = "正在批量导入..."
            scope.launch {
                val folderName = uri.lastPathSegment?.substringAfterLast('/') ?: "文件夹"
                val count = kbService.importFolder(uri, folderName, assistantId = assistantId)
                importedCount = count
                importing = false
                status = if (count > 0) "导入完成：$count 个文件" else "未找到支持的文档"
                if (count > 0) onDone()
            }
        }
    }

    Column {
        Text("选择一个文件夹，自动导入里面所有 PDF/DOCX/EPUB/TXT 文件", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { folderPicker.launch(null) },
            enabled = !importing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (importing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Icon(HugeIcons.Folder02, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (importing) status else "选择文件夹")
        }
        if (importedCount > 0) {
            Spacer(Modifier.height(4.dp))
            Text("已导入 $importedCount 个文件", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
