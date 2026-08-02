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
import me.rerere.rikkahub.data.model.buildExampleMessages
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
        generationType: me.rerere.rikkahub.data.model.GenerationType = me.rerere.rikkahub.data.model.GenerationType.NORMAL,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

    fun describeTool(name: String): String = when {
        name.startsWith("github_") -> "🔧 GitHub → 正在操作..."
        name.startsWith("execute_python") -> "🔧 Python → 正在执行代码..."
        name.startsWith("execute_command") -> "🔧 Shell → 正在执行命令..."
        name == "file" -> "🔧 文件 → 正在操作..."
        name.startsWith("database_") -> "🔧 数据库 → 正在查询..."
        name.startsWith("search_web") || name.startsWith("scrape_") -> "🔧 搜索 → 正在搜索..."
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
                        val persona = settings.personas.find { it.id == settings.activePersonaId }
                        val personaDesc = persona?.description?.takeIf { it.isNotBlank() }
                        if (assistant.tavernData != null) {
                            assistant.assembleContext(
                                userName = settings.displaySetting.userNickname.ifBlank { "User" },
                                personaDesc = personaDesc ?: "",
                                personaTitle = persona?.title ?: "",
                                personaPosition = persona?.position
                                    ?: me.rerere.rikkahub.data.model.PersonaInjectionPosition.AFTER_SYSTEM,
                            )
                        } else if (personaDesc != null) {
                            val personaLabel = persona?.title?.ifBlank { persona?.name } ?: "User"
                            assistant.systemPrompt + "\n\n[User Persona: $personaLabel]\n$personaDesc"
                        } else {
                            assistant.systemPrompt
                        }
                    }
                append(effectiveSystemPrompt)
            },
            leadInInstructions = buildString {
                appendLine("<tool_selection>")
                appendLine("Workspace files → workspace_read/write/edit (/workspace/...)")
                appendLine("Workspace shell → workspace_shell (git, builds, Unix tools in sandbox)")
                appendLine("Device files → file action=\"read/write/patch/list/search/copy/move/delete\" (Download/skills dirs)")
                appendLine("Device shell → execute_command (logcat, device info only)")
                appendLine("Python → execute_python (data processing, API)")
                appendLine("Math → calculator (NOT execute_python)")
                appendLine("Web → web_search / web_fetch")
                appendLine("GitHub → github_tool")
                appendLine("Memory → memory_tool")
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
                appendLine("<mingli_workflow>")
                appendLine("【命理工作流】")
                appendLine("")
                appendLine("第1步 — 排盘取数据:")
                appendLine("  优先调用 mingli(system='系统名', params={...}) → 确定性排盘,不出错")
                appendLine("  如果 mingli 的数据不够用(比如要做卜卦占星/自定义相位分析/未覆盖的引擎功能):")
                appendLine("    → 调 eval_javascript 自探索引擎 API (用 Object.keys/dir 探索后再调用)")
                appendLine("    → 或调 execute_python 做自定义计算(不写排盘代码,只写分析逻辑)")
                appendLine("")
                appendLine("  各系统 params 格式(含别名):")
                appendLine("    塔罗(韦特/塔罗牌/tarot): {spread, seed, question_type?, kaabalah?, cards?}")
                appendLine("    雷诺曼(lenormand): {spread, seed, cards?}")
                appendLine("    八字(四柱/生辰八字/bazi/排盘): {year, month, day, hour, minute?, gender, feature?}")
                appendLine("    紫微(紫微斗数/紫薇/ziwei): {year, month, day, hour, minute?, gender, engine?}")
                appendLine("    现代西洋占星(现代占星/星座/western_astro): {year, month, day, hour, minute?, tz, lat, lon}")
                appendLine("    传统西洋占星(古典占星/中世纪/卜卦/horary/traditional_astro): {year, month, day, hour, minute?, tz_offset, lat, lon}")
				appendLine("    深度古典占星(深度古典/stellium/hellenistic/希腊占星/古典占星deep): {year, month, day, hour, minute?, tz?=IANA时区, lat?, lon?, house_system?=placidus|whole_sign|regiomontanus, partner_year?, transit_date?, transit_forecast_months?, return_year?, progression_age?, crossings_start?+crossings_end?}")
                appendLine("    吠陀(印度占星/吠陀占星/jyotish/vedic): {year, month, day, hour, minute?, tz=IANA时区/数字偏移, lat?, lon?}")
                appendLine("    人类图(human_design/humandesign): {year, month, day, hour, minute?, tz, gene_keys?, transits?}")
                appendLine("    灵数卡巴拉(生命灵数/生命数字/卡巴拉/kabbalah): {year, month, day, word?, feature}")
                appendLine("    奇门(含大六壬/奇门遁甲/qimen): {year, month, day, hour?, minute?, feature} (大六壬需feature=liuren)")
                appendLine("    六爻(含梅花易数/六爻纳甲/liuyao): {method, seed?, year?, month?, day?, feature?} (六爻与梅花易数各走独立解读模板)")
                appendLine("  feature可选值见 mingli 工具声明")
                appendLine("")
                appendLine("第2步 — 读解读模板(默认必选):")
                appendLine("  【强制】拿到mingli数据后，必须调 mingli_guide(system='系统名') 读取解读模板")
                appendLine("  模板内容是经过校验的确定性解读规则，不是建议，必须逐条遵守")
                appendLine("  之后可缓存(同系统无需重复读取)，但同系统每次解读都必须以该模板为准")
                appendLine("  任何时候都不允许跳过 mingli_guide 直接解读 — 跳过模板视为违规解读")
                appendLine("  例外：如果用户明确提供了替代的解读框架或技能指令(如自定义提示词/技能中已有解读规则)，")
                appendLine("  则用户显式指令优先，禁止调用mingli_guide，直接按用户指令解读。")
                appendLine("")
                appendLine("第3步 — 组织回复(严格按模板):")
                appendLine("  【强制】必须严格按照 mingli_guide 模板的每一条规则、每一个步骤解读")
                appendLine("  【强制】模板中提到的所有要点都必须覆盖解读，不得遗漏")
                appendLine("  【强制利用全部字段】mingli返回数据中的所有字段都必须被AI使用。模板是解读框架，但每个字段都须在回复中有对应位置——")
                appendLine("  要么按模板中的规则解读，要么在合理上下文中融入。不得因为模板未明确提及就跳过任何数据字段。")
                appendLine("  AI拿到数据后应主动检查所有字段，确保每个字段都发挥了作用。回复的质量与数据利用率正相关。")
                appendLine("  模板中未提及的解读方法不得自行添加")
                appendLine("  先用 mingli 数据(确定性数据)，再按模板逐条对应解读")
                appendLine("  print仅取数据，解读正文写在回复里")
                appendLine("")
                appendLine("【返回值中的 _hint 字段】")
                appendLine("  每个 mingli 返回的数据里带 _hint 字段, 说明该引擎还有哪些API未调用")
                appendLine("  按 _hint 指引自探索, 能做 mingli 没覆盖的自定义分析")
                appendLine("</mingli_workflow>")
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
                    generationType = generationType,
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
        generationType: me.rerere.rikkahub.data.model.GenerationType = me.rerere.rikkahub.data.model.GenerationType.NORMAL,
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
                            val persona = settings.personas.find { it.id == settings.activePersonaId }
                            val personaDesc = persona?.description?.takeIf { it.isNotBlank() }
                            if (assistant.tavernData != null) {
                                assistant.assembleContext(
                                    userName = settings.displaySetting.userNickname.ifBlank { "User" },
                                    personaDesc = personaDesc ?: "",
                                    personaTitle = persona?.title ?: "",
                                    personaPosition = persona?.position
                                        ?: me.rerere.rikkahub.data.model.PersonaInjectionPosition.AFTER_SYSTEM,
                                )
                            } else if (personaDesc != null) {
                                val personaLabel = persona?.title?.ifBlank { persona?.name } ?: "User"
                                assistant.systemPrompt + "\n\n[User Persona: $personaLabel]\n$personaDesc"
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

            // ── 官方 mes_example：作为示例消息注入（story string 之后、聊天历史之前）──
            if (assistant.tavernData != null) {
                addAll(
                    assistant.buildExampleMessages(
                        userName = settings.displaySetting.userNickname.ifBlank { "User" }
                    )
                )
            }

            // ── s10: getUserContext — 用户上下文通过 <system-reminder> UserMessage 注入 ──
            // 对标 Claude Code context.ts → prependUserContext()
            // getUserContext 返回 { claudeMd, currentDate }，此处映射为 memories + currentDate
            val userContext = buildUserContext(memories, assistant, settings)
            if (userContext.isNotBlank()) {
                add(UIMessage.user(prompt = userContext))
            }

            addAll(messages.limitContext(assistant.contextMessageLimit))
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
            generationType = generationType,
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
