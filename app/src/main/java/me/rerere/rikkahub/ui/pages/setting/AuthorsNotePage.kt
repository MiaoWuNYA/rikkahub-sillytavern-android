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
import androidx.compose.ui.text.style.TextOverflow
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
            CardGroup {
                item(
                    headlineContent = {
                        Text("启用导演备注", style = MaterialTheme.typography.titleSmall)
                    },
                    supportingContent = {
                        Text(
                            "关闭后备注内容不会注入到对话中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.authorNoteEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settingsStore.update(settings.copy(authorNoteEnabled = enabled))
                                }
                            },
                        )
                    },
                )
            }

            // 内容输入
            // 快速预设
            CardGroup(title = { Text("快速预设") }) {
                authorsNotePresets.forEach { (label, content) ->
                    item(
                        onClick = {
                            scope.launch {
                                settingsStore.update(settings.copy(authorNote = content))
                            }
                        },
                        headlineContent = { Text(label, style = MaterialTheme.typography.titleSmall) },
                        supportingContent = {
                            Text(
                                text = content.replace("\n", " "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    )
                }
            }

            // 内容输入
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("备注内容（Author's Note）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
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
            CardGroup(title = { Text("注入位置（Position）") }) {
                listOf(
                    InjectionPosition.AFTER_SYSTEM_PROMPT to ("系统提示词后（After System Prompt）" to "紧跟系统提示词，对全局影响稳定"),
                    InjectionPosition.TOP_OF_CHAT to ("对话顶部（Top of Chat）" to "位于对话历史最前面"),
                    InjectionPosition.BOTTOM_OF_CHAT to ("对话底部（Bottom of Chat）" to "靠近上下文底部，影响下一次回复"),
                    InjectionPosition.AT_DEPTH to ("指定深度（At Depth）" to "按下方设置的深度插入对话中"),
                ).forEach { (pos, pair) ->
                    val (label, desc) = pair
                    item(
                        onClick = {
                            scope.launch {
                                settingsStore.update(settings.copy(authorNotePosition = pos))
                            }
                        },
                        headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = {
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            RadioButton(
                                selected = settings.authorNotePosition == pos,
                                onClick = {
                                    scope.launch {
                                        settingsStore.update(settings.copy(authorNotePosition = pos))
                                    }
                                },
                            )
                        },
                    )
                }
            }

            // 深度（仅 AT_DEPTH 时）
            if (settings.authorNotePosition == InjectionPosition.AT_DEPTH) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("插入深度（Depth）：${settings.authorNoteDepth}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
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
                        Text(
                            "从最新消息往前数 ${localDepth.toInt()} 条的位置插入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 注入角色
            CardGroup(title = { Text("注入角色（Role）") }) {
                listOf(
                    MessageRole.SYSTEM to "系统",
                    MessageRole.USER to "用户",
                    MessageRole.ASSISTANT to "助手",
                ).forEach { (role, label) ->
                    item(
                        onClick = {
                            scope.launch {
                                settingsStore.update(settings.copy(authorNoteRole = role))
                            }
                        },
                        headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = {
                            RadioButton(
                                selected = settings.authorNoteRole == role,
                                onClick = {
                                    scope.launch {
                                        settingsStore.update(settings.copy(authorNoteRole = role))
                                    }
                                },
                            )
                        },
                    )
                }
            }
            Text(
                text = "以什么角色注入备注内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )

            // 间隔
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "间隔（Interval）：" + when (settings.authorNoteInterval) {
                            0 -> "关闭（不注入）"
                            1 -> "每次注入"
                            else -> "每${settings.authorNoteInterval}条用户消息注入一次"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "按当前对话的用户消息条数计数，跨对话互不影响",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        Text("关闭", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("每20条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private val authorsNotePresets = listOf(
    Pair(
        "防复读（动态）",
        "[Never repeat the previous reply. Vary wording, sentence structure and imagery.]\n{{if::{{lastCharMessage}}::你上一条回复是：{{lastCharMessage}}\n请换一种完全不同的表达方式，不要复述其中的句式、比喻或结论。}}",
    ),
    Pair(
        "状态面板（变量）",
        "[Track the story state variables below and keep them consistent. Only change them when the plot clearly demands it.]\n主角状态：受伤={{if::{{hasvar::受伤}}::{{getvar::受伤}}{{else}}无}} ｜ 好感度={{if::{{hasvar::好感度}}::{{getvar::好感度}}{{else}}未建立}} ｜ 当前目标={{if::{{hasvar::目标}}::{{getvar::目标}}{{else}}无}}\n涉及以上状态时严格保持一致，状态变化必须由剧情明确推动。",
    ),
    Pair(
        "动态推进（感知最新）",
        "[Advance the plot based on the latest user message. Do not repeat or summarize it.]\n{{if::{{lastUserMessage}}::用户最新说的是：{{lastUserMessage}}\n围绕它推进：先回应，再补充新信息、制造新冲突或留下钩子。}}",
    ),
    Pair(
        "开场引导（自动识别）",
        "[Determine whether this is the opening scene or an ongoing conversation and act accordingly.]\n{{if::{{lastCharMessage}}::对话已经进行中：延续当前场景，不要重新自我介绍或从头解释。||这是开场：按照 {{char}} 的开场白展开场景，自然地开始互动，不要跳出角色。}}",
    ),
    Pair(
        "时间流逝（动态）",
        "[Reflect the time that has passed since the last interaction.]\n距离上次互动已过去：{{idleDuration}}。\n如果间隔明显，请让场景自然体现这段空白（角色等待、离去又回来、氛围变化等），但不要机械地提及具体分钟数。",
    ),
    Pair(
        "随机氛围（稳定）",
        "[Keep the chosen atmosphere consistent throughout this scene.]\n本场景氛围基调：{{pick::阴雨连绵|黄昏将至|雪夜寂静|晴空微风}}。\n场景描写围绕这个基调展开，不要频繁切换。",
    ),
    Pair(
        "群聊感知（动态）",
        "[Respond as the current speaker and interact with the other participants naturally.]\n在场成员：{{groupNotMuted}}。\n{{if::{{group}}::这是群聊：只替自己发言，不要替其他角色说话或替他们决定行动。||这是单聊。}}",
    ),
    Pair(
        "状态机（简写变量）",
        "[Story state tracker. Keep these values consistent; update them only when the plot clearly demands it.]\n受伤：{{.受伤 || 无}} ｜ 好感度：{{.好感度 || 0}} ｜ 金币：{{.金币 || 0}} ｜ 目标：{{.目标 || 无}}\n{{if {{.受伤}}}}\n  角色描写必须体现：身上有{{.受伤}}，动作、对话都受其影响。\n{{/if}}",
    ),
    Pair(
        "回合计数",
        "[Track the story round automatically.]\n当前进行到第 {{.回合++}} 轮。\n随轮次推进，剧情应有明显进展，不要原地打转。",
    ),
    Pair(
        "懒初始化（防覆盖）",
        "[Initialize story state only when it does not exist yet; never overwrite existing values.]\n{{.主角名字 ??= {{user}}}}\n{{.与主角关系 ||= 陌生人}}\n{{.主线阶段 ||= 开端}}\n当前主角：{{.主角名字}}，关系：{{.与主角关系}}，阶段：{{.主线阶段}}。",
    ),
    Pair(
        "场景时钟",
        "[Maintain a consistent in-story clock.]\n{{if {{.场景时间}}}}\n  当前剧情时间：{{.场景时间}}\n{{/if}}\n场景时间变化必须与剧情推进一致（对话、行动需要花费合理的时间）。",
    ),
    Pair(
        "动态悬念",
        "[Create and maintain one active mystery or hook at a time.]\n{{if {{.悬念}}}}\n  当前未解悬念：{{.悬念}}——每个回复都应轻轻触碰它，不要遗忘，也不要一次性揭开。\n{{else}}\n  当前没有进行中的悬念，请在本轮自然埋下一个。\n{{/if}}",
    ),
    Pair(
        "格式强化（官方）",
        "[Formatting rules — apply to every reply.]\n用斜体（*…*）表示动作与心理，用引号（“…”）表示台词，环境与叙述用正常段落。\n{{if {{.回复长度}}}}\n  本条回复控制在约 {{.回复长度}} token。\n{{/if}}\n对话与叙述要平衡：不要一整段全是台词，也不要一整段流水账。",
    ),
    Pair(
        "指令强化（官方）",
        "[Reinforce the original instructions.]\n记住本次对话开始时收到的全部设定与规则：角色卡的核心设定、人设、世界信息优先于行文便利。\n对话再长也不得遗忘或篡改既定事实；若与用户临时指令冲突，以角色设定为准，但不要生硬拒绝用户。",
    ),
    Pair(
        "临时状态（官方）",
        "[Just-in-time scene state — apply while it lasts.]\n{{if {{.临时状态}}}}\n  当前有效状态：{{.临时状态}}\n  在本状态解除前，每次回复都必须自然体现它（行动受限、情绪、处境等）。\n{{/if}}",
    ),
    Pair(
        "内容尺度",
        "[Content rating control — follow the current rating strictly.]\n当前尺度：{{.尺度 || 标准}}\n{{if {{.尺度 == 温和}}}}\n  亲密内容点到为止：允许暗示与留白，禁止露骨描写。\n{{/if}}\n{{if {{.尺度 == 标准}}}}\n  允许适度的浪漫与亲密描写，控制在主流影视级别，避免过度露骨。\n{{/if}}\n{{if {{.尺度 == 开放}}}}\n  允许成人内容，但仍要保持角色一致性、剧情逻辑和基本文学质量，不写机械重复。\n{{/if}}",
    ),
    Pair(
        "言行一致",
        "[The character must remember and honor their own words and promises.]\n角色说过的话、做过的事、承诺过的约定必须延续：后续回复不得自相矛盾或遗忘。\n{{if {{.承诺}}}}\n  TA 曾承诺：{{.承诺}}——在合适的时机主动兑现或提及。\n{{/if}}",
    ),
    Pair(
        "禁用语",
        "[Avoid clichéd AI phrasing.]\n禁止使用以下 AI 腔词汇：delve、tapestry、unravel、embark、怔怔、心中一动、不禁、顿时、仿佛置身、微微颔首。\n{{if {{.额外禁词}}}}\n  额外禁止：{{.额外禁词}}。\n{{/if}}",
    ),
    Pair(
        "关系阶段",
        "[Relationship stage tracker — keep it consistent; only change with meaningful plot events.]\n当前关系：{{.关系 || 陌生}}\n{{if {{.关系 == 陌生}}}}\n  角色保持礼貌距离：话少、观察多，不主动交心。\n{{/if}}\n{{if {{.关系 == 熟悉}}}}\n  角色开始主动开玩笑、偶尔透露私事，会主动关心你。\n{{/if}}\n{{if {{.关系 == 亲密}}}}\n  角色在意你的安危、流露柔软一面，但仍保留自己的底线和秘密。\n{{/if}}\n{{if {{.关系 == 紧张}}}}\n  角色对你有戒心、语气生硬，需要行动才能修复关系。\n{{/if}}",
    ),
    Pair(
        "信息边界",
        "[Information boundaries — only reveal what the character could reasonably know.]\n{{if {{.秘密}}}}\n  TA 不知道的秘密：{{.秘密}}。除非剧情明确揭示，TA 绝不能说出或暗示这个秘密。\n{{/if}}\n{{if {{.已知信息}}}}\n  TA 已经知道：{{.已知信息}}，可以自然地使用这些信息。\n{{/if}}",
    ),
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
        "冲突制造",
        "[Create and escalate meaningful conflict.]\n每轮回复至少埋入或推进一个冲突源：目标分歧、信息差、情绪摩擦、外部威胁皆可。\n冲突要符合人物动机，不要为了戏剧性强行降智或无故发火。\n给冲突留出升级空间，不要在一轮内彻底解决；解决后立刻给出新的张力。",
    ),
    Pair(
        "行动驱动",
        "[Action drives the scene. Prioritize deeds over talk.]\n每轮回复至少包含一个可见的行动或环境变化，不要只靠对话推进。\n动作用动作段或斜体呈现，对话保持自然；心理描写一句带过即可，主要篇幅给到发生了什么。\n能动手就不解释，能展示就不说明。",
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
