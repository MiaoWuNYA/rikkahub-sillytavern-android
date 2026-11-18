package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SelectiveLogic
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.IntTextField
import me.rerere.rikkahub.ui.components.ui.NullableIntTextField
import me.rerere.ui.components.FormItem

/** Single source of layout, controls and help text for individual and batch entry editors. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RegexEntryEditorFields(entry: PromptInjection.RegexInjection, onEdit: (PromptInjection.RegexInjection) -> Unit,
    bulk: LorebookBulkEditState? = null, modifier: Modifier = Modifier) {
    var newKeyword by remember { mutableStateOf("") }
    var newSecKey by remember { mutableStateOf("") }
    Column(
        modifier = modifier
        .fillMaxWidth().testTag("regex-entry-editor-fields")
        .verticalScroll(rememberScrollState())
        .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (bulk == null) {
            OutlinedTextField(
                value = entry.name,
                onValueChange = { onEdit(entry.copy(name = it)) },
                label = { Text(stringResource(R.string.prompt_page_name)) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        EntrySettingRegion(EntrySettingKey.ENABLED, entry, onEdit, bulk) { update ->
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_enabled)) },
                tail = {
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = { update(entry.copy(enabled = it)) }
                    )
                }
            )
        }

        EntrySettingRegion(EntrySettingKey.PRIORITY, entry, onEdit, bulk) { update ->
            IntTextField(
                value = entry.priority,
                onValueChange = { update(entry.copy(priority = it)) },
                label = { Text(stringResource(R.string.prompt_page_priority_label)) },
                modifier = Modifier.fillMaxWidth().then(if (bulk != null) Modifier.testTag("bulk-priority") else Modifier),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        EntrySettingRegion(EntrySettingKey.POSITION, entry, onEdit, bulk) { update ->
            Text(
                stringResource(R.string.prompt_page_injection_position),
                style = MaterialTheme.typography.titleSmall
            )
            InjectionPositionSelector(
                position = entry.position,
                onSelect = { update(entry.copy(position = it)) }
            )
        }

        // 深度 (depth) — 始终显示
        EntrySettingRegion(EntrySettingKey.DEPTH, entry, onEdit, bulk) { update ->
            IntTextField(
                value = entry.injectDepth,
                onValueChange = { update(entry.copy(injectDepth = it)) },
                label = { Text(stringResource(R.string.prompt_page_inject_depth)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        // 关键词
        if (bulk == null) {
            Text(stringResource(R.string.prompt_page_keywords_label), style = MaterialTheme.typography.titleSmall)
        }
        if (bulk == null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                entry.keywords.forEach { keyword ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(keyword) },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    onEdit(entry.copy(keywords = entry.keywords - keyword))
                                },
                                modifier = Modifier.size(16.dp)
                            ) {
                                Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp))
                            }
                        }
                    )
                }
            }
        }
        if (bulk == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newKeyword,
                    onValueChange = { newKeyword = it },
                    label = { Text(stringResource(R.string.prompt_page_new_keyword)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(
                    onClick = {
                        if (newKeyword.isNotBlank()) {
                            onEdit(entry.copy(keywords = entry.keywords + newKeyword.trim()))
                            newKeyword = ""
                        }
                    }
                ) {
                    Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add))
                }
            }
        }

        if (bulk == null) {
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_use_regex)) },
                tail = {
                    Switch(
                        checked = entry.useRegex,
                        onCheckedChange = { onEdit(entry.copy(useRegex = it)) }
                    )
                }
            )
        }

        if (bulk == null) {
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_case_sensitive)) },
                tail = {
                    Switch(
                        checked = entry.caseSensitive,
                        onCheckedChange = { onEdit(entry.copy(caseSensitive = it)) }
                    )
                }
            )
        }

        if (bulk == null) {
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_match_whole_words)) },
                tail = {
                    Switch(
                        checked = entry.matchWholeWords,
                        onCheckedChange = { onEdit(entry.copy(matchWholeWords = it)) }
                    )
                }
            )
        }

        EntrySettingRegion(EntrySettingKey.EXCLUDE_RECURSION, entry, onEdit, bulk) { update ->
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_exclude_recursion)) },
                tail = {
                    Switch(
                        checked = entry.excludeRecursion,
                        onCheckedChange = { update(entry.copy(excludeRecursion = it)) }
                    )
                }
            )
        }

        EntrySettingRegion(EntrySettingKey.PREVENT_RECURSION, entry, onEdit, bulk) { update ->
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_prevent_recursion)) },
                tail = {
                    Switch(
                        checked = entry.preventRecursion,
                        onCheckedChange = { update(entry.copy(preventRecursion = it)) }
                    )
                }
            )
        }

        EntrySettingRegion(EntrySettingKey.DELAY_RECURSION, entry, onEdit, bulk) { update ->
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_delay_until_recursion)) },
                tail = {
                    Switch(
                        checked = entry.delayUntilRecursion > 0,
                        onCheckedChange = { update(entry.copy(delayUntilRecursion = if (it) 1 else 0)) }
                    )
                }
            )
            AnimatedVisibility(visible = entry.delayUntilRecursion > 0) {
                IntTextField(
                    value = entry.delayUntilRecursion,
                    onValueChange = { update(entry.copy(delayUntilRecursion = it)) },
                    validate = { it > 0 },
                    label = { Text(stringResource(R.string.prompt_page_delay_until_recursion_level)) },
                    supportingText = { Text(stringResource(R.string.prompt_page_delay_until_recursion_level_desc)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 次级关键词

        if (bulk == null) {
            Text(stringResource(R.string.prompt_page_secondary_keys_label), style = MaterialTheme.typography.titleSmall)
        }
        if (bulk == null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                entry.secondaryKeys.forEach { keyword ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(keyword) },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    onEdit(entry.copy(secondaryKeys = entry.secondaryKeys - keyword))
                                },
                                modifier = Modifier.size(16.dp)
                            ) {
                                Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp))
                            }
                        }
                    )
                }
            }
        }
        if (bulk == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newSecKey,
                    onValueChange = { newSecKey = it },
                    label = { Text(stringResource(R.string.prompt_page_new_keyword)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(
                    onClick = {
                        if (newSecKey.isNotBlank()) {
                            onEdit(entry.copy(secondaryKeys = entry.secondaryKeys + newSecKey.trim()))
                            newSecKey = ""
                        }
                    }
                ) {
                    Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add))
                }
            }
        }

        // Selective mode
        if (bulk == null) {
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_selective)) },
                description = { Text(stringResource(R.string.prompt_page_selective_desc)) },
                tail = {
                    Switch(
                        checked = entry.selective,
                        onCheckedChange = { onEdit(entry.copy(selective = it)) }
                    )
                }
            )
        }

        // 当 selective 启用时，显示 selectiveLogic
        if (bulk == null) {
            AnimatedVisibility(visible = entry.selective) {
                CardGroup(title = { Text(stringResource(R.string.prompt_page_selective_logic)) }) {
                    // 官方 world-info.js 仅 4 档：AND_ANY / AND_ALL / NOT_ALL / NOT_ANY（主键必须先命中）
                    listOf(
                        SelectiveLogic.AND_ANY,
                        SelectiveLogic.AND_ALL,
                        SelectiveLogic.NOT_ALL,
                        SelectiveLogic.NOT_ANY,
                    ).forEach { logic ->
                        item(
                            onClick = { onEdit(entry.copy(selectiveLogic = logic)) },
                            headlineContent = {
                                Text(
                                    when (logic) {
                                        SelectiveLogic.AND_ANY -> stringResource(R.string.prompt_page_logic_and_any)
                                        SelectiveLogic.AND_ALL -> stringResource(R.string.prompt_page_logic_and_all)
                                        SelectiveLogic.NOT_ALL -> stringResource(R.string.prompt_page_logic_not_all)
                                        SelectiveLogic.NOT_ANY -> stringResource(R.string.prompt_page_logic_not_any)
                                        SelectiveLogic.OR_ANY -> stringResource(R.string.prompt_page_logic_or_any)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            trailingContent = {
                                RadioButton(
                                    selected = entry.selectiveLogic == logic,
                                    onClick = { onEdit(entry.copy(selectiveLogic = logic)) },
                                )
                            },
                        )
                    }
                }
            }
        }

        // 分组设置
        if (bulk == null) {
            OutlinedTextField(
                value = entry.group,
                onValueChange = { onEdit(entry.copy(group = it)) },
                label = { Text(stringResource(R.string.prompt_page_group)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        if (bulk == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntTextField(
                    value = entry.groupWeight,
                    onValueChange = { onEdit(entry.copy(groupWeight = it)) },
                    label = { Text(stringResource(R.string.prompt_page_group_weight)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                FormItem(
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.prompt_page_group_override)) },
                    tail = {
                        Switch(
                            checked = entry.groupOverride,
                            onCheckedChange = { onEdit(entry.copy(groupOverride = it)) }
                        )
                    }
                )
            }
        }

        // 概率
        EntrySettingRegion(EntrySettingKey.USE_PROBABILITY, entry, onEdit, bulk) { update ->
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_probability_label)) },
                tail = {
                    Switch(
                        checked = entry.useProbability,
                        onCheckedChange = { update(entry.copy(useProbability = it)) }
                    )
                }
            )
        }
        EntrySettingRegion(EntrySettingKey.PROBABILITY, entry, onEdit, bulk) { update ->
            AnimatedVisibility(visible = bulk != null || entry.useProbability) {
                Column {
                    Text(
                        stringResource(R.string.prompt_page_probability, entry.probability),
                        style = MaterialTheme.typography.titleSmall
                    )
                    var localProb by remember { mutableFloatStateOf(entry.probability.toFloat()) }
                    Slider(
                        value = localProb,
                        onValueChange = { localProb = it },
                        onValueChangeFinished = { update(entry.copy(probability = localProb.toInt())) },
                        valueRange = 0f..100f,
                        steps = 99
                    )
                }
            }
        }

        // 粘性 + 冷却
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EntrySettingRegion(EntrySettingKey.STICKY, entry, onEdit, bulk, Modifier.weight(1f)) { update ->
                IntTextField(
                    value = entry.sticky,
                    onValueChange = { update(entry.copy(sticky = it)) },
                    label = { Text(stringResource(R.string.prompt_page_sticky)) },
                    supportingText = { Text(stringResource(R.string.prompt_page_sticky_desc)) },
                    suffix = { Text(stringResource(R.string.prompt_page_rounds_suffix), style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
            EntrySettingRegion(EntrySettingKey.COOLDOWN, entry, onEdit, bulk, Modifier.weight(1f)) { update ->
                IntTextField(
                    value = entry.cooldown,
                    onValueChange = { update(entry.copy(cooldown = it)) },
                    label = { Text(stringResource(R.string.prompt_page_cooldown)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        }
        EntrySettingRegion(EntrySettingKey.DELAY, entry, onEdit, bulk) { update ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntTextField(
                    value = entry.delay,
                    onValueChange = { update(entry.copy(delay = it)) },
                    label = { Text(stringResource(R.string.prompt_page_delay)) },
                    supportingText = { Text(stringResource(R.string.prompt_page_delay_until_recursion_desc)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Spacer(Modifier.weight(1f))
            }
        }

        EntrySettingRegion(EntrySettingKey.CONSTANT_ACTIVE, entry, onEdit, bulk) { update ->
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_constant_active)) },
                description = { Text(stringResource(R.string.prompt_page_constant_active_desc)) },
                tail = {
                    Switch(
                        checked = entry.constantActive,
                        onCheckedChange = { update(entry.copy(constantActive = it)) }
                    )
                }
            )
        }

        // 向量检索激活（官方 vectorized）：开启后按语义相似度激活，无关键词也可
        EntrySettingRegion(EntrySettingKey.VECTORIZED, entry, onEdit, bulk) { update ->
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_vectorized)) },
                description = { Text(stringResource(R.string.prompt_page_vectorized_desc)) },
                tail = {
                    Switch(
                        checked = entry.vectorized,
                        onCheckedChange = { update(entry.copy(vectorized = it)) }
                    )
                }
            )
        }

        EntrySettingRegion(EntrySettingKey.SCAN_DEPTH, entry, onEdit, bulk) { update ->
            NullableIntTextField(
                value = entry.scanDepth,
                onValueChange = { update(entry.copy(scanDepth = it)) },
                label = { Text(stringResource(R.string.prompt_page_scan_depth)) },
                placeholder = { Text(stringResource(R.string.prompt_page_scan_depth_global)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 条目级组评分开关（官方 extensions.use_group_scoring）
        EntrySettingRegion(EntrySettingKey.GROUP_SCORING, entry, onEdit, bulk) { update ->
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_use_group_scoring)) },
                description = { Text(stringResource(R.string.prompt_page_use_group_scoring_desc)) },
                tail = {
                    Switch(
                        checked = entry.useGroupScoring,
                        onCheckedChange = { update(entry.copy(useGroupScoring = it)) }
                    )
                }
            )
        }

        // 忽略预算（官方 extensions.ignore_budget）：溢出时仍注入
        EntrySettingRegion(EntrySettingKey.IGNORE_BUDGET, entry, onEdit, bulk) { update ->
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_ignore_budget)) },
                description = { Text(stringResource(R.string.prompt_page_ignore_budget_desc)) },
                tail = {
                    Switch(
                        checked = entry.ignoreBudget,
                        onCheckedChange = { update(entry.copy(ignoreBudget = it)) }
                    )
                }
            )
        }

        // Automation ID（官方 extensions.automation_id，仅保存透传）
        if (bulk == null) {
            OutlinedTextField(
                value = entry.automationId,
                onValueChange = { onEdit(entry.copy(automationId = it)) },
                label = { Text(stringResource(R.string.prompt_page_automation_id)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // 扫描范围（官方 extensions.match_*：把角色卡/人设字段纳入关键词扫描源）
        if (bulk == null) {
            Text(stringResource(R.string.prompt_page_match_scope), style = MaterialTheme.typography.titleSmall)
        }
        if (bulk == null) {
            listOf(
                "matchPersonaDescription" to stringResource(R.string.prompt_page_match_persona),
                "matchCharacterDescription" to stringResource(R.string.prompt_page_match_char_description),
                "matchCharacterPersonality" to stringResource(R.string.prompt_page_match_char_personality),
                "matchCharacterDepthPrompt" to stringResource(R.string.prompt_page_match_char_depth_prompt),
                "matchScenario" to stringResource(R.string.prompt_page_match_scenario),
                "matchCreatorNotes" to stringResource(R.string.prompt_page_match_creator_notes),
            ).forEach { (field, label) ->
                val checked = when (field) {
                    "matchPersonaDescription" -> entry.matchPersonaDescription
                    "matchCharacterDescription" -> entry.matchCharacterDescription
                    "matchCharacterPersonality" -> entry.matchCharacterPersonality
                    "matchCharacterDepthPrompt" -> entry.matchCharacterDepthPrompt
                    "matchScenario" -> entry.matchScenario
                    else -> entry.matchCreatorNotes
                }
                FormItem(
                    label = { Text(label) },
                    tail = {
                        Switch(
                            checked = checked,
                            onCheckedChange = {
                                onEdit(
                                    when (field) {
                                        "matchPersonaDescription" -> entry.copy(matchPersonaDescription = it)
                                        "matchCharacterDescription" -> entry.copy(matchCharacterDescription = it)
                                        "matchCharacterPersonality" -> entry.copy(matchCharacterPersonality = it)
                                        "matchCharacterDepthPrompt" -> entry.copy(matchCharacterDepthPrompt = it)
                                        "matchScenario" -> entry.copy(matchScenario = it)
                                        else -> entry.copy(matchCreatorNotes = it)
                                    }
                                )
                            }
                        )
                    }
                )
            }
        }

        EntrySettingRegion(EntrySettingKey.ROLE, entry, onEdit, bulk) { update ->
            AnimatedVisibility(visible = bulk != null || entry.position.usesStandaloneMessage()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.prompt_page_injection_role),
                        style = MaterialTheme.typography.titleSmall
                    )
                    InjectionRoleSelector(
                        role = entry.role,
                        onSelect = { update(entry.copy(role = it)) },
                        // 对话框底色和默认的按钮底色一样，换一个能分开的
                        containerColor = MaterialTheme.colorScheme.surfaceBright,
                    )
                }
            }
        }

        if (bulk == null) {
            OutlinedTextField(
                value = entry.content,
                onValueChange = { onEdit(entry.copy(content = it)) },
                label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
                minLines = 4
            )
        }
    }
}
