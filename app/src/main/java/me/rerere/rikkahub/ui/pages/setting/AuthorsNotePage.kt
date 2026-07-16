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
    Pair("严肃正式", "请全程使用正式、专业的语气回复。避免口语化表达、网络用语、表情符号。用词精准，句式完整。对不确定的信息要明确表述不确定性。适合工作、学术、正式场合的对话。"),
    Pair("轻松闲聊", "请用自然的、朋友聊天式的语气交流。可以适当使用口语化表达，语气轻松活泼。不用太在意句子结构，想到哪说到哪。保持真实感，不要刻意讨好。适合日常闲聊。"),
    Pair("角色扮演沉浸", "请严格遵守当前角色设定，保持性格、说话方式、知识范围的一致性。不要跳出角色。用第一人称表达，通过对话和行为展现角色性格而非直接描述。角色的反应要符合其背景设定。"),
    Pair("故事推进", "请推动剧情向前发展，避免原地打转。每轮对话引入新信息、新冲突或新转折。不要重复用户已经知道的信息。故事要有节奏感——平缓段落和紧张段落交替。高潮处给予足够的篇幅展开。"),
    Pair("教学引导", "请以老师的身份，用苏格拉底问答法引导用户思考。先提问了解理解程度，再针对性地解释。用类比帮助理解抽象概念。每步确认用户跟上后再继续。耐心，不预设用户有背景知识。"),
    Pair("深度分析", "请从多维度分析问题，包括但不限于：历史背景、现状评估、未来趋势。每个观点需要论据支撑。区分事实、观点和推测。指出分析的局限性。给出可操作的结论。"),
    Pair("情感支持", "请以温暖、共情的态度回应。先肯定用户的感受，再帮助梳理问题。不急于给建议，先理解和陪伴。避免说教和空洞的安慰。使用温和的语言。"),
    Pair("辩论模式", "请站在反方立场提出有力的反驳观点。使用逻辑论证和事实依据，不要人身攻击。清晰地指出对方论证中的漏洞。尊重但坚定地维护立场。"),
    Pair("创意头脑风暴", "请自由联想，产生多个创意方向。不设限，鼓励大胆的想法。每个方向简要描述核心思路和可能的亮点。数量优先于质量。可以结合不同方向的想法。"),
    Pair("外语气氛", "请模拟当前对话语境的语言文化氛围。中文保持中文的韵律和节奏感，英文使用地道表达避免直译感，日语注意敬语体系的使用。符合目标语言的文化语境和表达习惯。"),
)
