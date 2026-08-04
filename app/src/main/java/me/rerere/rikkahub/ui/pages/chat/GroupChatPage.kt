package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

private fun messageText(node: MessageNode): String =
    if (node.selectIndex in node.messages.indices) node.messages[node.selectIndex].toText()
    else node.messages.firstOrNull()?.toText() ?: ""

private fun buildHistoryWithNames(
    messageNodes: List<MessageNode>,
    speakerMap: Map<Uuid, Uuid>,
    members: List<Assistant>,
): List<UIMessage> {
    return messageNodes.map { node ->
        val speakerId = speakerMap[node.id]
        val speaker = members.find { it.id == speakerId }
        val namePrefix = if (speaker != null && node.role != MessageRole.USER) {
            "${speaker.name}:\n"
        } else ""
        val text = messageText(node)
        UIMessage(
            role = node.role,
            parts = listOf(UIMessagePart.Text("$namePrefix$text")),
        )
    }
}

/** 获取自上次用户消息后的发言者顺序 */
private fun getSpeakerHistory(
    nodes: List<MessageNode>,
    speakerMap: Map<Uuid, Uuid>,
): List<Uuid> {
    val result = mutableListOf<Uuid>()
    for (node in nodes.reversed()) {
        if (node.role == MessageRole.USER) break
        speakerMap[node.id]?.let { result.add(it) }
    }
    return result
}

/**
 * 按生成模式构造本次生成使用的助手（对齐酒馆）：
 * SWAP = 只用当前发言成员自己的角色卡；
 * APPEND / APPEND_DISABLED = 合并全体成员角色卡（禁言成员仅 APPEND_DISABLED 时包含）。
 */
private fun buildGenerationSpeaker(
    gc: GroupChat,
    members: List<Assistant>,
    speaker: Assistant,
): Assistant {
    val base = if (gc.chatModelId != null) speaker.copy(chatModelId = gc.chatModelId) else speaker
    if (gc.generationMode == GroupGenerationMode.SWAP) return base
    val tav = base.tavernData ?: return base
    val includeDisabled = gc.generationMode == GroupGenerationMode.APPEND_DISABLED
    val pool = members.filter { includeDisabled || it.id !in gc.disabledMemberIds }
    if (pool.size <= 1) return base

    fun join(getter: (TavernCharacterData) -> String): String =
        pool.mapNotNull { m -> m.tavernData?.let(getter) }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    return base.copy(
        tavernData = tav.copy(
            description = join { it.description },
            personality = join { it.personality },
            scenario = join { it.scenario },
            mesExample = join { it.mesExample },
        )
    )
}

/**
 * 群聊内重新生成某条消息：按该消息的发言人重新生成（避免串成第一个成员）
 */
private suspend fun regenerateGroupMessage(
    convId: Uuid,
    targetNodeId: Uuid,
    speaker: Assistant,
    gc: GroupChat,
    members: List<Assistant>,
    chatService: ChatService,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onSpeakerStart: (String) -> Unit,
) {
    // 1. 截断：删除目标节点及其后的消息（含 speakerMap）
    chatService.updateConversationState(convId) { conv ->
        val idx = conv.messageNodes.indexOfFirst { it.id == targetNodeId }
        if (idx < 0) return@updateConversationState conv
        val removedIds = conv.messageNodes.drop(idx).map { it.id }.toSet()
        conv.copy(
            messageNodes = conv.messageNodes.take(idx),
            speakerMap = conv.speakerMap.filterKeys { it !in removedIds },
        )
    }

    // 2. 用该发言人重新生成（历史 = 截断后的消息，prompt = 最后一条）
    val effectiveSpeaker = buildGenerationSpeaker(gc, members, speaker)
    val conv = chatService.getConversationFlow(convId).value
    val history = buildHistoryWithNames(conv.messageNodes, conv.speakerMap, members)
    if (history.isEmpty()) return
    val prompt = history.last().toText()
    val historyWithoutLast = history.dropLast(1)

    val placeholderNode = UIMessage.assistant("").toMessageNode()
    chatService.updateConversationState(convId) { c ->
        c.copy(
            messageNodes = c.messageNodes + placeholderNode,
            speakerMap = c.speakerMap + (placeholderNode.id to speaker.id),
        )
    }
    onSpeakerStart(speaker.name)

    try {
        val response = chatService.generateForAssistant(
            assistant = effectiveSpeaker,
            settings = settings,
            prompt = prompt,
            history = historyWithoutLast,
            conversationId = convId,
            onChunk = { partialText, parts ->
                chatService.updateConversationState(convId) { c ->
                    val nodes = c.messageNodes.toMutableList()
                    val i2 = nodes.indexOfLast { it.id == placeholderNode.id }
                    if (i2 >= 0) {
                        val updatedMsg = if (parts != null) {
                            UIMessage(
                                role = me.rerere.ai.core.MessageRole.ASSISTANT,
                                parts = parts,
                            )
                        } else {
                            UIMessage.assistant(partialText)
                        }
                        nodes[i2] = MessageNode(id = placeholderNode.id, messages = listOf(updatedMsg))
                    }
                    c.copy(messageNodes = nodes)
                }
            },
        )
        if (response.isBlank()) {
            chatService.updateConversationState(convId) { c ->
                c.copy(
                    messageNodes = c.messageNodes.filter { it.id != placeholderNode.id },
                    speakerMap = c.speakerMap - placeholderNode.id,
                )
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        chatService.updateConversationState(convId) { c ->
            c.copy(
                messageNodes = c.messageNodes.filter { it.id != placeholderNode.id },
                speakerMap = c.speakerMap - placeholderNode.id,
            )
        }
        throw e
    } catch (e: Exception) {
        chatService.updateConversationState(convId) { c ->
            c.copy(
                messageNodes = c.messageNodes.filter { it.id != placeholderNode.id },
                speakerMap = c.speakerMap - placeholderNode.id,
            )
        }
    }
}

/** 群聊状态提示条：生成中/等待/失败统一样式 */
@Composable
private fun GroupChatStatusBar(
    status: String,
    modifier: Modifier = Modifier,
) {
    val isError = status.contains("失败") || status.contains("错误")
    val isBusy = status.contains("正在") || status.contains("等待")
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                CustomColors.listItemColors.containerColor
            },
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun GroupChatPage(groupId: String) {
    val settingsStore: SettingsStore = koinInject()
    val chatService: ChatService = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val navController = me.rerere.rikkahub.ui.context.LocalNavController.current

    val gcId = Uuid.parse(groupId)
    val gc = settings.groupChats.find { it.id == gcId } ?: return
    val members = gc.memberIds.mapNotNull { id -> settings.assistants.find { it.id == id } }
    val enabledMembers = members.filter { it.id !in gc.disabledMemberIds }

    // 初始化/加载 Conversation（简化：一次性处理，不用 flowConvId 切换）
    var convId by remember { mutableStateOf<Uuid?>(null) }
    val currentConvId = convId
    val conversation by (currentConvId?.let { chatService.getConversationFlow(it) }?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(null) })

    LaunchedEffect(Unit) {
        val existingId = gc.conversationId ?: Uuid.random()
        if (gc.conversationId == null) {
            // 对齐酒馆：新群聊先加入每个成员的开场白（first_mes 或随机备选开场白）
            val greetingPairs = members.mapNotNull { m ->
                val tav = m.tavernData ?: return@mapNotNull null
                val texts = listOfNotNull(tav.firstMessage.takeIf { it.isNotBlank() }) +
                    tav.alternateGreetings.filter { it.isNotBlank() }
                val chosen = texts.randomOrNull() ?: return@mapNotNull null
                val node = UIMessage.assistant(chosen).toMessageNode()
                node to m.id
            }
            val conv = Conversation(
                id = existingId,
                assistantId = gc.memberIds.firstOrNull() ?: Uuid.random(),
                messageNodes = greetingPairs.map { it.first },
                speakerMap = greetingPairs.associate { it.first.id to it.second },
            )
            chatService.initializeConversation(existingId)
            chatService.updateConversationState(existingId) { conv }
            chatService.saveConversation(existingId, conv)
            settingsStore.update(settings.copy(
                groupChats = settings.groupChats.map { if (it.id == gcId) it.copy(conversationId = existingId) else it }
            ))
        } else {
            chatService.initializeConversation(existingId)
        }
        convId = existingId
    }

    if (currentConvId == null) return

    var selectedSpeakerId by remember { mutableStateOf(enabledMembers.firstOrNull()?.id) }
    var showSettings by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var queueStatus by remember { mutableStateOf("") }
    var queueMembers by remember { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()
    val inputState = remember { ChatInputState() }
    val hazeState = rememberHazeState()
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var generationEpoch by remember { mutableStateOf(0) }

    // 退出页面时取消生成
    DisposableEffect(Unit) {
        onDispose {
            generationJob?.cancel()
        }
    }

    val messageNodes = conversation?.messageNodes ?: emptyList()
    val speakerMap = conversation?.speakerMap ?: emptyMap()
    val lastAssistantSpeakerId = messageNodes.lastOrNull()?.let { speakerMap[it.id] }

    // 自动滚动
    LaunchedEffect(messageNodes.size) {
        if (messageNodes.isNotEmpty()) {
            listState.animateScrollToItem(messageNodes.size - 1)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(gc.name.ifBlank { "群聊" }) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(HugeIcons.Settings03, contentDescription = "群聊设置")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp,
            ) {
                Column {
                    // 排队状态
                    // 只要有状态提示就显示（含"等待自动接话""未选择发言人"等无成员名的情况）
                    AnimatedVisibility(visible = queueStatus.isNotEmpty()) {
                        GroupChatStatusBar(
                            status = queueStatus,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // MANUAL 模式：选人按钮
                        if (gc.activationStrategy == GroupActivationStrategy.MANUAL) {
                            var expanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.padding(start = 4.dp)) {
                                val speaker = members.find { it.id == selectedSpeakerId }
                                Surface(
                                    onClick = { expanded = true },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        UIAvatar(
                                            value = speaker?.avatar ?: Avatar.Dummy,
                                            name = speaker?.name ?: "选",
                                            modifier = Modifier.size(20.dp),
                                        )
                                        if (!speaker?.name.isNullOrBlank()) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                speaker!!.name,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                modifier = Modifier.widthIn(max = 60.dp),
                                            )
                                        }
                                    }
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    members.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(m.name) },
                                            onClick = {
                                                selectedSpeakerId = m.id
                                                expanded = false
                                            },
                                            leadingIcon = {
                                                UIAvatar(value = m.avatar, name = m.name, modifier = Modifier.size(20.dp))
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        ChatInput(
                            state = inputState,
                            loading = isGenerating,
                            settings = settings,
                            hazeState = hazeState,
                            enableSearch = false,
                            onToggleSearch = {},
                            onUpdateChatModel = { model ->
                                scope.launch {
                                    settingsStore.update { s ->
                                        s.copy(groupChats = s.groupChats.map { if (it.id == gcId) it.copy(chatModelId = model.id) else it })
                                    }
                                }
                            },
                            onUpdateAssistant = {},
                            onUpdateConversation = {},
                            onUpdateSearchService = {},
                            onCompressContext = { _, _, _ -> scope.launch { } },
                            onCancelClick = { generationJob?.cancel(); isGenerating = false; queueStatus = ""; queueMembers = emptyList() },
                            onSendClick = {
                                val inputContents = inputState.getContents()
                                val text = inputContents.joinToString("") { if (it is UIMessagePart.Text) it.text else "" }.trim()
                                if ((text.isBlank() && inputContents.all { it is UIMessagePart.Text }) || isGenerating) return@ChatInput

                                generationJob?.cancel()
                                val myEpoch = generationEpoch + 1
                                generationEpoch = myEpoch
                                isGenerating = true

                                // 编辑模式：直接更新消息，不走选人+生成
                                if (inputState.isEditing()) {
                                    val editMsgId = inputState.editingMessage!!
                                    inputState.clearInput()
                                    generationJob = scope.launch {
                                        chatService.updateConversationState(currentConvId) { conv ->
                                            val updatedNodes = conv.messageNodes.map { node ->
                                                val idx = node.messages.indexOfFirst { it.id == editMsgId }
                                                if (idx >= 0) {
                                                    node.copy(
                                                        messages = node.messages.mapIndexed { i, m ->
                                                            if (i == idx) m.copy(parts = inputContents) else m
                                                        }
                                                    )
                                                } else node
                                            }
                                            conv.copy(messageNodes = updatedNodes)
                                        }
                                        isGenerating = false
                                    }
                                    return@ChatInput
                                }

                                // 选人
                                val allPicked = GroupSpeakerSelector.pick(
                                    strategy = gc.activationStrategy,
                                    members = members,
                                    enabledMembers = enabledMembers,
                                    userInput = text,
                                    lastSpeakerId = lastAssistantSpeakerId,
                                    speakerHistory = getSpeakerHistory(messageNodes, speakerMap),
                                    allowSelfResponses = gc.allowSelfResponses,
                                    manualSpeakerId = selectedSpeakerId,
                                    isUserInput = true,
                                )

                                queueMembers = allPicked.mapNotNull { id -> members.find { it.id == id }?.name }
                                queueStatus = when {
                                    enabledMembers.isEmpty() -> "全部成员已禁言，仅发送消息"
                                    allPicked.isEmpty() -> "已发送（未选择发言人）"
                                    else -> "等待 ${queueMembers.joinToString("、")} 回复..."
                                }

                                inputState.clearInput()

                                generationJob = scope.launch {
                                    try {
                                        // ====== 1. 添加用户消息（无论是否生成，消息都要上屏） ======
                                        chatService.updateConversationState(currentConvId) { conv ->
                                            val userNode = UIMessage(
                                                role = MessageRole.USER,
                                                parts = inputContents,
                                            ).toMessageNode()
                                            conv.copy(messageNodes = conv.messageNodes + userNode)
                                        }
                                        if (allPicked.isEmpty()) {
                                            isGenerating = false
                                            return@launch
                                        }

                                        // ====== 2. 逐个生成 ======
                                        for ((idx, sid) in allPicked.withIndex()) {
                                            if (!isActive) break
                                            val speaker = members.find { it.id == sid } ?: continue
                                            queueStatus = "${speaker.name} 正在输入...（${idx + 1}/${allPicked.size}）"

                                            // 创建占位消息（流式更新用）
                                            val placeholderNode = UIMessage.assistant("").toMessageNode()
                                            chatService.updateConversationState(currentConvId) { conv ->
                                                conv.copy(
                                                    messageNodes = conv.messageNodes + placeholderNode,
                                                    speakerMap = conv.speakerMap + (placeholderNode.id to sid),
                                                )
                                            }

                                            val effectiveSpeaker = buildGenerationSpeaker(gc, members, speaker)

                                            // 构建历史（不含最新的user消息+占位消息，但包含之前的群聊消息）
                                            val currentConv = chatService.getConversationFlow(currentConvId).value
                                            val historyNodes = currentConv.messageNodes.dropLast(1) // 去掉占位

                                            // 构建带角色名前缀的历史
                                            val history = buildHistoryWithNames(historyNodes, currentConv.speakerMap, members)
                                            // 去掉最后一条用户消息（prompt里已经有了），保留其他上下文
                                            val lastUserIdx = history.indexOfLast { it.role == MessageRole.USER }
                                            val historyWithoutLastUser = if (lastUserIdx >= 0) {
                                                history.filterIndexed { idx, _ -> idx != lastUserIdx }
                                            } else history

                                            try {
                                                val response = chatService.generateForAssistant(
                                                    assistant = effectiveSpeaker,
                                                    settings = settings,
                                                    prompt = text,
                                                    history = historyWithoutLastUser,
                                                    conversationId = currentConvId,
                                                    onChunk = { partialText, parts ->
                                                        // 实时更新占位消息的内容
                                                        chatService.updateConversationState(currentConvId) { conv ->
                                                            val nodes = conv.messageNodes.toMutableList()
                                                            val idx2 = nodes.indexOfLast { it.id == placeholderNode.id }
                                                            if (idx2 >= 0) {
                                                                val updatedMsg = if (parts != null) {
                                                                    UIMessage(
                                                                        role = me.rerere.ai.core.MessageRole.ASSISTANT,
                                                                        parts = parts,
                                                                    )
                                                                } else {
                                                                    UIMessage.assistant(partialText)
                                                                }
                                                                nodes[idx2] = MessageNode(
                                                                    id = placeholderNode.id,
                                                                    messages = listOf(updatedMsg),
                                                                )
                                                            }
                                                            conv.copy(messageNodes = nodes)
                                                        }
                                                    },
                                                )

                                                if (response.isBlank()) {
                                                    // 空回复，删除占位
                                                    chatService.updateConversationState(currentConvId) { conv ->
                                                        conv.copy(
                                                            messageNodes = conv.messageNodes.filter { it.id != placeholderNode.id },
                                                            speakerMap = conv.speakerMap - placeholderNode.id,
                                                        )
                                                    }
                                                    queueStatus = "${speaker.name} 回复为空"
                                                    delay(1200)
                                                }
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                // 用户打断：清理占位消息后向上抛出
                                                chatService.updateConversationState(currentConvId) { conv ->
                                                    conv.copy(
                                                        messageNodes = conv.messageNodes.filter { it.id != placeholderNode.id },
                                                        speakerMap = conv.speakerMap - placeholderNode.id,
                                                    )
                                                }
                                                throw e
                                            } catch (e: Exception) {
                                                queueStatus = "${speaker.name} 生成失败：${e.message?.take(40) ?: "未知错误"}"
                                                // 删除占位消息
                                                chatService.updateConversationState(currentConvId) { conv ->
                                                    conv.copy(
                                                        messageNodes = conv.messageNodes.filter { it.id != placeholderNode.id },
                                                        speakerMap = conv.speakerMap - placeholderNode.id,
                                                    )
                                                }
                                                delay(2000)
                                            }
                                        }

                                        // ====== 3. 自动接话 ======
                                        if (gc.autoModeDelay > 0 && isActive) {
                                            queueStatus = "等待自动接话（${gc.autoModeDelay}秒）..."
                                            delay(gc.autoModeDelay * 1000L)
                                            if (isActive) {
                                                // 解锁输入：用户发消息即打断自动接话（对齐酒馆打字即停）
                                                isGenerating = false
                                                // 用最后一条 AI 回复作为输入触发下一轮
                                                val freshConv = chatService.getConversationFlow(currentConvId).value
                                                val lastAsstMsg = freshConv.messageNodes.lastOrNull { it.role == MessageRole.ASSISTANT }
                                                val autoText = lastAsstMsg?.let { messageText(it) } ?: ""
                                                if (autoText.isNotBlank()) {
                                                    // 自动触发下一轮
                                                    runAutoChat(
                                                        convId = currentConvId,
                                                        gc = gc,
                                                        members = members,
                                                        enabledMembers = enabledMembers,
                                                        chatService = chatService,
                                                        settingsStore = settingsStore,
                                                        settings = settings,
                                                        isCurrent = { myEpoch == generationEpoch },
                                                        onSpeakerStart = { name, round, total ->
                                                            queueStatus = if (total <= 0) {
                                                                "$name 正在输入...（自动第 $round 轮）"
                                                            } else {
                                                                "$name 正在输入...（自动 $round/$total）"
                                                            }
                                                            queueMembers = listOf(name)
                                                        },
                                                        onWaiting = {
                                                            queueStatus = "等待自动接话（${gc.autoModeDelay}秒）..."
                                                            queueMembers = emptyList()
                                                        },
                                                        onEmptyReply = { name ->
                                                            queueStatus = "$name 回复为空"
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    } finally {
                                        // 只有当前代次才负责收尾，旧任务被新消息打断时不碰状态
                                        if (myEpoch == generationEpoch) {
                                            // 持久化群聊会话
                                            val finalConv = chatService.getConversationFlow(currentConvId).value
                                            chatService.saveConversation(currentConvId, finalConv)
                                            isGenerating = false
                                            queueStatus = ""
                                            queueMembers = emptyList()
                                        }
                                    }
                                }
                            },
                            onLongSendClick = {
                                chatService.sendMessage(currentConvId, inputState.getContents(), answer = false)
                                inputState.clearInput()
                            },
                            onMoreClick = {},
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            itemsIndexed(messageNodes, key = { _, n -> n.id }) { index, node ->
                val speakerId = speakerMap[node.id]
                val speaker = members.find { it.id == speakerId }
                if (speaker != null && node.role != MessageRole.USER) {
                    Row(
                        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UIAvatar(
                            value = speaker.avatar,
                            name = speaker.name,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(speaker.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                    }
                }
                ChatMessage(
                    node = node,
                    assistant = speaker ?: members.firstOrNull(),
                    model = null,
                    loading = (isGenerating || queueStatus.contains("正在")) && index >= messageNodes.lastIndex - 2,
                    lastMessage = index == messageNodes.lastIndex,
                    onRegenerate = {
                        val target = node.messages[node.selectIndex]
                        val sid = speakerMap[node.id]
                        val sp = sid?.let { id -> members.find { it.id == id } }
                        if (sp == null) {
                            chatService.regenerateAtMessage(currentConvId, target)
                        } else {
                            generationJob?.cancel()
                            val myEpoch = generationEpoch + 1
                            generationEpoch = myEpoch
                            isGenerating = true
                            generationJob = scope.launch {
                                try {
                                    regenerateGroupMessage(
                                        convId = currentConvId,
                                        targetNodeId = node.id,
                                        speaker = sp,
                                        gc = gc,
                                        members = members,
                                        chatService = chatService,
                                        settings = settings,
                                        onSpeakerStart = { name ->
                                            queueStatus = "$name 正在重新生成..."
                                            queueMembers = listOf(name)
                                        },
                                    )
                                } finally {
                                    if (myEpoch == generationEpoch) {
                                        chatService.saveConversation(
                                            currentConvId,
                                            chatService.getConversationFlow(currentConvId).value,
                                        )
                                        isGenerating = false
                                        queueStatus = ""
                                        queueMembers = emptyList()
                                    }
                                }
                            }
                        }
                    },
                    onEdit = {
                        val msg = node.messages[node.selectIndex]
                        inputState.setContents(msg.parts)
                        inputState.editingMessage = msg.id
                    },
                    onDelete = { scope.launch { chatService.deleteMessage(currentConvId, node.messages.first().id) } },
                    onShare = {},
                    onUpdate = { newNode ->
                        chatService.updateConversationState(currentConvId) { conv ->
                            conv.copy(
                                messageNodes = conv.messageNodes.map { if (it.id == newNode.id) newNode else it }
                            )
                        }
                    },
                    onFork = {
                        scope.launch {
                            val fork = chatService.forkConversationAtMessage(currentConvId, node.messages[node.selectIndex].id)
                            me.rerere.rikkahub.utils.navigateToChatPage(navController, chatId = fork.id)
                        }
                    },
                    onImpersonate = { inputState.setMessageText(messageText(node)) },
                    onTranslate = { msg, locale -> chatService.translateMessage(currentConvId, msg, locale) },
                    onClearTranslation = { chatService.clearTranslationField(currentConvId, it.id) },
                )
            }
        }
    }

    // —— 群聊设置对话框 ——
    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
        ) {
            ScrollableColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                    // 群名
                    Column {
                        Text(stringResource(R.string.group_chat_name), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = gc.name,
                            onValueChange = { v ->
                                scope.launch {
                                    settingsStore.update { s ->
                                        s.copy(groupChats = s.groupChats.map { if (it.id == gcId) it.copy(name = v) else it })
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // 模型选择
                    Column {
                        Text(stringResource(R.string.group_model), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        ModelSelector(
                            modelId = gc.chatModelId,
                            providers = settings.providers,
                            type = ModelType.CHAT,
                            onSelect = { model ->
                                scope.launch {
                                    settingsStore.update { s ->
                                        s.copy(groupChats = s.groupChats.map { if (it.id == gcId) it.copy(chatModelId = model.id) else it })
                                    }
                                }
                            },
                        )
                    }

                    Divider()

                    // 激活策略
                    CardGroup(title = { Text(stringResource(R.string.group_activation_strategy)) }) {
                        listOf(
                            GroupActivationStrategy.NATURAL to "自然(Natural)",
                            GroupActivationStrategy.LIST to "列表(List)",
                            GroupActivationStrategy.MANUAL to "手动(Manual)",
                            GroupActivationStrategy.POOLED to "随机(Pooled)",
                        ).forEach { (strategy, label) ->
                            item(
                                onClick = {
                                    scope.launch {
                                        settingsStore.update { s ->
                                            s.copy(groupChats = s.groupChats.map {
                                                if (it.id == gcId) it.copy(activationStrategy = strategy) else it
                                            })
                                        }
                                    }
                                },
                                headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                trailingContent = {
                                    RadioButton(
                                        selected = gc.activationStrategy == strategy,
                                        onClick = {
                                            scope.launch {
                                                settingsStore.update { s ->
                                                    s.copy(groupChats = s.groupChats.map {
                                                        if (it.id == gcId) it.copy(activationStrategy = strategy) else it
                                                    })
                                                }
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }

                    // 生成模式
                    CardGroup(title = { Text(stringResource(R.string.group_generation_mode)) }) {
                        listOf(
                            GroupGenerationMode.SWAP to "替换(Swap)",
                            GroupGenerationMode.APPEND to "追加(Append)",
                            GroupGenerationMode.APPEND_DISABLED to "追加含禁言(Append Disabled)",
                        ).forEach { (mode, label) ->
                            item(
                                onClick = {
                                    scope.launch {
                                        settingsStore.update { s ->
                                            s.copy(groupChats = s.groupChats.map {
                                                if (it.id == gcId) it.copy(generationMode = mode) else it
                                            })
                                        }
                                    }
                                },
                                headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                trailingContent = {
                                    RadioButton(
                                        selected = gc.generationMode == mode,
                                        onClick = {
                                            scope.launch {
                                                settingsStore.update { s ->
                                                    s.copy(groupChats = s.groupChats.map {
                                                        if (it.id == gcId) it.copy(generationMode = mode) else it
                                                    })
                                                }
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }

                    // 允许自回复
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.group_allow_self_reply), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.group_self_reply_desc), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = gc.allowSelfResponses,
                            onCheckedChange = { v ->
                                scope.launch {
                                settingsStore.update { s ->
                                    s.copy(groupChats = s.groupChats.map { if (it.id == gcId) it.copy(allowSelfResponses = v) else it })
                                }
                                }
                            }
                        )
                    }

                    // 自动接话延迟
                    Column {
                        Text(stringResource(R.string.group_auto_reply_delay, gc.autoModeDelay), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.group_auto_reply_delay_desc), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Slider(
                            value = gc.autoModeDelay.toFloat(),
                            onValueChange = { v ->
                                scope.launch {
                                    settingsStore.update { s ->
                                        s.copy(groupChats = s.groupChats.map { if (it.id == gcId) it.copy(autoModeDelay = v.toInt()) else it })
                                    }
                                }
                            },
                            valueRange = 0f..30f,
                            steps = 29,
                        )
                    }

                    // 自动接话轮数
                    Column {
                        Text(
                            "自动接话轮数: ${if (gc.autoChatRounds <= 0) "无上限" else gc.autoChatRounds}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "0=无上限；每轮全体已选成员各发言一次，发消息即打断",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Slider(
                            value = gc.autoChatRounds.toFloat(),
                            onValueChange = { v ->
                                scope.launch {
                                    settingsStore.update { s ->
                                        s.copy(groupChats = s.groupChats.map { if (it.id == gcId) it.copy(autoChatRounds = v.toInt()) else it })
                                    }
                                }
                            },
                            valueRange = 0f..30f,
                            steps = 29,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.group_rounds_unlimited), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("30", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Divider()

                    // 成员列表
                    Text(stringResource(R.string.group_member_settings), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    members.forEach { m ->
                        val isEnabled = m.id !in gc.disabledMemberIds
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    UIAvatar(
                                        value = m.avatar,
                                        name = m.name,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(m.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                        Text(if (isEnabled) "已启用" else "已禁用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = isEnabled,
                                        onCheckedChange = { v ->
                                            scope.launch {
                                            settingsStore.update { s ->
                                                val newDisabled = if (v) gc.disabledMemberIds - m.id else gc.disabledMemberIds + m.id
                                                s.copy(groupChats = s.groupChats.map { if (it.id == gcId) it.copy(disabledMemberIds = newDisabled) else it })
                                            }
                                            }
                                        }
                                    )
                                }
                                if (isEnabled) {
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.group_talkativeness), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp))
                                        Slider(
                                            value = m.talkativeness,
                                            onValueChange = { v ->
                                                scope.launch {
                                                settingsStore.update { s ->
                                                    s.copy(assistants = s.assistants.map { if (it.id == m.id) it.copy(talkativeness = v) else it })
                                                }
                                                }
                                            },
                                            valueRange = 0f..1f,
                                            steps = 19,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text("%.1f".format(m.talkativeness), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(24.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
    }
}

/** 可滚动的设置面板 */
@Composable
private fun ScrollableColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

private suspend fun runAutoChat(
    convId: Uuid,
    gc: GroupChat,
    members: List<Assistant>,
    enabledMembers: List<Assistant>,
    chatService: ChatService,
    settingsStore: SettingsStore,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    isCurrent: () -> Boolean,
    onSpeakerStart: (name: String, round: Int, total: Int) -> Unit,
    onWaiting: () -> Unit,
    onEmptyReply: (name: String) -> Unit,
) {
    val autoDelay = gc.autoModeDelay
    if (autoDelay <= 0) return

    // 对齐酒馆：每轮一批；用户发消息（代次变化）即打断，另有固定轮数上限防止无限接话
    val maxAutoRounds = if (gc.autoChatRounds <= 0) Int.MAX_VALUE else gc.autoChatRounds.coerceAtLeast(1)
    var round = 0
    while (isCurrent() && round < maxAutoRounds) {
        round++
        val conv = chatService.getConversationFlow(convId).value
        val lastSpeakerId = conv.messageNodes.lastOrNull()?.let { conv.speakerMap[it.id] }

        // 以最后一条AI回复作为triggerText供选人匹配
        val lastText = conv.messageNodes.lastOrNull()?.let { messageText(it) } ?: ""
        if (lastText.isBlank()) break

        // 选人
        val picked = GroupSpeakerSelector.pick(
            strategy = gc.activationStrategy,
            members = members,
            enabledMembers = enabledMembers,
            userInput = lastText,
            lastSpeakerId = lastSpeakerId,
            speakerHistory = getSpeakerHistory(conv.messageNodes, conv.speakerMap),
            allowSelfResponses = gc.allowSelfResponses,
            isUserInput = false,
        )
        if (picked.isEmpty()) break

        var generatedCount = 0
        for ((idx, sid) in picked.withIndex()) {
            if (!isCurrent()) return
            val speaker = members.find { it.id == sid } ?: continue
            onSpeakerStart(speaker.name, round, maxAutoRounds)

            val placeholderNode = UIMessage.assistant("").toMessageNode()
            chatService.updateConversationState(convId) { c ->
                c.copy(
                    messageNodes = c.messageNodes + placeholderNode,
                    speakerMap = c.speakerMap + (placeholderNode.id to sid),
                )
            }

            val effectiveSpeaker = buildGenerationSpeaker(gc, members, speaker)

            val currentConv = chatService.getConversationFlow(convId).value
            val historyNodes = currentConv.messageNodes.dropLast(1)
            val history = buildHistoryWithNames(historyNodes, currentConv.speakerMap, members)
            // 自动接话模式下以最后一条AI回复作为prompt
            val prompt = history.lastOrNull()?.let { it.toText() } ?: lastText
            val historyWithoutLast = history.dropLast(1)

            try {
                val response = chatService.generateForAssistant(
                    assistant = effectiveSpeaker,
                    settings = settings,
                    prompt = prompt,
                    history = historyWithoutLast,
                    conversationId = convId,
                    onChunk = { partialText, parts ->
                        chatService.updateConversationState(convId) { c ->
                            val nodes = c.messageNodes.toMutableList()
                            val i2 = nodes.indexOfLast { it.id == placeholderNode.id }
                            if (i2 >= 0) {
                                val updatedMsg = if (parts != null) {
                                    UIMessage(
                                        role = me.rerere.ai.core.MessageRole.ASSISTANT,
                                        parts = parts,
                                    )
                                } else {
                                    UIMessage.assistant(partialText)
                                }
                                nodes[i2] = MessageNode(id = placeholderNode.id, messages = listOf(updatedMsg))
                            }
                            c.copy(messageNodes = nodes)
                        }
                    },
                )
                if (response.isBlank()) {
                    // 空回复：删除占位并提示，不计入已生成
                    chatService.updateConversationState(convId) { c ->
                        c.copy(
                            messageNodes = c.messageNodes.filter { it.id != placeholderNode.id },
                            speakerMap = c.speakerMap - placeholderNode.id,
                        )
                    }
                    onEmptyReply(speaker.name)
                    kotlinx.coroutines.delay(1200)
                } else {
                    generatedCount++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户打断：清理占位消息后向上抛出
                chatService.updateConversationState(convId) { c ->
                    c.copy(
                        messageNodes = c.messageNodes.filter { it.id != placeholderNode.id },
                        speakerMap = c.speakerMap - placeholderNode.id,
                    )
                }
                throw e
            } catch (e: Exception) {
                chatService.updateConversationState(convId) { c ->
                    c.copy(
                        messageNodes = c.messageNodes.filter { it.id != placeholderNode.id },
                        speakerMap = c.speakerMap - placeholderNode.id,
                    )
                }
            }
        }

        if (generatedCount == 0) break

        if (round < maxAutoRounds) {
            onWaiting()
            kotlinx.coroutines.delay(autoDelay * 1000L)
        }
    }
}
