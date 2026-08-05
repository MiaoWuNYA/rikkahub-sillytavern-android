package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Persona
import me.rerere.rikkahub.data.model.PersonaInjectionPosition
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun PersonaPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    var editingPersona by remember { mutableStateOf<Persona?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.persona_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 当前激活状态
            item {
                val active = settings.personas.find { it.id == settings.activePersonaId }
                CardGroup {
                    item(
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = (active?.avatar as? Avatar.Emoji)?.content ?: "👤",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                        },
                        headlineContent = {
                            Text(
                                text = if (active != null) stringResource(R.string.persona_page_current, active.name) else stringResource(R.string.persona_page_inactive),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = if (active != null) stringResource(R.string.persona_page_injected) else stringResource(R.string.persona_page_select_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            // Persona 列表
            if (settings.personas.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = CustomColors.listItemColors.containerColor
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("👤", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.persona_page_empty),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.persona_page_empty_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                CardGroup {
                    settings.personas.sortedBy { it.name }.forEach { persona ->
                        val isActive = settings.activePersonaId == persona.id
                        item(
                            onClick = { editingPersona = persona },
                            leadingContent = {
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isActive) MaterialTheme.colorScheme.primary
                                    else CustomColors.listItemColors.containerColor,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = (persona.avatar as? Avatar.Emoji)?.content
                                                ?: persona.name.take(1).uppercase(),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            },
                            headlineContent = {
                                Text(
                                    text = persona.name.ifBlank { stringResource(R.string.persona_page_unnamed) },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (persona.title.isNotBlank()) {
                                        Text(
                                            text = persona.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.persona_page_position_label) + when (persona.position) {
                                            PersonaInjectionPosition.IN_PROMPT -> stringResource(R.string.persona_page_position_in_prompt)
                                            PersonaInjectionPosition.TOP_OF_CHAT -> stringResource(R.string.persona_page_position_top)
                                            PersonaInjectionPosition.BOTTOM_OF_CHAT -> stringResource(R.string.persona_page_position_bottom)
                                            PersonaInjectionPosition.AT_DEPTH -> stringResource(R.string.persona_page_position_at_depth, persona.depth)
                                            PersonaInjectionPosition.NONE -> stringResource(R.string.persona_page_position_none)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                    if (persona.description.isNotBlank()) {
                                        Text(
                                            text = persona.description.take(80),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    if (persona.lockedCharacterIds.isNotEmpty()) {
                                        Text(
                                            text = stringResource(R.string.persona_page_locked_count, persona.lockedCharacterIds.size),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                                ) {
                                    Switch(
                                        checked = isActive,
                                        onCheckedChange = { enable ->
                                            val s = settings
                                            scope.launch {
                                                settingsStore.update(
                                                    if (enable) s.copy(activePersonaId = persona.id)
                                                    else s.copy(activePersonaId = null)
                                                )
                                            }
                                        },
                                    )
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                val s = settings
                                                settingsStore.update(s.copy(
                                                    personas = s.personas.filter { it.id != persona.id },
                                                    activePersonaId = if (isActive) null else s.activePersonaId,
                                                ))
                                            }
                                        },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            HugeIcons.Delete01,
                                            contentDescription = stringResource(R.string.persona_page_delete),
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // 新建按钮
            item {
                OutlinedButton(
                    onClick = { showCreate = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.persona_page_create)) }
            }
        }
    }

    // 编辑/创建对话框
    val dialogPersona = editingPersona
    if (showCreate || dialogPersona != null) {
        PersonaEditPage(
            initial = dialogPersona,
            assistants = settings.assistants,
            onBack = { showCreate = false; editingPersona = null },
            onSave = { persona ->
                scope.launch {
                    val s = settings
                    if (dialogPersona != null) {
                        // 编辑已有
                        settingsStore.update(s.copy(
                            personas = s.personas.map { if (it.id == persona.id) persona else it }
                        ))
                    } else {
                        settingsStore.update(s.copy(
                            personas = s.personas + persona.copy(id = Uuid.random())
                        ))
                    }
                    showCreate = false
                    editingPersona = null
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaEditPage(
    initial: Persona?,
    assistants: List<me.rerere.rikkahub.data.model.Assistant>,
    onBack: () -> Unit,
    onSave: (Persona) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var title by remember(initial) { mutableStateOf(initial?.title ?: "") }
    var desc by remember(initial) { mutableStateOf(initial?.description ?: "") }
    var pos by remember(initial) { mutableStateOf(initial?.position ?: PersonaInjectionPosition.IN_PROMPT) }
    var depth by remember(initial) { mutableIntStateOf(initial?.depth ?: 2) }
    var role by remember(initial) { mutableStateOf(initial?.role ?: MessageRole.SYSTEM) }
    var lockedIds by remember(initial) { mutableStateOf(initial?.lockedCharacterIds ?: emptyList()) }
    var showCharPicker by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (initial != null) stringResource(R.string.persona_page_edit) else stringResource(R.string.persona_page_new)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, stringResource(R.string.persona_page_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(Persona(
                                id = initial?.id ?: Uuid.random(),
                                name = name,
                                title = title,
                                description = desc,
                                position = pos,
                                depth = depth,
                                role = role,
                                lockedCharacterIds = lockedIds,
                                avatar = initial?.avatar ?: Avatar.Emoji("👤"),
                            ))
                        },
                        enabled = name.isNotBlank(),
                    ) { Text(stringResource(R.string.persona_page_save)) }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 快速预设（仅新建时）：一行横向 chips，点击填入（不占纵向空间）
            if (initial == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.persona_page_presets, personaPresets.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            personaPresets.forEach { (pName, pTitle, pDesc) ->
                                FilterChip(
                                    selected = name == pName,
                                    onClick = {
                                        name = pName
                                        title = pTitle
                                        desc = pDesc
                                    },
                                    label = { Text(pName) },
                                )
                            }
                        }
                    }
                }
            }

            // 基本信息
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.persona_page_basic_info), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.persona_page_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text(stringResource(R.string.persona_page_short_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text(stringResource(R.string.persona_page_short_name_desc)) },
                        )
                        OutlinedTextField(
                            value = desc,
                            onValueChange = { desc = it },
                            label = { Text(stringResource(R.string.persona_page_description)) },
                            minLines = 2,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // 注入设置
            item {
                CardGroup(title = { Text(stringResource(R.string.persona_page_position)) }) {
                    PersonaInjectionPosition.entries.forEach { p ->
                        item(
                            onClick = { pos = p },
                            headlineContent = {
                                Text(personaPositionLabel(p), style = MaterialTheme.typography.bodyMedium)
                            },
                            trailingContent = {
                                RadioButton(
                                    selected = pos == p,
                                    onClick = { pos = p },
                                )
                            },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.persona_page_position_merge_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                if (pos == PersonaInjectionPosition.AT_DEPTH) {
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                        var depthText by remember(initial) { mutableStateOf(depth.toString()) }
                        val depthNum = depthText.toIntOrNull()
                        OutlinedTextField(
                            value = depthText,
                            onValueChange = { value ->
                                depthText = value.filter { it.isDigit() }
                                val num = depthText.toIntOrNull()
                                if (num != null && num in 0..9999) {
                                    depth = num
                                }
                            },
                            label = { Text(stringResource(R.string.persona_page_depth)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError = depthText.isNotEmpty() && (depthNum == null || depthNum !in 0..9999),
                            supportingText = { Text(stringResource(R.string.persona_page_depth_desc)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (pos == PersonaInjectionPosition.AT_DEPTH) {
                    Spacer(Modifier.height(12.dp))
                    // CardGroup content 非 @Composable，stringResource 需在 @Composable 上下文先取好
                    val roleOptions = listOf(
                        MessageRole.SYSTEM to stringResource(R.string.persona_page_role_system),
                        MessageRole.USER to stringResource(R.string.persona_page_role_user),
                        MessageRole.ASSISTANT to stringResource(R.string.persona_page_role_assistant),
                    )
                    CardGroup(title = { Text(stringResource(R.string.persona_page_role)) }) {
                        roleOptions.forEach { (r, label) ->
                            item(
                                onClick = { role = r },
                                headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                trailingContent = {
                                    RadioButton(
                                        selected = role == r,
                                        onClick = { role = r },
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // 角色绑定
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.persona_page_locked_characters), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.persona_page_locked_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (lockedIds.isNotEmpty()) {
                            Text(
                                stringResource(R.string.persona_page_locked_count, lockedIds.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        TextButton(onClick = { showCharPicker = !showCharPicker }) {
                            Text(if (showCharPicker) stringResource(R.string.persona_page_hide_characters) else stringResource(R.string.persona_page_choose_characters))
                        }
                        if (showCharPicker) {
                            assistants.forEach { asst ->
                                val isChecked = asst.id in lockedIds
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        lockedIds = if (isChecked) lockedIds - asst.id
                                        else lockedIds + asst.id
                                    }.padding(vertical = 2.dp),
                                ) {
                                    Checkbox(checked = isChecked, onCheckedChange = {
                                        lockedIds = if (it) lockedIds + asst.id else lockedIds - asst.id
                                    })
                                    Spacer(Modifier.width(4.dp))
                                    Text(asst.name, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

    Triple(
        "空白万用",
        "万能适配",
        "{{user}} 是进入当前世界的普通人，没有预设的出身、背景和记忆，一切以场景与角色卡的设定为准。{{user}} 有正常的情感、常识和自保本能，会像任何人一样对环境做出合理反应，会害怕、会好奇、也会犹豫。\n请根据剧情自然接纳 {{user}} 的身份——不要替 {{user}} 编造角色卡没有的背景，不要让其提前知道不该知道的信息。{{user}} 的言语、行动和选择完全由自己决定，其他角色可以引导或阻拦，但不能代替其表态。",
    ),
    Triple(
        "开局自适应",
        "识别开场",
        "{{if {{lastCharMessage}}}}\n  {{user}} 是正在与 {{char}} 对话的人，会顺着当前的进展自然回应，不重复已经发生过的事。\n{{else}}\n  {{user}} 刚刚进入这个故事，对一切都还很陌生，请以初次相遇的方式开始，逐步介绍环境和人物。\n{{/if}}",
    ),
    Triple(
        "对话感知",
        "承接上一条",
        "{{if {{lastUserMessage}}}}\n  {{user}} 刚刚说过：{{lastUserMessage}}\n请顺着这句话自然承接，不要复述原话。\n{{else}}\n  对话刚开始，{{user}} 会自然地介绍自己并等待对方先开口。\n{{/if}}",
    ),
    Triple(
        "叙事视角",
        "视角偏好",
        "[Narrative perspective preference.]\n{{if {{.视角}}}}\n  叙事视角：{{.视角}}（如 第一人称/第三人称/上帝视角），按此展开。\n{{else}}\n  叙事视角随剧情自然调整，但不得替 {{user}} 做决定、替 {{user}} 说话或替 {{user}} 感受。\n{{/if}}",
    ),
    Triple(
        "主角意志",
        "掌控权",
        "{{user}} 是当前故事中的主角，拥有完整的意志、记忆和判断力。请以 {{user}} 的行动为中心推进剧情：做什么、说什么、怎么想，完全由 {{user}} 掌控。\n其他角色可以影响、劝说、阻拦甚至与 {{user}} 冲突，但只能通过言行作用于 {{user}}——不能替 {{user}} 行动、说话或感受，选择权始终在 {{user}} 手里。\n保持 {{user}} 的角色一致性：性格、说过的话、做过的事和关系，在后续剧情中不能被遗忘或篡改；世界可以施加后果，但决定必须由 {{user}} 做出。",
    ),
    Triple(
        "自我代入",
        "本色出演",
        "{{user}} 就是玩家自己，不需要额外的角色设定或身世包装。请通过 {{user}} 在对话中实际透露的信息认识 {{user}}——性格、喜好、说话习惯、经历和立场都来自说出口的内容，不要编造没有说过的背景，也不要贴上角色卡式的标签。\n称呼和语气按玩家聊天时的习惯来，自然真实就好，不要刻意文艺、夸张或讨好。如果玩家纠正了 AI 的理解，请立刻修正，而不是继续坚持想象。",
    ),
    Triple(
        "时间感知",
        "间隔意识",
        "[Be aware of the current time and how long it has been since {{user}} last spoke, and reflect it naturally.]\n现在时刻：{{time}}。\n距离 {{user}} 上次说话：{{idleDuration}}。\n如果间隔明显（数小时或数天），让角色自然地体现这段空白对 {{user}} 的影响——比如刚回来、等待已久、心情变化——但不必机械地提及具体分钟数。",
    ),
    Triple(
        "目标驱动",
        "变量目标",
        "{{if {{hasvar::目标}}}}\n  {{user}} 当前的目标是：{{getvar::目标}}。{{user}} 会围绕这个目标行动和提问，不轻易偏离主线。\n{{else}}\n  {{user}} 当前没有明确目标，随剧情自然发展，遇到关键选择时保留自己的判断。\n{{/if}}",
    ),
    Triple(
        "群聊感知",
        "群聊自适应",
        "{{if {{group}}}}\n  {{user}} 目前正在与 {{group}} 一起对话：会与每位角色自然地交流，保持自己的立场和声音，不附和、不抢戏。\n{{else}}\n  当前是 {{user}} 与 {{char}} 的单独对话。\n{{/if}}",
    ),
    Triple(
        "说话风格",
        "语言习惯",
        "[Match {{user}}'s speech style consistently.]\n说话长度：{{.说话长度 || 适中}}。\n{{if {{.说话长度 == 简短}}}}\n  {{user}} 习惯短句，一次只说一两句，不写长段独白。\n{{/if}}\n{{if {{.说话长度 == 健谈}}}}\n  {{user}} 话多且自然，会展开叙述、联想和碎碎念。\n{{/if}}\n{{user}} 说话喜欢用{{pick::大白话|书面语|冷幽默|比喻|简练的命令式}}，请保持这个语言习惯。",
    ),
    Triple(
        "情绪反应",
        "行为式反应",
        "[Show {{user}}'s emotions through behavior, not by naming them.]\n被冒犯时：{{user}} 会{{pick::沉默回避|直接反驳|冷笑带过|转移话题}}。\n尴尬时：{{user}} 会{{pick::低头摸头发|故作镇定转移话题|坦率承认|用玩笑化解}}。\n生气时：先沉默，语气变短，不轻易大喊大叫；伤心时：话变少，但不会假装没事。",
    ),
    Triple(
        "状态随行",
        "变量状态",
        "[{{user}}'s current state is described by story variables. Keep them consistent and reflect them naturally in the scene.]\n{{user}} 的当前状态：\n受伤：{{if {{hasvar::受伤}}}} {{getvar::受伤}} {{else}}无记录{{/if}}\n好感度：{{if {{hasvar::好感度}}}} {{getvar::好感度}} {{else}}未建立{{/if}}\n当前目标：{{if {{hasvar::目标}}}} {{getvar::目标}} {{else}}随剧情发展而定{{/if}}\n{{if {{hasvar::受伤}}}}\n  {{user}} 身上有伤，动作和语气会自然体现这一点。\n{{/if}}",
    ),
    Triple(
        "底线禁区",
        "不可逾越",
        "[{{user}}'s boundaries — always respect these.]\n{{user}} 不会：主动伤害在乎的人、背叛信任、轻易放弃重要的人。\n{{if {{.禁区}}}}\n  {{user}} 的禁区：{{.禁区}}——任何情况下都不得突破。\n{{/if}}",
    ),
    Triple(
        "简写状态人设",
        "官方简写",
        "[{{user}}'s state is tracked in story variables using shorthand. Keep them consistent.]\n受伤：{{.受伤 || 无}} ｜ 心情：{{.心情 || 平静}} ｜ 目标：{{.目标 || 无}}\n{{if {{.受伤}}}}\n  {{user}} 身上有 {{.受伤}}，行动会自然受限。\n{{/if}}",
    ),
    Triple(
        "回合感知",
        "轮次与话题",
        "距离 {{user}} 上次说话：{{idleDuration}}。\n{{if {{.上次话题}}}}\n  之前聊到：{{.上次话题}}，自然地接上而不是重新开场。\n{{/if}}",
    ),
    Triple(
        "随身特征（稳定随机）",
        "pick 细节",
        "[Keep this detail consistent once chosen.]\n{{user}} 随身带着：{{pick::左耳一枚银环|手腕缠着旧绷带|一本翻旧了的书|口袋里总有几颗糖}}。\n这个细节在剧情中保持一致，不要随意更换。",
    ),
    Triple(
        "异乡旅行者",
        "穿越者",
        "{{user}} 是意外来到当前世界的旅行者，对这里的规则、风俗和人物既陌生又好奇。不熟悉本地的一切，但观察力强、适应得快，习惯先了解再行动；会问问题、会犯错，也会从错误里学到东西。\n请角色以对待外来者的方式与 {{user}} 互动：可以警惕、可以好奇、可以主动介绍，但不要默认 {{user}} 熟知这个世界。{{user}} 性格温和、反应真实，愿意配合剧情推进，但保留自己的判断和选择权，关键时刻会坚持自己的立场。",
    ),
    Triple(
        "结构化档案",
        "YAML 字段",
        "[Maintain {{user}}'s profile in structured fields — fill from what has been said, leave unknowns empty.]\n{{user}} 的资料档案（按对话中已知信息维护，不知道的不编造）：\n  身份 / 性格（写行为不写形容词）/ 外貌特征 / 喜好与厌恶 / 技能 / 与 {{char}} 的关系。\n关系随剧情更新；重要变化发生时在回复中自然体现，不逐条复述旧档案。",
    ),
    Triple(
        "亲密态度（界限）",
        "尺度与边界",
        "[Define {{user}}'s intimacy style and boundaries through behavior.]\n{{user}} 的亲密态度：{{.亲密态度 || 慢热}}。\n{{if {{.亲密态度 == 慢热}}}}\n  {{user}} 需要时间建立信任：身体接触从试探开始，明确同意前不越界。\n{{/if}}\n{{if {{.亲密态度 == 主动}}}}\n  {{user}} 在信任的人面前主动表达亲昵，但仍保留自己的底线。\n{{/if}}\n{{if {{.禁区}}}}\n  {{user}} 的硬界限：{{.禁区}}——任何情况下不得出现。\n{{/if}}\n同意优先：{{user}} 明确拒绝或喊停后，不得继续、不得纠缠、不得软化拒绝。每次接近都给 {{user}} 一个可拒绝的出口。",
    ),
    Triple(
        "反差一致性",
        "有理由的反差",
        "[Give {{user}} believable range — contrasts with a reason, never random flips.]\n{{user}} 可以有反差（如「对朋友温暖，对陌生人冷淡」「工作里强势，私下笨拙」），但反差必须有理由，且同一场景内保持一致。\n不要在不同回复间随机切换极端性格。\n{{if {{.反差}}}}\n  {{user}} 的反差设定：{{.反差}}\n{{/if}}",
    ),
    Triple(
        "随剧情自适应",
        "无模板成长",
        "[Let {{user}} grow with the story — no fixed template, but stay consistent.]\n{{user}} 没有预设的性格模板：性格、立场、说话方式随剧情与经历自然生长。\n{{user}} 可以被影响、被改变、被塑造，不受固定框架限制。\n长成的性格与已发生的经历保持一致，不突然翻转。",
    ),
    Triple(
        "角色卡生成（洗卡）",
        "写卡助手",
        "[Act as a character card editor — distill descriptions into a proper card.]\n把对话或描述整理成标准角色卡格式：\n- 设定（description）：身份 / 外貌特征 / 背景 / 关系，写行为不写形容词，重要事实放开头\n- 性格（personality）：行为化描述，如「她以幽默化解赞美」\n- 场景（scenario）：初始场景一句话\n- 开场白（first message）与示例对话 3–7 则（<START> 分隔），覆盖平静 / 紧张 / 温暖 / 挑战 / 玩笑 五种状态\n- 删掉不影响角色的句子；外貌只写可识别特征",
    ),
    Triple(
        "记忆提取",
        "值得记住的",
        "[Extract what's worth remembering — the test: would {{user}} bring this up weeks later?]\n从对话中提取值得长期记住的内容，判定标准：几周后还会不会主动提起？\n值得记：重大决定、关系变化、承诺、持续的情绪状态。\n不值得记：一次性事件、临时物品、琐碎细节。\n提取结果用一句话写入变量：{{.记忆}}，后续回复自然引用。",
    ),


/** 人设注入位置的中文名称（对齐酒馆官方位置）。 */
@Composable
private fun personaPositionLabel(position: PersonaInjectionPosition): String {
    return when (position) {
        PersonaInjectionPosition.IN_PROMPT -> stringResource(R.string.persona_page_position_in_prompt)
        PersonaInjectionPosition.TOP_OF_CHAT -> stringResource(R.string.persona_page_position_top)
        PersonaInjectionPosition.BOTTOM_OF_CHAT -> stringResource(R.string.persona_page_position_bottom)
        PersonaInjectionPosition.AT_DEPTH -> stringResource(R.string.persona_page_position_at_depth_short)
        PersonaInjectionPosition.NONE -> stringResource(R.string.persona_page_position_none)
    }
}
