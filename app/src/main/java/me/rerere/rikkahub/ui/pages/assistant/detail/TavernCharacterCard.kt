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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MapPin
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Message02
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
) {
    val tav = assistant.tavernData ?: return
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
                            text = "角色卡",
                            style = MaterialTheme.typography.titleSmall,
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
                    // 统计行 — 用图标+颜色表示有无内容
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
            }

            // 展开内容
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 标签
                    if (tav.tags.isNotEmpty()) {
                        Text(
                            text = "标签(Tags)：${tav.tags.joinToString(" · ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    // 角色信息分组标题
                    SectionTitle("角色信息")

                    // 可编辑字段
                    if (tav.systemPrompt.isNotBlank()) {
                        EditableField("系统提示词(System Prompt)", tav.systemPrompt) { v ->
                            val newTav = tav.copy(systemPrompt = v)
                            onAssistantUpdate?.invoke(assistant.copy(tavernData = newTav))
                        }
                    }
                    EditableField("描述(Description)", tav.description) { v ->
                        val newTav = tav.copy(description = v)
                        onAssistantUpdate?.invoke(assistant.copy(tavernData = newTav))
                    }
                    EditableField("性格(Personality)", tav.personality) { v ->
                        val newTav = tav.copy(personality = v)
                        onAssistantUpdate?.invoke(assistant.copy(tavernData = newTav))
                    }
                    EditableField("场景(Scenario)", tav.scenario) { v ->
                        val newTav = tav.copy(scenario = v)
                        onAssistantUpdate?.invoke(assistant.copy(tavernData = newTav))
                    }
                    EditableField("示例消息(Examples)", tav.mesExample) { v ->
                        val newTav = tav.copy(mesExample = v)
                        onAssistantUpdate?.invoke(assistant.copy(tavernData = newTav))
                    }
                    if (tav.postHistoryInstructions.isNotBlank()) {
                        EditableField("历史后续指令(Post-Hist)", tav.postHistoryInstructions) { v ->
                            val newTav = tav.copy(postHistoryInstructions = v)
                            onAssistantUpdate?.invoke(assistant.copy(tavernData = newTav))
                        }
                    }
                    if (tav.firstMessage.isNotBlank()) {
                        EditableField("开场白(First Message)", tav.firstMessage, previewLines = 1) { v ->
                            val newTav = tav.copy(firstMessage = v)
                            onAssistantUpdate?.invoke(assistant.copy(tavernData = newTav))
                        }
                    }

                    // 备选开场白
                    if (tav.alternateGreetings.isNotEmpty()) {
                        SectionTitle("开场白")
                        tav.alternateGreetings.forEachIndexed { i, greeting ->
                            EditableField(
                                label = "G${i + 1}",
                                value = greeting,
                            ) { v ->
                                val newGreetings = tav.alternateGreetings.toMutableList().apply { set(i, v) }
                                val newTav = tav.copy(alternateGreetings = newGreetings)
                                onAssistantUpdate?.invoke(assistant.copy(tavernData = newTav))
                            }
                        }
                    }

                    // 群聊专用开场白
                    if (tav.groupOnlyGreetings.isNotEmpty()) {
                        if (tav.alternateGreetings.isEmpty()) {
                            SectionTitle("开场白")
                        }
                        tav.groupOnlyGreetings.forEachIndexed { i, greeting ->
                            EditableField(
                                label = "G${i + 1}",
                                value = greeting,
                            ) { v ->
                                val newGreetings = tav.groupOnlyGreetings.toMutableList().apply { set(i, v) }
                                val newTav = tav.copy(groupOnlyGreetings = newGreetings)
                                onAssistantUpdate?.invoke(assistant.copy(tavernData = newTav))
                            }
                        }
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

                    // 元数据分组
                    if (tav.creatorNotes.isNotBlank() || tav.creator.isNotBlank() ||
                        tav.characterVersion.isNotBlank() || tav.extensions.isNotEmpty() || tav.assets.isNotEmpty()
                    ) {
                        SectionTitle("元数据")
                    }

                    // creator notes
                    if (tav.creatorNotes.isNotBlank()) {
                        EditableField("${tav.creator.ifBlank { "作者" }} 的备注", tav.creatorNotes) { v ->
                            val newTav = tav.copy(creatorNotes = v)
                            onAssistantUpdate?.invoke(assistant.copy(tavernData = newTav))
                        }
                    }

                    // 作者信息 — FlowRow + 小标签样式
                    if (tav.creator.isNotBlank() || tav.characterVersion.isNotBlank()) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (tav.creator.isNotBlank()) {
                                MetaTag("作者: ${tav.creator}")
                            }
                            if (tav.characterVersion.isNotBlank()) {
                                MetaTag("版本: v${tav.characterVersion}")
                            }
                        }
                    }

                    // 数据完整性摘要
                    if (tav.extensions.isNotEmpty() || tav.assets.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (tav.extensions.isNotEmpty()) {
                                MetaTag("扩展字段: ${tav.extensions.size}")
                            }
                            if (tav.assets.isNotEmpty()) {
                                MetaTag("资源文件: ${tav.assets.size}")
                            }
                        }
                    }
                    if (tav.extensions.isNotEmpty()) {
                        Text(
                            text = "扩展字段为原始数据，当前界面不展开（导出时原样保留）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
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

/**
 * 元数据小标签 — secondaryContainer 背景的小标签
 */
@Composable
private fun MetaTag(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * 可编辑字段组件 — 折叠预览3行，展开后OutlinedTextField可编辑
 */
@Composable
private fun EditableField(
    label: String,
    value: String,
    previewLines: Int = 2,
    onSave: (String) -> Unit,
) {
    if (value.isBlank()) return
    var expanded by remember { mutableStateOf(false) }
    var editText by remember(value) { mutableStateOf(value) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
    )
    // 折叠时自动保存
    LaunchedEffect(expanded) {
        if (!expanded && editText != value) {
            onSave(editText)
        }
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
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
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 4.dp),
            ) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    minLines = 3,
                )
            }
        } else {
            Text(
                text = value.lines().take(previewLines).joinToString("\n")
                    .let { if (it.length < value.length) "$it…" else it },
                modifier = Modifier.padding(start = 16.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = previewLines,
                overflow = TextOverflow.Ellipsis,
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
            Text("内嵌世界书(Embedded Lorebook)", style = MaterialTheme.typography.labelMedium)
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
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "全局世界书激活设置（作用于所有世界书）",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            FormItem(
                                label = { Text("预算(Token Budget, 0=不限)", style = MaterialTheme.typography.labelSmall) },
                                tail = {
                                    OutlinedTextField(
                                        value = settings.worldInfoBudget.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let {
                                                onSettingsUpdate(settings.copy(worldInfoBudget = it.coerceIn(0, 100000)))
                                            }
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.width(110.dp),
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                            )
                            FormItem(
                                label = { Text("最少激活(Minimum Activations, 0=关)", style = MaterialTheme.typography.labelSmall) },
                                tail = {
                                    OutlinedTextField(
                                        value = settings.worldInfoMinActivations.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let {
                                                onSettingsUpdate(settings.copy(worldInfoMinActivations = it.coerceIn(0, 50)))
                                            }
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.width(84.dp),
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                            )
                            FormItem(
                                label = { Text("递归扫描(Recursive Scanning)", style = MaterialTheme.typography.labelSmall) },
                                tail = {
                                    Switch(
                                        checked = settings.worldInfoRecursive,
                                        onCheckedChange = {
                                            onSettingsUpdate(settings.copy(worldInfoRecursive = it))
                                        },
                                    )
                                },
                            )
                            if (settings.worldInfoRecursive) {
                                FormItem(
                                    label = { Text("最大递归层数(Max Recursion Steps, 0=不限)", style = MaterialTheme.typography.labelSmall) },
                                    tail = {
                                        OutlinedTextField(
                                            value = settings.worldInfoMaxRecursionSteps.toString(),
                                            onValueChange = { v ->
                                                v.toIntOrNull()?.let {
                                                    onSettingsUpdate(
                                                        settings.copy(worldInfoMaxRecursionSteps = it.coerceIn(0, 20))
                                                    )
                                                }
                                            },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.width(84.dp),
                                            textStyle = MaterialTheme.typography.bodyMedium,
                                        )
                                    },
                                )
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
                    Text("未分组(Ungrouped)", style = MaterialTheme.typography.labelSmall,
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
    var scanDepth by remember { mutableStateOf(template.scanDepth.toString()) }
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
            Text("组设置(Group Settings)：$groupName", style = MaterialTheme.typography.titleMedium)

            // 组名称
            OutlinedTextField(
                value = editGroupName, onValueChange = { editGroupName = it },
                label = { Text("分组名称(Group Name)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )

            // 状态切换
            CardGroup {
                item(
                    headlineContent = { Text("启用(Enabled)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = enabled, onCheckedChange = { enabled = it }) },
                )
                item(
                    headlineContent = { Text("常驻(Constant)", style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = {
                        Text(
                            "不依赖关键词，始终注入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = { Switch(checked = constant, onCheckedChange = { constant = it }) },
                )
                item(
                    headlineContent = { Text("关键词触发(Keyword)", style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = {
                        Text(
                            "关闭后按向量触发",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = { Switch(checked = selective, onCheckedChange = { selective = it }) },
                )
            }

            // 概率
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("触发概率(Probability)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Switch(checked = useProbability, onCheckedChange = { useProbability = !useProbability })
            }
            AnimatedVisibility(visible = useProbability) {
                Column {
                    Text("触发概率(Probability)：${probability.toInt()}%", style = MaterialTheme.typography.labelMedium)
                    Slider(value = probability, onValueChange = { probability = it }, valueRange = 0f..100f, steps = 99)
                }
            }

            // 插入位置
            CardGroup(title = { Text("插入位置(Insertion Position)") }) {
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
                    Text("优先级(Priority)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = priority, onValueChange = { priority = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("深度(Depth)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = depth, onValueChange = { depth = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("粘性(Sticky)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = sticky, onValueChange = { sticky = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("冷却(Cooldown)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = cooldown, onValueChange = { cooldown = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("扫描深度(Scan Depth)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = scanDepth, onValueChange = { scanDepth = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("组权重(Group Weight)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = groupWeight, onValueChange = { groupWeight = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
            }

            // 开关
            CardGroup {
                item(
                    headlineContent = { Text("区分大小写(Case Sensitive)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = caseSensitive, onCheckedChange = { caseSensitive = it }) },
                )
                item(
                    headlineContent = { Text("使用正则(Use Regex)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = useRegex, onCheckedChange = { useRegex = it }) },
                )
                item(
                    headlineContent = { Text("覆盖同组(Group Override)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = groupOverride, onCheckedChange = { groupOverride = it }) },
                )
            }

            // 选择性逻辑
            CardGroup(title = { Text("次要关键词逻辑(Selective Logic)") }) {
                listOf(
                    "全部匹配(AND_ALL)",
                    "任一匹配(OR_ANY)",
                    "均不匹配(NOT_ANY)",
                    "非全部匹配(NOT_ALL)",
                ).forEachIndexed { i, label ->
                    item(
                        onClick = { selectiveLogic = i },
                        headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = {
                            RadioButton(selected = selectiveLogic == i, onClick = { selectiveLogic = i })
                        },
                    )
                }
            }

            // 匹配与递归控制
            CardGroup(title = { Text("匹配与递归(Match & Recursion)") }) {
                item(
                    headlineContent = { Text("整词匹配(Whole Words)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchWholeWords, onCheckedChange = { matchWholeWords = it }) },
                )
                item(
                    headlineContent = { Text("排除递归(Exclude Recursion)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = excludeRecursion, onCheckedChange = { excludeRecursion = it }) },
                )
                item(
                    headlineContent = { Text("禁止被递归触发(Prevent Recursion)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = preventRecursion, onCheckedChange = { preventRecursion = it }) },
                )
                item(
                    headlineContent = { Text("仅递归时检查(Delay Until Recursion)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = delayUntilRecursion, onCheckedChange = { delayUntilRecursion = it }) },
                )
                item(
                    headlineContent = { Text("忽略预算(Ignore Budget)", style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = {
                        Text(
                            "该条目不受世界书 token 预算限制，总是注入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = { Switch(checked = ignoreBudget, onCheckedChange = { ignoreBudget = it }) },
                )
            }

            // 官方高级字段
            Text(
                "官方高级字段(Official Advanced)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = inclusionGroupStr,
                onValueChange = { inclusionGroupStr = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                label = { Text("包含组(Inclusion Group)") },
            )
            CardGroup {
                item(
                    headlineContent = { Text("使用组评分(Use Group Scoring)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = useGroupScoring, onCheckedChange = { useGroupScoring = it }) },
                )
                item(
                    headlineContent = { Text("包含优先(Group Priority)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = groupPriority, onCheckedChange = { groupPriority = it }) },
                )
            }
            OutlinedTextField(
                value = automationIdStr,
                onValueChange = { automationIdStr = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                label = { Text("自动化ID(Automation ID)") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("显示序号(Display Index)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = displayIndexStr, onValueChange = { displayIndexStr = it },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("显示位置(Display Position)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                label = { Text("触发类型(Triggers)") },
                supportingText = { Text("逗号分隔；不选 = 所有生成类型都触发") },
            )

            // 扫描范围（官方 match_*）
            Text(
                "扫描范围(Match)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            CardGroup {
                item(
                    headlineContent = { Text("角色描述(Char Description)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchCharacterDescription, onCheckedChange = { matchCharacterDescription = it }) },
                )
                item(
                    headlineContent = { Text("角色性格(Char Personality)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchCharacterPersonality, onCheckedChange = { matchCharacterPersonality = it }) },
                )
                item(
                    headlineContent = { Text("角色深度提示(Char Depth Prompt)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchCharacterDepthPrompt, onCheckedChange = { matchCharacterDepthPrompt = it }) },
                )
                item(
                    headlineContent = { Text("角色场景(Scenario)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchScenario, onCheckedChange = { matchScenario = it }) },
                )
                item(
                    headlineContent = { Text("作者备注(Creator Notes)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchCreatorNotes, onCheckedChange = { matchCreatorNotes = it }) },
                )
                item(
                    headlineContent = { Text("用户人设(Persona)", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(checked = matchPersonaDescription, onCheckedChange = { matchPersonaDescription = it }) },
                )
            }

            // 按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
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
                        scanDepth = scanDepth.toIntOrNull() ?: 1000,
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
                    Text("应用到 ${entries.size} 条")
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
                                    if (entry.constant) "常驻(Constant)" else "触发(Trigger)",
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
    var scanDepthStr by remember(entry.id) { mutableStateOf(entry.scanDepth.toString()) }
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
                headlineContent = { Text("启用(Enabled)", style = MaterialTheme.typography.bodyMedium) },
                trailingContent = { Switch(checked = enabled, onCheckedChange = { enabled = it }) },
            )
            item(
                headlineContent = { Text("常驻(Constant)", style = MaterialTheme.typography.bodyMedium) },
                supportingContent = {
                    Text(
                        "不依赖关键词，始终注入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = { Switch(checked = constant, onCheckedChange = { constant = it }) },
            )
            item(
                headlineContent = { Text("关键词触发(Keyword)", style = MaterialTheme.typography.bodyMedium) },
                supportingContent = {
                    Text(
                        "关闭后按向量触发",
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
            label = { Text("名称(Name)") },
        )

        // 触发词
        OutlinedTextField(
            value = keysStr,
            onValueChange = { keysStr = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            label = { Text("触发词(Keywords)") },
        )

        // 次级触发词
        if (selective) {
            OutlinedTextField(
                value = secondaryKeysStr,
                onValueChange = { secondaryKeysStr = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                label = { Text("次级触发词(Secondary Keywords)") },
            )
        }

        // 触发概率
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("触发概率(Probability)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
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
        CardGroup(title = { Text("插入位置(Insertion Position)") }) {
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
            label = { Text("内容(Content)") },
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
                Text("触发控制(Trigger)", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("优先级(Priority)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = priority, onValueChange = { priority = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("深度(Depth)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = depth, onValueChange = { depth = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("冷却(Cooldown)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = cooldown, onValueChange = { cooldown = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("粘性(Sticky)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = sticky, onValueChange = { sticky = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("延迟(Delay)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = delay, onValueChange = { delay = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                }
                CardGroup {
                    item(
                        headlineContent = { Text("区分大小写(Case Sensitive)", style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = caseSensitive, onCheckedChange = { caseSensitive = it }) },
                    )
                    item(
                        headlineContent = { Text("使用正则(Use Regex)", style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = useRegex, onCheckedChange = { useRegex = it }) },
                    )
                    item(
                        headlineContent = { Text("整词匹配(Whole Words)", style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchWholeWords, onCheckedChange = { matchWholeWords = it }) },
                    )
                    item(
                        headlineContent = { Text("排除递归(Exclude Recursion)", style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = {
                            Text(
                                "内容不参与递归扫描",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = { Switch(checked = excludeRecursion, onCheckedChange = { excludeRecursion = it }) },
                    )
                    item(
                        headlineContent = { Text("禁止被递归触发(Prevent Recursion)", style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = preventRecursion, onCheckedChange = { preventRecursion = it }) },
                    )
                    item(
                        headlineContent = { Text("仅递归时检查(Delay Until Recursion)", style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = delayUntilRecursion, onCheckedChange = { delayUntilRecursion = it }) },
                    )
                    item(
                        headlineContent = { Text("覆盖同组(Group Override)", style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = groupOverride, onCheckedChange = { groupOverride = it }) },
                    )
                }

                // 插入控制
                Text("插入控制(Insert)", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("分组名称(Group)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = groupStr, onValueChange = { groupStr = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("组权重(Group Weight)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = groupWeight, onValueChange = { groupWeight = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("扫描深度(Scan Depth)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = scanDepthStr, onValueChange = { scanDepthStr = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.weight(1f))
                }
                CardGroup(title = { Text("注入角色(Role)") }) {
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
                CardGroup(title = { Text("次要关键词逻辑(Selective Logic)") }) {
                    val logicLabels = listOf(
                        "全部匹配(AND_ALL)",
                        "任一匹配(OR_ANY)",
                        "均不匹配(NOT_ANY)",
                        "非全部匹配(NOT_ALL)",
                    )
                    logicLabels.forEachIndexed { i, label ->
                        item(
                            onClick = { selectiveLogic = i },
                            headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                            trailingContent = {
                                RadioButton(selected = selectiveLogic == i, onClick = { selectiveLogic = i })
                            },
                        )
                    }
                }

                // 酒馆官方高级字段（自动化ID本App暂不执行，仅保留数据）
                Text("官方高级字段(Official Advanced)", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp))
                OutlinedTextField(
                    value = inclusionGroupStr,
                    onValueChange = { inclusionGroupStr = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    label = { Text("包含组(Inclusion Group)") },
                )
                CardGroup {
                    item(
                        headlineContent = { Text("使用组评分(Use Group Scoring)", style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = {
                            Text(
                                "同组按匹配关键词数选胜者",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = { Switch(checked = useGroupScoring, onCheckedChange = { useGroupScoring = it }) },
                    )
                    item(
                        headlineContent = { Text("包含优先(Group Priority)", style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = {
                            Text(
                                "同组优先选优先级最高的条目",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = { Switch(checked = groupPriority, onCheckedChange = { groupPriority = it }) },
                    )
                }
                OutlinedTextField(
                    value = automationIdStr,
                    onValueChange = { automationIdStr = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    label = { Text("自动化ID(Automation ID)") },
                    supportingText = { Text("仅保留数据，暂不执行") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示序号(Display Index)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = displayIndexStr, onValueChange = { displayIndexStr = it },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示位置(Display Position)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    label = { Text("触发类型(Triggers)") },
                    supportingText = { Text("逗号分隔；不选 = 所有生成类型都触发") },
                )
                CardGroup {
                    item(
                        headlineContent = { Text("忽略预算(Ignore Budget)", style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = {
                            Text(
                                "该条目不受世界书 token 预算限制，总是注入",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = { Switch(checked = ignoreBudget, onCheckedChange = { ignoreBudget = it }) },
                    )
                }
                Text(
                    "扫描范围(Match)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                CardGroup {
                    item(
                        headlineContent = { Text("角色描述(Char Description)", style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchCharacterDescription, onCheckedChange = { matchCharacterDescription = it }) },
                    )
                    item(
                        headlineContent = { Text("角色性格(Char Personality)", style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchCharacterPersonality, onCheckedChange = { matchCharacterPersonality = it }) },
                    )
                    item(
                        headlineContent = { Text("角色深度提示(Char Depth Prompt)", style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchCharacterDepthPrompt, onCheckedChange = { matchCharacterDepthPrompt = it }) },
                    )
                    item(
                        headlineContent = { Text("角色场景(Scenario)", style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchScenario, onCheckedChange = { matchScenario = it }) },
                    )
                    item(
                        headlineContent = { Text("作者备注(Creator Notes)", style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Switch(checked = matchCreatorNotes, onCheckedChange = { matchCreatorNotes = it }) },
                    )
                    item(
                        headlineContent = { Text("用户人设(Persona)", style = MaterialTheme.typography.bodyMedium) },
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
                    scanDepth = scanDepthStr.toIntOrNull() ?: 1000,
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
Text("保存修改(Save)")
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
