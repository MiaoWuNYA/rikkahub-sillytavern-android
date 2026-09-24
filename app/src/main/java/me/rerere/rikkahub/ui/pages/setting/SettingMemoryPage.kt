package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
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
    @StringRes val label: Int,
    @StringRes val description: Int,
    @StringRes val cost: Int,
) {
    NATURAL(R.string.setting_memory_strategy_natural, R.string.setting_memory_strategy_natural_desc, R.string.setting_memory_strategy_natural_cost),
    SAVER(R.string.setting_memory_strategy_saver, R.string.setting_memory_strategy_saver_desc, R.string.setting_memory_strategy_saver_cost),
    STRONG(R.string.setting_memory_strategy_strong, R.string.setting_memory_strategy_strong_desc, R.string.setting_memory_strategy_strong_cost),
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
            title = { Text(stringResource(R.string.setting_memory_clear_recent_title)) },
            text = { Text(stringResource(R.string.setting_memory_clear_recent_message)) },
            confirmButton = {
                TextButton(onClick = {
                    store.clearAssistant(assistant.id.toString())
                    recentRefresh++
                    confirmClear = false
                }) { Text(stringResource(R.string.setting_memory_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_memory_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (assistant == null) {
            Column(Modifier.padding(padding).padding(24.dp)) { Text(stringResource(R.string.setting_memory_no_assistant)) }
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
                Text(stringResource(R.string.setting_memory_header), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(
                        R.string.setting_memory_header_desc,
                        assistant.name.ifBlank { stringResource(R.string.setting_memory_ta) }
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Select(
                    options = assistants,
                    selectedOption = assistant,
                    onOptionSelected = { selectedId = it.id.toString() },
                    optionToString = { it.name.ifBlank { stringResource(R.string.setting_memory_unnamed_assistant) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.setting_memory_fixed_section)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_memory_role_card)) },
                        supportingContent = {
                            Text(
                                if (rolePrompt.isBlank()) stringResource(R.string.setting_memory_role_card_empty)
                                else stringResource(R.string.setting_memory_role_card_managed)
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
                    label = { Text(stringResource(R.string.setting_memory_fixed_memory)) },
                    supportingText = { Text(stringResource(R.string.setting_memory_fixed_memory_desc)) },
                )
                Button(
                    onClick = {
                        updateAssistant { it.copy(systemPrompt = it.systemPrompt.withFixedMemory(fixedDraft)) }
                    },
                    enabled = fixedDraft.trim() != assistant.systemPrompt.extractFixedMemory(),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.setting_memory_save_fixed)) }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.setting_memory_recent_section)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_memory_recent)) },
                        supportingContent = {
                            Text(
                                when {
                                    !assistant.enableCrossWindowMemory -> stringResource(R.string.setting_memory_recent_disabled)
                                    summary != null -> stringResource(R.string.setting_memory_recent_summarized, recent.size)
                                    recent.isNotEmpty() -> stringResource(R.string.setting_memory_recent_count, recent.size)
                                    else -> stringResource(R.string.setting_memory_recent_empty)
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
                            Text(stringResource(R.string.setting_memory_summary_title), fontWeight = FontWeight.SemiBold)
                            Text(savedSummary.text)
                            Text(
                                stringResource(R.string.setting_memory_summary_time, formatMemoryTime(savedSummary.updatedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (recent.isNotEmpty()) {
                    Text(
                        stringResource(R.string.setting_memory_recent_raw),
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
                                    stringResource(
                                        R.string.setting_memory_entry_meta,
                                        if (entry.role == "user") stringResource(R.string.setting_memory_you)
                                        else assistant.name.ifBlank { stringResource(R.string.setting_memory_ta) },
                                        formatMemoryTime(entry.timestamp),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { recentRefresh++ }) { Text(stringResource(R.string.setting_memory_refresh)) }
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = recent.isNotEmpty() || summary != null,
                    ) { Text(stringResource(R.string.setting_memory_clear_recent_button)) }
                }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.setting_memory_long_term_section)) }) {
                    item(
                        onClick = { nav.navigate(Screen.AssistantMemory(assistantId)) },
                        headlineContent = { Text(stringResource(R.string.setting_memory_manage_long_term)) },
                        supportingContent = { Text(stringResource(R.string.setting_memory_manage_long_term_desc)) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_memory_enable_long_term)) },
                        supportingContent = {
                            Text(
                                if (assistant.enableMemory) stringResource(R.string.setting_memory_long_term_on)
                                else stringResource(R.string.setting_memory_long_term_off)
                            )
                        },
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
                Text(stringResource(R.string.setting_memory_strategy_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        R.string.setting_memory_strategy_current,
                        strategy?.let { stringResource(it.label) } ?: stringResource(R.string.setting_memory_strategy_custom)
                    ),
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
                    Text(
                        if (advancedExpanded) stringResource(R.string.setting_memory_advanced_collapse)
                        else stringResource(R.string.setting_memory_advanced)
                    )
                }
                if (advancedExpanded) {
                    CardGroup(title = { Text(stringResource(R.string.setting_memory_how_it_works)) }) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_memory_three_layer)) },
                            supportingContent = { Text(stringResource(R.string.setting_memory_three_layer_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = assistant.enableThreeLayerMemory,
                                    onCheckedChange = { enabled -> updateAssistant { it.copy(enableThreeLayerMemory = enabled) } },
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_memory_background_compress)) },
                            supportingContent = { Text(stringResource(R.string.setting_memory_background_compress_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = assistant.enableCrossWindowMemoryCompression,
                                    onCheckedChange = { enabled -> updateAssistant { it.copy(enableCrossWindowMemoryCompression = enabled) } },
                                )
                            },
                        )
                    }

                    Text(stringResource(R.string.setting_memory_custom_params), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                    MemoryNumericField(stringResource(R.string.setting_memory_param_threshold), thresholdDraft) { thresholdDraft = digitsOnly(it) }
                    MemoryNumericField(stringResource(R.string.setting_memory_param_tail), tailDraft) { tailDraft = digitsOnly(it) }
                    MemoryNumericField(stringResource(R.string.setting_memory_param_recall), recallDraft) { recallDraft = digitsOnly(it) }
                    MemoryNumericField(stringResource(R.string.setting_memory_param_recall_chars), recallCharsDraft) { recallCharsDraft = digitsOnly(it) }
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
                    ) { Text(stringResource(R.string.setting_memory_save_custom_params)) }
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
            Text(stringResource(strategy.label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(strategy.description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(strategy.cost), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
