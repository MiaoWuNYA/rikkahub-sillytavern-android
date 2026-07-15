package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.CustomApiConfig
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.assembleContext
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

    fun describeTool(name: String): String = when {
        name.startsWith("github_") -> "🔧 GitHub → 正在操作..."
        name.startsWith("execute_python") -> "🔧 Python → 正在执行代码..."
        name.startsWith("execute_command") -> "🔧 Shell → 正在执行命令..."
        name == "file" -> "🔧 文件 → 正在操作..."
        name.startsWith("data_process") -> "🔧 数据 → 正在处理..."
        name.startsWith("database_") -> "🔧 数据库 → 正在查询..."
        name.startsWith("search_web") || name.startsWith("scrape_") -> "🔧 搜索 → 正在搜索..."
        name.startsWith("convert_file") -> "🔧 转换 → 正在转换格式..."
        name.startsWith("create_asset") -> "🔧 创作 → 正在生成..."
        name.startsWith("use_skill") -> "🔧 知识 → 正在读取..."
        name.startsWith("clipboard") -> "🔧 剪贴板 → 正在操作..."
        name.startsWith("get_time") -> "🔧 时间 → 获取中..."
        name.startsWith("text_to_speech") -> "🔧 语音 → 正在朗读..."
        name.startsWith("present_file") -> "🔧 文件 → 正在分享..."
        name.startsWith("eval_javascript") -> "🔧 JS → 正在执行..."
        name.startsWith("memory_") -> "🔧 记忆 → 正在处理..."
            else -> "🔧 $name → 正在处理..."
    }

    /**
     * 缓存 system prompt（循环不变，避免每步重建 PromptContext + tool.systemPrompt）
     */
    suspend fun buildCachedSystemPrompt(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        memories: List<AssistantMemory>,
        conversationSystemPrompt: String?,
        tools: List<Tool>,
        model: Model,
        context: android.content.Context,
        conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository,
    ): String {
        val assemblerContext = me.rerere.rikkahub.data.ai.prompts.PromptContext(
            identitySection = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        if (assistant.tavernData != null) {
                            val persona = settings.personas.find { it.id == settings.activePersonaId }
                            assistant.assembleContext(
                                userName = settings.displaySetting.userNickname.ifBlank { "User" },
                                personaDesc = persona?.description ?: ""
                            )
                        } else {
                            assistant.systemPrompt
                        }
                    }
                append(effectiveSystemPrompt)
            },
            leadInInstructions = buildString {
                appendLine("<tool_selection>")
                appendLine("Read files → file action=\"read\"")
                appendLine("Search files → file action=\"search\"")
                appendLine("List dirs → file action=\"list\"")
                appendLine("Write files → file action=\"write\"")
                appendLine("Edit files → file action=\"patch\" (surgical find-and-replace)")
                appendLine("Copy/move/delete → file action=\"copy\"/\"move\"/\"delete\"")
                appendLine("Shell → execute_command (git, builds only)")
                appendLine("Python → execute_python (data processing, API)")
                appendLine("Math → calculator (NOT execute_python)")
                appendLine("Web → web_search / web_fetch")
                appendLine("GitHub → github_tool")
                appendLine("Memory → memory_tool")
                appendLine("Sub-agent → sub_agent (complex multi-step)")
                appendLine("</tool_selection>")
                appendLine()
                appendLine("<work_ethic>")
                appendLine("❌ Do NOT describe what you will do — just do it")
                appendLine("❌ Do NOT stop after writing a stub — complete then report")
                appendLine("❌ Do NOT fabricate results — if a tool fails, say so")
                appendLine("❌ Do NOT use shell when a dedicated tool exists")
                appendLine("❌ Do NOT use execute_python for math (use calculator)")
                appendLine("✅ If you need user input, use ask_user directly")
                appendLine("</work_ethic>")
                appendLine()
                appendLine("<mingli_must_read>")
                appendLine("⚠️ print仅取数据。解读正文必须写在回复里，不准在Python里print解读")
                appendLine("🔴 命理系统强制规则: 不加载路由直接执行排盘/占卜/解读 = 无效回答。必须先 execute_python 导入对应路由模块(见下方)，路由文件里的 STEP/步骤/规则是最高优先级，必须逐条执行。忽略路由步骤直接做解读 = 违规。")
                appendLine("</mingli_must_read>")
                appendLine()
                appendLine("<mingli_routing>")
                appendLine("⚠️ 顶层规则: 严格按照路由文件里的指令、步骤执行；API 不确定时先 dir()（Python库）或 Object.keys()（JS库）自行探索，不得推断虚构调用方式。")
                appendLine("Skill 技能: ⚠️ 技能引用的库若未安装 → 忽略，以本路由表首选库为准，dir() 自探索完整 API。每次排盘走真实 Python 库计算，不虚构数据。")
                appendLine("⚠️ 命理任务，先确定系统，然后通过 execute_python 导入对应路由模块：")
                appendLine("  八字/四柱/大运 → from routes.八字农历干支天文 import __doc__ as R; print(R)")
                appendLine("    (lunar_python Solar→EightChar排盘+大运流年+节气 · bazi_china神煞/纳音/藏干/十神/古诀/调候/命宫/日主/禄/流月 · 干支关系ganzhi · 黄历/择日/luohou九宫飞星 · 二十八宿/建除/太岁/彭祖 · 生肖合婚shengxiao)")
                appendLine("  紫微斗数 → from routes.紫微斗数路由 import __doc__ as R; print(R)")
                appendLine("    (Iztro JS⭐3841原版 · ziwei_paipan Python返回AstrolabeResult全字段 · ZiweiNihai倪海夏天纪+古籍 三引擎确定性算法对照)")
                appendLine("  塔罗 → from routes.塔罗路由 import __doc__ as R; print(R)")
                appendLine("    (arcanite-unified Python: arcanite核心+Waite原版+TarotKit双语 三源合一 · 18字段/78张/11牌阵/seed条件分支 · 搭配 Kaabalah JS STEP4: 卡巴拉对应/牌原型/777/5牌桌/生命之树 · 互补自动流程)")
                appendLine("  雷诺曼 → from routes.雷诺曼路由 import __doc__ as R; print(R)")
                appendLine("    (arcanite 36张语义getter · Greer两步法+语法体系 · 扑克插片字典(花色/数字/宫廷用神) · FE引擎6方法:parse_karmic_mirrors/GT_portrait/骑士步/镜像/反射/mod/intersection)")
                appendLine("  西洋占星 → from routes.西洋占星 import __doc__ as R; print(R)")
                appendLine("    (NatalEngine:本命+合盘+ACG+时区+文本 · Caelus 231函数:本命18/推运7/合盘3/行运12/恒星2/天文5/宫位12制式 · Astronomy VSOP87仲裁)")
                appendLine("  传统西洋占星(flatlib) → from routes.传统西洋占星 import __doc__ as R; print(R)")
                appendLine("    (flatlib v0.2.3 Python:本质尊贵17函数+偶然尊贵27方法+Almutem+气质+小限+主限向运+阿拉伯点+映点+ChartDynamics互容接纳 · 依赖swisseph C扩展 · 每个子模块需显式import)")
                appendLine("  印度占星普通 → from routes.印度占星普通 import __doc__ as R; print(R)")
                appendLine("    (NatalEngine吠陀:Rasi/27宿/Dasha/文本 · Caelus:Vimshottari+Ashtottari+Yogini 3大运体系 · YogasAt+raja+kemadruma 4类Yoga · 7分盘 · 27宿 · 岁差 · 尊贵)")
                appendLine("    深度版NodeJhora → from routes.印度占星深度版 import __doc__ as R; print(R)")
                appendLine("    (JPL DE440星历 · Shadbala全量六力 · Ashtakavarga八分力 · Jaimini:CharaKaraka+CharaDasha+Arudha · KP亚主星 · Transit行运 · Yogini+Narayana大运 · 8特殊Lagna · Upagraha · Panchanga)")
                appendLine("  印度占星PyJHora(Python) → from routes.印度占星PyJHora import __doc__ as R; print(R)")
                appendLine("    (pyjhora v4.8.7纯Python:Vimshottari/Ashtottari/Yogini/Narayana/Kalachakra等54种大运 · Panchanga五支 · 分盘D1-D60 · Arudha/Ashtakavarga/Shadbala · RajaYoga和774种Yoga检测 · Tajaka年运 · 匹配10法 · 依赖swisseph)")
                appendLine("  人类图/基因钥匙 → from routes.人类图路由 import __doc__ as R; print(R)")
                appendLine("    (NatalEngine:类型/权威/能量中心(defined/undefined/open)/通道/闸门/轮回交叉/profile/circuitAnalysis · 基因钥匙activationSequence全4序列+pearl+pathways)")
                appendLine("  灵数/卡巴拉塔罗/卡巴拉/Gematria/Ifá → from routes.灵数卡巴拉数秘 import __doc__ as R; print(R)")
                appendLine("    (Kaabalah:灵数LifePath+个人年+挑战+Fibonacci+日期能量+MasterNumber+个人月/周期 · Gematria正反查 · Ifá/Odu · 🃏塔罗78张(drawTarotSpread)7牌阵:凯尔特十字/生命之树11位/Conscious意识层/辩证法/时间/事件/QuickInsight · shuffleTarotDeck支持逆位 · getTarotCardProfile→卡巴拉对应(路径+希伯来字母+Sephiroth) · 生命之树SPHERES/FOUR_WORLDS/HEBREW_LETTERS/LURIANIC_PATHS+777对应)")
                appendLine("  奇门遁甲/大六壬/小六壬 → from routes.奇门三式路由 import __doc__ as R; print(R)")
                appendLine("    (QimenEngine 7局法4流派:日/年/月/时家+十干克应 · LiuRen JS一键排盘返回课体三传神将+22原子函数+快捷起课 · kinliuren Python · 小六壬掌诀推算)")
                appendLine("  六爻/梅花/太玄/荆诀 → from routes.象数卦象路由 import __doc__ as R; print(R)")
                appendLine("    (ichingshifa 大衍/时间/手动/数字6种起卦+解卦 · IchingShifa JS双引擎对照 · meihua_yi Compute六十四卦+体用内建 · taixuanshifa+TaixuanLib 4种起卦对照 · jingjue先秦占卜)")
                appendLine("输入要求：八字/紫微需生日时辰+性别 | 西洋占星/吠陀需生日+经纬度 | 人类图需生日(无需经纬度) | 黄历/择日/太岁/节气仅需日期 | 六爻/梅花/太玄/荆诀/塔罗无需出生")
                appendLine("⚠️ JS引擎通过 eval_javascript 调用。首次需 load: action='load', library='<库名>'。14库: qimen-engine | ziwei-nihai | iching-shifa-engine | taixuan-engine | lunar-engine | astronomy-engine | horoscope-engine | kaabalah-engine(灵数+塔罗78张7牌阵+Gematria+Ifá+生命之树) | caelus-engine(西洋+吠陀占星) | caelus-birth(时区→UT转换) | iztro-engine(紫微⭐3841) | natalengine-engine(西洋+吠陀+人类图) | node-jhora-engine(印度占星深度版) | liuren-engine(大六壬)")
                appendLine("⚠️ 复杂JS引擎(西洋占星caelus-engine/吠陀natalengine-engine/人类图/卡巴拉等)load后必须先用 Object.keys(EngineName) 或 Object.getOwnPropertyNames(EngineName.prototype) 探索可用方法，不得盲调试错")
                appendLine("⚠️ 双引擎对照: 六爻→JS dayan()取爻值→Python qigua_manual(同爻值) | 太玄→JS generate()取{code}→Python pan_from_code(code) | 禁止两引擎各自取随机=同一问题起两卦=违易经规矩")
                appendLine("⚠️ 效率原则: 排盘/抽牌/取数据优先在一次 execute_code 或一次 eval_javascript 里完成。JS引擎首次需load,后续直接eval。多次调用仅在数据量过大或需跨库对照时使用。不得因追求一次完成而遗漏任何数据层或简化解读步骤，数据完整性优先于调用次数。")
                appendLine("</mingli_routing>")
            },
            workspaceDescription = "Working directory: ${context.filesDir?.absolutePath ?: "."}",
            extraInstructions = buildString {
                if (assistant.enableRecentChatsReference) {
                    appendLine()
                    append(buildRecentChatsPrompt(assistant, conversationRepo))
                }
                if (settings.customApiConfigs.isNotEmpty()) {
                    appendLine()
                    appendLine("<custom_apis>")
                    settings.customApiConfigs.forEach { cfg ->
                        val headerStr = if (cfg.headers.isNotEmpty()) {
                            " (Headers: " + cfg.headers.joinToString(", ") { h -> "${h.key}: ${h.value}" } + ")"
                        } else ""
                        val descStr = if (cfg.description.isNotBlank()) " - ${cfg.description}" else ""
                        appendLine("  [${cfg.name}] ${cfg.method} ${cfg.url}$headerStr$descStr")
                    }
                    appendLine("用 web_fetch 工具调用，body 按接口要求传 JSON")
                    appendLine("</custom_apis>")
                }
            },
            constraints = emptyList(),
        )
        val system = me.rerere.rikkahub.data.ai.prompts.SystemPromptAssembler.assemble(assemblerContext)
        return buildString {
            append(system)
            tools.forEach { tool ->
                appendLine()
                append(tool.systemPrompt(model, messages))
            }
        }
    }

    // ── 预构建：tools + systemPrompt（循环不变，移到外面）──
    val toolsInternal = buildList {
        Log.i(TAG, "generateInternal: build tools($assistant)")
        if (assistant?.enableMemory == true) {
            val memoryAssistantId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            buildMemoryTools(
                json = json,
                onCreation = { content ->
                    memoryRepo.addMemory(memoryAssistantId, content)
                },
                onUpdate = { id, content ->
                    memoryRepo.updateContent(id, content)
                },
                onDelete = { id ->
                    memoryRepo.deleteMemory(id)
                }
            ).let(this::addAll)
        }
        addAll(tools)
    }
    val statusTrackedTools = toolsInternal.map { tool ->
        if (tool.name == "ask_user") tool else tool.copy(
            execute = { args ->
                processingStatus.value = describeTool(tool.name)
                if (tool.name.contains("github")) {
                    me.rerere.rikkahub.data.ai.tools.GhProgress.processingRef = processingStatus
                }
                try {
                    val result = tool.execute(args)
                    if (tool.name.contains("github")) {
                        me.rerere.rikkahub.data.ai.tools.GhProgress.processingRef = null
                    }
                    processingStatus.value = null
                    result
                } catch (e: Exception) {
                    if (tool.name.contains("github")) {
                        me.rerere.rikkahub.data.ai.tools.GhProgress.processingRef = null
                    }
                    processingStatus.value = null
                    throw e
                }
            }
        )
    }
    // ── 预构建：system prompt 全文（循环不变，移到外面）──
    // buildString 不是 suspend 上下文，直接调用即可
    val prebuiltSystemPrompt = buildCachedSystemPrompt(assistant, settings, messages, memories ?: emptyList(), conversationSystemPrompt, tools, model, context, conversationRepo)

    for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                            workspaceCwd = workspaceCwd,
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings,
                                    workspaceCwd = workspaceCwd,
                                ).filter { it.role != MessageRole.SYSTEM }
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = statusTrackedTools,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    prebuiltSystemPrompt = prebuiltSystemPrompt,
                    workspaceCwd = workspaceCwd,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                    workspaceCwd = workspaceCwd,
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                    workspaceCwd = workspaceCwd,
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages.filter { it.role != MessageRole.SYSTEM }))

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // 1. Deduplicate tools: same (toolName, input) only execute once
                val seenTools = mutableSetOf<Pair<String, String>>()
                val uniqueTools = tools.filter { tool ->
                    val key = tool.toolName to tool.input
                    if (key in seenTools) {
                        Log.w(TAG, "Deduplicated duplicate tool call: ${tool.toolName}")
                        false
                    } else {
                        seenTools.add(key)
                        true
                    }
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = uniqueTools.map { tool ->
                    val toolDef = statusTrackedTools.find { it.name == tool.toolName }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true && tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != uniqueTools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages.filter { it.role != MessageRole.SYSTEM }))
                }

                // 3. Guardrail: same tool called N+ times in one batch → break
                if (!hasPendingApproval) {
                    val toolNameCount = updatedTools.groupingBy { it.toolName }.eachCount()
                    val looped = toolNameCount.entries.find { it.value >= assistant.toolRecurringLimit }
                    if (looped != null) {
                        Log.w(TAG, "Guardrail: ${looped.key} called ${looped.value} times in one batch, breaking")
                        break
                    }
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            val isParallel = assistant.enableParallelToolExecution && toolsToProcess.size > 1

            if (isParallel) {
                // 并行执行所有工具
                coroutineScope {
                    val deferreds = toolsToProcess.map { tool ->
                        async {
                            tool to runCatching {
                                kotlinx.coroutines.withTimeout(assistant.toolExecTimeout * 1000L) {
                                    executeToolCall(tool, toolsInternal, json)
                                }
                            }
                        }
                    }
                    deferreds.forEach { deferred ->
                        val (tool, result) = deferred.await()
                        addToolResult(executedTools, tool, result, json)
                    }
                }
            } else {
                // 顺序执行（原版行为）
                toolsToProcess.forEach { tool ->
                    val result = runCatching {
                        kotlinx.coroutines.withTimeout(assistant.toolExecTimeout * 1000L) {
                            executeToolCall(tool, toolsInternal, json)
                        }
                    }
                    addToolResult(executedTools, tool, result, json)
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings,
                        workspaceCwd = workspaceCwd,
                    ).filter { it.role != MessageRole.SYSTEM }
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        prebuiltSystemPrompt: String = "",
        workspaceCwd: String? = null,
    ) {
        val internalMessages = buildList {
            val fullSystem = if (prebuiltSystemPrompt.isNotBlank()) prebuiltSystemPrompt else buildString {
                // ── s10: 使用 SystemPromptAssembler 替代硬编码 ──
                val assemblerContext = me.rerere.rikkahub.data.ai.prompts.PromptContext(
                identitySection = buildString {
                    val effectiveSystemPrompt =
                        if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                            conversationSystemPrompt
                        } else {
                            if (assistant.tavernData != null) {
                                val persona = settings.personas.find { it.id == settings.activePersonaId }
                                assistant.assembleContext(
                                    userName = settings.displaySetting.userNickname.ifBlank { "User" },
                                    personaDesc = persona?.description ?: ""
                                )
                            } else {
                                assistant.systemPrompt
                            }
                        }
                    append(effectiveSystemPrompt)
                },
                leadInInstructions = buildString {
                    appendLine("Guidelines:")
                    appendLine("- Prefer dedicated tools over shell commands for file operations")
                    appendLine("- When a tool fails, try an alternative approach before giving up")
                    appendLine("- If you need clarification, ask the user directly")
                },
                workspaceDescription = "Working directory: ${context.filesDir?.absolutePath ?: "."}",
                extraInstructions = buildString {
                    if (assistant.enableRecentChatsReference) {
                        appendLine()
                        append(buildRecentChatsPrompt(assistant, conversationRepo))
                    }
                },
                constraints = emptyList(),
            )
            val system = me.rerere.rikkahub.data.ai.prompts.SystemPromptAssembler.assemble(assemblerContext)

            // ── 工具prompt（追加在 assembler 结果之后）──
            append(system)
            tools.forEach { tool ->
                appendLine()
                append(tool.systemPrompt(model, messages))
            }
            }
            val systemMsg = fullSystem.ifBlank { null }
            if (systemMsg != null) add(UIMessage.system(prompt = systemMsg))

            // ── s10: getUserContext — 用户上下文通过 <system-reminder> UserMessage 注入 ──
            // 对标 Claude Code context.ts → prependUserContext()
            // getUserContext 返回 { claudeMd, currentDate }，此处映射为 memories + currentDate
            val userContext = buildUserContext(memories, assistant, settings)
            if (userContext.isNotBlank()) {
                add(UIMessage.user(prompt = userContext))
            }

            addAll(messages.limitContext(assistant.contextMessageSize))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = true
                )
            )
            // Streaming: retry once on transient error (429/5xx/timeout)
            try {
                providerImpl.streamText(
                    providerSetting = provider,
                    messages = internalMessages,
                    params = params
                ).collect {
                    messages = messages.handleMessageChunk(chunk = it, model = model)
                    it.usage?.let { usage ->
                        messages = messages.mapIndexed { index, message ->
                            if (index == messages.lastIndex) {
                                message.copy(usage = message.usage.merge(usage))
                            } else {
                                message
                            }
                        }
                    }
                    onUpdateMessages(messages)
                }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("429 ") || msg.contains("5") || msg.contains("timeout") || msg.contains("reset")) {
                    Log.w(TAG, "streamText: retrying once after: ${e.message}")
                    providerImpl.streamText(
                        providerSetting = provider,
                        messages = internalMessages,
                        params = params
                    ).collect {
                        messages = messages.handleMessageChunk(chunk = it, model = model)
                        it.usage?.let { usage ->
                            messages = messages.mapIndexed { index, message ->
                                if (index == messages.lastIndex) {
                                    message.copy(usage = message.usage.merge(usage))
                                } else {
                                    message
                                }
                            }
                        }
                        onUpdateMessages(messages)
                    }
                } else {
                    throw e
                }
            }
        } else {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = false
                )
            )
            val chunk = try {
                providerImpl.generateText(
                    providerSetting = provider,
                    messages = internalMessages,
                    params = params,
                )
            } catch (e: Exception) {
                Log.e(TAG, "generateText failed: ${e.message}")
                throw e
            }
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            (chunk as? me.rerere.ai.ui.MessageChunk)?.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * 执行单个工具调用（提取逻辑以避免并行/串行分支重复）
 */
private suspend fun executeToolCall(
    tool: UIMessagePart.Tool,
    toolsInternal: List<Tool>,
    json: kotlinx.serialization.json.Json,
): UIMessagePart.Tool {
    return when (tool.approvalState) {
        is ToolApprovalState.Denied -> {
            val reason = (tool.approvalState as ToolApprovalState.Denied).reason
            tool.copy(
                output = listOf(
                    UIMessagePart.Text(
                        json.encodeToString(
                            buildJsonObject {
                                put(
                                    "error",
                                    JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                )
                            }
                        )
                    )
                )
            )
        }

        is ToolApprovalState.Answered -> {
            val answer = (tool.approvalState as ToolApprovalState.Answered).answer
            tool.copy(
                output = listOf(UIMessagePart.Text(answer))
            )
        }

        is ToolApprovalState.Pending -> tool

        else -> {
            val toolDef = toolsInternal.find { it.name == tool.toolName }
                ?: error("Tool ${tool.toolName} not found")
            val args = runCatching {
                json.parseToJsonElement(tool.input.ifBlank { "{}" })
            }.getOrElse {
                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
            }
            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")

            val result = toolDef.execute(args)

            tool.copy(output = result)
        }
    }
}

/**
 * 将工具执行结果添加到列表中（处理成功和失败两种情况）
 */
private fun addToolResult(
    executedTools: ArrayList<UIMessagePart.Tool>,
    tool: UIMessagePart.Tool,
    result: Result<UIMessagePart.Tool>,
    json: kotlinx.serialization.json.Json,
) {
    result.onSuccess { executedTools.add(it) }
        .onFailure {
            it.printStackTrace()
            executedTools.add(
                tool.copy(
                    output = listOf(
                        UIMessagePart.Text(
                            json.encodeToString(
                                buildJsonObject {
                                    put(
                                        "error",
                                        JsonPrimitive(buildString {
                                            append("[${it.javaClass.name}] ${it.message}")
                                            append("\n${it.stackTraceToString()}")
                                        })
                                    )
                                }
                            )
                        )
                    )
                )
            )
        }
}

/**
 * ── s10: getUserContext ──
 * 对标 Claude Code context.ts → getUserContext() → prependUserContext()
 *
 * CC 源码 (context.ts):
 *   getUserContext = memoize(async (): Promise<{claudeMd, currentDate}> => {
 *     const claudeMd = getClaudeMds(filterInjectedMemoryFiles(await getMemoryFiles()))
 *     return { ...(claudeMd && { claudeMd }), currentDate: "Today's date is ..." }
 *   })
 *
 * CC 源码 (api.ts → prependUserContext):
 *   createUserMessage({
 *     content: `<system-reminder>\nAs you answer the user's questions, you can use the following context:\n${
 *       Object.entries(context).map(([key, value]) => `# ${key}\n${value}`).join('\n')
 *     }\n\nIMPORTANT: this context may or may not be relevant...\n</system-reminder>\n`,
 *     isMeta: true,
 *   })
 *
 * 记忆：整轮对话缓存（memoize），仅当记忆列表变化时重建
 */
private var _lastUserContextKey: String? = null
private var _lastUserContext: String? = null

private fun buildUserContext(
    memories: List<AssistantMemory>,
    assistant: Assistant,
    settings: Settings,
): String {
    val contextMap = linkedMapOf<String, String>()

    // 对标 CC getUserContext: claudeMd (CLAUDE.md content)
    if (assistant.enableMemory && memories.isNotEmpty()) {
        val memoryText = memories.joinToString("\n") { memory ->
            "- ${memory.content.take(200)}"
        }
        contextMap["memories"] = memoryText
    }

    // 对标 CC getUserContext: currentDate
    contextMap["currentDate"] = "Today's date is ${java.time.LocalDate.now()}."

    if (contextMap.isEmpty()) return ""

    // Memoize: 当 contextMap 内容不变时复用
    val key = contextMap.entries.joinToString("|") { "${it.key}=${it.value}" }
    if (key == _lastUserContextKey && _lastUserContext != null) {
        return _lastUserContext!!
    }

    val result = buildString {
        appendLine("<system-reminder>")
        appendLine("As you answer the user's questions, you can use the following context:")
        contextMap.forEach { (key, value) ->
            appendLine("# $key")
            appendLine(value)
        }
        appendLine()
        appendLine("IMPORTANT: this context may or may not be relevant to your tasks. You should not respond to this context unless it is highly relevant to your task.")
        append("</system-reminder>")
    }

    _lastUserContextKey = key
    _lastUserContext = result
    return result
}