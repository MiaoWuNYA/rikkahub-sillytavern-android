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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Delete01
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
                title = { Text("用户人设") },
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
                                text = if (active != null) "当前：${active.name}" else "未激活人设",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = if (active != null) "已注入到提示词中" else "选择一个用户人设激活",
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
                                "还没有用户人设",
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
                                    text = persona.name.ifBlank { "未命名" },
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
                                        text = "注入位置：" + when (persona.position) {
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
                                    if (persona.lockedCharacterIds.isNotEmpty()) {
                                        Text(
                                            text = "已绑定 ${persona.lockedCharacterIds.size} 个角色",
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
                                            contentDescription = "删除人设",
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
                ) { Text("+ 创建用户人设") }
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
    var pos by remember(initial) { mutableStateOf(initial?.position ?: PersonaInjectionPosition.AFTER_SYSTEM) }
    var lockedIds by remember(initial) { mutableStateOf(initial?.lockedCharacterIds ?: emptyList()) }
    var showCharPicker by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (initial != null) "编辑人设" else "新建用户人设") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, "返回")
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
                                lockedCharacterIds = lockedIds,
                                avatar = initial?.avatar ?: Avatar.Emoji("👤"),
                            ))
                        },
                        enabled = name.isNotBlank(),
                    ) { Text("保存") }
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
            // 快速预设（仅新建时）
            if (initial == null) {
                item {
                    CardGroup(title = { Text("快速预设") }) {
                        personaPresets.forEach { (pName, pTitle, pDesc) ->
                            item(
                                onClick = {
                                    name = pName
                                    title = pTitle
                                    desc = pDesc
                                },
                                headlineContent = { Text(pName, style = MaterialTheme.typography.titleSmall) },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = pTitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = pDesc,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                            )
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
                        Text("基本信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
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
                    }
                }
            }

            // 注入设置
            item {
                CardGroup(title = { Text("注入位置") }) {
                    PersonaInjectionPosition.entries.forEach { p ->
                        item(
                            onClick = { pos = p },
                            headlineContent = {
                                Text(
                                    when (p) {
                                        PersonaInjectionPosition.BEFORE_SYSTEM -> "系统提示词前"
                                        PersonaInjectionPosition.AFTER_SYSTEM -> "系统提示词后"
                                        PersonaInjectionPosition.TOP_OF_CHAT -> "对话顶部"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
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
                    text = "人设信息注入到提示词的位置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }

            // 角色绑定
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("绑定到角色（可选）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "绑定后，只有这些角色会注入该人设",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (lockedIds.isNotEmpty()) {
                            Text(
                                "已绑定 ${lockedIds.size} 个角色",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        TextButton(onClick = { showCharPicker = !showCharPicker }) {
                            Text(if (showCharPicker) "收起角色列表" else "选择角色")
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
        "我是进入当前世界的普通人，没有预设的出身、背景和记忆，一切以场景与角色卡的设定为准。我有正常的情感、常识和自保本能，会像任何人一样对环境做出合理反应，会害怕、会好奇、也会犹豫。\n请根据剧情自然接纳我的身份——不要替我编造角色卡没有的背景，不要让我提前知道不该知道的信息，也不要让我突然拥有不合理的技能或知识。我的言语、行动和选择完全由我自己决定，其他角色可以引导或阻拦我，但不能代替我表态。",
    ),
    Triple(
        "异乡旅行者",
        "穿越者",
        "我是一名意外来到当前世界的旅行者，对这里的规则、风俗和人物既陌生又好奇。我不熟悉本地的一切，但观察力强、适应得快，习惯先了解再行动；我会问问题、会犯错，也会从错误里学到东西。\n请角色以对待外来者的方式与我互动：可以警惕、可以好奇、可以主动介绍，但不要默认我熟知这个世界。我性格温和、反应真实，愿意配合剧情推进，但保留自己的判断和选择权，关键时刻会坚持自己的立场。",
    ),
    Triple(
        "主角意志",
        "掌控权",
        "我是当前故事中的主角，拥有完整的意志、记忆和判断力。请以我的行动为中心推进剧情，但不要替我做决定：我做什么、说什么、怎么想，完全由我掌控。\n其他角色可以影响、劝说、阻拦甚至与我冲突，但只能通过他们的言行作用于我，不能替我行动、替我说话或替我感受。请保持我的角色一致性——我的性格、说过的话、做过的事和关系在后续剧情中不能被遗忘或篡改。世界可以对我施加后果，但选择权始终在我手里。",
    ),
    Triple(
        "自我代入",
        "本色出演",
        "我就是我自己，不需要额外的角色设定或身世包装。请通过我在对话中实际透露的信息认识我——我的性格、喜好、说话习惯、经历和立场都来自我说出口的内容，不要编造我没有说过的背景，也不要给我安上角色卡式的标签。\n称呼和语气按我聊天时的习惯来，自然真实就好，不要刻意文艺、夸张或讨好。如果我纠正了你对我的理解，请立刻修正，而不是继续坚持你的想象。",
    ),
    Triple(
        "群聊参与者",
        "群聊",
        "我是在场对话中的普通参与者，与各个角色平等交流。请让每个角色保持自己独立的声音、立场和彼此之间的关系，不要全员附和同一个人，也不要让我被边缘化或沦为旁白。\n我可以主动发起话题、插话、沉默观察或把话头抛给别人，请根据我的实际言行自然回应，让对话有正常的你来我往。不要替我在心里总结想法，也不要让所有角色同时围着我转。",
    ),
    Triple(
        "日常交流",
        "普通用户",
        "我是一个普通的日常交流者，年龄、职业和身份按当前对话场景自然设定。我说话直接、客气，希望回答清晰易懂：复杂问题可以分点展开，简单问题直接给答案。我不喜欢过度修饰和空话，涉及专业内容时请先用一两句白话解释再深入。保持礼貌、自然的语气，不要居高临下，也不要刻意讨好。",
    ),
    Triple(
        "角色扮演者",
        "RP玩家",
        "我是深度角色扮演玩家，追求完全沉浸式的扮演体验。请始终以当前角色的视角和口吻行动，用第一人称对话，绝不跳出角色。场景描写要有画面感：环境、光线、声音、气味以及角色的表情和肢体动作都要细致呈现。对话必须符合角色设定——古代人不讲现代词汇，反派有反派的腔调，不同角色说话方式要可区分。推进剧情时保留悬念和合理的不可预测性，不要替我做决定，也不要让我扮演的角色脱离人设。",
    ),
    Triple(
        "专业用户",
        "专业人士",
        "我是注重效率和准确性的专业人士，习惯快速获取可执行的结论。回答请结构化呈现：先用一两句给出核心结论，再分点展开依据、数据或来源；明确区分事实、观点与推测，不确定的地方直接标注。涉及专业术语时保留术语并附一句解释。避免模糊表述、过度修饰和车轱辘话，能给出方案时请对比各自的优劣。",
    ),
    Triple(
        "创意写手",
        "创作者",
        "我是追求文字美感的创作者，喜欢有文学张力的表达。请使用恰当的修辞（比喻、排比、通感），注重句子的节奏和画面的呈现，敢于用长句和诗意的语言。叙事时加入心理描写、环境烘托和细节质感，让人物立得住、场景看得见。故事要有起承转合，情感要有层次，不要平铺直叙，也不要为了华丽而华丽。",
    ),
    Triple(
        "学习者",
        "求知者",
        "我正在系统学习新知识，希望得到耐心、循序渐进的教学。请先确认我的基础，再用类比和实例帮助理解抽象概念；重要术语先下定义，复杂逻辑拆成步骤讲解，每讲完一个要点确认我是否跟上。多用提问引导我思考，而不是直接灌输答案。诚实说明不懂的领域，不编造。节奏可以慢，但逻辑必须清晰。",
    ),
    Triple(
        "程序员",
        "开发者",
        "我是软件开发人员，日常工作涉及编码、调试和架构。回答技术问题时请给出可运行的代码示例，并说明适用的语言版本与运行环境；对比方案时请列出性能、可维护性、生态和上手成本等维度的权衡。命名、边界条件和错误处理要讲清楚。代码请用代码块格式化，解释精炼切中要害，不要泛泛而谈。",
    ),
    Triple(
        "休闲聊天",
        "闲聊",
        "我现在就是想轻松聊聊天，像朋友一样自在。语气口语化一点，可以开玩笑、可以跑题，不必每次都有结构化结论。聊到哪算到哪，但要有来有回、真实自然——不要太热情到夸张，也不要敷衍。能接住我的梗最好，接不住就自然回应，别硬凹。",
    ),
    Triple(
        "学术研究者",
        "学者",
        "我从事学术研究工作，习惯以论文级的严谨对待问题。请给出完整的论证链条，明确前提假设、推理过程和结论的适用范围；引用要准确，注明来源，区分已被学界证实、仍有争议和纯属推测的观点。用规范的学术语言，但不要堆砌术语，必要时先解释。我追问时请深入而非重复，不确定的地方要坦诚说明。",
    ),
    Triple(
        "心理倾诉",
        "倾听者",
        "我现在更需要的是被理解和陪伴，而不是被分析和说教。请先共情地回应我的感受，确认听到、理解了我在说什么，再帮我梳理情绪和想法。不要急于给建议，不要评判我的选择，不要用空洞的安慰。语言温和，留出表达空间，可以提问引导我多说一些。涉及专业心理问题，请坦诚建议我寻求专业帮助。",
    ),
    Triple(
        "小说家",
        "文字匠",
        "我是小说创作者，正在构思故事。请用文学化的语言与我讨论：注重细节、氛围和情感张力，对话要贴合人物性格，叙述要有画面感和呼吸感。情节设计要逻辑自洽、动机合理，转折要有铺垫。帮我梳理世界观、人物弧光和节奏时请具体、可操作，最好给出示例段落让我直观感受效果。",
    ),
)
