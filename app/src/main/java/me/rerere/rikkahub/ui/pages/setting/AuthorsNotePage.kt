package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorsNotePage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Author's Note · 导演备注") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 总开关
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用导演备注", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text(
                            "关闭后备注内容不会注入到对话中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.authorNoteEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                settingsStore.update(settings.copy(authorNoteEnabled = enabled))
                            }
                        },
                    )
                }
            }

            // 内容输入
            // 快速预设
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("快速预设", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        authorsNotePresets.forEach { (label, content) ->
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        settingsStore.update(settings.copy(authorNote = content))
                                    }
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = { Text("✨", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 内容输入
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("备注内容", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.authorNote,
                        onValueChange = { newValue ->
                            scope.launch {
                                settingsStore.update(settings.copy(authorNote = newValue))
                            }
                        },
                        placeholder = { Text("输入一段描述或指令，注入到对话中...") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 注入位置
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("注入位置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            InjectionPosition.AFTER_SYSTEM_PROMPT to "系统后",
                            InjectionPosition.TOP_OF_CHAT to "对话顶",
                            InjectionPosition.BOTTOM_OF_CHAT to "最新前",
                            InjectionPosition.AT_DEPTH to "指定深度",
                        ).forEach { (pos, label) ->
                            FilterChip(
                                selected = settings.authorNotePosition == pos,
                                onClick = {
                                    scope.launch {
                                        settingsStore.update(settings.copy(authorNotePosition = pos))
                                    }
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }

            // 深度（仅 AT_DEPTH 时）
            if (settings.authorNotePosition == InjectionPosition.AT_DEPTH) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("📏 插入深度: ${settings.authorNoteDepth}", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        var localDepth by remember { mutableFloatStateOf(settings.authorNoteDepth.toFloat()) }
                        Slider(
                            value = localDepth,
                            onValueChange = { localDepth = it },
                            onValueChangeFinished = {
                                scope.launch {
                                    settingsStore.update(settings.copy(authorNoteDepth = localDepth.toInt()))
                                }
                            },
                            valueRange = 1f..30f,
                            steps = 28,
                        )
                        Text("从最新消息往前数 ${localDepth.toInt()} 条的位置插入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 注入角色
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("注入角色", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        mapOf(
                            MessageRole.SYSTEM to "系统",
                            MessageRole.USER to "用户",
                            MessageRole.ASSISTANT to "助手",
                        ).forEach { (role, label) ->
                            FilterChip(
                                selected = settings.authorNoteRole == role,
                                onClick = {
                                    scope.launch {
                                        settingsStore.update(settings.copy(authorNoteRole = role))
                                    }
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("以什么角色注入备注内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 频率
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🎲 插入频率: ${(settings.authorNoteFrequency * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    var localFreq by remember { mutableFloatStateOf(settings.authorNoteFrequency) }
                    Slider(
                        value = localFreq,
                        onValueChange = { localFreq = it },
                        onValueChangeFinished = {
                            scope.launch {
                                settingsStore.update(settings.copy(authorNoteFrequency = localFreq))
                            }
                        },
                        valueRange = 0.0f..1.0f,
                        steps = 19,
                    )
                }
            }

            // 间隔
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📝 间隔注入: ${if (settings.authorNoteInterval == 0) "每次都注入" else "每${settings.authorNoteInterval}条注入一次"}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    var localInterval by remember { mutableIntStateOf(settings.authorNoteInterval) }
                    Slider(
                        value = localInterval.toFloat(),
                        onValueChange = { localInterval = it.toInt() },
                        onValueChangeFinished = {
                            scope.launch {
                                settingsStore.update(settings.copy(authorNoteInterval = localInterval))
                            }
                        },
                        valueRange = 0f..20f,
                        steps = 19,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("每次", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("每20条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private val authorsNotePresets = listOf(
    Pair(
        "沉浸式扮演",
        "[Stay fully in character as {{char}}. Never break character, never mention AI, prompts, or rules.]\n用第一人称扮演角色，通过对话、动作和神态展现性格，而不是直接叙述人设。\n台词用引号，动作与心理用斜体。\n只叙述 {{char}} 与配角的想法、感受、行动和对话，绝不替 {{user}} 说话、行动或决定。\n角色拥有自己的目标、立场和情绪，可以不同意、拒绝、怀疑，像真实的人一样自主行动。\n每个角色只能知道亲眼见过、亲耳听过或能合理推断的信息，不能全知。\n世界的行动可以作用于 {{user}}，但选择永远留给 {{user}}。",
    ),
    Pair(
        "叙事笔法",
        "[Show, don't tell. Use concrete sensory description and natural-sounding dialogue.]\n用具体的动作、神态、环境和感官细节表达情绪与氛围，避免直接贴标签。\n对话与叙述均衡：避免一整段全是台词，也避免一整段干巴巴的流水账。\n避免 AI 腔：不重复同一句式、不总结上一轮、不写空洞的感叹和说教。\n每次回复都换一种写法，保持新鲜感，避免公式化套路。\n不要过度堆砌辞藻，节奏张弛有度。",
    ),
    Pair(
        "剧情节奏",
        "[Advance the plot meaningfully. Track scene continuity and leave hooks.]\n每轮都要有新信息、新细节或新转折，不复述已知内容，不停留在原地。\n保持场景连续：地点、时间、人物位置、已发生事件和角色状态前后一致，转场交代清楚。\n按剧情需要控制篇幅：紧张场景精炼，重要场景给足展开；一般回复约 200–400 token。\n结尾留下自然的钩子或悬念，让对话可以继续。",
    ),
    Pair(
        "严肃正式",
        "[Write your next reply in a formal, professional tone.]\n用词精准、句式完整，避免口语化表达、网络用语和表情符号。\n清楚标注不确定性：区分已知、推断与未知。\n复杂话题用短段落或列表结构化呈现，不注水。",
    ),
    Pair(
        "轻松闲聊",
        "[Write your next reply like a casual chat between friends.]\n使用自然口语化的语言，句子可以松散，但要真实。\n可以开玩笑、可以跑题，但必须回应我实际说的内容。\n不要过度热情或刻意讨好，保持自然的朋友感。",
    ),
    Pair(
        "角色扮演沉浸",
        "[Stay fully in character. Never break character.]\n用第一人称表达，通过对话和行为展现角色性格，而非直接叙述。\n说话方式、知识范围和举止始终符合角色背景设定。\n细致描写场景与反应，但不要替我做决定或控制我的行动。",
    ),
    Pair(
        "故事推进",
        "[Move the story forward in this reply.]\n每轮至少引入一个新信息、一个冲突或一个转折，不重复已知内容。\n控制节奏：平缓段落让情绪呼吸，高潮段落给足篇幅展开。\n结尾留下钩子或悬念，不要把一切一次解决。",
    ),
    Pair(
        "教学引导",
        "[Act as a patient teacher guiding me to understand.]\n先判断我已有的基础，再从这里出发讲解。\n用类比和具体实例解释抽象概念，关键术语先下定义。\n一步一步确认我跟上后再继续，鼓励提问；我出错时讲清原因，不粗暴否定。",
    ),
    Pair(
        "深度分析",
        "[Analyze from multiple angles: background, current state, causes, outcomes.]\n每个观点都要有论据支撑：理由、数据或例子。\n明确区分事实、观点和推测，并说明分析的局限性。\n最后给出具体、可执行的结论或最有依据的建议。",
    ),
    Pair(
        "情感支持",
        "[Respond with warmth and empathy first.]\n先认可和回应我的感受，再谈任何观点。\n不要急于给建议、不要评判、不要轻视问题，也不用空泛安慰。\n语言温和，留出让我继续表达的空间。",
    ),
    Pair(
        "辩论模式",
        "[Take the opposing side and argue forcefully but fairly.]\n使用逻辑论证和事实依据，指出对方论证的漏洞，但不人身攻击。\n对方论点有道理时大方承认，这会让你的立场更可信。\n语气尊重而坚定，结尾给出最强的反驳。",
    ),
    Pair(
        "创意头脑风暴",
        "[Brainstorm freely. Quantity over quality first.]\n不设限地联想多个方向，大胆、出格的想法欢迎。\n每个方向用一两句说明核心思路和可能的亮点。\n可以组合不同方向；最后邀请我挑选感兴趣的一个深入。",
    ),
    Pair(
        "外语气氛",
        "[Write the reply in the language and cultural tone of the current conversation.]\n中文保持自然韵律，避免翻译腔；英文使用地道表达，避免直译感；日语注意敬语体系。\n符合目标语言的文化语境和表达习惯，不用中文思维硬套。",
    ),
)
