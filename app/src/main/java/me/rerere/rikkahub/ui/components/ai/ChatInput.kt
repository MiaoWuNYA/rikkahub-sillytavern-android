package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.asr.ASRStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionContext
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionItem
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionList
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionProvider
import me.rerere.rikkahub.ui.components.ai.SlashCommand
import me.rerere.rikkahub.ui.components.ai.collectSlashCommands
import me.rerere.rikkahub.ui.components.ai.matchSlashCommand
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.SoundEffectPlayer
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    settings: Settings,
    hazeState: HazeState,
    enableSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    completionProviders: List<ChatCompletionProvider> = emptyList(),
    onUpdateChatModel: (Model) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onMoreClick: () -> Unit,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onUpdateConversation: (Conversation) -> Unit = {},
    onCompressContext: (String, String, String) -> Unit = { _, _, _ -> },
    onLongSendClick: () -> Unit,
    onSlashDuplicate: (() -> Unit)? = null,
    onSlashInsert: ((MessageRole, String) -> Unit)? = null,
    onSlashPersona: ((String) -> Unit)? = null,
    onSlashTrigger: (() -> Unit)? = null,
    onSlashSysgen: ((String) -> Unit)? = null,
    onSlashInject: ((String, InjectionPosition, Int, MessageRole) -> Unit)? = null,
    onSlashVar: ((SlashVarOp, String, String) -> String?)? = null,
) {
    val toaster = LocalToaster.current
    val assistant = settings.getCurrentAssistant()
    val skillManager: SkillManager = koinInject()
    val slashCommands = remember(assistant.enabledSkills) {
        val allSkills = skillManager.listSkills()
        val enabledSkills = allSkills.filter { it.name in assistant.enabledSkills }
        collectSlashCommands(enabledSkills)
    }
    val hazeTintColor = MaterialTheme.colorScheme.surfaceContainerLow
    val inputHazeStyle = HazeMaterials.thin(containerColor = hazeTintColor)

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 键盘弹出时让底部两角变直角，贴合 IME
    val imeVisible = WindowInsets.isImeVisible
    val containerShape = if (imeVisible) {
        MaterialTheme.shapes.largeIncreased.copy(
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp),
        )
    } else {
        MaterialTheme.shapes.largeIncreased
    }

    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading) {
            onCancelClick()
            return
        }
        val text = state.textContent.text.toString().trimStart()
        if (text.startsWith("/")) {
            val cmd = matchSlashCommand(text, slashCommands)
            if (cmd != null) {
                val args = text.substringAfter(" ", "").trim()
                if (cmd.builtinKind != null) {
                    handleBuiltinSlash(
                        cmd = cmd,
                        args = args,
                        onShowHelp = { showHelpDialog = true },
                        state = state,
                        toaster = toaster,
                        settings = settings,
                        assistant = assistant,
                        onUpdateAssistant = onUpdateAssistant,
                        onSlashDuplicate = onSlashDuplicate,
                        onSlashInsert = onSlashInsert,
                        onSlashPersona = onSlashPersona,
                        onSlashTrigger = onSlashTrigger,
                        onSlashSysgen = onSlashSysgen,
                        onSlashInject = onSlashInject,
                        onSlashVar = onSlashVar,
                    )
                } else {
                    // 技能命令：与弹窗点击一致，把替换后的内容填回输入框
                    val argsList = args.split(" ", limit = 10)
                    var content = cmd.content
                        .replace("\$ARGUMENTS", args)
                        .replace("\$ARGS", args)
                    for (i in 0..9) {
                        val value = argsList.getOrElse(i) { "" }
                        content = content.replace("\$ARGS.$i", value)
                    }
                    state.setMessageText(content)
                }
                return
            }
        }
        onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading) onCancelClick() else onLongSendClick()
    }

    val asr = LocalASRState.current
    val asrState by asr.state.collectAsState()
    val hapticFeedback = LocalHapticFeedback.current
    val soundEffectPlayer: SoundEffectPlayer = koinInject()
    LaunchedEffect(Unit) {
        soundEffectPlayer.preload(R.raw.asr_start, R.raw.asr_stop)
    }
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)
    var asrBaseText by remember { mutableStateOf("") }
    LaunchedEffect(asrState.status) {
        when (asrState.status) {
            ASRStatus.Listening -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                soundEffectPlayer.play(R.raw.asr_start)
            }

            ASRStatus.Stopping -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                soundEffectPlayer.play(R.raw.asr_stop)
            }

            else -> {}
        }
    }
    LaunchedEffect(asrState.errorMessage) {
        asrState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
        }
    }

    Surface(
        color = Color.Transparent,
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(bottom = if (imeVisible) 0.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(containerShape)
                    .then(
                        if (settings.displaySetting.enableBlurEffect) Modifier.hazeEffect(
                            state = hazeState
                        ) {
                            blurEffect {
                                style = inputHazeStyle
                            }
                        }
                        else Modifier
                    ),
                shape = containerShape,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                color = if (settings.displaySetting.enableBlurEffect) Color.Transparent else hazeTintColor,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (state.messageContent.isNotEmpty()) {
                        MediaFileInputRow(state = state)
                    }

                    TextInputRow(
                        state = state,
                        completionProviders = completionProviders,
                        onSendMessage = { sendMessage() },
                        toaster = toaster,
                        onUpdateAssistant = onUpdateAssistant,
                        onSlashDuplicate = onSlashDuplicate,
                        onSlashInsert = onSlashInsert,
                        onSlashPersona = onSlashPersona,
                        onSlashTrigger = onSlashTrigger,
                        onSlashSysgen = onSlashSysgen,
                        onSlashInject = onSlashInject,
                        onSlashVar = onSlashVar,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Model Picker
                            ModelSelector(
                                modelId = assistant.chatModelId ?: settings.chatModelId,
                                providers = settings.providers,
                                onSelect = {
                                    onUpdateChatModel(it)
                                },
                                type = ModelType.CHAT,
                                onlyIcon = true,
                                modifier = Modifier,
                            )

                            // Search
                            val enableSearchMsg = stringResource(R.string.web_search_enabled)
                            val disableSearchMsg = stringResource(R.string.web_search_disabled)
                            val chatModel = settings.getCurrentChatModel()
                            SearchPickerButton(
                                enableSearch = enableSearch,
                                settings = settings,
                                onToggleSearch = { enabled ->
                                    onToggleSearch(enabled)
                                    toaster.show(
                                        message = if (enabled) enableSearchMsg else disableSearchMsg,
                                        duration = 1.seconds,
                                        type = if (enabled) {
                                            ToastType.Success
                                        } else {
                                            ToastType.Normal
                                        }
                                    )
                                },
                                onUpdateSearchService = onUpdateSearchService,
                                model = chatModel,
                            )

                            // Reasoning
                            val model = settings.getCurrentChatModel()
                            if (model?.abilities?.contains(ModelAbility.REASONING) == true) {
                                ReasoningButton(
                                    reasoningLevel = assistant.reasoningLevel,
                                    onUpdateReasoningLevel = {
                                        onUpdateAssistant(assistant.copy(reasoningLevel = it))
                                    },
                                    onlyIcon = true,
                                )
                            }

                        }

                        ActionIconButton(
                            onClick = onMoreClick
                        ) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = stringResource(R.string.more_options)
                            )
                        }

                        if (asrState.isAvailable || asrState.isRecording) {
                            AsrButton(
                                state = asrState,
                                onClick = {
                                    when (asrState.status) {
                                        ASRStatus.Listening -> asr.stop()
                                        ASRStatus.Idle, ASRStatus.Error -> {
                                            if (!asrPermission.allRequiredPermissionsGranted) {
                                                asrPermission.requestPermissions()
                                            } else {
                                                asrBaseText = state.textContent.text.toString()
                                                asr.start { transcript ->
                                                    val spacer =
                                                        if (asrBaseText.isBlank() || transcript.isBlank()) "" else " "
                                                    state.setMessageText(asrBaseText + spacer + transcript)
                                                }
                                            }
                                        }

                                        ASRStatus.Connecting, ASRStatus.Stopping -> {}
                                    }
                                }
                            )
                        }

                        AnimatedVisibility(
                            visible = !asrState.isRecording,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut(),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(30.dp)
                                    .testTag("chat_send_button")
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        enabled = loading || !state.isEmpty(),
                                        onClick = {
                                            sendMessage()
                                        }, onLongClick = {
                                            sendMessageWithoutAnswer()
                                        }
                                    )
                            ) {
                                val containerColor = when {
                                    loading -> MaterialTheme.colorScheme.errorContainer
                                    state.isEmpty() -> MaterialTheme.colorScheme.surfaceContainerHigh
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                val contentColor = when {
                                    loading -> MaterialTheme.colorScheme.onErrorContainer
                                    state.isEmpty() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    else -> MaterialTheme.colorScheme.onPrimary
                                }
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = CircleShape,
                                    color = containerColor,
                                    content = {})
                                if (loading) {
                                    KeepScreenOn()
                                    Icon(
                                        imageVector = HugeIcons.Cancel01,
                                        contentDescription = stringResource(R.string.stop),
                                        tint = contentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = HugeIcons.ArrowUp02,
                                        contentDescription = stringResource(R.string.send),
                                        tint = contentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun ActionIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        tonalElevation = 0.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun TextInputRow(
    state: ChatInputState,
    completionProviders: List<ChatCompletionProvider>,
    onSendMessage: () -> Unit,
    toaster: com.dokar.sonner.ToasterState,
    onUpdateAssistant: (Assistant) -> Unit,
    onSlashDuplicate: (() -> Unit)?,
    onSlashInsert: ((MessageRole, String) -> Unit)?,
    onSlashPersona: ((String) -> Unit)?,
    onSlashTrigger: (() -> Unit)?,
    onSlashSysgen: ((String) -> Unit)?,
    onSlashInject: ((String, InjectionPosition, Int, MessageRole) -> Unit)?,
    onSlashVar: ((SlashVarOp, String, String) -> String?)?,
) {
    val settings = LocalSettings.current
    val filesManager: FilesManager = koinInject()
    val skillManager: SkillManager = koinInject()
    val assistant = settings.getCurrentAssistant()
    val quickMessages = remember(settings.quickMessages, assistant.quickMessageIds) {
        settings.getQuickMessagesOfAssistant(assistant)
    }
    // 斜杠命令
    val slashCommands = remember(assistant.enabledSkills) {
        val allSkills = skillManager.listSkills()
        val enabledSkills = allSkills.filter { it.name in assistant.enabledSkills }
        collectSlashCommands(enabledSkills)
    }
    var showSlashPopup by remember { mutableStateOf(false) }
    var slashFilter by remember { mutableStateOf("") }
    var slashArgs by remember { mutableStateOf("") }
    var showHelpDialog by remember { mutableStateOf(false) }

    // 监听文本变化，检测斜杠命令
    val currentText = state.textContent.text
    LaunchedEffect(currentText) {
        if (currentText.startsWith("/") && currentText.length <= 30) {
            val parts = currentText.split(" ", limit = 2)
            val cmdName = parts[0].removePrefix("/").lowercase()
            slashArgs = parts.getOrElse(1) { "" }
            if (slashCommands.any { it.name.lowercase().startsWith(cmdName) }) {
                slashFilter = cmdName
                showSlashPopup = true
            } else {
                showSlashPopup = false
            }
        } else {
            showSlashPopup = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { state.clearInput() }
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
        var completionList by remember { mutableStateOf<ChatCompletionList?>(null) }
        val receiveContentListener = remember(
            settings.displaySetting.pasteLongTextAsFile, settings.displaySetting.pasteLongTextThreshold
        ) {
            ReceiveContentListener { transferableContent ->
                when {
                    transferableContent.hasMediaType(MediaType.Image) -> {
                        transferableContent.consume { item ->
                            val uri = item.uri
                            if (uri != null) {
                                state.addImages(
                                    filesManager.createChatFilesByContents(
                                        listOf(uri)
                                    )
                                )
                            }
                            uri != null
                        }
                    }

                    settings.displaySetting.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                        transferableContent.consume { item ->
                            val text = item.text?.toString()
                            if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                                val document = filesManager.createChatTextFile(text)
                                state.addFiles(listOf(document))
                                true
                            } else {
                                false
                            }
                        }
                    }

                    else -> transferableContent
                }
            }
        }

        LaunchedEffect(completionProviders, isFocused) {
            if (!isFocused || completionProviders.isEmpty()) {
                completionList = null
                return@LaunchedEffect
            }

            snapshotFlow {
                ChatCompletionContext(
                    text = state.textContent.text.toString(),
                    selection = state.textContent.selection,
                )
            }.collectLatest { context ->
                val lists = completionProviders.mapNotNull { provider ->
                    try {
                        provider.complete(context)
                            ?.takeIf { it.items.isNotEmpty() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val primary = lists.firstOrNull()
                completionList = primary?.let { list ->
                    val mergedItems = lists
                        .filter { it.replacementRange == list.replacementRange }
                        .flatMap { it.items }
                        .distinctBy { it.label to it.insertText }
                        .sortedWith(
                            compareByDescending<ChatCompletionItem> { it.sortScore }
                                .thenBy { it.label.length }
                                .thenBy { it.label.lowercase() }
                        )
                        .take(8)
                    list.copy(items = mergedItems)
                }
            }
        }

        completionList?.takeIf { it.items.isNotEmpty() }?.let { list ->
            CompletionPopup(
                completionList = list,
                onItemClick = { item ->
                    state.applyCompletion(list.replacementRange, item)
                    completionList = null
                },
            )
        }

        // /help 命令列表对话框
        if (showHelpDialog) {
            BasicAlertDialog(
                onDismissRequest = { showHelpDialog = false },
                content = {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "可用命令",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            slashCommands.forEach { cmd ->
                                Column {
                                    Text(
                                        text = buildString {
                                            append("/${cmd.name}")
                                            if (cmd.argumentHint.isNotBlank()) append(" ${cmd.argumentHint}")
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = cmd.description,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }

        // 斜杠命令弹窗
        if (showSlashPopup && slashCommands.isNotEmpty()) {
            val filtered = slashCommands.filter {
                it.name.contains(slashFilter, ignoreCase = true)
            }
            if (filtered.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        filtered.take(5).forEach { cmd ->
                            Surface(
                                onClick = {
                                    if (cmd.builtinKind != null) {
                                        if (cmd.argumentHint.isBlank()) {
                                            // 无参数命令（argumentHint 为空）：点击直接执行（如 /trigger /help），不污染聊天
                                            handleBuiltinSlash(
                                                cmd = cmd,
                                                args = slashArgs,
                                                state = state,
                                                toaster = toaster,
                                                settings = settings,
                                                assistant = assistant,
                                                onUpdateAssistant = onUpdateAssistant,
                                                onSlashDuplicate = onSlashDuplicate,
                                                onSlashInsert = onSlashInsert,
                                                onSlashPersona = onSlashPersona,
                                                onSlashTrigger = onSlashTrigger,
                                                onSlashSysgen = onSlashSysgen,
                                                onSlashInject = onSlashInject,
                                                onSlashVar = onSlashVar,
                                                onShowHelp = { showHelpDialog = true },
                                            )
                                        } else {
                                            // 需要参数的命令：填入输入框，补参数后按发送执行（官方 AutoComplete 行为）
                                            state.setMessageText("/${cmd.name} ${slashArgs}".trimEnd())
                                        }
                                    } else {
                                        val argsList = slashArgs.split(" ", limit = 10)
                                        var text = cmd.content
                                            .replace("\$ARGUMENTS", slashArgs)
                                            .replace("\$ARGS", slashArgs)
                                        // $ARGS.0, $ARGS.1 ... 按位置替换
                                        for (i in 0..9) {
                                            val value = argsList.getOrElse(i) { "" }
                                            text = text.replace("\$ARGS.$i", value)
                                        }
                                        state.setMessageText(text)
                                    }
                                    showSlashPopup = false
                                },
                                color = Color.Transparent,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp, 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "/${cmd.name}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        if (cmd.argumentHint.isNotBlank()) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = cmd.argumentHint,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = cmd.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (cmd.disableModelInvocation) {
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                                            ) {
                                                Text(
                                                    text = "⚡脚本",
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        TextField(
            state = state.textContent,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_input")
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                },
            shape = MaterialTheme.shapes.largeIncreased,
            placeholder = {
                Text(stringResource(R.string.chat_input_placeholder))
            },
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            keyboardOptions = KeyboardOptions(
                imeAction = if (settings.displaySetting.sendOnEnter) ImeAction.Send else ImeAction.Default
            ),
            onKeyboardAction = {
                if (settings.displaySetting.sendOnEnter && !state.isEmpty()) {
                    onSendMessage()
                }
            },
            colors = TextFieldDefaults.colors().copy(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            trailingIcon = {
                if (isFocused) {
                    IconButton(
                        onClick = {
                            isFullScreen = !isFullScreen
                        }) {
                        Icon(HugeIcons.FullScreen, null)
                    }
                }
            },
            leadingIcon = if (quickMessages.isNotEmpty()) {
                {
                    QuickMessageButton(quickMessages = quickMessages, state = state)
                }
            } else null,
        )
        if (isFullScreen) {
            FullScreenEditor(state = state) {
                isFullScreen = false
            }
        }
    }
}

@Composable
private fun CompletionPopup(
    completionList: ChatCompletionList,
    onItemClick: (ChatCompletionItem) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            items(
                items = completionList.items,
                key = { item -> "${item.label}:${item.insertText}" },
            ) { item ->
                Surface(
                    onClick = { onItemClick(item) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item.icon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.detail?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ChatInputState.applyCompletion(
    replacementRange: TextRange,
    item: ChatCompletionItem,
) {
    val textLength = textContent.text.length
    val start = replacementRange.min.coerceIn(0, textLength)
    val end = replacementRange.max.coerceIn(start, textLength)
    textContent.edit {
        replace(start, end, item.insertText)
        selection = TextRange(start + item.insertText.length)
    }
}

/**
 * 执行内置斜杠命令（官方常用命令的实用子集）
 */
private fun handleBuiltinSlash(
    cmd: SlashCommand,
    args: String,
    state: ChatInputState,
    toaster: com.dokar.sonner.ToasterState,
    settings: Settings,
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    onSlashDuplicate: (() -> Unit)?,
    onSlashInsert: ((MessageRole, String) -> Unit)?,
    onSlashPersona: ((String) -> Unit)?,
    onSlashTrigger: (() -> Unit)?,
    onSlashSysgen: ((String) -> Unit)?,
    onSlashInject: ((String, InjectionPosition, Int, MessageRole) -> Unit)?,
    onSlashVar: ((SlashVarOp, String, String) -> String?)?,
    onShowHelp: () -> Unit = {},
) {
    when (cmd.builtinKind) {
        BuiltinSlashKind.HELP -> {
            // 在 UI 里列出全部可用命令，不再弹 toast
            onShowHelp()
        }

        BuiltinSlashKind.SYS -> {
            val text = args.trim()
            if (text.isBlank()) {
                toaster.show("用法: /sys 系统消息内容")
            } else if (onSlashInsert == null) {
                toaster.show("当前页面不支持该命令")
            } else {
                onSlashInsert(MessageRole.SYSTEM, text)
                state.clearInput()
            }
        }

        BuiltinSlashKind.SENDAS -> {
            val text = args.trim()
            if (text.isBlank()) {
                toaster.show("用法: /sendas 文本（以当前助手身份发言）")
            } else if (onSlashInsert == null) {
                toaster.show("当前页面不支持该命令")
            } else {
                onSlashInsert(MessageRole.ASSISTANT, text)
                state.clearInput()
            }
        }

        BuiltinSlashKind.PERSONA -> {
            if (onSlashPersona != null) {
                onSlashPersona(args.trim())
                state.clearInput()
            } else {
                toaster.show("当前页面不支持该命令")
            }
        }

        BuiltinSlashKind.TRIGGER -> {
            if (onSlashTrigger != null) {
                onSlashTrigger()
                state.clearInput()
            } else {
                toaster.show("当前页面不支持该命令")
            }
        }

        BuiltinSlashKind.SYSGEN -> {
            val text = args.trim()
            if (text.isBlank()) {
                toaster.show("用法: /sysgen 提示词，如 描写雨夜街道")
            } else if (onSlashSysgen == null) {
                toaster.show("当前页面不支持该命令")
            } else {
                onSlashSysgen(text)
                state.clearInput()
            }
        }

        BuiltinSlashKind.INJECT -> {
            val tokens = args.trim().split(" ")
            if (tokens.any { it.equals("position=none", ignoreCase = true) }) {
                toaster.show("本地暂不支持隐藏注入(none)，请用 position=before / position=chat 或默认(主提示后)")
                return
            }
            val position = when {
                tokens.any { it.equals("position=before", ignoreCase = true) } -> InjectionPosition.BEFORE_SYSTEM_PROMPT
                tokens.any { it.equals("position=chat", ignoreCase = true) } -> InjectionPosition.AT_DEPTH
                else -> InjectionPosition.AFTER_SYSTEM_PROMPT
            }
            val depth = tokens.firstOrNull { it.startsWith("depth=", ignoreCase = true) }
                ?.substringAfter("=")?.toIntOrNull()?.coerceIn(1, 100) ?: 4
            val role = when {
                tokens.any { it.equals("role=user", ignoreCase = true) } -> MessageRole.USER
                tokens.any { it.equals("role=assistant", ignoreCase = true) } -> MessageRole.ASSISTANT
                else -> MessageRole.SYSTEM
            }
            val content = tokens
                .filterNot {
                    it.startsWith("position=", ignoreCase = true) ||
                        it.startsWith("depth=", ignoreCase = true) ||
                        it.startsWith("role=", ignoreCase = true)
                }
                .joinToString(" ")
                .trim()
            if (content.isBlank()) {
                toaster.show("用法: /inject 注入内容 [position=before|chat] [depth=数字] [role=user|assistant]")
            } else if (onSlashInject == null) {
                toaster.show("当前页面不支持该命令")
            } else {
                onSlashInject(content, position, depth, role)
                state.clearInput()
            }
        }

        BuiltinSlashKind.VAR -> {
            val op = when (cmd.name) {
                "setvar" -> SlashVarOp.SET
                "getvar" -> SlashVarOp.GET
                "addvar" -> SlashVarOp.ADD
                "incvar" -> SlashVarOp.INC
                "decvar" -> SlashVarOp.DEC
                "flushvar" -> SlashVarOp.FLUSH
                "listvar" -> SlashVarOp.LIST
                else -> null
            } ?: return

            if (op == SlashVarOp.LIST) {
                val result = onSlashVar?.invoke(op, "", "")
                if (result == null) {
                    toaster.show("当前页面不支持该命令")
                } else {
                    toaster.show(result)
                }
                state.clearInput()
                return
            }

            val trimmed = args.trim()
            val keyToken = trimmed.substringBefore(" ").trim()
            val key = keyToken.removePrefix("key=").ifBlank { keyToken }
            val value = trimmed.substringAfter(" ", "").trim()
            val needsValue = op == SlashVarOp.SET || op == SlashVarOp.ADD
            if (key.isBlank() || (needsValue && value.isBlank())) {
                toaster.show(
                    if (needsValue) "用法: /${cmd.name} key 值（也支持 key=名称 写法）" else "用法: /${cmd.name} key"
                )
                return
            }

            val result = onSlashVar?.invoke(op, key, value)
            if (result == null) {
                toaster.show("当前页面不支持该命令")
            } else {
                toaster.show(result)
            }
            state.clearInput()
        }

        BuiltinSlashKind.INFO -> {
            when (cmd.name) {
                "char-get" -> {
                    val tav = assistant.tavernData
                    if (tav == null) {
                        toaster.show("当前助手没有角色卡")
                    } else {
                        val summary = buildString {
                            append("角色卡: ${tav.name.ifBlank { assistant.name }}")
                            if (tav.description.isNotBlank()) append("\n描述: ${tav.description.take(80)}")
                            if (tav.personality.isNotBlank()) append("\n性格: ${tav.personality.take(80)}")
                            if (tav.systemPrompt.isNotBlank()) append("\n系统提示词: ${tav.systemPrompt.take(80)}")
                            if (tav.embeddedBook != null) append("\n内嵌世界书: ${tav.embeddedBook.entries.size} 条")
                        }
                        toaster.show(summary)
                    }
                }
            }
        }

        BuiltinSlashKind.RENAME -> {
            val newName = args.trim()
            if (newName.isBlank()) {
                toaster.show("用法: /rename-char 新名字")
            } else {
                onUpdateAssistant(
                    assistant.copy(
                        name = newName,
                        tavernData = assistant.tavernData?.copy(name = newName),
                    )
                )
                toaster.show("已重命名为: $newName")
            }
        }

        BuiltinSlashKind.UPDATE_CHAR -> {
            val eqIndex = args.indexOf('=')
            if (eqIndex <= 0) {
                toaster.show("用法: /char-update 字段=值")
                return
            }
            val field = args.substring(0, eqIndex).trim()
            val value = args.substring(eqIndex + 1).trim()
            val tav = assistant.tavernData
            if (tav == null) {
                toaster.show("当前助手没有角色卡")
                return
            }
            val updated = when (field.lowercase()) {
                "name" -> {
                    onUpdateAssistant(assistant.copy(name = value, tavernData = tav.copy(name = value)))
                    "名称"
                }
                "description" -> { onUpdateAssistant(assistant.copy(tavernData = tav.copy(description = value))); "描述" }
                "personality" -> { onUpdateAssistant(assistant.copy(tavernData = tav.copy(personality = value))); "性格" }
                "scenario" -> { onUpdateAssistant(assistant.copy(tavernData = tav.copy(scenario = value))); "场景" }
                "system_prompt", "systemprompt" -> { onUpdateAssistant(assistant.copy(tavernData = tav.copy(systemPrompt = value))); "系统提示词" }
                "first_mes", "firstmes" -> { onUpdateAssistant(assistant.copy(tavernData = tav.copy(firstMessage = value))); "开场白" }
                "mes_example", "mesexample" -> { onUpdateAssistant(assistant.copy(tavernData = tav.copy(mesExample = value))); "示例对话" }
                "post_history_instructions", "phi" -> { onUpdateAssistant(assistant.copy(tavernData = tav.copy(postHistoryInstructions = value))); "历史后指令" }
                "creator_notes", "creatornotes" -> { onUpdateAssistant(assistant.copy(tavernData = tav.copy(creatorNotes = value))); "作者备注" }
                "character_version", "characterversion" -> { onUpdateAssistant(assistant.copy(tavernData = tav.copy(characterVersion = value))); "角色版本" }
                else -> null
            }
            if (updated == null) {
                toaster.show("未知字段: $field（可用 name/description/personality/scenario/system_prompt/first_mes/mes_example/phi/creator_notes）")
            } else {
                toaster.show("已更新$updated")
            }
        }

        BuiltinSlashKind.DUPLICATE -> {
            if (onSlashDuplicate != null) {
                onSlashDuplicate()
            } else {
                toaster.show("当前页面不支持该命令")
            }
        }

        null -> Unit
    }
}

@Composable
private fun QuickMessageButton(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            expanded = !expanded
        }) {
        Icon(HugeIcons.Zap, null)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 200.dp, max = 360.dp)
        ) {
            quickMessages.forEach { quickMessage ->
                Surface(
                    onClick = {
                        state.appendText(quickMessage.content)
                        expanded = false
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = quickMessage.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenEditor(
    state: ChatInputState, onDone: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                onDone()
                            }) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextField(
                        state = state.textContent,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = {
                            Text(stringResource(R.string.chat_input_placeholder))
                        },
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}
