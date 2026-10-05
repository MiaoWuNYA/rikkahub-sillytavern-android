package me.rerere.rikkahub.data.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AI 主动发消息前台服务.
 *
 * 流程: 取助手 + 最近会话 → 构建主动消息上下文（上次聊天距今、当前时间、电量）
 * → 用 provider.streamText 直接生成（带 maxSteps 上限，流式写入会话）
 * → 空回复/[PASS] 回滚 → 保存 + 通知 + 重排下次。
 *
 * 并发保护: 通过 chatService.tryClaimGeneration 礼貌性抢占会话生成权；
 * 若正在生成（正常聊天或另一路主动消息），直接放弃本次触发，不排队等待、不打断。
 */
class ProactiveMessageRunner(
    /**
     * 只依赖 Context，不依赖 Service。
     *
     * 原来这是个 Service，靠 startForegroundService 从后台拉起。但
     * targetSdk 31+ 禁止后台启动前台服务，闹钟/WorkManager 触发时
     * 系统会抛 ForegroundServiceStartNotAllowedException，而调用方
     * 的 catch(Exception) 把它吞了——表现就是「定时到了但毫无反应」。
     *
     * 生成逻辑本身只用到 Context 能力（读配置、起协程、发通知），
     * 根本不需要前台服务，所以改成普通类由 WorkManager 直接驱动。
     */
    private val appContext: Context,
) : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val providerManager: ProviderManager by inject()
    private val templateTransformer: TemplateTransformer by inject()
    private val localTools: LocalTools by inject()
    private val mcpManager: McpManager by inject()
    private val json: Json by inject()

    // 惰性解析：ChatService 构造时会注册 ProcessLifecycleOwner 观察者，
    // 首次解析必须在主线程完成（本服务由闹钟/WorkManager 在后台线程拉起，直接注入会崩溃）。
    // 这里统一在主线程取一次并缓存，之后所有访问复用同一实例。
    @Volatile
    private var lazyChatService: ChatService? = null

    private val chatService: ChatService
        get() = lazyChatService ?: synchronized(this) {
            lazyChatService ?: resolveChatServiceOnMain().also { lazyChatService = it }
        }

    /**
     * 在主线程同步解析 ChatService。
     *
     * 用 CountDownLatch + Handler 而非 runBlocking(Dispatchers.Main)：后者会占用主线程调度器，
     * 若调用方已持有主线程相关锁会造成死锁；Latch 方式只阻塞当前后台线程，投递后立即返回。
     */
    private fun resolveChatServiceOnMain(): ChatService {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return getKoin().get()
        }
        var result: ChatService? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                result = getKoin().get()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        // 超时兜底：主线程若长时间不可用，不无限期卡住前台服务
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timed out resolving ChatService on main thread")
        }
        error?.let { throw it }
        return result ?: throw IllegalStateException("ChatService resolution returned null")
    }

    private val proactiveMessageService = ProactiveMessageService()

    companion object {
        private const val TAG = "ProactiveMessageTrigger"
        private const val NOTIFICATION_ID = 20001
        private const val PROACTIVE_NOTIFICATION_ID = 20002
        private const val MAX_TOOL_STEPS = 5 // 主动消息最大工具调用步数

        // 保护 last_triggered_time 的 check-then-act 竞态（防止 AlarmManager 与 WorkManager
        // 前后脚触发导致"最小间隔"被砍半）。纯同步 SharedPreferences 读写，无挂起点，用对象锁即可。
        private val prefsLock = Any()
    }

    // 输入转换器（与 ChatService 保持一致的子集）
    private val inputTransformers by lazy {
        listOf(
            TimeReminderTransformer,
            PromptInjectionTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
            OcrTransformer,
        )
    }

    // 输出转换器（与 ChatService 保持一致的子集）
    private val outputTransformers by lazy {
        listOf(
            ThinkTagTransformer,
        )
    }

    /**
     * 跑一次主动消息生成。
     *
     * 由 WorkManager 直接调用（见 ProactiveMessageWorker），不经过前台服务，
     * 因此不受「后台启动前台服务」限制。挂起直到全部完成，调用方据此
     * 决定 Result.success/retry。
     *
     * 返回是否真正跑到了生成阶段（false 表示被跳过：未启用、无对话、
     * 有并发生成、无模型等）。跳过也要算成功——不是错误。
     */
    suspend fun run(): Boolean {
        var conversationId: Uuid? = null
        var reachedGeneration = false
        localNodes = emptyList()
        loadedConversation = null

        // 本轮生成所操作的对话内容，**全程只存在内存里**。
        //
        // 这是重构的核心数据结构。原实现把生成过程写进 ChatService 的
        // session，再靠 saveConversation 落库，于是「session 从哪来」
        // 变成了安全问题。现在改成：开工时读一次数据库，之后所有增删改
        // 都作用在这份本地副本上，成功且校验通过才写回一次。
        //
        // 由此得到的结构保证：
        //   · 不存在「空会话」—— 它只能来自一次成功的数据库读取
        //   · 写回时的节点数一定 >= 读到的节点数（只追加、或删掉自己刚加的）
        //   · 用户在这期间的改动会被 update_at 校验发现并放弃保存

        try {
                val settings = settingsStore.settingsFlow.first()
                // 归一化：设置里可能存着非法值，直接拿去算间隔会得到过去的时间点
                val proactiveSetting = settings.proactiveMessageSetting.normalized()
                if (!proactiveSetting.enabled) {
                    ProactiveMessageLog.log(
                        appContext, ProactiveMessageLog.Outcome.SKIPPED, "主动消息",
                        "总开关处于关闭状态，本次触发直接结束。",
                    )
                    return false
                }

                val prefs = appContext.getSharedPreferences(ProactiveMessageService.PREFS_NAME, Context.MODE_PRIVATE)

                // 去重判断：防止 AlarmManager 和 WorkManager 在同一窗口内重复触发。
                //
                // 这里**只读不写**。原先是在检查通过时就立刻写入 last_triggered_time，
                // 但那个时刻还没法确定这次能不能真的生成出来——后面任何一步失败
                // （没有可用对话、没有模型、provider 缺失、网络报错）都会直接
                // return，而时间戳已经钉死了。后果是：一次失败会让之后 minInterval
                // 窗口内的所有真实触发全被判成 duplicate 跳过，失败被自我强化成
                // 永久静默，用户只能靠关掉再打开开关来恢复。
                //
                // 正确做法是「成功才提交」，写入点放在真正生成出结果之后。
                val minIntervalMs = proactiveSetting.minIntervalMinutes
                    .coerceAtLeast(ProactiveMessageSetting.MIN_INTERVAL_MINUTES) * 60 * 1000L
                val lastTriggeredTime = prefs.getLong(ProactiveMessageService.KEY_LAST_TRIGGERED_TIME, 0L)
                if (System.currentTimeMillis() - lastTriggeredTime < minIntervalMs) {
                    Log.d(TAG, "Duplicate trigger within min interval, skipping")
                    val waited = (System.currentTimeMillis() - lastTriggeredTime) / 60_000
                    ProactiveMessageLog.log(
                        appContext, ProactiveMessageLog.Outcome.SKIPPED, "去重检查",
                        "距离上次成功发送只过了 ${waited} 分钟，小于设定的最小间隔 " +
                            "${proactiveSetting.minIntervalMinutes} 分钟，本次跳过。" +
                            "（闹钟与 WorkManager 可能各触发一次，这是正常的去重。）",
                    )
                    ProactiveMessageService.scheduleNext(appContext, proactiveSetting)
                    return false
                }

                // 失败冷静期：上一次尝试失败后的几分钟内不再重试。
                //
                // 没有这道闸，一个持续复现的失败场景（助手没配模型、
                // 没选对话、key 失效）会变成死循环：失败 -> finally 重排
                // -> 立刻又触发 -> 又失败。日志里会看到每隔固定时间刷一条
                // 同样的失败，耗电、刷屏，而且掩盖了真正的问题。
                val lastAttempt = prefs.getLong(ProactiveMessageService.KEY_LAST_ATTEMPT_TIME, 0L)
                val sinceLastAttemptMin = (System.currentTimeMillis() - lastAttempt) / 60_000L
                if (lastAttempt > 0 && sinceLastAttemptMin < ProactiveMessageService.FAILURE_BACKOFF_MINUTES) {
                    Log.d(TAG, "Within failure backoff (${sinceLastAttemptMin}m), skipping")
                    ProactiveMessageService.scheduleNext(appContext, proactiveSetting)
                    return false
                }

                // 获取助手（设置里指定或当前助手）
                val assistant = settings.assistants.find { it.id.toString() == proactiveSetting.assistantId }
                    ?: settings.getCurrentAssistant()
                val assistantUuid = assistant.id
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                if (model == null) {
                    Log.e(TAG, "No model found for proactive message")
                    ProactiveMessageLog.log(
                        appContext, ProactiveMessageLog.Outcome.FAILED, "模型检查",
                        buildString {
                            appendLine("助手：${assistant.name.ifBlank { assistantUuid.toString() }}")
                            appendLine("助手配置的模型 ID：" + (assistant.chatModelId ?: "（未设置）"))
                            appendLine("全局默认模型 ID：" + settings.chatModelId)
                            append("原因：找不到对应的模型定义。去助手设置里选一个模型，" +
                                "或检查该模型是否已被删除。")
                        },
                    )
                    markAttempt(appContext)
                    ProactiveMessageService.scheduleNext(appContext, proactiveSetting)
                    return false
                }

                // 找到目标对话：优先设置里指定的固定对话，没有就退回该助手最近的对话。
                // 主动消息会写进这个对话的历史，指定错误等于污染别处，所以由用户决定。
                val conversation = ProactiveMessageService.resolveTargetConversation(
                    repository = conversationRepository,
                    assistantId = assistantUuid,
                    configuredConversationId = proactiveSetting.conversationId,
                )
                if (conversation == null) {
                    Log.d(TAG, "No target conversation for assistant, skipping proactive message")
                    ProactiveMessageLog.log(
                        appContext, ProactiveMessageLog.Outcome.SKIPPED, "查找对话",
                        buildString {
                            append("找不到可用的目标对话。")
                            if (proactiveSetting.conversationId.isNotBlank()) {
                                append(
                                    "设置里指定的对话要么已不存在，要么不属于当前选中的助手。" +
                                        "主动消息会把该助手的人设和记忆写进目标对话，" +
                                        "归属不符会造成污染，所以这里直接拒绝。请重新选择发送到的对话。"
                                )
                            } else {
                                append("该助手还没有任何对话，请先与它聊一次。")
                            }
                        },

                    )
                    markAttempt(appContext)
                    ProactiveMessageService.scheduleNext(appContext, proactiveSetting)
                    return false
                }
                conversationId = conversation.id
                loadedConversation = conversation
                localNodes = conversation.messageNodes

                // 不借用 ChatService 的会话机制。
                //
                // 这是整个重构的核心。原先这里做两件事：
                //   chatService.addConversationReference(conversationId)
                //   chatService.updateConversationState(conversationId) { conversation }
                //
                // 借用那套机制的代价是致命的：session 是给**前台交互式聊天**
                // 设计的有状态对象（引用计数、idle 回收、生成权抢占、保存锁），
                // 后台定时任务去借它，就必然踩到「内存里没有 session 时凭空造
                // 空会话」这条路径，而空会话一旦经 saveConversation 落库，就是
                // 整段历史被覆盖。
                //
                // 本功能其实只需要三件事：读对话、生成、写回。那就直接做这三件，
                // 不引入任何中间状态：
                //   · 读：resolveTargetConversation 已经给了完整内容
                //   · 生成：在本地内存里追加，不碰数据库
                //   · 写：成功后才更新一次，且必须基于刚读到的版本
                //
                // 这样结构上就不存在「空会话」这个概念——本方法永远只会在
                // 读到的 conversation 上追加，写回的节点数只会变多。
                //
                // 与用户正在聊天时的并发：不再抢占，直接跳过。理由和原来一致
                // （等对方结束再发，上下文已过时），但判断方式简单得多——看
                // 数据库里的 update_at 有没有在生成期间被改过，以及目标对话
                // 是否正被前台生成（通过 ChatService 只读查询）。
                if (chatService.isGenerating(conversationId)) {
                    Log.d(TAG, "Skip proactive trigger: $conversationId is generating in foreground")
                    ProactiveMessageLog.log(
                        appContext, ProactiveMessageLog.Outcome.SKIPPED, "并发保护",
                        "该对话正在进行生成（你正在聊天），本次触发放弃，不排队等待。",
                    )
                    return false
                }

                // 构建上下文与历史
                val idleMinutes = runCatching {
                    val last = proactiveMessageService.getLastMessageTimeMs(assistantUuid)
                    if (last > 0) ((System.currentTimeMillis() - last) / 60_000L).toInt() else Int.MAX_VALUE
                }.getOrDefault(Int.MAX_VALUE)

                val contextStr = proactiveMessageService.buildProactiveContext(
                    appContext,
                    assistantUuid,
                )

                // 获取历史消息（先过滤掉悬空的工具调用消息，避免 tool_use 结构不完整触发 400）
                val historyMessages = filterInvalidToolMessages(
                    conversation.currentMessages.let {
                        if (assistant.contextMessageLimit > 0) {
                            it.takeLast(assistant.contextMessageLimit)
                        } else it
                    }
                )

                // 构建系统提示词（助手人设 + 记忆 + 触发说明与上下文，上下文放最后避免被淹没）
                val systemPrompt = buildSystemPrompt(
                    assistant = assistant,
                    settings = settings,
                    idleMinutes = idleMinutes,
                    context = contextStr,
                )

                // user message 只放简短指令（上下文已在系统提示词中）
                val userMessage = UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(
                        "请根据以上上下文决定是否发消息。没什么好说的就回复 [PASS] 即可，不要强行找话题。"
                    ))
                )

                // 应用输入转换器
                val processedUserMessage = listOf(userMessage).transforms(
                    transformers = inputTransformers + templateTransformer,
                    context = appContext,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                ).first()

                // 组合完整消息列表：System + History + User，合并相邻同角色消息避免 API 400
                val messages = mergeAdjacentSameRoleMessages(
                    buildList {
                        add(UIMessage(
                            role = MessageRole.SYSTEM,
                            parts = listOf(UIMessagePart.Text(systemPrompt))
                        ))
                        addAll(historyMessages)
                        add(processedUserMessage)
                    }
                )

                val providerSetting = model.findProvider(settings.providers)
                if (providerSetting == null) {
                    Log.e(TAG, "No provider found for proactive message")
                    ProactiveMessageLog.log(
                        appContext, ProactiveMessageLog.Outcome.FAILED, "供应商检查",
                        "模型「${model.modelId}」找不到对应的供应商配置，" +
                            "可能是该供应商已被删除或改名。",
                    )
                    return false
                }
                val providerImpl = providerManager.getProviderByType(providerSetting)

                // 构建工具列表（主动消息场景精简版：本地工具 + MCP 工具）
                val tools = buildTools(settings, assistant, model)

                val params = TextGenerationParams(
                    model = model,
                    temperature = assistant.temperature,
                    topP = assistant.topP,
                    maxTokens = assistant.maxTokens,
                    tools = tools,
                    reasoningLevel = assistant.reasoningLevel,
                    customHeaders = assistant.customHeaders + model.customHeaders,
                    customBody = assistant.customBodies + model.customBodies,
                )

                Log.d(
                    TAG,
                    "Calling AI API for proactive message with ${historyMessages.size} history messages, " +
                        "${tools.size} tools (model=${model.modelId})"
                )

                // 执行生成，支持工具调用（流式写入会话）
                val (finalMessages, hasJumpFlag) = generateWithTools(
                    conversationId = conversationId,
                    providerImpl = providerImpl,
                    providerSetting = providerSetting,
                    initialMessages = messages,
                    params = params,
                    tools = tools,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                )

                // 提取 AI 消息
                val aiMessage = finalMessages.lastOrNull()
                    ?: UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())

                // 解析 [JUMP] 标记
                val rawText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }.trim()
                val replyText = rawText.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim()
                val shouldJump = hasJumpFlag &&
                    proactiveSetting.allowForceJump &&
                    idleMinutes >= proactiveSetting.jumpIdleThresholdMinutes

                // 若移除了标记，同步更新 session 里 aiMessage 的文本 parts
                if (rawText != replyText) {
                    val cleanedAiMessage = aiMessage.copy(
                        parts = aiMessage.parts.map { part ->
                            if (part is UIMessagePart.Text) {
                                UIMessagePart.Text(part.text.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim())
                            } else {
                                part
                            }
                        }
                    )
                    updateOrAppendAiMessage(cleanedAiMessage)
                }

                Log.d(TAG, "Proactive message generated: '${replyText.take(100)}' (${replyText.length} chars), shouldJump=$shouldJump")

                if (replyText.isBlank() || rawText.contains("[PASS]")) {
                    // AI 选择跳过，移除本次生成的 aiMessage node（基于 id 匹配，不误删历史）
                    Log.d(TAG, "AI chose to skip proactive message")
                    val aiId = aiMessage.id
                    // AI 选择沉默：把本次追加的 AI 节点从**本地维护的列表**里去掉。
                    // 因为整个生成过程都在内存里进行（见 generateWithTools 的注释），
                    // 这里连数据库都不用碰——直接把节点从本轮的 messageNodes 里删掉即可。
                    localNodes = localNodes.filterNot { node ->
                        node.messages.any { it.id == aiId }
                    }
                    Log.d(TAG, "AI chose to skip; node removed from in-memory list only")
                    // 到这里说明整轮生成真的跑完了（AI 主动选择沉默也算成功），
                    // 现在才提交触发时间戳。失败路径一律不写，见上面的去重注释。
                    commitTriggerStamp(prefs)
                    ProactiveMessageLog.log(
                        appContext, ProactiveMessageLog.Outcome.PASSED, "生成完成",
                        buildString {
                            appendLine("目标对话：${conversation.title.ifBlank { "未命名对话" }}")
                            appendLine("对话 ID：$conversationId")
                            appendLine("使用助手：${assistant.name.ifBlank { assistantUuid.toString() }}")
                            appendLine("使用模型：${model.displayName.ifBlank { model.modelId }}")
                            appendLine("模型原始回复：" + rawText.take(200).trim())
                            append("结论：模型判断当前没有合适的话可说，本轮不发消息。这是正常行为，不是故障。")
                        },
                    )
                } else {
                    // 有效回复：把本轮内存里累积的节点一次性写回数据库。
                    // 只有写回成功后（并且校验通过）才算真正发送，也才提交时间戳。
                    val saved = saveProactiveMessage(conversationId)
                    if (!saved) {
                        Log.w(TAG, "Proactive message generated but not persisted; not stamping trigger")
                        return false
                    }
                    commitTriggerStamp(prefs)
                    ProactiveMessageLog.log(
                        appContext, ProactiveMessageLog.Outcome.SENT, "生成完成",
                        buildString {
                            appendLine("目标对话：${conversation.title.ifBlank { "未命名对话" }}")
                            appendLine("对话 ID：$conversationId")
                            appendLine("使用助手：${assistant.name.ifBlank { assistantUuid.toString() }}")
                            appendLine("使用模型：${model.displayName.ifBlank { model.modelId }}")
                            appendLine("消息长度：${replyText.length} 字")
                            append("消息内容：")
                            appendLine()
                            append(replyText.trim())
                        },
                    )
                    showProactiveNotification(conversationId, assistant.name.ifBlank { "AI" }, replyText)
                    // 拉起聊天界面（AI 通过 [JUMP] 标记自行判断，且需满足开关与闲置阈值）
                    if (shouldJump) {
                        try {
                            val jumpIntent = Intent(appContext, RouteActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                                putExtra("conversationId", conversationId.toString())
                            }
                            appContext.startActivity(jumpIntent)
                            Log.d(TAG, "Force jump to conversation $conversationId")
                        } catch (e: Exception) {
                            Log.e(TAG, "Force jump failed", e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 协程被取消。**原因不一定是用户操作。**
                //
                // 原来的文案写死成「生成过程中你开始发新消息」，把一次
                // 取消断言成用户行为。但 CancellationException 的来源很多：
                // WorkManager 到达执行上限、进程被系统回收、协程作用域被
                // 取消……用户看到「你开始发新消息」却明明没发，只会更困惑。
                //
                // 说不出准确原因时就不要编一个。这里改成如实陈述，
                // 并把是否真有用户介入留给用户自己判断。
                Log.d(TAG, "Proactive generation cancelled (cause unknown), conversationId=$conversationId")
                ProactiveMessageLog.log(
                    appContext, ProactiveMessageLog.Outcome.SKIPPED, "生成被打断",
                    "生成过程中协程被取消，本轮未完成。常见原因是你发新消息" +
                        "抢占了本次生成；但也可能是系统回收了后台任务。" +
                        "如果反复出现而你并没有在聊天，请检查电池优化设置。",
                )
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger proactive message", e)
                e.cause?.let { cause ->
                    Log.e(TAG, "Underlying cause: ${cause::class.simpleName}: ${cause.message}", cause)
                }
                markAttempt(appContext)
                ProactiveMessageLog.log(
                    appContext, ProactiveMessageLog.Outcome.FAILED, "生成异常",
                    buildString {
                        val conv = loadedConversation
                        appendLine("目标对话：${conv?.title?.ifBlank { "未命名对话" } ?: "（未确定，异常发生在选对话之前）"}")
                        appendLine("对话 ID：${conversationId ?: "（未确定）"}")
                        appendLine("异常类型：${e::class.simpleName}")
                        appendLine("异常信息：${e.message ?: "无详情"}")
                        e.cause?.let { append("底层原因：${it::class.simpleName}: ${it.message}") }
                    },
                )
                // 出错后不要碰数据库。
                //
                // 这里原本做的是「把当前会话状态存一次」。看着无害，实际是
                // **清空聊天记录的元凶**：
                //
                //   acquireSessionForBackground -> getOrCreateSession
                //   在内存里没有该会话的 session 时会用一个
                //   Conversation.ofId(id, ...) 的**空会话**去初始化它
                //   （ChatService.kt:349-357，零条 messageNodes）。
                //
                //   紧接着 getConversationFlow(cid).value 拿到的就是这个空会话，
                //   saveConversation 又因为「会话已存在」而不走那条
                //   「新会话且为空就跳过」的保护（ChatService.kt:2582），
                //   于是直接把空内容 update 回数据库——整个对话的历史没
                //   了。记忆清空是同一路径的连带后果。
                //
                // 触发条件在真机上很容易满足：进程被系统回收后内存里本就没有
                // session，此时定时触发失败就走了这条路。
                //
                // 正确做法：出错时**什么都不写**。流式过程中已经通过
                // updateOrAppendAiMessage 就地更新过 session，那些更新要么
                // 已落库、要么会在下次正常保存时带上；不完整的半截 AI 消息
                // 由各处的 filterInvalidToolMessages / checkInvalidMessages
                // 处理，不需要靠这次写来「清理」。
                Log.d(TAG, "Skipping post-error conversation save (never overwrite from a possibly-empty session)")
            } finally {
                // 确保无论成功/失败/取消都安排下一次，避免一次 API 错误或用户打断永久中断定时链。
                // 用 NonCancellable 包裹：协程被取消后挂起点会立刻抛 CancellationException，
                // NonCancellable 保证这段收尾逻辑跑完。
                withContext(NonCancellable) {
                    try {
                        val currentSettings = settingsStore.settingsFlow.first()
                        ProactiveMessageService.scheduleNext(
                            appContext,
                            currentSettings.proactiveMessageSetting
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reschedule after completion/error/cancellation", e)
                    }
                }

                // 这里不再需要「释放生成权 / 释放会话引用」。
                //
                // 那两件事存在的唯一理由是：原实现借用了 ChatService 的
                // session 机制（tryClaimGeneration 注册 job、
                // addConversationReference 持有引用计数），而 session 的
                // isInUse 包含 _generationJob != null，不显式释放就会让
                // session 永远「正在生成」，导致之后每次触发都被跳过。
                //
                // 重构后本功能完全不碰 session，也就没有东西需要释放。
                // 这比「记得成对释放」可靠得多——它不会因为漏写一行而失效。
                loadedConversation = null
                synchronized(localNodesLock) { localNodes = emptyList() }
        }
        return reachedGeneration
    }

    /**
     * 提交本次触发时间戳。
     *
     * 只在整轮生成真正跑完后调用；任何失败分支都不调，这样一次失败
     * 不会把后续 minInterval 窗口内的真实触发全判掉。
     */
    private fun commitTriggerStamp(prefs: android.content.SharedPreferences) {
        runCatching {
            prefs.edit()
                .putLong(ProactiveMessageService.KEY_LAST_TRIGGERED_TIME, System.currentTimeMillis())
                // 成功一次就把失败冷静期清掉，下一轮该跑就跑。
                .remove(ProactiveMessageService.KEY_LAST_ATTEMPT_TIME)
                .apply()
        }.onFailure { Log.w(TAG, "Failed to commit trigger stamp", it) }
    }

    /** 记一次「跑过一轮但没成功」，用于失败冷静期。 */
    private fun markAttempt(context: Context) {
        runCatching {
            context.getSharedPreferences(ProactiveMessageService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(ProactiveMessageService.KEY_LAST_ATTEMPT_TIME, System.currentTimeMillis())
                .apply()
        }.onFailure { Log.w(TAG, "Failed to mark attempt", it) }
    }

    /**
     * 构建系统提示词：助手人设 + 记忆 + 主动消息触发说明与上下文。
     */
    private suspend fun buildSystemPrompt(
        assistant: Assistant,
        settings: Settings,
        idleMinutes: Int,
        context: String,
    ): String {
        return buildString {
            if (assistant.systemPrompt.isNotBlank()) {
                append(assistant.systemPrompt)
            }

            // 记忆
            if (assistant.enableMemory) {
                val memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                }
                if (memories.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine("## 记忆")
                    memories.forEach { memory ->
                        appendLine("- ${memory.content}")
                    }
                }
            }

            appendLine()
            appendLine()
            appendLine("## 主动消息触发（定时触发）")
            appendLine("距离用户上次回复已过去 $idleMinutes 分钟。")
            appendLine("这是定时触发的主动消息。绝对不要复述上一轮的对话内容，要发新的话题或新的关心。")
            appendLine("如果你觉得现在没什么好说的，或者没什么有趣的话题，请只回复 [PASS] 即可。")
            appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
            appendLine()
            appendLine(context)
        }
    }

    /**
     * 保存主动消息：流式过程中已实时追加 aiMessage 到 session，这里直接持久化当前 session 状态。
     * synchronized(session) 防止与用户发送消息等并发操作互相覆盖。
     */
    /**
     * 把本轮生成的结果写回数据库。
     *
     * **这是本功能唯一一次写数据库。** 所以安全性集中在这一个函数里，
     * 可以被完整地检查和测试。
     *
     * 写回前做三项校验，任何一项不过就放弃保存：
     *
     *   1. 加载过基线 —— loadedConversation 不为 null，说明内容来自一次
     *      成功的数据库读取，不是凭空造的空壳
     *   2. 节点只增不减 —— 结果不得少于读到的节点数。本功能只会追加、
     *      或在 AI 选择沉默时删掉自己刚追加的那一条，永远不会让对话变小
     *   3. 期间没被别人改过 —— update_at 与读入时一致。否则说明用户正在
     *      这个对话里聊天，我们手里的内容已经过时，写回会覆盖他的新消息
     *
     * 放弃保存的代价只是这一轮主动消息没出现；写错一次的代价是整段历史。
     * 这个取舍方向必须是明确的。
     */
    private suspend fun saveProactiveMessage(conversationId: Uuid): Boolean {
        val base = loadedConversation ?: run {
            Log.e(TAG, "Refusing to save: no loaded baseline for $conversationId")
            return false
        }
        val nodes = synchronized(localNodesLock) { localNodes }

        // 校验 1 + 2：只能基于读到的内容做增量
        if (nodes.size < base.messageNodes.size) {
            Log.e(
                TAG,
                "Refusing to save proactive message for $conversationId: " +
                    "node count would shrink ${base.messageNodes.size} -> ${nodes.size}",
            )
            return false
        }

        // 校验 3：期间数据库没被改过（用户没在聊天）
        val latest = runCatching { conversationRepository.getConversationById(conversationId) }
            .getOrNull()
        if (latest == null) {
            Log.e(TAG, "Refusing to save: conversation $conversationId vanished")
            return false
        }
        if (latest.updateAt != base.updateAt) {
            Log.w(
                TAG,
                "Skipping proactive save for $conversationId: conversation changed underneath " +
                    "(updateAt ${base.updateAt} -> ${latest.updateAt}); user is probably chatting.",
            )
            ProactiveMessageLog.log(
                appContext, ProactiveMessageLog.Outcome.SKIPPED, "保存校验",
                "生成期间该对话有了新消息（你可能正在聊天），为避免覆盖你的内容，" +
                    "本轮主动消息没有写入。",
            )
            return false
        }

        val updated = latest.copy(
            messageNodes = nodes,
            updateAt = java.time.Instant.now(),
        )
        conversationRepository.updateConversation(updated)
        Log.d(TAG, "Saved proactive message to $conversationId (${nodes.size} nodes)")
        return true
    }

    private fun showProactiveNotification(
        conversationId: Uuid,
        senderName: String,
        message: String
    ) {
        val intent = Intent(appContext, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        appContext.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = PROACTIVE_NOTIFICATION_ID
        ) {
            title = senderName
            content = message.take(100)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = pendingIntent
            useBigTextStyle = true
        }
    }

    /**
     * 构建工具列表（主动消息场景精简版）：本地工具 + MCP 工具。
     * 不加载搜索/Skill 工具，避免工具过多导致请求体过大触发 API 400。
     */
    private suspend fun buildTools(settings: Settings, assistant: Assistant, model: Model): List<Tool> {
        return buildList {
            // 本地工具（助手已启用的）
            addAll(localTools.getTools(assistant.localTools))

            // MCP 工具：对齐 ChatService，工具名带服务器名前缀
            mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = {
                            mcpManager.callTool(serverId, tool.name, it.jsonObject)
                        },
                    )
                )
            }
        }
    }

    /**
     * 基于 AI 消息 id 在对话里就地更新（保留 MessageNode.id，避免 Compose 重建/状态丢失）
     * 或追加新 node。synchronized(session) 保护 read-modify-write，防止并发覆盖。
     */
    /**
     * 把流式产出的 AI 消息合并进本轮的本地节点列表。
     *
     * **只改内存，不碰数据库。** 原实现每收到一个 chunk 就
     * updateConversationState + saveConversation 落库一次，这不只是慢，
     * 更要命的是它把「session 从哪来」变成了安全问题——session 若是
     * 凭空造的空壳，第一次落库就把历史覆盖了。
     *
     * 现在流式过程完全不落库，落库只在整轮结束后做一次（见 persistResult），
     * 而那时手里握着的是「读到的内容 + 本次新增」，结构上不可能变少。
     */
    private fun updateOrAppendAiMessage(aiMessage: UIMessage) {
        synchronized(localNodesLock) {
            val existingNodeIndex = localNodes.indexOfFirst { node ->
                node.messages.any { it.id == aiMessage.id }
            }
            localNodes = if (existingNodeIndex >= 0) {
                val oldNode = localNodes[existingNodeIndex]
                val updatedNode = oldNode.copy(
                    messages = oldNode.messages.map {
                        if (it.id == aiMessage.id) aiMessage else it
                    }
                )
                localNodes.toMutableList().apply { this[existingNodeIndex] = updatedNode }
            } else {
                localNodes + aiMessage.toMessageNode()
            }
        }
    }

    private val localNodesLock = Any()

    /**
     * 本轮生成所操作的对话内容，**全程只存在内存里**。
     *
     * 这是重构的核心。原实现把生成过程写进 ChatService 的 session，再靠
     * saveConversation 落库，于是「session 从哪来」变成了安全问题——空壳
     * session 一落库就是整段历史被覆盖。
     *
     * 现在：开工时读一次数据库存进 [loadedConversation]，之后所有增删改
     * 都作用在 [loadedConversation] 的节点列表上，成功且校验通过才写回一次。
     *
     * 由此得到的结构保证：
     *   · 不存在「空会话」——内容只能来自一次成功的数据库读取
     *   · 写回时节点数一定 >= 读到的节点数（只追加，或删掉自己刚加的）
     *   · 期间用户改了对话会被 updateAt 校验发现并放弃保存
     */
    @Volatile
    private var loadedConversation: me.rerere.rikkahub.data.model.Conversation? = null

    @Volatile
    private var localNodes: List<me.rerere.rikkahub.data.model.MessageNode> = emptyList()

    /**
     * 过滤历史消息中"悬空"的工具调用：
     * 若某条消息存在未执行且不可恢复的工具调用，说明工具调用链没有走完，
     * 直接把整条消息剔除，避免把结构不完整的 tool_use 发给 API 触发 400。
     * （判断逻辑与 ChatService.checkInvalidMessages 保持一致）
     */
    private fun filterInvalidToolMessages(messages: List<UIMessage>): List<UIMessage> {
        return messages.filterNot { message ->
            val tools = message.getTools()
            val hasPendingTools = tools.any { !it.isExecuted }
            if (!hasPendingTools) return@filterNot false
            val hasResumableTool = tools.any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
            !hasResumableTool
        }
    }

    /**
     * 合并相邻同角色消息（ASSISTANT-ASSISTANT / USER-USER 都要合并），
     * 避免相邻同角色消息触发 Anthropic 等 API 的 400 错误。
     */
    private fun mergeAdjacentSameRoleMessages(messages: List<UIMessage>): List<UIMessage> {
        if (messages.size < 2) return messages
        return messages.fold(emptyList()) { acc, msg ->
            val prev = acc.lastOrNull()
            if (prev != null && prev.role == msg.role) {
                acc.dropLast(1) + prev.copy(parts = prev.parts + msg.parts)
            } else {
                acc + msg
            }
        }
    }

    /**
     * 生成消息，支持工具调用。流式写入会话（打开的聊天界面可实时看到）。
     * 返回最终消息列表和 AI 原始输出是否含 [JUMP] 标记。
     */
    private suspend fun generateWithTools(
        conversationId: Uuid,
        providerImpl: Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        initialMessages: List<UIMessage>,
        params: TextGenerationParams,
        tools: List<Tool>,
        model: Model,
        assistant: Assistant,
        settings: Settings,
    ): Pair<List<UIMessage>, Boolean> {
        var messages = initialMessages.toMutableList()
        var hasJumpFlag = false // AI 原始输出是否含 [JUMP] 标记（在输出转换器处理前检测）

        for (step in 0 until MAX_TOOL_STEPS) {
            Log.d(TAG, "generateWithTools: step $step/$MAX_TOOL_STEPS")

            // 防御性：每轮调用前合并相邻同角色消息，避免多步工具调用产生相邻 assistant 消息触发 400
            messages = mergeAdjacentSameRoleMessages(messages).toMutableList()

            // 流式调用 AI
            var streamMessages = messages.toList()
            val chunkHandler = StreamChunkHandler(model)
            providerImpl.streamText(
                providerSetting = providerSetting,
                messages = messages,
                params = params
            ).collect { chunk ->
                streamMessages = chunkHandler.handle(streamMessages, chunk)

                // 实时更新 session 状态，让打开的聊天界面能看到消息生成
                val currentAiMessage = streamMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                if (currentAiMessage != null) {
                    // 用 id 匹配就地更新（保留 node id，避免覆盖上一条 assistant）
                    updateOrAppendAiMessage(currentAiMessage)
                }
            }

            // 流式结束，更新 messages
            messages = streamMessages.toMutableList()
            val aiMessage = streamMessages.lastOrNull() ?: run {
                Log.w(TAG, "No message in AI response")
                break
            }

            // 在输出转换器处理前，检测 AI 原始输出是否含 [JUMP] 标记
            val rawAiText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            if (rawAiText.contains("[JUMP]")) {
                hasJumpFlag = true
                Log.d(TAG, "[JUMP] flag detected in raw AI output")
            }
            // 应用输出转换器
            val processedMessage = listOf(aiMessage).transforms(
                transformers = outputTransformers,
                context = appContext,
                model = model,
                assistant = assistant,
                settings = settings,
            ).first()
            messages[messages.lastIndex] = processedMessage

            // 检查是否有工具调用
            val toolCalls = processedMessage.getTools().filter { !it.isExecuted }

            if (toolCalls.isEmpty()) {
                // 没有工具调用，生成完成
                // 设置 Reasoning 的 finishedAt，否则 UI 会一直显示"思考中"
                val now = kotlin.time.Clock.System.now()
                val finalMessage = processedMessage.copy(
                    parts = processedMessage.parts.map { part ->
                        if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                            part.copy(finishedAt = now)
                        } else {
                            part
                        }
                    }
                )
                messages[messages.lastIndex] = finalMessage
                // 最终更新 session 状态（用 id 匹配就地更新）
                updateOrAppendAiMessage(finalMessage)
                break
            }

            Log.d(TAG, "Tool calls detected: ${toolCalls.size}")

            // 执行工具（后台模式下自动执行；需要审批的工具自动拒绝）
            val executedTools = mutableListOf<UIMessagePart.Tool>()
            for (toolCall in toolCalls) {
                val toolDef = tools.find { it.name == toolCall.toolName }
                if (toolDef == null) {
                    Log.w(TAG, "Tool ${toolCall.toolName} not found")
                    executedTools.add(toolCall.copy(
                        output = listOf(UIMessagePart.Text("""{"error":"Tool not found"}"""))
                    ))
                    continue
                }

                // toolCall.input 可能因为流式截断而是不完整的 JSON, 回退为空对象
                val args = try {
                    json.parseToJsonElement(toolCall.input.ifBlank { "{}" })
                } catch (e: Exception) {
                    Log.w(TAG, "Tool ${toolCall.toolName} input JSON is incomplete, falling back to empty object")
                    JsonObject(emptyMap())
                }

                if (toolDef.needsApproval(args)) {
                    // 后台模式下，需要审批的工具自动拒绝
                    Log.w(TAG, "Tool ${toolCall.toolName} needs approval, auto-denying in proactive mode")
                    executedTools.add(toolCall.copy(
                        output = listOf(UIMessagePart.Text("""{"error":"Tool execution denied: requires user approval in proactive mode"}""")),
                        approvalState = ToolApprovalState.Denied("Proactive mode: requires approval")
                    ))
                } else {
                    try {
                        val result = toolDef.execute(args)
                        executedTools.add(toolCall.copy(output = result))
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool execution failed: ${toolCall.toolName}", e)
                        executedTools.add(toolCall.copy(
                            output = listOf(UIMessagePart.Text("""{"error":"${e.message}"}"""))
                        ))
                    }
                }
            }

            // 更新消息中的工具状态
            val updatedParts = processedMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else {
                    part
                }
            }
            val updatedMessage = processedMessage.copy(parts = updatedParts)
            messages[messages.lastIndex] = updatedMessage
            // 更新 session 状态（带工具结果的消息，用 id 匹配就地更新）
            updateOrAppendAiMessage(updatedMessage)
        }

        return messages to hasJumpFlag
    }

}
