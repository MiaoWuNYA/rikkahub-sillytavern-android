@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.TavernBookEntry
import me.rerere.rikkahub.data.model.TavernEmbeddedBook
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.IntTextField
import me.rerere.rikkahub.ui.components.ui.InsertionStrategySelector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.MapPin
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.rikkahub.ui.theme.CustomColors
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 酒馆角色卡信息面板 — 简洁高级，分层展示
 */
@Composable
fun TavernCharacterCard(
    assistant: Assistant,
    modifier: Modifier = Modifier,
    onAssistantUpdate: ((Assistant) -> Unit)? = null,
    settings: Settings? = null,
    onSettingsUpdate: ((Settings) -> Unit)? = null,
    onExport: (() -> Unit)? = null,
) {
    val tav = assistant.tavernData ?: return
    val displayName = tav.name.ifBlank { assistant.name.ifBlank { "角色卡" } }
    val subParts = buildList {
        add(tav.spec.removePrefix("chara_card_").uppercase())
        if (tav.embeddedBook != null) add("世界书 ${tav.embeddedBook!!.entries.size} 条")
        if (tav.alternateGreetings.isNotEmpty()) add("开场白 ${1 + tav.alternateGreetings.size} 个")
    }
    val subInfo = subParts.joinToString(" · ")
    var expanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column {
            // 紧凑头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = rotationAngle },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    HugeIcons.Book01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = tav.spec.removePrefix("chara_card_").uppercase(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    // 副信息：spec · 世界书条数 · 开场白数
                    Text(
                        text = subInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 统计行 — 图标徽章表示有无内容
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        IconStatBadge(HugeIcons.File01, tav.description.isNotBlank())
                        IconStatBadge(HugeIcons.UserCircle, tav.personality.isNotBlank())
                        IconStatBadge(HugeIcons.MapPin, tav.scenario.isNotBlank())
                        IconStatBadge(HugeIcons.Message01, tav.mesExample.isNotBlank())
                        if (tav.embeddedBook != null) {
                            IconStatBadge(
                                icon = HugeIcons.Book01,
                                active = true,
                                count = tav.embeddedBook!!.entries.size,
                            )
                        }
                        if (tav.alternateGreetings.isNotEmpty()) {
                            IconStatBadge(
                                icon = HugeIcons.Message02,
                                active = true,
                                count = 1 + tav.alternateGreetings.size,
                            )
                        }
                    }
                }

                // 导出按钮：右上角，与角色卡信息同区域，无需展开卡片即可使用
                if (onExport != null) {
                    IconButton(
                        onClick = onExport,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            HugeIcons.Share01,
                            contentDescription = "导出角色卡",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 展开内容
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 标签 — 与作者/版本一致的小标签流式排布
                    if (tav.tags.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            tav.tags.forEach { tag ->
                                Text(
                                    text = "#$tag",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    // 角色信息 — 统一字段卡（每个字段一行，点击展开编辑）
                    FieldCardGroup(
                        title = "角色信息",
                        fields = buildList {
                            if (tav.systemPrompt.isNotBlank()) {
                                add(FieldSpec("系统提示词(System Prompt)", tav.systemPrompt) { v ->
                                    onAssistantUpdate?.invoke(assistant.copy(tavernData = tav.copy(systemPrompt = v)))
                                })
                            }
                            add(FieldSpec("描述(Description)", tav.description) { v ->
                                onAssistantUpdate?.invoke(assistant.copy(tavernData = tav.copy(description = v)))
                            })
                            add(FieldSpec("性格(Personality)", tav.personality) { v ->
                                onAssistantUpdate?.invoke(assistant.copy(tavernData = tav.copy(personality = v)))
                            })
                            add(FieldSpec("场景(Scenario)", tav.scenario) { v ->
                                onAssistantUpdate?.invoke(assistant.copy(tavernData = tav.copy(scenario = v)))
                            })
                            add(FieldSpec("示例消息(Examples)", tav.mesExample) { v ->
                                onAssistantUpdate?.invoke(assistant.copy(tavernData = tav.copy(mesExample = v)))
                            })
                            if (tav.postHistoryInstructions.isNotBlank()) {
                                add(FieldSpec("历史后续指令(PHI)", tav.postHistoryInstructions) { v ->
                                    onAssistantUpdate?.invoke(assistant.copy(tavernData = tav.copy(postHistoryInstructions = v)))
                                })
                            }
                            if (tav.firstMessage.isNotBlank()) {
                                add(FieldSpec("开场白(First Message)", tav.firstMessage, previewLines = 1) { v ->
                                    onAssistantUpdate?.invoke(assistant.copy(tavernData = tav.copy(firstMessage = v)))
                                })
                            }
                            if (tav.creatorNotes.isNotBlank()) {
                                add(FieldSpec("作者备注(Author's Notes)", tav.creatorNotes) { v ->
                                    onAssistantUpdate?.invoke(assistant.copy(tavernData = tav.copy(creatorNotes = v)))
                                })
                            }
                        },
                    )

                    // 备选/群聊开场白 — 统一字段卡
                    if (tav.alternateGreetings.isNotEmpty() || tav.groupOnlyGreetings.isNotEmpty()) {
                        FieldCardGroup(
                            title = "开场白",
                            fields = buildList {
                                tav.alternateGreetings.forEachIndexed { i, greeting ->
                                    add(FieldSpec("G${i + 1}", greeting) { v ->
                                        val newGreetings = tav.alternateGreetings.toMutableList().apply { set(i, v) }
                                        onAssistantUpdate?.invoke(assistant.copy(tavernData = tav.copy(alternateGreetings = newGreetings)))
                                    })
                                }
                                tav.groupOnlyGreetings.forEachIndexed { i, greeting ->
                                    add(FieldSpec("群聊G${i + 1}", greeting) { v ->
                                        val newGreetings = tav.groupOnlyGreetings.toMutableList().apply { set(i, v) }
                                        onAssistantUpdate?.invoke(assistant.copy(tavernData = tav.copy(groupOnlyGreetings = newGreetings)))
                                    })
                                }
                            },
                        )
                    }

                    // 内嵌世界书
                    tav.embeddedBook?.let { book ->
                        SectionTitle("世界书")
                        EmbeddedBookSummary(
                            book = book,
                            settings = settings,
                            onSettingsUpdate = onSettingsUpdate,
                            onEntryUpdate = { updated ->
                                val tav = assistant.tavernData ?: return@EmbeddedBookSummary
                                val oldBook = tav.embeddedBook ?: return@EmbeddedBookSummary
                                val newEntries = oldBook.entries.map {
                                    if (it.id == updated.id) updated else it
                                }
                                val newBook = oldBook.copy(entries = newEntries)
                                val newTav = tav.copy(embeddedBook = newBook)
                                onAssistantUpdate?.invoke(assistant.copy(tavernData = newTav))
                            },
                        )
                    }

                    // 元数据 — CardGroup 列表项（作者/版本/扩展/资源摘要）
                    if (tav.creator.isNotBlank() || tav.characterVersion.isNotBlank() ||
                        tav.extensions.isNotEmpty() || tav.assets.isNotEmpty()
                    ) {
                        CardGroup {
                            if (tav.creator.isNotBlank()) {
                                item(
                                    headlineContent = { Text(stringResource(R.string.tavern_card_author)) },
                                    supportingContent = { Text(tav.creator) },
                                )
                            }
                            if (tav.characterVersion.isNotBlank()) {
                                item(
                                    headlineContent = { Text(stringResource(R.string.tavern_card_version)) },
                                    supportingContent = { Text("v${tav.characterVersion}") },
                                )
                            }
                            if (tav.extensions.isNotEmpty()) {
                                item(
                                    headlineContent = { Text(stringResource(R.string.tavern_card_extensions)) },
                                    supportingContent = { Text(stringResource(R.string.tavern_card_extensions_count, tav.extensions.size)) },
                                )
                            }
                            if (tav.assets.isNotEmpty()) {
                                item(
                                    headlineContent = { Text(stringResource(R.string.tavern_card_assets)) },
                                    supportingContent = { Text(stringResource(R.string.tavern_card_assets_count, tav.assets.size)) },
                                )
                            }
                        }
                    }

                    // 资源文件列表
                    if (tav.assets.isNotEmpty()) {
                        Text(
                            text = "资源文件(Assets)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        CardGroup {
                            tav.assets.forEach { asset ->
                                item(
                                    leadingContent = {
                                        Icon(
                                            imageVector = when (asset.type.lowercase()) {
                                                "image" -> HugeIcons.Image02
                                                "audio" -> HugeIcons.MusicNote03
                                                "video" -> HugeIcons.Video01
                                                else -> HugeIcons.File02
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    headlineContent = {
                                        Text(
                                            text = asset.name.ifBlank { asset.type.ifBlank { "未命名资源" } },
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = "类型(Type): ${asset.type.ifBlank { "-" }} · 扩展(Ext): ${asset.ext.ifBlank { "-" }}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (asset.uri.isNotBlank()) {
                                                Text(
                                                    text = "URI: ${asset.uri}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    },
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
private fun IconStatBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    count: Int? = null,
) {
    val tint = if (active)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val bgColor = if (active)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val contentColor = if (active)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = tint,
            )
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                )
            }
        }
    }
}

/**
 * 分组小标题 — 统一样式：labelMedium + primary + SemiBold
 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private data class FieldSpec(
    val label: String,
    val value: String,
    val previewLines: Int = 2,
    val onSave: (String) -> Unit,
)

/**
 * 可编辑字段分组卡 — CardGroup 内每行一个字段：字段名+预览，点击整行展开编辑，折叠时自动保存。
 */
@Composable
private fun FieldCardGroup(
    title: String,
    fields: List<FieldSpec>,
) {
    // 用「标签+值」做稳定 key：字段值没变时保留展开/编辑状态，外部保存后值变化才重置
    val stateKey = fields.map { it.label to it.value }
    val expandedKeys = remember(stateKey) { mutableStateMapOf<String, Boolean>() }
    val editTexts = remember(stateKey) { mutableStateMapOf<String, String>() }

    CardGroup(title = { Text(title) }) {
        fields.forEach { field ->
            item(
                onClick = {
                    if (expandedKeys[field.label] == true) {
                        expandedKeys[field.label] = false
                        val text = editTexts[field.label] ?: field.value
                        if (text != field.value) field.onSave(text)
                    } else {
                        expandedKeys[field.label] = true
                    }
                },
                headlineContent = {
                    Text(
                        text = field.label,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                supportingContent = {
                    if (expandedKeys[field.label] == true) {
                        OutlinedTextField(
                            value = editTexts.getOrPut(field.label) { field.value },
                            onValueChange = { editTexts[field.label] = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall,
                            minLines = 3,
                        )
                    } else {
                        Text(
                            text = field.value.lines().take(field.previewLines).joinToString("\n")
                                .let { if (it.length < field.value.length) "$it…" else it },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = field.previewLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                trailingContent = {
                    val rotationAngle by animateFloatAsState(
                        targetValue = if (expandedKeys[field.label] == true) 90f else 0f,
                        animationSpec = tween(200),
                    )
                    Icon(
                        HugeIcons.ArrowRight01,
                        contentDescription = null,
                        modifier = Modifier
                            .size(14.dp)
                            .graphicsLayer { rotationZ = rotationAngle },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

@Composable
private fun EmbeddedBookSummary(
    book: TavernEmbeddedBook,
    onEntryUpdate: (TavernBookEntry) -> Unit = {},
    settings: Settings? = null,
    onSettingsUpdate: ((Settings) -> Unit)? = null,
) {
    var showEntries by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (showEntries) 90f else 0f,
        animationSpec = tween(200),
    )
    // 组设置状态
    var groupSettingsTarget by remember { mutableStateOf<Pair<String, List<TavernBookEntry>>?>(null) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showEntries = !showEntries },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = rotationAngle },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                HugeIcons.Book01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.prompt_page_embedded_lorebook), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = "${book.entries.size}",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (book.name.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "· ${book.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = showEntries) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 全局世界书激活设置（与外置世界书页同一份全局数据，天然同步）
                if (settings != null && onSettingsUpdate != null) {
                    var worldInfoSettingsExpanded by rememberSaveable { mutableStateOf(false) }
                    TextButton(
                        onClick = { worldInfoSettingsExpanded = !worldInfoSettingsExpanded },
                    ) {
                        Icon(HugeIcons.Setting07, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.prompt_page_world_info_settings_button), style = MaterialTheme.typography.labelSmall)
                    }
                    AnimatedVisibility(visible = worldInfoSettingsExpanded) {
                    CardGroup {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.prompt_page_world_info_depth_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.prompt_page_world_info_depth_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IntTextField(
                                    value = settings.worldInfoDepth,
                                    onValueChange = { onSettingsUpdate(settings.copy(worldInfoDepth = it.coerceIn(0, 1000))) },
                                    modifier = Modifier.width(84.dp),
                                )
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.prompt_page_world_info_budget_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.prompt_page_world_info_budget_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IntTextField(
                                    value = settings.worldInfoBudget,
                                    onValueChange = { onSettingsUpdate(settings.copy(worldInfoBudget = it.coerceIn(0, 100000))) },
                                    modifier = Modifier.width(84.dp),
                                )
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.prompt_page_world_info_min_activations_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.prompt_page_world_info_min_activations_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IntTextField(
                                    value = settings.worldInfoMinActivations,
                                    onValueChange = { onSettingsUpdate(settings.copy(worldInfoMinActivations = it.coerceIn(0, 50))) },
                                    modifier = Modifier.width(84.dp),
                                )
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.prompt_page_world_info_recursive_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.prompt_page_world_info_recursive_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = settings.worldInfoRecursive,
                                    onCheckedChange = { onSettingsUpdate(settings.copy(worldInfoRecursive = it)) },
                                )
                            }
                        }
                        if (settings.worldInfoRecursive) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.prompt_page_world_info_max_recursion_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.prompt_page_world_info_max_recursion_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IntTextField(
                                        value = settings.worldInfoMaxRecursionSteps,
                                        onValueChange = { onSettingsUpdate(settings.copy(worldInfoMaxRecursionSteps = it.coerceIn(0, 20))) },
                                        modifier = Modifier.width(84.dp),
                                    )
                            }
                        }
                        }
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(stringResource(R.string.prompt_page_world_info_strategy_title), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.prompt_page_world_info_strategy_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                InsertionStrategySelector(
                                    selected = settings.worldInfoCharacterStrategy,
                                    onSelect = { onSettingsUpdate(settings.copy(worldInfoCharacterStrategy = it)) },
                                )
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.prompt_page_world_info_overflow_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.prompt_page_world_info_overflow_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = settings.worldInfoOverflowAlert,
                                    onCheckedChange = { onSettingsUpdate(settings.copy(worldInfoOverflowAlert = it)) },
                                )
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.prompt_page_world_info_group_scoring_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.prompt_page_world_info_group_scoring_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = settings.worldInfoUseGroupScoring,
                                    onCheckedChange = { onSettingsUpdate(settings.copy(worldInfoUseGroupScoring = it)) },
                                )
                            }
                        }
                    }
                    }
                }

                val grouped = book.entries.groupBy { it.group }
                val namedGroups = grouped.filterKeys { it.isNotBlank() }
                val ungrouped = grouped[""] ?: emptyList()

                namedGroups.forEach { (groupName, groupEntries) ->
                    var groupExpanded by rememberSaveable(groupName) { mutableStateOf(false) }
                    val grpRotation by animateFloatAsState(
                        targetValue = if (groupExpanded) 90f else 0f,
                        animationSpec = tween(200),
                    )
                    Surface(
                        onClick = { groupExpanded = !groupExpanded },
                        color = CustomColors.listItemColors.containerColor,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(HugeIcons.ArrowRight01, null,
                                modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = grpRotation },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(HugeIcons.Folder01, null,
                                modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(groupName,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${groupEntries.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // 组设置齿轮
                            IconButton(
                                onClick = { groupSettingsTarget = Pair(groupName, groupEntries) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(HugeIcons.Tools, "组设置",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    AnimatedVisibility(visible = groupExpanded) {
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            groupEntries.forEach { entry ->
                                CollapsibleEntryCard(entry = entry, onUpdate = onEntryUpdate)
                            }
                        }
                    }
                }

                if (ungrouped.isNotEmpty()) {
                    Text(stringResource(R.string.prompt_page_ungrouped), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp))
                    ungrouped.forEach { entry ->
                        CollapsibleEntryCard(entry = entry, onUpdate = onEntryUpdate)
                    }
                }
            }
        }
    }

    // 组设置弹窗
    groupSettingsTarget?.let { (groupName, entries) ->
        EmbeddedGroupSettingsDialog(
            groupName = groupName,
            entries = entries,
            onDismiss = { groupSettingsTarget = null },
            onConfirm = { newName, template ->
                entries.forEach { entry ->
                    onEntryUpdate(entry.copy(
                        group = newName,
                        disable = template.disable,
                        probability = template.probability,
                        position = template.position,
                        priority = template.priority,
                        role = template.role,
                        constant = template.constant,
                        selective = template.selective,
                        selectiveLogic = template.selectiveLogic,
                        sticky = template.sticky,
                        cooldown = template.cooldown,
                        delay = template.delay,
                        depth = template.depth,
                        scanDepth = template.scanDepth,
                        caseSensitive = template.caseSensitive,
                        useRegex = template.useRegex,
                        matchWholeWords = template.matchWholeWords,
                        excludeRecursion = template.excludeRecursion,
                        preventRecursion = template.preventRecursion,
                        delayUntilRecursion = template.delayUntilRecursion,
                        useProbability = template.useProbability,
                        inclusionGroup = template.inclusionGroup,
                        useGroupScoring = template.useGroupScoring,
                        groupPriority = template.groupPriority,
                        automationId = template.automationId,
                        displayIndex = template.displayIndex,
                        displayPosition = template.displayPosition,
                        triggers = template.triggers,
                        matchPersonaDescription = template.matchPersonaDescription,
                        matchCharacterDescription = template.matchCharacterDescription,
                        matchCharacterPersonality = template.matchCharacterPersonality,
                        matchCharacterDepthPrompt = template.matchCharacterDepthPrompt,
                        matchScenario = template.matchScenario,
                        matchCreatorNotes = template.matchCreatorNotes,
                        ignoreBudget = template.ignoreBudget,
                        groupWeight = template.groupWeight,
                        groupOverride = template.groupOverride,
                    ))
                }
                groupSettingsTarget = null
            },
        )
    }
}

/**
 * 内嵌世界书组设置弹窗 — 批量修改组内所有条目的共有属性
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmbeddedGroupSettingsDialog(
    groupName: String,
    entries: List<TavernBookEntry>,
    onDismiss: () -> Unit,
    onConfirm: (String, TavernBookEntry) -> Unit,
) {
    val template = remember(entries) { entries.first() }
    var editGroupName by remember { mutableStateOf(groupName) }
    var enabled by remember { mutableStateOf(!template.disable) }
    var probability by remember { mutableStateOf(template.probability.toFloat()) }
    var position by remember { mutableStateOf(template.position) }
    var priority by remember { mutableStateOf(template.priority.toString()) }
    var sticky by remember { mutableStateOf(template.sticky.toString()) }
    var cooldown by remember { mutableStateOf(template.cooldown.toString()) }
    var delay by remember { mutableStateOf(template.delay.toString()) }
    var depth by remember { mutableStateOf(template.depth.toString()) }
    var scanDepth by remember { mutableStateOf(template.scanDepth?.toString() ?: "") }
    var groupWeight by remember { mutableStateOf(template.groupWeight.toString()) }
    var groupOverride by remember { mutableStateOf(template.groupOverride) }
    var constant by remember { mutableStateOf(template.constant) }
    var selective by remember { mutableStateOf(template.selective) }
    var useProbability by remember { mutableStateOf(template.useProbability) }
    var selectiveLogic by remember { mutableStateOf(template.selectiveLogic) }
    var caseSensitive by remember { mutableStateOf(template.caseSensitive) }
    var useRegex by remember { mutableStateOf(template.useRegex) }
    var matchWholeWords by remember { mutableStateOf(template.matchWholeWords) }
    var excludeRecursion by remember { mutableStateOf(template.excludeRecursion) }
    var preventRecursion by remember { mutableStateOf(template.preventRecursion) }
    var delayUntilRecursion by remember { mutableStateOf(template.delayUntilRecursion) }
    var inclusionGroupStr by remember { mutableStateOf(template.inclusionGroup) }
    var useGroupScoring by remember { mutableStateOf(template.useGroupScoring) }
    var groupPriority by remember { mutableStateOf(template.groupPriority) }
    var automationIdStr by remember { mutableStateOf(template.automationId) }
    var displayIndexStr by remember { mutableStateOf(template.displayIndex.toString()) }
    var displayPositionStr by remember { mutableStateOf(template.displayPosition.toString()) }
    var triggersStr by remember { mutableStateOf(template.triggers.joinToString(", ")) }
    var matchPersonaDescription by remember { mutableStateOf(template.matchPersonaDescription) }
    var matchCharacterDescription by remember { mutableStateOf(template.matchCharacterDescription) }
    var matchCharacterPersonality by remember { mutableStateOf(template.matchCharacterPersonality) }
    var matchCharacterDepthPrompt by remember { mutableStateOf(template.matchCharacterDepthPrompt) }
    var matchScenario by remember { mutableStateOf(template.matchScenario) }
    var matchCreatorNotes by remember { mutableStateOf(template.matchCreatorNotes) }
    var ignoreBudget by remember { mutableStateOf(template.ignoreBudget) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.prompt_page_group_settings, groupName), style = MaterialTheme.typography.titleMedium)

            // 组名称
            OutlinedTextField(
                value = editGroupName, onValueChange = { editGroupName = it },
                label = { Text(stringResource(R.string.prompt_page_group)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )

            // 状态切换
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_enabled), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = enabled, onCheckedChange = { enabled = it }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_constant_active), style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = {
                        Text(
                            stringResource(R.string.prompt_page_constant_active_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = { Switch(checked = constant, onCheckedChange = { constant = it }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_keyword_trigger), style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = {
                        Text(
                            stringResource(R.string.prompt_page_vector_trigger_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = { Switch(checked = selective, onCheckedChange = { selective = it }) },
                )
            }

            // 概率
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.prompt_page_probability_label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Switch(checked = useProbability, onCheckedChange = { useProbability = !useProbability })
            }
            AnimatedVisibility(visible = useProbability) {
                Column {
                    Text(stringResource(R.string.prompt_page_probability, probability.toInt()), style = MaterialTheme.typography.labelMedium)
                    Slider(value = probability, onValueChange = { probability = it }, valueRange = 0f..100f, steps = 99)
                }
            }

            // 插入位置
            CardGroup(title = { Text(stringResource(R.string.prompt_page_insertion_position)) }) {
                listOf(
                    "角色卡前(Before Char)",
                    "角色卡后(After Char)",
                    "作者备注前(AN Top)",
                    "作者备注后(AN Bottom)",
                    "指定深度(@Depth)",
                    "示例消息前(EM Top)",
                    "示例消息后(EM Bottom)",
                    "出口(Outlet, 暂不支持)",
                ).forEachIndexed { i, label ->
                    item(
                        onClick = { position = i },
                        headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = {
                            RadioButton(selected = position == i, onClick = { position = i })
                        },
                    )
                }
            }

            // 数值字段 — 分两行，每行两列
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.prompt_page_priority_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = priority, onValueChange = { priority = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.prompt_page_inject_depth), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = depth, onValueChange = { depth = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.prompt_page_sticky), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = sticky, onValueChange = { sticky = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.prompt_page_cooldown), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = cooldown, onValueChange = { cooldown = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.prompt_page_scan_depth), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = scanDepth, onValueChange = { scanDepth = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.prompt_page_group_weight), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = groupWeight, onValueChange = { groupWeight = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
            }

            // 开关
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_case_sensitive), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = caseSensitive, onCheckedChange = { caseSensitive = it }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_use_regex), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = useRegex, onCheckedChange = { useRegex = it }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_group_override), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = groupOverride, onCheckedChange = { groupOverride = it }) },
                )
            }

            // 选择性逻辑
            CardGroup(title = { Text(stringResource(R.string.prompt_page_selective_logic)) }) {
                listOf(
                    // 官方 world-info.js：0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL
                    "任一副键匹配(AND_ANY)" to 0,
                    "全部副键匹配(AND_ALL)" to 3,
                    "副键非全部匹配(NOT_ALL)" to 1,
                    "副键均不匹配(NOT_ANY)" to 2,
                ).forEach { (label, value) ->
                    item(
                        onClick = { selectiveLogic = value },
                        headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = {
                            RadioButton(selected = selectiveLogic == value, onClick = { selectiveLogic = value })
                        },
                    )
                }
            }

            // 匹配与递归控制
            CardGroup(title = { Text(stringResource(R.string.prompt_page_match_recursion)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_match_whole_words), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchWholeWords, onCheckedChange = { matchWholeWords = it }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_exclude_recursion), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = excludeRecursion, onCheckedChange = { excludeRecursion = it }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_prevent_recursion), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = preventRecursion, onCheckedChange = { preventRecursion = it }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_delay_until_recursion), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = delayUntilRecursion > 0, onCheckedChange = { delayUntilRecursion = if (it) 1 else 0 }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_ignore_budget), style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = {
                        Text(
                            stringResource(R.string.prompt_page_ignore_budget_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = { Switch(checked = ignoreBudget, onCheckedChange = { ignoreBudget = it }) },
                )
            }
            if (delayUntilRecursion > 0) {
                IntTextField(
                    value = delayUntilRecursion,
                    onValueChange = { delayUntilRecursion = it },
                    validate = { it > 0 },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text(stringResource(R.string.prompt_page_delay_until_recursion_level)) },
                )
            }

            // 官方高级字段
            Text(
                stringResource(R.string.prompt_page_official_advanced),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_use_group_scoring), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = useGroupScoring, onCheckedChange = { useGroupScoring = it }) },
                )
            }
            OutlinedTextField(
                value = automationIdStr,
                onValueChange = { automationIdStr = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                label = { Text(stringResource(R.string.prompt_page_automation_id)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.prompt_page_display_index), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = displayIndexStr, onValueChange = { displayIndexStr = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.prompt_page_display_position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = displayPositionStr, onValueChange = { displayPositionStr = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
            }
            OutlinedTextField(
                value = triggersStr,
                onValueChange = { triggersStr = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                label = { Text(stringResource(R.string.prompt_page_triggers)) },
                supportingText = { Text(stringResource(R.string.prompt_page_triggers_desc)) },
            )

            // 扫描范围（官方 match_*）
            Text(
                stringResource(R.string.prompt_page_match_scope),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_match_char_description), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchCharacterDescription, onCheckedChange = { matchCharacterDescription = it }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_match_char_personality), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchCharacterPersonality, onCheckedChange = { matchCharacterPersonality = it }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_match_char_depth_prompt), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchCharacterDepthPrompt, onCheckedChange = { matchCharacterDepthPrompt = it }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_match_scenario), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchScenario, onCheckedChange = { matchScenario = it }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_match_creator_notes), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchCreatorNotes, onCheckedChange = { matchCreatorNotes = it }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.prompt_page_match_persona), style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchPersonaDescription, onCheckedChange = { matchPersonaDescription = it }) },
                )
            }

            // 按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = {
                    onConfirm(editGroupName, template.copy(
                        disable = !enabled,
                        probability = probability.toInt(),
                        position = position,
                        priority = priority.toIntOrNull() ?: 100,
                        sticky = sticky.toIntOrNull() ?: 0,
                        cooldown = cooldown.toIntOrNull() ?: 0,
                        delay = delay.toIntOrNull() ?: 0,
                        depth = depth.toIntOrNull() ?: 4,
                        scanDepth = scanDepth.toIntOrNull(),
                        groupWeight = groupWeight.toIntOrNull() ?: 100,
                        groupOverride = groupOverride,
                        constant = constant,
                        selective = selective,
                        useProbability = useProbability,
                        selectiveLogic = selectiveLogic,
                        caseSensitive = caseSensitive,
                        useRegex = useRegex,
                        matchWholeWords = matchWholeWords,
                        excludeRecursion = excludeRecursion,
                        preventRecursion = preventRecursion,
                        delayUntilRecursion = delayUntilRecursion,
                        inclusionGroup = inclusionGroupStr.trim(),
                        useGroupScoring = useGroupScoring,
                        groupPriority = groupPriority,
                        automationId = automationIdStr.trim(),
                        displayIndex = displayIndexStr.toIntOrNull() ?: 0,
                        displayPosition = displayPositionStr.toIntOrNull() ?: 0,
                        triggers = triggersStr.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        matchPersonaDescription = matchPersonaDescription,
                        matchCharacterDescription = matchCharacterDescription,
                        matchCharacterPersonality = matchCharacterPersonality,
                        matchCharacterDepthPrompt = matchCharacterDepthPrompt,
                        matchScenario = matchScenario,
                        matchCreatorNotes = matchCreatorNotes,
                        ignoreBudget = ignoreBudget,
                    ))
                }) {
                    Text(stringResource(R.string.prompt_page_apply_to_entries, entries.size))
                }
            }
        }
    }
}

/**
 * 可折叠世界书条目卡片 — 收起显示 keys+预览，展开显示全部可编辑字段
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollapsibleEntryCard(
    entry: TavernBookEntry,
    onUpdate: (TavernBookEntry) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = CustomColors.listItemColors.containerColor
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
                // ======== 收起预览 ========
                if (!expanded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (entry.keys.isNotEmpty()) {
                                Text(
                                    text = "触发词(Keywords)：${entry.keys.take(4).joinToString(" · ")}" +
                                        if (entry.keys.size > 4) " +${entry.keys.size - 4}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // 状态信息行
                            Spacer(Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "P${entry.probability}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    if (entry.constant) stringResource(R.string.prompt_page_constant_active) else stringResource(R.string.prompt_page_trigger_preview),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                                if (entry.group.isNotBlank()) {
                                    Text(
                                        entry.group,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                            // 内容预览
                            if (entry.content.isNotBlank()) {
                                Text(
                                    text = entry.content.replace("\n", " ").take(60)
                                        .let { if (it.length < entry.content.length) "$it…" else it },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        Icon(
                            HugeIcons.ArrowRight01, contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ======== 展开编辑 ========
                if (expanded) {
                    EntryEditor(entry = entry, onUpdate = onUpdate, onCollapse = { expanded = false })
                }
            }
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryEditor(
    entry: TavernBookEntry,
    onUpdate: (TavernBookEntry) -> Unit,
    onCollapse: () -> Unit,
) {
    // 所有字段的状态 — key用entry.id避免外部onUpdate触发remember重置
    var enabled by remember(entry.id) { mutableStateOf(!entry.disable) }
    var content by remember(entry.id) { mutableStateOf(entry.content) }
    var probability by remember(entry.id) { mutableStateOf(entry.probability.toFloat()) }
    var position by remember(entry.id) { mutableStateOf(entry.position) }
    var priority by remember(entry.id) { mutableStateOf(entry.priority.toString()) }
    var role by remember(entry.id) { mutableStateOf(entry.role) }
    var constant by remember(entry.id) { mutableStateOf(entry.constant) }
    var selective by remember(entry.id) { mutableStateOf(entry.selective) }
    var selectiveLogic by remember(entry.id) { mutableStateOf(entry.selectiveLogic) }
    var sticky by remember(entry.id) { mutableStateOf(entry.sticky.toString()) }
    var cooldown by remember(entry.id) { mutableStateOf(entry.cooldown.toString()) }
    var delay by remember(entry.id) { mutableStateOf(entry.delay.toString()) }
    var depth by remember(entry.id) { mutableStateOf(entry.depth.toString()) }
    var caseSensitive by remember(entry.id) { mutableStateOf(entry.caseSensitive) }
    var useRegex by remember(entry.id) { mutableStateOf(entry.useRegex) }
    var matchWholeWords by remember(entry.id) { mutableStateOf(entry.matchWholeWords) }
    var excludeRecursion by remember(entry.id) { mutableStateOf(entry.excludeRecursion) }
    var preventRecursion by remember(entry.id) { mutableStateOf(entry.preventRecursion) }
    var delayUntilRecursion by remember(entry.id) { mutableStateOf(entry.delayUntilRecursion) }
    var groupStr by remember(entry.id) { mutableStateOf(entry.group) }
    var groupWeight by remember(entry.id) { mutableStateOf(entry.groupWeight.toString()) }
    var groupOverride by remember(entry.id) { mutableStateOf(entry.groupOverride) }
    var scanDepthStr by remember(entry.id) { mutableStateOf(entry.scanDepth?.toString() ?: "") }
    var keysStr by remember(entry.id) { mutableStateOf(entry.keys.joinToString(", ")) }
    var secondaryKeysStr by remember(entry.id) { mutableStateOf(entry.secondaryKeys.joinToString(", ")) }
    var commentStr by remember(entry.id) { mutableStateOf(entry.comment) }
    var inclusionGroupStr by remember(entry.id) { mutableStateOf(entry.inclusionGroup) }
    var useGroupScoring by remember(entry.id) { mutableStateOf(entry.useGroupScoring) }
    var groupPriority by remember(entry.id) { mutableStateOf(entry.groupPriority) }
    var automationIdStr by remember(entry.id) { mutableStateOf(entry.automationId) }
    var displayIndexStr by remember(entry.id) { mutableStateOf(entry.displayIndex.toString()) }
    var displayPositionStr by remember(entry.id) { mutableStateOf(entry.displayPosition.toString()) }
    var triggersStr by remember(entry.id) { mutableStateOf(entry.triggers.joinToString(", ")) }
    var matchPersonaDescription by remember(entry.id) { mutableStateOf(entry.matchPersonaDescription) }
    var matchCharacterDescription by remember(entry.id) { mutableStateOf(entry.matchCharacterDescription) }
    var matchCharacterPersonality by remember(entry.id) { mutableStateOf(entry.matchCharacterPersonality) }
    var matchCharacterDepthPrompt by remember(entry.id) { mutableStateOf(entry.matchCharacterDepthPrompt) }
    var matchScenario by remember(entry.id) { mutableStateOf(entry.matchScenario) }
    var matchCreatorNotes by remember(entry.id) { mutableStateOf(entry.matchCreatorNotes) }
    var ignoreBudget by remember(entry.id) { mutableStateOf(entry.ignoreBudget) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 顶部：标题 + 收起
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.keys.firstOrNull() ?: entry.comment.ifBlank { "Entry #${entry.id}" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                Icon(HugeIcons.ArrowRight01, contentDescription = "收起",
                    modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 90f })
            }
        }

        // 状态切换
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.prompt_page_enabled), style = MaterialTheme.typography.bodyMedium) },
                trailingContent = { Switch(checked = enabled, onCheckedChange = { enabled = it }) },
            )
            item(
                headlineContent = { Text(stringResource(R.string.prompt_page_constant_active), style = MaterialTheme.typography.bodyMedium) },
                supportingContent = {
                    Text(
                        stringResource(R.string.prompt_page_constant_active_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = { Switch(checked = constant, onCheckedChange = { constant = it }) },
            )
            item(
                headlineContent = { Text(stringResource(R.string.prompt_page_keyword_trigger), style = MaterialTheme.typography.bodyMedium) },
                supportingContent = {
                    Text(
                        stringResource(R.string.prompt_page_vector_trigger_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = { Switch(checked = selective, onCheckedChange = { selective = it }) },
            )
        }

        // 名称
        OutlinedTextField(
            value = commentStr,
            onValueChange = { commentStr = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            label = { Text(stringResource(R.string.prompt_page_name)) },
        )

        // 触发词
        OutlinedTextField(
            value = keysStr,
            onValueChange = { keysStr = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            label = { Text(stringResource(R.string.prompt_page_keywords_label)) },
        )

        // 次级触发词
        if (selective) {
            OutlinedTextField(
                value = secondaryKeysStr,
                onValueChange = { secondaryKeysStr = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                label = { Text(stringResource(R.string.prompt_page_secondary_keys_label)) },
            )
        }

        // 触发概率
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.prompt_page_probability_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Switch(
                checked = entry.useProbability,
                onCheckedChange = { onUpdate(entry.copy(useProbability = it)) },
            )
        }
        if (entry.useProbability) {
            Slider(
                value = probability,
                onValueChange = { probability = it },
                valueRange = 0f..100f,
                steps = 99,
            )
            Text("${probability.toInt()}%", style = MaterialTheme.typography.labelSmall)
        }

        // 插入位置
        CardGroup(title = { Text(stringResource(R.string.prompt_page_insertion_position)) }) {
            val posOptions = listOf(
                "角色卡前(Before Char)",
                "角色卡后(After Char)",
                "作者备注前(AN Top)",
                "作者备注后(AN Bottom)",
                "指定深度(@Depth)",
                "示例消息前(EM Top)",
                "示例消息后(EM Bottom)",
                "出口(Outlet, 暂不支持)",
            )
            posOptions.forEachIndexed { i, label ->
                item(
                    onClick = { position = i },
                    headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = {
                        RadioButton(selected = position == i, onClick = { position = i })
                    },
                )
            }
        }

        // 内容
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 200.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text(stringResource(R.string.prompt_page_injection_content)) },
        )

        // 高级设置
        var showAdvanced by remember { mutableStateOf(false) }
        val advRotation by animateFloatAsState(
            targetValue = if (showAdvanced) 90f else 0f, animationSpec = tween(200),
        )
        Row(
            modifier = Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.ArrowRight01, null, modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = advRotation },
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(if (showAdvanced) "收起高级设置" else "展开高级设置",
                 style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showAdvanced) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 触发控制
                Text(stringResource(R.string.prompt_page_trigger_control), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.prompt_page_priority_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = priority, onValueChange = { priority = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.prompt_page_inject_depth), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = depth, onValueChange = { depth = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.prompt_page_cooldown), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = cooldown, onValueChange = { cooldown = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.prompt_page_sticky), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = sticky, onValueChange = { sticky = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.prompt_page_delay), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = delay, onValueChange = { delay = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                }
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_case_sensitive), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = caseSensitive, onCheckedChange = { caseSensitive = it }) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_use_regex), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = useRegex, onCheckedChange = { useRegex = it }) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_match_whole_words), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchWholeWords, onCheckedChange = { matchWholeWords = it }) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_exclude_recursion), style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = {
                            Text(
                                stringResource(R.string.prompt_page_prevent_recursion_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = { Switch(checked = excludeRecursion, onCheckedChange = { excludeRecursion = it }) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_prevent_recursion), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = preventRecursion, onCheckedChange = { preventRecursion = it }) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_delay_until_recursion), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = delayUntilRecursion > 0, onCheckedChange = { delayUntilRecursion = if (it) 1 else 0 }) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_group_override), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = groupOverride, onCheckedChange = { groupOverride = it }) },
                    )
                }
                if (delayUntilRecursion > 0) {
                    IntTextField(
                        value = delayUntilRecursion,
                        onValueChange = { delayUntilRecursion = it },
                        validate = { it > 0 },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text(stringResource(R.string.prompt_page_delay_until_recursion_level)) },
                    )
                }

                // 插入控制
                Text(stringResource(R.string.prompt_page_insert_control), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.prompt_page_group), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = groupStr, onValueChange = { groupStr = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.prompt_page_group_weight), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = groupWeight, onValueChange = { groupWeight = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.prompt_page_scan_depth), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = scanDepthStr, onValueChange = { scanDepthStr = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.weight(1f))
                }
                CardGroup(title = { Text(stringResource(R.string.prompt_page_injection_role)) }) {
                    val roleLabels = listOf(
                        "system" to "系统(System)",
                        "user" to "用户(User)",
                        "assistant" to "助手(Assistant)",
                    )
                    roleLabels.forEach { (value, label) ->
                        item(
                            onClick = { role = value },
                            headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                            trailingContent = {
                                RadioButton(selected = role == value, onClick = { role = value })
                            },
                        )
                    }
                }
                CardGroup(title = { Text(stringResource(R.string.prompt_page_selective_logic)) }) {
                    val logicLabels = listOf(
                        // 官方 world-info.js：0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL
                        "任一副键匹配(AND_ANY)" to 0,
                        "全部副键匹配(AND_ALL)" to 3,
                        "副键非全部匹配(NOT_ALL)" to 1,
                        "副键均不匹配(NOT_ANY)" to 2,
                    )
                    logicLabels.forEach { (label, value) ->
                        item(
                            onClick = { selectiveLogic = value },
                            headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                            trailingContent = {
                                RadioButton(selected = selectiveLogic == value, onClick = { selectiveLogic = value })
                            },
                        )
                    }
                }

                // 酒馆官方高级字段（自动化ID本App暂不执行，仅保留数据）
                Text(stringResource(R.string.prompt_page_official_advanced), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp))
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_use_group_scoring), style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = {
                            Text(
                                stringResource(R.string.prompt_page_use_group_scoring_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = { Switch(checked = useGroupScoring, onCheckedChange = { useGroupScoring = it }) },
                    )
                }
                OutlinedTextField(
                    value = automationIdStr,
                    onValueChange = { automationIdStr = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    label = { Text(stringResource(R.string.prompt_page_automation_id)) },
                    supportingText = { Text(stringResource(R.string.tavern_card_automation_id_desc)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.prompt_page_display_index), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = displayIndexStr, onValueChange = { displayIndexStr = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.prompt_page_display_position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = displayPositionStr, onValueChange = { displayPositionStr = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                }
                OutlinedTextField(
                    value = triggersStr,
                    onValueChange = { triggersStr = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    label = { Text(stringResource(R.string.prompt_page_triggers)) },
                    supportingText = { Text(stringResource(R.string.prompt_page_triggers_desc)) },
                )
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_ignore_budget), style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = {
                            Text(
                                stringResource(R.string.prompt_page_ignore_budget_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = { Switch(checked = ignoreBudget, onCheckedChange = { ignoreBudget = it }) },
                    )
                }
                Text(
                    stringResource(R.string.prompt_page_match_scope),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_match_char_description), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchCharacterDescription, onCheckedChange = { matchCharacterDescription = it }) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_match_char_personality), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchCharacterPersonality, onCheckedChange = { matchCharacterPersonality = it }) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_match_char_depth_prompt), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchCharacterDepthPrompt, onCheckedChange = { matchCharacterDepthPrompt = it }) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_match_scenario), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchScenario, onCheckedChange = { matchScenario = it }) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_match_creator_notes), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchCreatorNotes, onCheckedChange = { matchCreatorNotes = it }) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_page_match_persona), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchPersonaDescription, onCheckedChange = { matchPersonaDescription = it }) },
                    )
                }
            }
        }

        // 保存按钮
        TextButton(
            onClick = {
                onUpdate(entry.copy(
                    disable = !enabled,
                    content = content,
                    probability = probability.toInt(),
                    position = position,
                    priority = priority.toIntOrNull() ?: 100,
                    role = role,
                    constant = constant,
                    selective = selective,
                    selectiveLogic = selectiveLogic,
                    sticky = sticky.toIntOrNull() ?: 0,
                    cooldown = cooldown.toIntOrNull() ?: 0,
                    delay = delay.toIntOrNull() ?: 0,
                    depth = depth.toIntOrNull() ?: 4,
                    scanDepth = scanDepthStr.toIntOrNull(),
                    caseSensitive = caseSensitive,
                    useRegex = useRegex,
                    matchWholeWords = matchWholeWords,
                    excludeRecursion = excludeRecursion,
                    preventRecursion = preventRecursion,
                    delayUntilRecursion = delayUntilRecursion,
                    group = groupStr,
                    groupWeight = groupWeight.toIntOrNull() ?: 100,
                    groupOverride = groupOverride,
                    inclusionGroup = inclusionGroupStr.trim(),
                    useGroupScoring = useGroupScoring,
                    groupPriority = groupPriority,
                    automationId = automationIdStr.trim(),
                    displayIndex = displayIndexStr.toIntOrNull() ?: 0,
                    displayPosition = displayPositionStr.toIntOrNull() ?: 0,
                    triggers = triggersStr.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    matchPersonaDescription = matchPersonaDescription,
                    matchCharacterDescription = matchCharacterDescription,
                    matchCharacterPersonality = matchCharacterPersonality,
                    matchCharacterDepthPrompt = matchCharacterDepthPrompt,
                    matchScenario = matchScenario,
                    matchCreatorNotes = matchCreatorNotes,
                    ignoreBudget = ignoreBudget,
                    keys = keysStr.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    secondaryKeys = secondaryKeysStr.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    comment = commentStr,
                ))
                onCollapse()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
Text(stringResource(R.string.prompt_page_save_changes))
        }
    }
}

@Composable
private fun KeywordInput(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (label.isNotBlank()) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
        )
    }
}
