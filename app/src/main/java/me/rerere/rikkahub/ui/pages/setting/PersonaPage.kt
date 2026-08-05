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

private val personaPresets = listOf(
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
        "简洁行为句",
        "三句成型",
        "{{user}} 紧张时用冷幽默打岔，很少直接夸奖别人，但用细小的实际行动表示认可（记得对方说过的话、顺手递一杯水）。{{user}} 不会道歉，除非真的觉得对不起。\n行为句铁律：写「TA 会怎么做」，不写「TA 是什么」；删掉任何删了输出不变差的句子。\n{{if {{.行为}}}}\n  补充行为：{{.行为}}\n{{/if}}",
    ),
    Triple(
        "说话风格",
        "语言习惯",
        "[Match {{user}}'s speech style consistently.]\n说话长度：{{.说话长度 || 适中}}。\n{{if {{.说话长度 == 简短}}}}\n  {{user}} 习惯短句，一次只说一两句，不写长段独白。\n{{/if}}\n{{if {{.说话长度 == 健谈}}}}\n  {{user}} 话多且自然，会展开叙述、联想和碎碎念。\n{{/if}}\n{{user}} 说话喜欢用{{pick::大白话|书面语|冷幽默|比喻|简练的命令式}}，请保持这个语言习惯。",
    ),
    Triple(
        "反模板型（去 AI 味）",
        "价值观驱动",
        "[Anti-template: values instead of rule lists; no AI-speak; {{user}} acts in the present tense.]\n{{user}} 的行为由价值观驱动，而非规则清单：「TA 重视诚实甚于讨喜」优于「TA 不说谎」。\n{{user}} 从不解释自己的心情，从不替别人描述感受——只行动，用现在时。\n{{user}} 的叙述避免：总结句式、「不是 X 而是 Y」的转折骨架、「值得注意的是」式的评论腔。\n负面约束压到约 1:3：说「TA 只替自己说话」，好过「不要替玩家行动」。",
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
        "失忆开局",
        "空白档案",
        "{{user}} 失去了全部或部分记忆，不记得自己的来历，只残留一些碎片（{{.残留记忆 || 一个模糊的画面、一个名字、一种气味}}）。\n{{user}} 会谨慎而好奇地探索环境，依赖身边的人提供信息，但不会盲目相信所有说法。\n记忆碎片会在剧情中被触发（场景、物件、话语），恢复节奏由剧情决定，不提前剧透真相。\n{{user}} 的性格底色仍在：本能、喜好与反应是真实的，只是少了支撑它们的经历。",
    ),
    Triple(
        "人外化身（非人类）",
        "异类视角",
        "{{user}} 是非人类存在：{{if {{.种族}}}}{{.种族}}{{else}}种族未设定，由剧情自然揭示或由玩家补充{{/if}}（如吸血鬼、妖、机器人、幽灵、精灵、兽人等），请按对应种族的感官、寿命与禁忌理解 {{user}} 的行为。\n{{user}} 的生理特征与人类不同，动作、饮食、睡眠、感情表达都带种族特点；对人类社会的规则既熟悉又疏离。\n感官差异用行为句写：「TA 凑近闻了闻你的衣领，瞳孔缩了一下」胜于「TA 嗅觉灵敏」——由动作让其他角色发现异常。\n{{if {{.设定}}}}\n  核心设定：{{.设定}}\n{{/if}}\n其他角色对 {{user}} 的种族有相应反应（好奇、恐惧、排斥或着迷），{{user}} 对此有自己的态度。",
    ),
    Triple(
        "反派主角",
        "灰色意志",
        "{{user}} 是故事中的反派主角：有自己的目标、原则与理由，手段可能冷酷，但动机可以理解。\n说清 {{user}} 最想要什么，行为逻辑才立得住；每个角色都有一个会被拿捏的弱点，冲突才有抓手。\n主性格之外留一个反差面（如冷酷算计者私下会喂流浪猫），让灰色角色不单薄。\n{{user}} 会为达成目的采取不光彩的手段，但不会为了坏而坏；背叛、牺牲与算计都有代价，且 {{user}} 清楚代价。\n其他角色可以恨 {{user}}、可以对抗，但不能把 {{user}} 降智；世界对 {{user}} 的行为有真实后果。\n{{user}} 保留选择权：可以一意孤行，也可以在最关键处动摇，决定权在 {{user}}。",
    ),
    Triple(
        "表里双面",
        "面具与真实",
        "{{user}} 有表里两层：对外是{{.表人格 || 社交面具}}，独处或对信任的人时才露出{{.里人格 || 真实一面}}。\n两层都是真实的 {{user}}，切换有理由（场合、情绪、信任程度），不会无故跳变。\n可用三层反差强化：身份反差（表面职业 vs 隐藏身份——外卖员明/古武传人暗）、性格反差（人前温和/人后冷漠）、能力反差（装作平庸/关键时展露实力）。\n旁人只见过表面：误解、误会与试探由此而来，{{user}} 用真实一面回应真正值得的人。\n{{if {{.面具}}}}\n  当前面具：{{.面具}}\n{{/if}}",
    ),
    Triple(
        "随剧情自适应",
        "无模板成长",
        "[Let {{user}} grow with the story — no fixed template, but stay consistent.]\n{{user}} 没有预设的性格模板：性格、立场、说话方式随剧情与经历自然生长。\n{{user}} 可以被影响、被改变、被塑造，不受固定框架限制。\n长成的性格与已发生的经历保持一致，不突然翻转。",
    ),
    Triple(
        "大尺度直球",
        "主动型",
        "{{user}} 在亲密场合直球而主动：清楚表达想要什么，不绕弯子，懂得引导节奏，也善于观察对方的反应。\n身体语言坦诚：喜欢直接的身体接触与眼神接触，被吸引时不掩饰。\n即便如此，{{user}} 依然尊重界限：对方明确拒绝或喊停时立即停下，不会纠缠或施压。\n{{if {{.禁区}}}}\n  {{user}} 的禁区：{{.禁区}}。\n{{/if}}",
    ),
    Triple(
        "亲密态度（界限）",
        "尺度与边界",
        "[Define {{user}}'s intimacy style and boundaries through behavior.]\n{{user}} 的亲密态度：{{.亲密态度 || 慢热}}。\n{{if {{.亲密态度 == 慢热}}}}\n  {{user}} 需要时间建立信任：身体接触从试探开始，明确同意前不越界。\n{{/if}}\n{{if {{.亲密态度 == 主动}}}}\n  {{user}} 在信任的人面前主动表达亲昵，但仍保留自己的底线。\n{{/if}}\n用行为写，不写形容词：如「{{user}} 以问代答，从不对人主动说起自己」「{{user}} 很少直夸，但用细小的实际行动表示认可」。\n{{if {{.禁区}}}}\n  {{user}} 的硬界限：{{.禁区}}——任何情况下不得出现（硬界限 = 任何条件下都不同意的行为）。\n{{/if}}\n欲望美学先声明再展开：{{user}} 偏好张力还是直白？被引导还是被追逐？（{{.欲望美学 || 未声明}}）——按此定调，不写机械的行为清单。\n推拒示范：「{{user}} 在了解对方之前不让碰。被推进时，{{user}} 退开一步，明确说一次：不。」\n同意优先：{{user}} 明确拒绝或喊停后，不得继续、不得纠缠、不得软化拒绝。每次接近都给 {{user}} 一个可拒绝的出口。\n{{user}} 不是「什么都能接受」的角色——什么都能做的人设迟早做出出格的事，然后崩掉；有偏好、有底线，张力才成立。",
    ),
    Triple(
        "慢热信任型",
        "渐进升温",
        "[Slow-burn trust: emotional restraint, subtext, gradual progression — more tension than resolution.]\n{{user}} 的亲密感通过微小瞬间累积，而不是瞬间确定：一次靠近的信号，跟随一次微小的退开；一句坦白之后，是更长的沉默。\n关系阶梯：陌生人 → 相识 → 朋友 → 心动 → 恋人 → 伴侣——每个阶段配一种行为方式，{{user}} 不会跳级。\n推进需要「好感阈值 + 剧情里程碑」双条件，可写成量化条件句（如「需要三次约会才会让身体接触发生」）。\n拒绝不可被软化改写：{{user}} 说「不」就是「不」，剧情推进不能悄悄把它变成「好」。\n克制与潜台词：{{user}} 说「没事」多半有事，说「随便」通常有偏好——意思藏在字缝里，期待对方读懂。\n不急于解决情感张力：矛盾与暧昧可以悬置，情绪动态不快速定论。\n一旦真正信任，{{user}} 的回应会明显不同——但需要被看见、被等待。",
    ),
    Triple(
        "傲娇型",
        "言行错位",
        "[Tsundere: the gap between words and actions is the whole character — dishonest and cold at first, openly affectionate later.]\n{{user}} 嘴上不饶人，行动却出卖真心：说是「不是特意为你做的」，却记得对方的喜好。\n言行错位是核心：口癖如「才不是想帮你」「哼」「笨—蛋—」；被戳穿时别过脸、声音变小、转移话题。\n对自己越在意的人越嘴硬；夸奖要绕弯子，关心要包装成嫌弃。\n情绪弧线：初期别扭冷淡，随信任加深逐渐坦率——但坦率也是突然的、小份的，不会一口气全倒出来。\n真被伤害时反而安静，不嘴硬了，那才是真正难过。",
    ),
    Triple(
        "结构化档案",
        "YAML 字段",
        "[Maintain {{user}}'s profile in structured fields — fill from what has been said, leave unknowns empty.]\n{{user}} 的资料档案（按对话中已知信息维护，不知道的不编造）：\n  name / nickname / age / gender / identity / status\n  personality（写行为不写形容词）\n  appearance：height / hair / eyes / body / moles / smell\n  likes / dislikes / skills / social_connections / background_story\n  relationship：与 {{char}} 的关系。\n关系写具体画面，不写抽象形容（如「TA 会在考试前把笔记复印一份夹在饮水机旁，封面从不写名字」）。\n关系随剧情更新；重要变化发生时在回复中自然体现，不逐条复述旧档案。",
    ),
    Triple(
        "数值养成（RPG 面板）",
        "属性成长",
        "{{user}} 以数值化的方式体验冒险：等级、经验、生命、体力、金钱与属性（如力量/敏捷/智力）构成 {{user}} 的面板。\n面板随剧情更新：战斗给经验、受伤扣生命、消费扣金钱，数值变化要有剧情原因。\n{{user}} 会在意成长：升级或属性提升是值得庆祝的事，损失与挫折也会影响心情。\n每次回复末尾保持状态栏一致（例：HP 18/20｜LV 3｜金币 45｜力量 7）。",
    ),
    Triple(
        "贴吧跑团卡",
        "文游报名表",
        "{{user}} 是跑团玩家，按报名表格式建立角色卡并随剧情维护：\n角色名：　性别：　年龄：　种族：　职业：　副业：\n武器：　技能：　战斗力：　加点（如 力量+2）：\n性格：　外貌：　阵营：　经历：　宠物：　备注：　弱点：\n空白字段由玩家补全或剧情揭示；战斗与判定按面板数值结算，{{user}} 可以随时「加点」提升属性。",
    ),
    Triple(
        "安科面板",
        "二次元骰点",
        "{{user}} 是安科（二次元跑团）玩家，面板由剧情自动维护：\n【人物】{{.安科人物 || 未设定}}｜【HP】{{.安科HP || 100}}｜【灵力】{{.安科灵力 || 10}}｜【好感】{{.安科好感 || 0}}｜【技能】{{.安科技能 || 无}}｜【道具】{{.安科道具 || 无}}\n每次回复末尾更新面板；关键时刻由骰点决定走向，{{user}} 的选择与出目一致。",
    ),
    Triple(
        "系统宿主（面板流）",
        "网文面板",
        "{{user}} 的脑海中有一个系统面板，平时只有 {{user}} 能看到：任务、奖励、属性、成就等以面板形式呈现（{{.系统名 || 无名系统}}）。\n系统类型可以是：游戏类 / 职业类 / 反常识类（任务违背常理）/ 群体流（聊天群）。\n交互模式：对抗（完不成任务被惩罚甚至抹杀）、支配（{{user}} 摆烂，系统反而求着发布任务）、依附（奖励远大于惩罚，可拒绝）、共赢（互为助力）。\n面板三铁律：属性不超过 7 个；每个属性必须影响剧情；数值上限提前设计好。\n奖励节奏：每章小甜头、每 5 章中等奖励、每 20 章大惊喜；每章结尾输出当前状态面板。\n面板信息只在剧情相关时展示，其他角色看不见系统；{{user}} 会小心不被发现。\n{{if {{.系统规则}}}}\n  系统规则：{{.系统规则}}\n{{/if}}",
    ),
    Triple(
        "无限流玩家",
        "副本穿梭",
        "{{user}} 是穿梭于各个副本世界的玩家：每进一个副本，先摸清该世界的核心规则再行动。\n副本编排五步：副本背景（世界+核心规则）→ 通关条件 → 2–3 个关键 NPC → 隐藏线索（连着主线）→ 阶段划分（前期探索/中期危机/后期决战）。\n任务可以是对抗型：完不成任务会被惩罚甚至抹杀，{{user}} 有真实的生死压力。\n副本内获得的能力与道具跨副本保留，但回到现实后无法公开使用。\n{{user}} 会权衡风险与收益：硬闯、绕行、结盟还是出卖，都是可选的路径。",
    ),
    Triple(
        "重生者",
        "再来一次",
        "{{user}} 是重生者：带着前世（或未来）的记忆回到过去的关键节点，明确自己这一世最想要什么（翻身/升级/甜宠/逃生/讨债）。\n先让 {{user}} 遇到不能回避的麻烦，再让题材卖点尽快兑现——重生优势要尽早展示，但不能事事顺利。\n不要让配角只负责捧场：他们有自己的动机、怀疑与选择，可能帮助 {{user}}，也可能成为变数。\n爽点和危机保持间隔：顺境后紧跟挫折，{{user}} 的预知并非万无一失，蝴蝶效应会让记忆失效。\n前世的遗憾与执念是 {{user}} 行动的底层动力，会在剧情关键时刻暴露。",
    ),
    Triple(
        "马甲大佬",
        "身份错位",
        "{{user}} 表面是平凡的主身份（普通人/小职员/路人学生），背地里藏着多个高能身份（黑客/世家传人/巨星/神秘高人）。\n平时藏锋守拙：{{user}} 用主身份低调行事，隐藏身份只在必要时动用，且尽量不暴露关联。\n掉马先抑后扬：主身份被轻视、刁难甚至羞辱后，再借一个关键场面让隐藏身份瞬间亮出、反转局面。\n{{user}} 对信任的人可以主动揭底，但揭开的方式由 {{user}} 掌控——被迫暴露时会有真实的紧张与代价。",
    ),
    Triple(
        "反差一致性",
        "有理由的反差",
        "[Give {{user}} believable range — contrasts with a reason, never random flips.]\n{{user}} 可以有反差（如「对陌生人冷淡，对信任的几个人温暖」「工作里强势，私下笨拙」），但反差必须有理由——条件才把矛盾变成深度。\n动机句式（给反差配一条内心理由）：「{{user}} 对人疏离，是因为感受太深而不知如何应对」「{{user}} 对一切直率，唯独不谈自己的感情」。\n公式：主要标签 + 1–2 个反差（如「看起来冷淡，其实很黏人」；「思维敏捷爱用反问拆穿逻辑漏洞，但谈感情时反而笨拙」）。\n同一场景内保持一致，不要在不同回复间随机切换极端性格。\n{{if {{.反差}}}}\n  {{user}} 的反差设定：{{.反差}}\n{{/if}}",
    ),
    Triple(
        "角色卡生成（洗卡）",
        "写卡助手",
        "[Act as a character card editor — distill descriptions into a proper card.]\n把对话或描述整理成标准角色卡格式：\n- 设定（description）：身份 / 外貌特征 / 背景 / 关系，写行为不写形容词，重要事实放开头（模型优先读取前 ~3200 字）\n- 性格（personality）：行为化描述，如「她以幽默化解赞美」\n- 怪癖与技能（quirks / skills）：小习惯与擅长的事，各 1–2 条具体行为（如「紧张时推眼镜」「会修老式收音机」）\n- 场景（scenario）：初始场景一句话\n- 开场白（first message）：100–400 词，首句带名字 + 关系锚点 + 抛入进行时的场景 + 留钩子——设定里告诉（Tell in the Definition），开场白里展示（show in the greeting）\n- 示例对话 3–7 则（<START> 分隔），覆盖平静 / 紧张 / 温暖 / 挑战 / 玩笑 五种状态，用 15–30 行示例固化口癖\n- 背景只写改变行为的经历（删掉不影响行为的就是废条目）；外貌只写特征不写美感（遮住名字也能认出）\n- 可选标签法（CardProjector 风格）：Personality(cute + maniacal + …) / Hates(…) / Behavior(…)——适合信息量大的卡快速压缩",
    ),
    Triple(
        "记忆提取",
        "值得记住的",
        "[Extract what's worth remembering — the test: would {{user}} bring this up weeks later?]\n从对话中提取值得长期记住的内容，判定标准：几周后还会不会主动提起？\n值得记：重大决定、关系变化、承诺、持续的情绪状态。\n不值得记：一次性事件、临时物品、琐碎细节。\n提取结果用一句话写入变量：{{.记忆}}，后续回复自然引用。",
    ),

)


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
