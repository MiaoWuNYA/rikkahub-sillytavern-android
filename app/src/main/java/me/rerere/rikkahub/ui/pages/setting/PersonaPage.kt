package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Persona
import me.rerere.rikkahub.data.model.PersonaInjectionPosition
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
                title = { Text("Persona · 用户人设") },
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
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (active?.avatar as? Avatar.Emoji)?.content ?: "👤",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (active != null) "当前: ${active.name}" else "未激活 Persona",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = if (active != null) "已注入到提示词中" else "选择一个 Persona 激活",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
                                "还没有 Persona",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "创建一个用户人设，注入到对话中让 AI 了解你的身份与风格",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(settings.personas.sortedBy { it.name }, key = { it.id }) { persona ->
                val isActive = settings.activePersonaId == persona.id
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 头像
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
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = persona.name.ifBlank { "未命名" },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (persona.title.isNotBlank()) {
                                    Text(
                                        text = persona.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = "📍 " + when (persona.position) {
                                        PersonaInjectionPosition.BEFORE_SYSTEM -> "系统提示词前"
                                        PersonaInjectionPosition.AFTER_SYSTEM -> "系统提示词后"
                                        PersonaInjectionPosition.TOP_OF_CHAT -> "对话顶部"
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
                                // 锁定角色标识
                                if (persona.lockedCharacterIds.isNotEmpty()) {
                                    Text(
                                        text = "🔒 已绑定 ${persona.lockedCharacterIds.size} 个角色",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // 操作栏
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                val s = settings
                                scope.launch {
                                    settingsStore.update(if (isActive) s.copy(activePersonaId = null)
                                    else s.copy(activePersonaId = persona.id))
                                }
                            }) {
                                Text(
                                    if (isActive) "停用" else "激活",
                                    color = if (isActive) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary,
                                )
                            }
                            TextButton(onClick = { editingPersona = persona }) {
                                Text("编辑", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val s = settings
                                    settingsStore.update(s.copy(
                                        personas = s.personas.filter { it.id != persona.id },
                                        activePersonaId = if (isActive) null else s.activePersonaId,
                                    ))
                                }
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // 新建按钮
            item {
                OutlinedButton(
                    onClick = { showCreate = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("+ 创建 Persona") }
            }
        }
    }

    // 编辑/创建对话框
    val dialogPersona = editingPersona
    if (showCreate || dialogPersona != null) {
        PersonaEditDialog(
            initial = dialogPersona,
            assistants = settings.assistants,
            onDismiss = { showCreate = false; editingPersona = null },
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

@Composable
private fun PersonaEditDialog(
    initial: Persona?,
    assistants: List<me.rerere.rikkahub.data.model.Assistant>,
    onDismiss: () -> Unit,
    onSave: (Persona) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var title by remember(initial) { mutableStateOf(initial?.title ?: "") }
    var desc by remember(initial) { mutableStateOf(initial?.description ?: "") }
    var pos by remember(initial) { mutableStateOf(initial?.position ?: PersonaInjectionPosition.AFTER_SYSTEM) }
    var lockedIds by remember(initial) { mutableStateOf(initial?.lockedCharacterIds ?: emptyList()) }
    var showCharPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "编辑 Persona" else "新建 Persona") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 预设快速选择
                Text("快速预设", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    personaPresets.forEach { (pName, pTitle, pDesc) ->
                        AssistChip(
                            onClick = {
                                name = pName
                                title = pTitle
                                desc = pDesc
                            },
                            label = { Text(pName, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Text("✨", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("短标题（展示用，可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("不填则使用名称") },
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("描述（外表/背景）") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("注入位置", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PersonaInjectionPosition.entries.forEach { p ->
                        FilterChip(
                            selected = pos == p,
                            onClick = { pos = p },
                            label = { Text(
                                when (p) {
                                    PersonaInjectionPosition.BEFORE_SYSTEM -> "系统前"
                                    PersonaInjectionPosition.AFTER_SYSTEM -> "系统后"
                                    PersonaInjectionPosition.TOP_OF_CHAT -> "对话顶"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )},
                        )
                    }
                }
                // 角色锁定
                Text("绑定到角色（可选）", style = MaterialTheme.typography.labelSmall)
                if (lockedIds.isNotEmpty()) {
                    Text(
                        "已绑定 ${lockedIds.size} 个角色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                TextButton(onClick = { showCharPicker = !showCharPicker }) {
                    Text(if (showCharPicker) "收起" else "选择角色")
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
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(Persona(
                            id = initial?.id ?: Uuid.random(),
                            name = name,
                            title = title,
                            description = desc,
                            position = pos,
                            lockedCharacterIds = lockedIds,
                            avatar = initial?.avatar ?: Avatar.Emoji("👤"),
                        ))
                    }
                },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private val personaPresets = listOf(
    Triple("普通用户", "一般交流", "我是一个普通用户，在日常交流和问题咨询中保持礼貌。请用清晰直接的语言回答，避免过多修饰。对于复杂问题可以分点说明，但对于简单问题直接给出答案即可。"),
    Triple("角色扮演者", "RP玩家", "我喜欢沉浸式的角色扮演体验。请用生动的文学性语言描述场景、角色的表情动作和环境氛围。说话方式要符合角色设定——古代人不说现代词汇，反派有反派的腔调。世界构建要细致，角色对话要有辨识度。"),
    Triple("专业用户", "专业人士", "我注重效率和准确性。请提供有数据支撑、有来源的答案。用结构化方式组织信息（分段、列表、对比），避免模糊表述和过度修饰。如果涉及专业术语请保留并解释。需要区分事实和观点。"),
    Triple("创意写手", "创作者", "我追求文字的美感和表现力。请使用丰富的修辞手法（比喻、排比、通感），注重语言的节奏感和画面感。可以适当使用文学化的表达方式，在叙事中加入心理描写和环境烘托。故事要有起承转合。"),
    Triple("学习者", "求知者", "我正在学习新知识，请以教育者的身份耐心讲解。用类比和实例帮助理解，由浅入深地展开。重要概念要定义清楚，复杂逻辑要拆解步骤。可以提问检验理解程度。不懂的领域直接说不知道。"),
    Triple("程序员", "开发者", "我是软件开发人员。回答技术问题时请给出可运行的代码示例，说明适用版本和环境。对比不同方案的优劣（性能/可维护性/生态）。涉及架构决策时请列出权衡。用代码块格式化。"),
    Triple("休闲聊天", "闲聊", "随意聊聊，像朋友一样轻松。可以开玩笑，语气口语化。不需要严肃的结构化回答，想到哪说到哪。但不要过于热情或过于冷淡，保持自然的朋友感。"),
    Triple("学术研究者", "学者", "我从事学术研究。请提供严谨的论证过程，明确前提假设和推理链条。引用需要准确，区分已被证实和仍在争议的观点。使用规范的学术语言，但不要卖弄术语。不确切的地方要注明。"),
    Triple("心理咨询", "倾听者", "我寻求情感支持。请以共情、温暖的方式回应，先理解和认可感受，再分析问题。避免武断的建议和评判。使用支持性语言，给予空间让用户表达。涉及专业心理问题请建议咨询专业人士。"),
    Triple("小说家", "文字匠", "我是小说创作者，在构思故事。请用文学化的语言表达，注重细节描写和情感渲染。对话要符合人物性格，叙述要有画面感。敢于使用长句和诗意的表达。故事逻辑要自洽，人物动机要合理。"),
)
