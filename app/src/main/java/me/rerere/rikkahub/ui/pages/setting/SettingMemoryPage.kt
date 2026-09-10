package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.memory.CrossWindowMemoryStore
import me.rerere.rikkahub.data.memory.extractFixedMemory
import me.rerere.rikkahub.data.memory.withFixedMemory
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.text.DateFormat
import java.util.Date

private enum class MemoryStrategy(
    val label: String,
    val description: String,
    val cost: String,
) {
    NATURAL("自然 · 推荐", "近期经历保留适中，重要旧记忆按需想起。", "中等记忆量 · 中等 Token"),
    SAVER("省 Token", "更积极整理旧内容，只保留较少近期细节。", "较少记忆量 · 较低 Token"),
    STRONG("记得更多", "保留更多近期细节，也更容易想起过去。", "更多记忆量 · 较高 Token"),
}

@Composable
fun SettingMemoryPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val context = LocalContext.current
    val store = remember { CrossWindowMemoryStore(context) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val assistants = settings.assistants
    val assistant = assistants.firstOrNull { it.id.toString() == selectedId } ?: assistants.firstOrNull()
    var recentRefresh by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun updateAssistant(transform: (Assistant) -> Assistant) {
        val current = assistant ?: return
        vm.updateSettings(settings.copy(assistants = assistants.map { if (it.id == current.id) transform(it) else it }))
    }

    if (confirmClear && assistant != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空最近发生的事？") },
            text = { Text("只会清除此角色的近期原文和已整理摘要，不会删除角色设定、固定记忆或长期记忆。") },
            confirmButton = {
                TextButton(onClick = {
                    store.clearAssistant(assistant.id.toString())
                    recentRefresh++
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("记忆") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (assistant == null) {
            Column(Modifier.padding(padding).padding(24.dp)) { Text("请先创建一个助手。") }
            return@Scaffold
        }

        val strategy = detectMemoryStrategy(assistant)
        val assistantId = assistant.id.toString()
        val recent = remember(assistant.id, recentRefresh) { store.peekRecent(assistantId, 30).reversed() }
        val summary = remember(assistant.id, recentRefresh) { store.peekSummary(assistantId) }
        var fixedDraft by remember(assistant.id, assistant.systemPrompt) {
            mutableStateOf(assistant.systemPrompt.extractFixedMemory())
        }
        val rolePrompt = remember(assistant.id, assistant.systemPrompt) {
            assistant.systemPrompt.withFixedMemory("").trim()
        }
        var thresholdDraft by remember(assistant.id, assistant.crossWindowMemoryCompressionThresholdChars) {
            mutableStateOf(assistant.crossWindowMemoryCompressionThresholdChars.toString())
        }
        var tailDraft by remember(assistant.id, assistant.crossWindowMemoryTailEntries) {
            mutableStateOf(assistant.crossWindowMemoryTailEntries.toString())
        }
        var recallDraft by remember(assistant.id, assistant.longTermMemoryRecallCount) {
            mutableStateOf(assistant.longTermMemoryRecallCount.toString())
        }
        var recallCharsDraft by remember(assistant.id, assistant.longTermMemoryMaxChars) {
            mutableStateOf(assistant.longTermMemoryMaxChars.toString())
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("TA 的记忆", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${assistant.name.ifBlank { "TA" }} 会把与你有关的内容分层保存。你可以随时查看、修改或删除。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Select(
                    options = assistants,
                    selectedOption = assistant,
                    onOptionSelected = { selectedId = it.id.toString() },
                    optionToString = { it.name.ifBlank { "未命名助手" } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                CardGroup(title = { Text("🧷 不会忘的事") }) {
                    item(
                        headlineContent = { Text("角色设定") },
                        supportingContent = {
                            Text(
                                if (rolePrompt.isBlank()) "角色设定暂时为空。请在助手设置中维护角色卡。"
                                else "角色卡由助手设置维护。这个记忆页面不会覆盖它。"
                            )
                        },
                    )
                }
                if (rolePrompt.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Text(
                            rolePrompt,
                            modifier = Modifier.padding(14.dp),
                            maxLines = 4,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = fixedDraft,
                    onValueChange = { fixedDraft = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    minLines = 4,
                    label = { Text("固定记忆") },
                    supportingText = { Text("关系、约定、稳定偏好等。只修改这里，不会覆盖角色卡。") },
                )
                Button(
                    onClick = {
                        updateAssistant { it.copy(systemPrompt = it.systemPrompt.withFixedMemory(fixedDraft)) }
                    },
                    enabled = fixedDraft.trim() != assistant.systemPrompt.extractFixedMemory(),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("保存固定记忆") }
            }

            item {
                CardGroup(title = { Text("🌿 最近发生的事") }) {
                    item(
                        headlineContent = { Text("近期记忆") },
                        supportingContent = {
                            Text(
                                when {
                                    !assistant.enableCrossWindowMemory -> "已关闭。不同聊天窗口不会共享近期生活。"
                                    summary != null -> "${recent.size} 条近期原文 · 更早内容已整理成摘要"
                                    recent.isNotEmpty() -> "${recent.size} 条近期原文 · 还没有需要整理的旧内容"
                                    else -> "还没有近期内容。开始聊天后会自动记录。"
                                }
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = assistant.enableCrossWindowMemory,
                                onCheckedChange = { enabled -> updateAssistant { it.copy(enableCrossWindowMemory = enabled) } },
                            )
                        },
                    )
                }

                summary?.let { savedSummary ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("较早内容摘要", fontWeight = FontWeight.SemiBold)
                            Text(savedSummary.text)
                            Text(
                                "整理于 ${formatMemoryTime(savedSummary.updatedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (recent.isNotEmpty()) {
                    Text(
                        "近期原文",
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    recent.take(8).forEach { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.text, maxLines = 4)
                                Text(
                                    "${if (entry.role == "user") "你" else assistant.name.ifBlank { "TA" }} · ${formatMemoryTime(entry.timestamp)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { recentRefresh++ }) { Text("刷新") }
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = recent.isNotEmpty() || summary != null,
                    ) { Text("清空最近发生的事") }
                }
            }

            item {
                CardGroup(title = { Text("📚 很久以前的事") }) {
                    item(
                        onClick = { nav.navigate(Screen.AssistantMemory(assistantId)) },
                        headlineContent = { Text("管理长期记忆") },
                        supportingContent = { Text("逐条新增、修改或删除。聊天时只会想起与当前内容相关的部分。") },
                    )
                    item(
                        headlineContent = { Text("启用长期记忆") },
                        supportingContent = { Text(if (assistant.enableMemory) "需要时会从长期记忆里找相关内容。" else "目前不会主动召回长期记忆。") },
                        trailingContent = {
                            Switch(
                                checked = assistant.enableMemory,
                                onCheckedChange = { enabled -> updateAssistant { it.copy(enableMemory = enabled) } },
                            )
                        },
                    )
                }
            }

            item {
                Text("记忆方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "当前：${strategy?.label ?: "自定义"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                MemoryStrategy.entries.forEach { preset ->
                    MemoryPresetCard(
                        strategy = preset,
                        selected = strategy == preset,
                        onClick = { updateAssistant { applyMemoryStrategy(it, preset) } },
                    )
                }
            }

            item {
                TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                    Text(if (advancedExpanded) "收起高级设置" else "高级设置")
                }
                if (advancedExpanded) {
                    CardGroup(title = { Text("工作方式") }) {
                        item(
                            headlineContent = { Text("三层记忆") },
                            supportingContent = { Text("固定记忆 + 近期生活流 + 长期记忆按需召回") },
                            trailingContent = {
                                Switch(
                                    checked = assistant.enableThreeLayerMemory,
                                    onCheckedChange = { enabled -> updateAssistant { it.copy(enableThreeLayerMemory = enabled) } },
                                )
                            },
                        )
                        item(
                            headlineContent = { Text("后台整理近期内容") },
                            supportingContent = { Text("超过阈值后，把较早的原文压缩成摘要。") },
                            trailingContent = {
                                Switch(
                                    checked = assistant.enableCrossWindowMemoryCompression,
                                    onCheckedChange = { enabled -> updateAssistant { it.copy(enableCrossWindowMemoryCompression = enabled) } },
                                )
                            },
                        )
                    }

                    Text("自定义参数", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                    MemoryNumericField("多少字符后整理旧内容", thresholdDraft) { thresholdDraft = digitsOnly(it) }
                    MemoryNumericField("保留多少条近期原文", tailDraft) { tailDraft = digitsOnly(it) }
                    MemoryNumericField("每次最多召回多少条长期记忆", recallDraft) { recallDraft = digitsOnly(it) }
                    MemoryNumericField("长期记忆每轮最多字符", recallCharsDraft) { recallCharsDraft = digitsOnly(it) }
                    Button(
                        onClick = {
                            val threshold = thresholdDraft.toIntOrNull()?.coerceIn(1000, 100000) ?: return@Button
                            val tail = tailDraft.toIntOrNull()?.coerceIn(2, 100) ?: return@Button
                            val recall = recallDraft.toIntOrNull()?.coerceIn(1, 50) ?: return@Button
                            val recallChars = recallCharsDraft.toIntOrNull()?.coerceIn(500, 20000) ?: return@Button
                            updateAssistant {
                                it.copy(
                                    enableThreeLayerMemory = true,
                                    crossWindowMemoryCompressionThresholdChars = threshold,
                                    crossWindowMemoryTailEntries = tail,
                                    longTermMemoryRecallCount = recall,
                                    longTermMemoryMaxChars = recallChars,
                                )
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("保存自定义参数") }
                }
            }
        }
    }
}

@Composable
private fun MemoryPresetCard(strategy: MemoryStrategy, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(strategy.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(strategy.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(strategy.cost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MemoryNumericField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
        label = { Text(label) },
    )
}

private fun digitsOnly(value: String): String = value.filter(Char::isDigit).take(6)

private fun formatMemoryTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

private fun detectMemoryStrategy(a: Assistant): MemoryStrategy? = when {
    a.crossWindowMemoryCompressionThresholdChars == 8000 && a.crossWindowMemoryTailEntries == 10 && a.longTermMemoryRecallCount == 4 && a.longTermMemoryMaxChars == 1800 -> MemoryStrategy.SAVER
    a.crossWindowMemoryCompressionThresholdChars == 20000 && a.crossWindowMemoryTailEntries == 24 && a.longTermMemoryRecallCount == 10 && a.longTermMemoryMaxChars == 5000 -> MemoryStrategy.STRONG
    a.crossWindowMemoryCompressionThresholdChars == 12000 && a.crossWindowMemoryTailEntries == 16 && a.longTermMemoryRecallCount == 6 && a.longTermMemoryMaxChars == 3000 -> MemoryStrategy.NATURAL
    else -> null
}

private fun applyMemoryStrategy(a: Assistant, strategy: MemoryStrategy): Assistant = when (strategy) {
    MemoryStrategy.NATURAL -> a.copy(
        enableThreeLayerMemory = true,
        enableCrossWindowMemory = true,
        enableCrossWindowMemoryCompression = true,
        crossWindowMemoryCompressionThresholdChars = 12000,
        crossWindowMemoryTailEntries = 16,
        longTermMemoryRecallCount = 6,
        longTermMemoryMaxChars = 3000,
    )
    MemoryStrategy.SAVER -> a.copy(
        enableThreeLayerMemory = true,
        enableCrossWindowMemory = true,
        enableCrossWindowMemoryCompression = true,
        crossWindowMemoryCompressionThresholdChars = 8000,
        crossWindowMemoryTailEntries = 10,
        longTermMemoryRecallCount = 4,
        longTermMemoryMaxChars = 1800,
    )
    MemoryStrategy.STRONG -> a.copy(
        enableThreeLayerMemory = true,
        enableCrossWindowMemory = true,
        enableCrossWindowMemoryCompression = true,
        crossWindowMemoryCompressionThresholdChars = 20000,
        crossWindowMemoryTailEntries = 24,
        longTermMemoryRecallCount = 10,
        longTermMemoryMaxChars = 5000,
    )
}
