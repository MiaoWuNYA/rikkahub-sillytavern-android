package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import me.rerere.ai.provider.ModelAbility
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Telephone
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.ai.core.MessageRole
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ai.ChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.richtext.findFencedHtmlDocument
import me.rerere.rikkahub.ui.components.richtext.splitCardSegments
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ai.SlashVarOp
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.components.ai.applyMacroVarSlash
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.ai.ui.isEmptyInputMessage
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.ui.pages.assistant.detail.hasFrontendCardContent

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Clear input focus so popup transitions cannot reopen the keyboard.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val startVoiceMode = rememberVoiceModeStarter(vm, setting)

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    // 开场白选择器：新对话且有多条开场白时自动弹出
    var showGreetingPicker by rememberSaveable { mutableStateOf(false) }
    var greetingPicked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(conversation.newConversation, setting.assistants, setting.assistantId) {
        if (conversation.newConversation && !greetingPicked) {
            val assistant = setting.getAssistantById(conversation.assistantId)
                ?: setting.getCurrentAssistant()
            val tav = assistant?.tavernData
            val allGreetings = listOfNotNull(tav?.firstMessage?.takeIf { it.isNotBlank() }) +
                (tav?.alternateGreetings?.filter { it.isNotBlank() } ?: emptyList())
            if (allGreetings.size > 1) {
                showGreetingPicker = true
            }
        }
    }

    if (showGreetingPicker && !greetingPicked) {
        val assistant = setting.getAssistantById(conversation.assistantId)
            ?: setting.getCurrentAssistant()
        val tav = assistant?.tavernData
        val allGreetings = listOfNotNull(tav?.firstMessage?.takeIf { it.isNotBlank() }) +
            (tav?.alternateGreetings?.filter { it.isNotBlank() } ?: emptyList())

        GreetingPickerDialog(
            greetings = allGreetings,
            currentGreeting = assistant?.presetMessages
                ?.firstOrNull { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
                ?.toText() ?: "",
            onSelect = { greeting ->
                scope.launch {
                    // 开场白里的宏（{{user}} 等）在此替换，避免界面显示原文
                    val processed = assistant?.let {
                        me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
                            .substituteGreetingMacros(context, setting, it, greeting)
                    } ?: greeting
                    val updatedAssistant = assistant?.copy(
                        presetMessages = listOf(UIMessage.assistant(processed))
                    )
                    if (updatedAssistant != null) {
                        val newConv = conversation.copy(
                            messageNodes = listOf()
                        ).updateCurrentMessages(updatedAssistant.presetMessages)
                        vm.updateConversation(newConv)
                    }
                }
                greetingPicked = true
                showGreetingPicker = false
            },
            onDismiss = {
                greetingPicked = true
                showGreetingPicker = false
            },
        )
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    onStartVoiceMode: () -> Unit,
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    assistant: Assistant,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val workspaceRepository: WorkspaceRepository = koinInject()

    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    var showFilesSheet by remember { mutableStateOf(false) }
    val attachmentPickerActions = rememberChatAttachmentPickerActions(
        inputState = inputState,
        setting = setting,
        onAttachmentAdded = { showFilesSheet = false },
    )
    val allowAudioVideoAttachments =
        currentChatModel?.findProvider(setting.providers) is ProviderSetting.Google

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = setting.displaySetting.chatBackgroundColor?.toComposeColor()
            ?: MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
    // WebView 工具条点按钮 → 原生菜单；处理完清空避免重复触发
    var proxyMenu by remember { mutableStateOf<String?>(null) }
    val pageScope = rememberCoroutineScope()
    // 三态：自动识别（开场白/角色世界书含网页）/ 强制开 / 强制关
    val frontendCardPreferredNow = remember(setting, conversation) {
        val current = setting.getAssistantOf(conversation)
        when (current.frontendCardMode) {
            me.rerere.rikkahub.data.model.FrontendCardMode.ALWAYS -> true
            me.rerere.rikkahub.data.model.FrontendCardMode.NEVER -> false
            me.rerere.rikkahub.data.model.FrontendCardMode.AUTO ->
                current.frontendCardPreferred ||
                    current.hasFrontendCardContent() ||
                    setting.lorebooks.any { book ->
                        book.id in current.lorebookIds && book.entries.any { entry ->
                            entry.content.contains("<html", true) || entry.content.contains("<body", true)
                        }
                    }
        }
    }
    // WebView 里点消息 → 用原生对话框做操作，功能与原生列表一致
    var webActionIndex by remember { mutableStateOf<Int?>(null) }
    var webEditIndex by remember { mutableStateOf<Int?>(null) }
    var webEditText by remember { mutableStateOf("") }

    // WebView 模式下的展示用消息：与原生渲染同一套展示层转换（消息正则
    var displayMessages by remember { mutableStateOf(conversation.currentMessages) }
    LaunchedEffect(conversation.currentMessages, frontendCardPreferredNow) {
        // 先立刻把原始消息交给页面：展示层转换是挂起操作，等它跑完再显示会让
        displayMessages = conversation.currentMessages
        displayMessages = if (!frontendCardPreferredNow) {
            conversation.currentMessages
        } else {
            runCatching {
                conversation.currentMessages.visualTransforms(
                    transformers = listOf(
                        me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer,
                        me.rerere.rikkahub.data.ai.transformers.OutputTagCleanupTransformer,
                        me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer,
                    ),
                    context = context,
                    model = currentChatModel ?: me.rerere.ai.provider.Model(),
                    assistant = setting.getAssistantOf(conversation),
                    settings = setting,
                    conversationId = conversation.id,
                )
            }.getOrDefault(conversation.currentMessages)
        }
    }

    // 原生事件总线：把生成与消息变化按酒馆事件名推给页面与卡片
    val webEvents = remember { MutableSharedFlow<WebUiEvent>(extraBufferCapacity = 64) }
    var lastEventKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(conversation.currentMessages, loadingJob != null) {
        val list = conversation.currentMessages
        val key = list.joinToString(",") { it.id.toString() } + "|" + (loadingJob != null)
        if (lastEventKey == key) return@LaunchedEffect
        val previous = lastEventKey
        lastEventKey = key
        if (previous == null) return@LaunchedEffect
        val previouslyLoading = previous.endsWith("|true")
        val nowLoading = loadingJob != null
        val previousCount = previous.substringBefore("|").split(",").count { it.isNotBlank() }
        if (!previouslyLoading && nowLoading) {
            webEvents.tryEmit(WebUiEvent("GENERATION_STARTED", "[]"))
        }
        if (previouslyLoading && !nowLoading) {
            webEvents.tryEmit(WebUiEvent("GENERATION_ENDED", "[${list.lastIndex}]"))
            webEvents.tryEmit(WebUiEvent("MESSAGE_RECEIVED", "[${list.lastIndex}]"))
        }
        if (list.size > previousCount && !nowLoading) {
            webEvents.tryEmit(WebUiEvent("MESSAGE_SENT", "[${list.lastIndex}]"))
        }
        if (list.size < previousCount) {
            webEvents.tryEmit(WebUiEvent("MESSAGE_DELETED", "[${list.size}]"))
        } else if (list.size == previousCount && !nowLoading && !previouslyLoading) {
            webEvents.tryEmit(WebUiEvent("MESSAGE_UPDATED", "[${list.lastIndex}]"))
        }
    }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopBar(
                    settings = setting,
                    assistant = assistant,
                    chatModel = currentChatModel,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    },
                    onVoiceCall = {
                        val activeId = VoiceCallService.activeConversationId.value
                        when {
                            activeId == null || activeId == conversation.id.toString() -> {
                                navController.navigate(Screen.VoiceCall(conversation.id.toString()))
                            }
                            else -> toaster.show("当前有通话进行中，请先挂断", type = ToastType.Warning)
                        }
                    },
                    onVideoCall = {
                        val activeId = VoiceCallService.activeConversationId.value
                        if (activeId == null || activeId == conversation.id.toString()) {
                            navController.navigate(Screen.VideoCall(conversation.id.toString()))
                        } else {
                            toaster.show("当前有通话进行中，请先挂断", type = ToastType.Warning)
                        }
                    },
                )
            },
            bottomBar = {
            val barFocusManager = LocalFocusManager.current
            val barKeyboard = LocalSoftwareKeyboardController.current
                val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
                val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
                // 前端卡（HTML 文档 WebView）在场时禁用输入栏模糊/玻璃：
                val hasFrontendCard = frontendCardPreferredNow
                ChatInput(
                    hasFrontendCard = hasFrontendCard,
                    hideToolbar = false,
                    proxyMenu = proxyMenu,
                    onProxyMenuHandled = { proxyMenu = null },
                    onStartVoiceMode = onStartVoiceMode,
                    voiceState = voiceState,
                    onStopVoiceMode = vm.voiceSession::stop,
                    state = inputState,
                    messageQueue = messageQueue,
                    onRemoveQueuedMessage = vm::removeQueuedMessage,
                    onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                    onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
                    onResumeMessageQueue = vm::resumeMessageQueue,
                    loading = loadingJob != null,
                    settings = setting,
                    assistant = assistant,
                    chatModel = currentChatModel,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    onUpdateSearchMode = { mode ->
                        vm.updateSearch(
                            enableWebSearch = mode == SearchMode.LOCAL,
                            builtInSearch = mode == SearchMode.BUILT_IN,
                        )
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show(context.getString(R.string.slash_toast_no_model), type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(inputState.getContents(), inputState.editingMessage!!)
                            inputState.clearInput()
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            inputState.clearInput()
                        }
                        barFocusManager.clearFocus(force = true)
                        barKeyboard?.hide()
                        scope.launch {
                            delay(100.milliseconds)
                            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                        }
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(inputState.getContents(), inputState.editingMessage!!)
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                        }
                        inputState.clearInput()
                        barFocusManager.clearFocus(force = true)
                        barKeyboard?.hide()
                    },
                    onSlashDuplicate = {
                        val src = setting.getCurrentAssistant()
                        val dup = src.copy(
                            id = kotlin.uuid.Uuid.random(),
                            name = src.name.ifBlank { context.getString(R.string.slash_ui_unnamed) } + context.getString(R.string.slash_ui_copy_suffix),
                        )
                        vm.updateSettings { it.copy(assistants = setting.assistants + dup) }
                        toaster.show(context.getString(R.string.slash_toast_char_duplicated, dup.name))
                    },
                    onSlashInsert = { role, text, name, at ->
                        vm.handleInsertMessage(role, text, name, at)
                    },
                    onSlashPersona = { query, mode ->
                        val personas = setting.personas
                        val current = personas.find { it.id == setting.activePersonaId }
                        when {
                            query.isBlank() -> {
                                toaster.show(
                                    if (current != null) {
                                        context.getString(R.string.slash_toast_persona_current, current.name)
                                    } else {
                                        context.getString(R.string.slash_toast_persona_none_active, personas.joinToString(context.getString(R.string.slash_field_sep)) { it.name })
                                    }
                                )
                            }

                            query.equals("off", ignoreCase = true) || query.equals("none", ignoreCase = true) -> {
                                if (setting.activePersonaId != null) {
                                    vm.updateSettings { it.copy(activePersonaId = null) }
                                    toaster.show(context.getString(R.string.slash_toast_persona_off))
                                } else {
                                    toaster.show(context.getString(R.string.slash_toast_persona_already_off))
                                }
                            }

                            else -> {
                                val target = personas.firstOrNull {
                                    it.name.equals(query, ignoreCase = true) || it.title.equals(query, ignoreCase = true)
                                }
                                when {
                                    // 官方 /persona-set mode=lookup：只选已有人设
                                    target == null && mode == "lookup" -> {
                                        toaster.show(context.getString(R.string.slash_toast_persona_not_found, query))
                                    }

                                    // 官方 /persona-set mode=temp：只设置临时用户名
                                    target != null && mode == "temp" -> {
                                        vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(userNickname = query)) }
                                        toaster.show(context.getString(R.string.slash_toast_persona_temp_set, query))
                                    }

                                    // 官方 /persona-set mode=all（默认）：先找已有
                                    target != null -> {
                                        vm.updateSettings { it.copy(activePersonaId = target.id) }
                                        toaster.show(context.getString(R.string.slash_toast_persona_switched, target.name))
                                    }

                                    else -> {
                                        vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(userNickname = query)) }
                                        toaster.show(context.getString(R.string.slash_toast_persona_fallback, query, query))
                                    }
                                }
                            }
                        }
                    },
                    onSlashTrigger = {
                        if (currentChatModel == null) {
                            toaster.show(context.getString(R.string.slash_toast_no_model), type = ToastType.Error)
                        } else if (vm.conversation.value.currentMessages.isEmpty()) {
                            toaster.show(context.getString(R.string.slash_toast_trigger_no_messages), type = ToastType.Warning)
                        } else {
                            vm.handleTriggerGeneration()
                        }
                    },
                    onSlashContinue = { prompt ->
                        if (currentChatModel == null) {
                            toaster.show(context.getString(R.string.slash_toast_no_model), type = ToastType.Error)
                        } else {
                            vm.continueGeneration(prompt)
                        }
                    },
                    onSlashImpersonate = { prefill ->
                        if (currentChatModel == null) {
                            toaster.show(context.getString(R.string.slash_toast_no_model), type = ToastType.Error)
                        } else {
                            var announced = false
                            vm.impersonateDraft(prefill) { draft ->
                                // 官方流式写输入框：每次 chunk 全量替换
                                inputState.setMessageText(draft)
                                if (!announced) {
                                    announced = true
                                    toaster.show(context.getString(R.string.slash_toast_draft_ready))
                                }
                            }
                        }
                    },
                    onSlashRerollPick = { seedArg ->
                        val chatKey = conversation.id.toString()
                        val current = setting.macroChatVariables[chatKey]?.get("__pick_reroll_seed")?.toLongOrNull() ?: 0L
                        val next = seedArg.toLongOrNull() ?: (current + 1)
                        val (newSettings, _) = applyMacroVarSlash(
                            context = context,
                            settings = setting,
                            op = SlashVarOp.SET,
                            name = "__pick_reroll_seed",
                            value = next.toString(),
                            chatKey = chatKey,
                        )
                        if (newSettings !== setting) {
                            vm.updateSettings { newSettings }
                        }
                        toaster.show(context.getString(R.string.slash_toast_reroll_done, next))
                    },
                    onSlashSysgen = { prompt, name, at, trim ->
                        if (currentChatModel == null) {
                            toaster.show(context.getString(R.string.slash_toast_no_model), type = ToastType.Error)
                        } else {
                            vm.handleGenerateSystemNarration(prompt, name, at, trim)
                        }
                    },
                    onSlashGen = { args, onDraft ->
                        if (currentChatModel == null) {
                            toaster.show(context.getString(R.string.slash_toast_no_model), type = ToastType.Error)
                        } else {
                            vm.quietGenerate(args, onDraft)
                        }
                    },
                    onSlashVar = { op, name, value ->
                        val (newSettings, result) = applyMacroVarSlash(
                            context = context,
                            settings = setting,
                            op = op,
                            name = name,
                            value = value,
                            chatKey = conversation.id.toString(),
                        )
                        if (newSettings !== setting) {
                            vm.updateSettings { newSettings }
                        }
                        result
                    },
                    onUpdateChatModel = vm::setChatModel,
                    onUpdateAssistant = vm::updateAssistant,
                    onUpdateSearchService = { index ->
                        vm.updateSettings { it.copy(searchServiceSelected = index) }
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                )
            },
        ) { innerPadding ->
            // 界面 WebView 化只在两种情况下启用：导入角色卡时检测到前端自动置位，
            val chatAssistant = setting.getAssistantOf(conversation)
            // 只有该助手被标记为前端卡会话时才走 WebView，普通会话一律原生
            val frontendCardMode = frontendCardPreferredNow
            // WebView 工具条点按钮 → 原生菜单；处理完清空避免重复触发
            webActionIndex?.let { index ->
                val message = conversation.currentMessages.getOrNull(index)
                if (message == null) {
                    webActionIndex = null
                } else {
                    AlertDialog(
                        onDismissRequest = { webActionIndex = null },
                        title = { Text(stringResource(R.string.assistant_page_actions)) },
                        text = {
                            Column {
                                TextButton(onClick = {
                                    val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clip.setPrimaryClip(android.content.ClipData.newPlainText("message", message.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().joinToString("\n") { it.text }))
                                    webActionIndex = null
                                }) { Text(stringResource(R.string.copy)) }
                                TextButton(onClick = {
                                    webEditText = message.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().joinToString("\n") { it.text }
                                    webEditIndex = index
                                    webActionIndex = null
                                }) { Text(stringResource(R.string.edit)) }
                                TextButton(onClick = {
                                    vm.deleteMessage(message)
                                    webActionIndex = null
                                }) { Text(stringResource(R.string.delete)) }
                                // 重试：走原生的按消息重新生成，而不是再发一条新消息
                                TextButton(onClick = {
                                    vm.regenerateAtMessage(message, regenerateAssistantMsg = true)
                                    webActionIndex = null
                                }) { Text(stringResource(R.string.prompt_page_trigger_regenerate)) }
                                // 翻译：与原生消息菜单同一入口，语言取当前显示语言
                                TextButton(onClick = {
                                    vm.translateMessage(message, java.util.Locale.getDefault())
                                    webActionIndex = null
                                }) { Text(stringResource(R.string.translate)) }
                                // 分享：把消息正文丢给系统分享
                                TextButton(onClick = {
                                    val text = message.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                                        .joinToString("\n") { it.text }
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                                    }
                                    runCatching { context.startActivity(android.content.Intent.createChooser(intent, null)) }
                                    webActionIndex = null
                                }) { Text(stringResource(R.string.share)) }
                                // 收藏：按消息节点切换，与原生列表一致
                                TextButton(onClick = {
                                    val node = conversation.messageNodes.find { node ->
                                        node.messages.any { it.id == message.id }
                                    }
                                    if (node != null) vm.toggleMessageFavorite(node)
                                    webActionIndex = null
                                }) { Text(stringResource(R.string.chat_message_add_favorite)) }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { webActionIndex = null }) { Text(stringResource(R.string.cancel)) }
                        },
                    )
                }
            }
            // 编辑对话框：改完写回同一条消息，走原生编辑路径
            webEditIndex?.let { index ->
                val message = conversation.currentMessages.getOrNull(index)
                if (message == null) {
                    webEditIndex = null
                } else {
                    AlertDialog(
                        onDismissRequest = { webEditIndex = null },
                        title = { Text(stringResource(R.string.edit)) },
                        text = {
                            OutlinedTextField(
                                value = webEditText,
                                onValueChange = { webEditText = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                vm.handleMessageEdit(listOf(me.rerere.ai.ui.UIMessagePart.Text(webEditText)), message.id)
                                webEditIndex = null
                            }) { Text(stringResource(R.string.confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { webEditIndex = null }) { Text(stringResource(R.string.cancel)) }
                        },
                    )
                }
            }

            if (frontendCardMode) {
                // 前端卡专属模式：整块消息区交给单个 WebView，标题栏与输入栏保持原生
                val uriHandler = LocalUriHandler.current
                WebChatSurface(
                    messages = displayMessages,
                    nodes = conversation.messageNodes,
                    assistant = chatAssistant,
                    settings = setting,
                    streaming = loadingJob != null,
                    modelName = currentChatModel?.displayName.orEmpty(),
                    searchEnabled = chatAssistant.enableWebSearch,
                    searchVisible = !setting.displaySetting.hideSearchPicker,
                    reasoningEnabled = chatAssistant.reasoningLevel.isEnabled,
                    // 与原生一致：模型不支持推理就不渲染该按钮（关掉它反而会让入口消失）
                    reasoningVisible = !setting.displaySetting.hideReasoningPicker &&
                        currentChatModel?.abilities?.contains(ModelAbility.REASONING) == true,
                    symbolVisible = setting.displaySetting.enableBracketButton &&
                        setting.displaySetting.bracketButtonSymbol.isNotBlank(),
                    bracketSymbol = setting.displaySetting.bracketButtonSymbol,
                    moreVisible = !setting.displaySetting.hideMoreButton,
                    onRequestMenu = { name ->
                        // 只接受原生真正拥有的菜单入口，其余忽略
                        if (name in setOf("model", "search", "reasoning", "more")) proxyMenu = name
                    },
                    variablesJson = buildVariablesJson(setting),
                    onSaveVariables = { json ->
                        val parsed = parseVariablesJson(json)
                        vm.updateSettings { it.copy(huadengSettings = it.huadengSettings.copy(webviewVariables = parsed)) }
                    },
                    // 卡片改消息：按脚下标定位到真实消息，走既有编辑/插入/删除路径，
                    onSetChatMessages = { payload ->
                        runCatching {
                            val list = conversation.currentMessages
                            val items = kotlinx.serialization.json.Json
                                .parseToJsonElement(payload) as? kotlinx.serialization.json.JsonArray
                                ?: return@runCatching
                            items.forEach { element ->
                                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@forEach
                                val index = (obj["message_id"]?.toString()?.trim('"'))?.toIntOrNull() ?: return@forEach
                                val text = obj["mes"]?.toString()?.trim('"') ?: return@forEach
                                val target = list.getOrNull(index)
                                if (target != null && target.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().joinToString("\n") { it.text } != text) {
                                    vm.handleMessageEdit(listOf(me.rerere.ai.ui.UIMessagePart.Text(text)), target.id)
                                }
                            }
                        }
                    },
                    onCreateChatMessages = { payload ->
                        runCatching {
                            val items = kotlinx.serialization.json.Json
                                .parseToJsonElement(payload) as? kotlinx.serialization.json.JsonArray
                                ?: return@runCatching
                            items.forEach { element ->
                                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@forEach
                                val text = obj["mes"]?.toString()?.trim('"') ?: return@forEach
                                val isUser = obj["is_user"]?.toString() == "true"
                                val name = obj["name"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                                vm.handleInsertMessage(
                                    role = if (isUser) MessageRole.USER else MessageRole.ASSISTANT,
                                    text = text,
                                    name = name,
                                )
                            }
                        }
                    },
                    onDeleteChatMessages = { payload ->
                        runCatching {
                            val list = conversation.currentMessages
                            val ids = kotlinx.serialization.json.Json
                                .parseToJsonElement(payload) as? kotlinx.serialization.json.JsonArray
                                ?: return@runCatching
                            ids.forEach { element ->
                                val index = element.toString().trim('"').toIntOrNull() ?: return@forEach
                                list.getOrNull(index)?.let { vm.deleteMessage(it) }
                            }
                        }
                    },
                    // 宏一律走后端引擎（与开场白同一入口），页面与卡片都不自己实现宏语义
                    onSubstituteMacros = { text ->
                        me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
                            .substituteGreetingMacros(context, setting, chatAssistant, text)
                    },
                    events = webEvents,
                    // 卡片里的本地路由请求走 App 的网络栈（代理/证书与 App 一致）
                    onHttpRequest = { payload, done ->
                        pageScope.launch {
                            val result = runCatching {
                                val obj = kotlinx.serialization.json.Json.parseToJsonElement(payload)
                                    as? kotlinx.serialization.json.JsonObject
                                val method = obj?.get("method")?.toString()?.trim('"') ?: "GET"
                                val url = obj?.get("url")?.toString()?.trim('"') ?: ""
                                val body = obj?.get("body")?.toString()?.trim('"').orEmpty()
                                val absolute = if (url.startsWith("http")) url else "http://127.0.0.1$url"
                                val connection = (java.net.URL(absolute).openConnection() as java.net.HttpURLConnection).apply {
                                    requestMethod = method.uppercase()
                                    connectTimeout = 15_000
                                    readTimeout = 20_000
                                    if (body.isNotEmpty() && method.uppercase() != "GET") {
                                        doOutput = true
                                        outputStream.use { it.write(body.toByteArray()) }
                                    }
                                }
                                val status = connection.responseCode
                                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                                buildJsonObject {
                                    put("status", JsonPrimitive(status))
                                    put("body", JsonPrimitive(text))
                                    put("contentType", JsonPrimitive(connection.contentType ?: "application/json"))
                                }.toString()
                            }.getOrElse { error ->
                                buildJsonObject {
                                    put("status", JsonPrimitive(0))
                                    put("body", JsonPrimitive(""))
                                    put("error", JsonPrimitive(error.message.orEmpty()))
                                }.toString()
                            }
                            done(result)
                        }
                    },
                    onSelectSwipe = { payload ->
                        runCatching {
                            val obj = kotlinx.serialization.json.Json.parseToJsonElement(payload)
                                as? kotlinx.serialization.json.JsonObject
                            val nodeId = obj?.get("nodeId")?.toString()?.trim('"')
                            val index = obj?.get("index")?.toString()?.trim('"')?.toIntOrNull()
                            val node = conversation.messageNodes.firstOrNull { it.id.toString() == nodeId }
                            if (node != null && index != null) vm.selectMessageNode(node.id, index)
                        }
                    },
                    onClipboard = { text ->
                        runCatching {
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("card", text))
                        }
                    },
                    // 页面内的操作按钮直接执行，不再弹原生菜单
                    onMessageAction = { payload ->
                        runCatching {
                            val obj = kotlinx.serialization.json.Json.parseToJsonElement(payload)
                                as? kotlinx.serialization.json.JsonObject
                            val action = obj?.get("action")?.toString()?.trim('"').orEmpty()
                            val id = obj?.get("id")?.toString()?.trim('"').orEmpty()
                            val message = conversation.currentMessages.firstOrNull { it.id.toString() == id }
                            if (message != null && action.isEmpty()) {
                                // 只给 id：打开原生菜单（复制、编辑、翻译、重试等）
                                webActionIndex = conversation.currentMessages.indexOf(message)
                            } else if (message != null) {
                                when (action) {
                                    "copy" -> {
                                        val text = message.parts
                                            .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                                            .joinToString("\n") { it.text }
                                        val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                        clip.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
                                        toaster.show(context.getString(R.string.copied))
                                    }

                                    "delete" -> vm.deleteMessage(message)
                                    "regenerate" -> vm.regenerateAtMessage(message, regenerateAssistantMsg = true)
                                    "translate" -> vm.translateMessage(message, java.util.Locale.getDefault())
                                    "favorite" -> {
                                        val node = conversation.messageNodes.find { node ->
                                            node.messages.any { it.id == message.id }
                                        }
                                        if (node != null) vm.toggleMessageFavorite(node)
                                    }

                                    "share" -> {
                                        val text = message.parts
                                            .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                                            .joinToString("\n") { it.text }
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, null))
                                    }

                                    "edit" -> {
                                        inputState.setContents(message.parts)
                                        inputState.editingMessage = message.id
                                    }
                                }
                            }
                        }
                    },
                    safeAreaTop = innerPadding.calculateTopPadding(),
                    safeAreaBottom = innerPadding.calculateBottomPadding(),
                    onMessagesJson = {
                        kotlinx.serialization.json.buildJsonArray {
                            conversation.currentMessages.forEachIndexed { index, message ->
                                add(
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("message_id", JsonPrimitive(index))
                                        put("name", JsonPrimitive(message.role.name))
                                        put("role", JsonPrimitive(if (message.role == MessageRole.USER) "user" else "assistant"))
                                        put("is_user", JsonPrimitive(message.role == MessageRole.USER))
                                        put("is_system", JsonPrimitive(message.role == MessageRole.SYSTEM))
                                        put(
                                            "mes",
                                            JsonPrimitive(
                                                message.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                                                    .joinToString("\n") { it.text },
                                            ),
                                        )
                                    },
                                )
                            }
                        }.toString()
                    },
                    onInsertSymbol = { symbol -> inputState.insertSymbol(symbol) },
                    onSlash = { command ->
                        val trimmed = command.trim()
                        val sendPrefix = "/send "
                        if (trimmed.startsWith(sendPrefix)) {
                            inputState.setMessageText(trimmed.removePrefix(sendPrefix))
                            vm.handleMessageSend(inputState.getContents())
                            inputState.clearInput()
                        } else {
                            toaster.show(
                                context.getString(R.string.huadeng_frontend_card_slash_unsupported, trimmed),
                                type = ToastType.Warning,
                            )
                        }
                    },
                    onSend = { text ->
                        inputState.setMessageText(text)
                        vm.handleMessageSend(inputState.getContents())
                        inputState.clearInput()
                    },
                    onStop = { vm.stopGeneration() },
                    onRegenerate = { conversation.currentMessages.lastOrNull()?.let { vm.regenerateAtMessage(it) } },
                    onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
                    // 铺满全屏：原生标题栏与输入栏由 Scaffold 覆盖在其上
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = { vm.regenerateAtMessage(it) },
                onImpersonate = { msg ->
                    inputState.setMessageText(msg.toText())
                },
                onEdit = { msg ->
                    inputState.setContents(msg.parts)
                    inputState.editingMessage = msg.id
                },
                onForkMessage = { msg ->
                    scope.launch {
                        val fork = vm.forkMessage(message = msg)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },

                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },

            )
            }
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                chatModel = currentChatModel,
                vm = vm,
                attachmentPickerActions = attachmentPickerActions,
                onStartVoiceMode = onStartVoiceMode,
                onDismiss = { showFilesSheet = false },
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    chatModel: Model?,
    vm: ChatVM,
    attachmentPickerActions: ChatAttachmentPickerActions,
    onStartVoiceMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showCompressDialog = false
        onDismiss()
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            chatModel = chatModel,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = vm::updateAssistant,
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = attachmentPickerActions.onTakePicture,
            onPickImage = attachmentPickerActions.onPickImage,
            onPickVideo = attachmentPickerActions.onPickVideo,
            onPickAudio = attachmentPickerActions.onPickAudio,
            onPickFile = attachmentPickerActions.onPickFile,
            onSketch = attachmentPickerActions.onSketch,
            onStartVoiceMode = if (
                setting.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
                voiceState.phase == VoicePhase.Off
            ) {
                {
                    dismissAll()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onStartVoiceMode()
                }
            } else null,
            // 酒馆模式快捷开关：就地写入华灯设置，保持面板不关闭，方便连点确认状态
            onToggleTavernMode = { enabled ->
                vm.updateSettings {
                    it.copy(
                        huadengSettings = it.huadengSettings.copy(enableTavernMode = enabled),
                    )
                }
            },
        )
    }
}

// 开场白选择弹窗 — 新对话有多条开场白时自动弹出
@Composable
private fun TopBar(
    settings: Settings,
    assistant: Assistant,
    chatModel: Model?,
    conversation: Conversation,
    bigScreen: Boolean,
    drawerState: DrawerState,
    previewMode: Boolean,
    onNewChat: () -> Unit,
    onClickMenu: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    onVoiceCall: () -> Unit = {},
    onVideoCall: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    // 顶部渐变遮罩：消息在标题栏下面滚动，遮罩从状态栏一直淡出到标题栏下沿之外
    val scrimColor = MaterialTheme.colorScheme.background
    TopAppBar(
        modifier = Modifier.drawWithCache {
            val scrimHeight = size.height + 24.dp.toPx()
            val brush = Brush.verticalGradient(
                0f to scrimColor,
                0.6f to scrimColor.copy(alpha = 0.85f),
                1f to scrimColor.copy(alpha = 0f),
                startY = 0f,
                endY = scrimHeight,
            )
            onDrawBehind { drawRect(brush, size = Size(size.width, scrimHeight)) }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val provider = chatModel?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chatModel != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${chatModel.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        navigationIcon = {
            if (!bigScreen) {
                IconButton(onClick = {
                    scope.launch { if (drawerState.isClosed) drawerState.open() else drawerState.close() }
                }) {
                    Icon(HugeIcons.Menu03, contentDescription = null)
                }
            }
        },
        actions = {
            // 语音 / 视频通话：仅在 TTS 与 ASR 均已配置时显示
            val ttsReady = settings.getSelectedTTSProvider() != null
            val asrReady = settings.getSelectedASRProvider() != null
            if (ttsReady && asrReady) {
                IconButton(onClick = onVideoCall) {
                    Icon(HugeIcons.Video01, contentDescription = "视频通话")
                }
                IconButton(onClick = onVoiceCall) {
                    Icon(HugeIcons.Telephone, contentDescription = "语音通话")
                }
            }
            if (!settings.displaySetting.hideTopBarListButton) {
                IconButton(onClick = onClickMenu) {
                    Icon(
                        if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet,
                        contentDescription = null,
                    )
                }
            }
            if (!settings.displaySetting.hideTopBarNewChatButton) {
                IconButton(onClick = onNewChat) {
                    Icon(HugeIcons.MessageAdd01, contentDescription = null)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
        ),
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = { titleState.dismiss() },
            title = { Text(stringResource(R.string.chat_page_edit_title)) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { titleState.confirm() }) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { titleState.dismiss() }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            },
        )
    }
}

@Composable
private fun GreetingPickerDialog(
    greetings: List<String>,
    currentGreeting: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CustomColors.listItemColors.containerColor,
        dragHandle = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_page_select_opening),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }) {
                    Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(greetings) { index, greeting ->
                val isSelected = greeting == currentGreeting
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSelected) { if (!isSelected) onSelect(greeting) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            CustomColors.listItemColors.containerColor,
                    ),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(24.dp),
                        )
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.chat_page_opening_current),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// WebView 变量域：从设置里取出 local/global 作用域
private fun buildVariablesJson(settings: Settings): String {
    val scopes = settings.huadengSettings.webviewVariables
    return kotlinx.serialization.json.buildJsonObject {
        scopes.forEach { (scope, vars) ->
            put(
                scope,
                kotlinx.serialization.json.buildJsonObject {
                    vars.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                },
            )
        }
    }.toString()
}

// 页面回传的变量域快照，转成可持久化的结构
private fun parseVariablesJson(json: String): Map<String, Map<String, String>> = runCatching {
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject
        ?: return emptyMap()
    obj.mapValues { (_, value) ->
        (value as? kotlinx.serialization.json.JsonObject)
            ?.mapValues { (_, v) -> v.toString().trim('"') }
            .orEmpty()
    }
}.getOrDefault(emptyMap())
