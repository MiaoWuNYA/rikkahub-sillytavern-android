package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.allRegexesOf
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.TRANSPARENT_BUBBLE as TRANSPARENT_BUBBLE_SENTINEL
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.CardHostContext
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.components.message.extractVideoCallArchiveSessionId
import me.rerere.rikkahub.ui.components.message.VideoCallArchiveCard
import me.rerere.rikkahub.ui.context.LocalWorkspaceId
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.LocalMaterialMode
import me.rerere.rikkahub.ui.theme.isGlass
import me.rerere.rikkahub.ui.theme.isTranslucent
import me.rerere.rikkahub.ui.theme.liquidGlassFill
import me.rerere.rikkahub.ui.theme.liquidGlassHighlight
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.toMessageTimeString
import me.rerere.rikkahub.utils.urlDecode
import coil3.compose.AsyncImage
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ui.charts.ChartCard
import me.rerere.ui.charts.ChartSpec
import me.rerere.ui.webview.WebViewContentCache
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    // 消息深度：0 = 最新一条，向前递增。正则脚本（尤其前端卡的 HTML 注入）
    // 用 minDepth/maxDepth 限定生效范围，必须传真实值，否则 maxDepth 失效、
    // 历史消息会被重复注入 HTML。
    messageDepth: Int = 0,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onImpersonate: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    onRequestMultiSelect: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    /**
     * 前端角色卡的宿主生成入口（卡内 `window.generate`）。
     * 为 null 时卡会显示「宿主未注入 generate 接口，无法生成」。
     */
    cardHost: CardHostContext? = null,
    /** 卡把开局正文写回消息的入口。 */
    cardMessagesJson: String = "[]",
) {
    val message = node.messages[node.selectIndex]
    val settings = LocalSettings.current.displaySetting
    val chatFontFamily = LocalChatFontFamily.current ?: rememberChatFontFamily(settings)
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = chatFontFamily
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f)
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        CompositionLocalProvider(
            LocalTextStyle provides textStyle,
            LocalWorkspaceId provides assistant?.workspaceId?.toString(),
        ) {
            MessagePartsBlock(
                assistant = assistant,
                messageDepth = messageDepth,
                role = message.role,
                parts = message.parts,
                annotations = message.annotations,
                loading = loading,
                isStreamingMessage = loading && message.finishedAt == null,
                model = model,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onUserMessageClick = if (message.role == MessageRole.USER) onEdit else null,
                cardHost = cardHost,
                cardMessagesJson = cardMessagesJson,
            )

            message.translation?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }

        val showActions = if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }

        val motionScheme = MaterialTheme.motionScheme
        AnimatedVisibility(
            visible = showActions,
            enter = slideInVertically(motionScheme.defaultSpatialSpec()) { it / 2 } +
                fadeIn(motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(motionScheme.fastSpatialSpec()) { it / 2 } +
                fadeOut(motionScheme.fastEffectsSpec())
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                ChatMessageActionButtons(
                    message = message,
                    onRegenerate = onRegenerate,
                    onImpersonate = onImpersonate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation
                )
            }
        }

        EditedFilesList(
            parts = message.parts,
            assistant = assistant,
        )

        ProvideTextStyle(textStyle) {
            ChatMessageNerdLine(message = message)
        }

    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onMultiSelect = onRequestMultiSelect,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    val contentId = WebViewContentCache.store(context.cacheDir, htmlContent)
                    navController.navigate(Screen.WebView(contentId = contentId))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    messageDepth: Int,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    // 仅当这条消息本身还在生成时为 true，历史消息恒为 false
    isStreamingMessage: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
    cardHost: CardHostContext? = null,
    cardMessagesJson: String = "[]",
) {
    val context = LocalContext.current
    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(parts)

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }

    // 正则可视化替换按 (原文, 作用域) 缓存，稳定消息重组直接复用；流式期间见 applyVisualRegexes
    val effectiveRegexes = remember(assistant?.id, assistant?.regexes, settings.globalRegexes, messageDepth) {
        settings.allRegexesOf(assistant)
    }
    val regexVisualCache = remember(effectiveRegexes, messageDepth) {
        mutableMapOf<Pair<String, AssistantAffectScope>, String>()
    }
    fun applyVisualRegexes(text: String, scope: AssistantAffectScope): String {
        if (effectiveRegexes.isEmpty()) return text
        // 流式期间不跑正则：整条消息每个 chunk 重算会卡，生成结束后再一次性生效
        if (loading) return text
        val key = text to scope
        return regexVisualCache.getOrPut(key) {
            text.replaceRegexes(
                regexes = effectiveRegexes,
                scope = scope,
                visual = true,
                depth = messageDepth,
            )
        }
    }

    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = block.steps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                        cardColors = CardDefaults.cardColors(
                            containerColor = customBubbleColor(
                                custom = settings.displaySetting.thinkingBubbleColor,
                                fallback = MaterialTheme.colorScheme.surfaceContainerHigh,
                                alpha = settings.displaySetting.bubbleOpacity,
                            ),
                        ),
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        assistant = assistant,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                    )
                                }
                            }

                            is ThinkingStep.ServerToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageServerToolStep(tool = step.tool)
                                }
                            }
                        }
                    }
                }
            }

            is MessagePartBlock.ChartBlock -> key(block.index) {
                val spec = remember(block.tool.input) { ChartSpec.fromJson(block.tool.inputAsJson()) }
                spec?.let { ChartCard(spec = it) }
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        val textContent = @Composable {
                            if (role == MessageRole.USER) {
                                val materialMode = LocalMaterialMode.current
                                val isGlassBubble = materialMode.isGlass
                                val userBubbleColor = customBubbleColor(
                                    custom = settings.displaySetting.userBubbleColor,
                                    fallback = MaterialTheme.colorScheme.primaryContainer,
                                    alpha = settings.displaySetting.bubbleOpacity,
                                )
                                Surface(
                                    modifier = Modifier
                                        .padding(start = 24.dp)
                                        .animateContentSize()
                                        .themeBubbleBorder(
                                            color = settings.displaySetting.bubbleBorderColor,
                                            width = settings.displaySetting.bubbleBorderWidth,
                                            shadowColor = settings.displaySetting.bubbleShadowColor,
                                            shadowWidth = settings.displaySetting.bubbleShadowWidth,
                                            cornerRadius = settings.displaySetting.bubbleCornerRadius,
                                        ),
                                    shape = RoundedCornerShape(settings.displaySetting.bubbleCornerRadius.dp),
                                    color = when {
                                        isGlassBubble -> Color.Transparent
                                        materialMode.isTranslucent -> userBubbleColor.copy(alpha = userBubbleColor.alpha * 0.72f)
                                        else -> userBubbleColor
                                    },
                                    onClick = { onUserMessageClick?.invoke() },
                                ) {
                                    Box {
                                        BubbleBackgroundImage(
                                            path = settings.displaySetting.userBubbleImagePath,
                                            overlayColor = if (settings.displaySetting.bubbleImageOverlayEnabled) {
                                                customBubbleColor(
                                                    custom = settings.displaySetting.userBubbleColor,
                                                    fallback = MaterialTheme.colorScheme.primaryContainer,
                                                    alpha = 0.55f,
                                                )
                                            } else null,
                                            contentScale = bubbleContentScale(
                                                settings.displaySetting.bubbleBackgroundSize
                                            ),
                                            modifier = Modifier.matchParentSize(),
                                        )
                                        if (isGlassBubble) {
                                            Box(
                                                modifier = Modifier
                                                    .matchParentSize()
                                                    .liquidGlassFill(
                                                        tint = customBubbleColor(
                                                            custom = settings.displaySetting.userBubbleColor,
                                                            fallback = MaterialTheme.colorScheme.primaryContainer,
                                                            alpha = 1f,
                                                        )
                                                    )
                                                    .liquidGlassHighlight()
                                            )
                                        } else if (materialMode.isTranslucent) {
                                            Box(
                                                modifier = Modifier
                                                    .matchParentSize()
                                                    .liquidGlassHighlight(intensity = 0.6f)
                                            )
                                        }
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            MarkdownBlock(
                                                content = applyVisualRegexes(part.text, AssistantAffectScope.USER),
                                                onClickCitation = handleClickCitation,
                                                cardHost = cardHost,
                                                cardMessagesJson = cardMessagesJson,
                                                // 生成中的半截 HTML 不交给 WebView，
                                                // 否则会渲染出残缺网页、观感像卡死
                                                                                                                isStreaming = isStreamingMessage,
                                            )
                                        }
                                    }
                                }
                            } else {
                                if (settings.displaySetting.showAssistantBubble) {
                                    val materialMode = LocalMaterialMode.current
                                    val isGlassBubble = materialMode.isGlass
                                    val assistantBubbleColor = customBubbleColor(
                                        custom = settings.displaySetting.assistantBubbleColor,
                                        fallback = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        alpha = settings.displaySetting.bubbleOpacity,
                                    )
                                    Surface(
                                        modifier = Modifier
                                            .animateContentSize()
                                            .themeBubbleBorder(
                                                color = settings.displaySetting.bubbleBorderColor,
                                                width = settings.displaySetting.bubbleBorderWidth,
                                                shadowColor = settings.displaySetting.bubbleShadowColor,
                                                shadowWidth = settings.displaySetting.bubbleShadowWidth,
                                                cornerRadius = settings.displaySetting.bubbleCornerRadius,
                                            ),
                                        shape = RoundedCornerShape(settings.displaySetting.bubbleCornerRadius.dp),
                                        color = when {
                                            isGlassBubble -> Color.Transparent
                                            materialMode.isTranslucent -> assistantBubbleColor.copy(alpha = assistantBubbleColor.alpha * 0.72f)
                                            else -> assistantBubbleColor
                                        },
                                    ) {
                                        Box {
                                            BubbleBackgroundImage(
                                                path = settings.displaySetting.assistantBubbleImagePath,
                                                overlayColor = if (settings.displaySetting.bubbleImageOverlayEnabled) {
                                                    customBubbleColor(
                                                        custom = settings.displaySetting.assistantBubbleColor,
                                                        fallback = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                        alpha = 0.55f,
                                                    )
                                                } else null,
                                                contentScale = bubbleContentScale(
                                                    settings.displaySetting.bubbleBackgroundSize
                                                ),
                                                modifier = Modifier.matchParentSize(),
                                            )
                                            if (isGlassBubble) {
                                                Box(
                                                    modifier = Modifier
                                                        .matchParentSize()
                                                        .liquidGlassFill(
                                                            tint = customBubbleColor(
                                                                custom = settings.displaySetting.assistantBubbleColor,
                                                                fallback = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                                alpha = 1f,
                                                            )
                                                        )
                                                        .liquidGlassHighlight()
                                                )
                                            } else if (materialMode.isTranslucent) {
                                                Box(
                                                    modifier = Modifier
                                                        .matchParentSize()
                                                        .liquidGlassHighlight(intensity = 0.6f)
                                                )
                                            }
                                            Column(modifier = Modifier.padding(8.dp)) {
                                                MarkdownBlock(
                                                    content = applyVisualRegexes(part.text, AssistantAffectScope.ASSISTANT),
                                                    onClickCitation = handleClickCitation,
                                                    cardHost = cardHost,
                                                                                                    isStreaming = isStreamingMessage,
                                                cardMessagesJson = cardMessagesJson,
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    MarkdownBlock(
                                        content = applyVisualRegexes(part.text, AssistantAffectScope.ASSISTANT),
                                        onClickCitation = handleClickCitation,
                                        cardHost = cardHost,
                                                cardMessagesJson = cardMessagesJson,
                                        // 生成中不渲染卡片：正文里的 <article> 之类
                                        // 会被判成卡片，而它是半截的
                                                                                        isStreaming = isStreamingMessage,
                                        modifier = Modifier
                                            .animateContentSize()
                                    )
                                }
                            }
                        }

                        // 流式生成期间不启用 SelectionContainer：Markdown 在不断重渲染，
                        // 内部可选择的 Text 会频繁注册/注销，与 Compose 选择工具栏在绘制阶段
                        // 对 selectable 列表的排序产生并发修改，导致 ConcurrentModificationException。
                        // 生成结束后内容稳定，再启用文本选择。
                        // 视频通话存档卡片：[VIDEO_CALL_ARCHIVE:<id>] 标记用专用卡片渲染
                        val archiveSessionId = remember(part.text) {
                            extractVideoCallArchiveSessionId(part.text)
                        }
                        if (archiveSessionId != null) {
                            VideoCallArchiveCard(sessionId = archiveSessionId)
                        } else if (loading) {
                            textContent()
                        } else {
                            SelectionContainer {
                                textContent()
                            }
                        }
                    }
                    is UIMessagePart.Video -> {
                        ChatMessageMediaTile(
                            icon = HugeIcons.Video01,
                            onClick = { context.openLocalFile(part.url) },
                        )
                    }

                    is UIMessagePart.Audio -> {
                        ChatMessageMediaTile(
                            icon = HugeIcons.MusicNote03,
                            onClick = { context.openLocalFile(part.url) },
                        )
                    }

                    is UIMessagePart.Image -> {
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImageLoading) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.large)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else {
                            ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.large)
                                    .height(72.dp)
                            )
                        }
                    }

                    is UIMessagePart.Document -> {
                        ChatMessageFileChip(
                            text = part.fileName,
                            onClick = { context.openLocalFile(part.url) },
                            icon = {
                                when (part.mime) {
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                        Icon(
                                            painter = painterResource(R.drawable.docx),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    "application/pdf" -> {
                                        Icon(
                                            painter = painterResource(R.drawable.pdf),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    else -> {
                                        Icon(
                                            imageVector = HugeIcons.File02,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            },
                        )
                    }

                    else -> {
                        // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                    }
                }
            }
        }
    }

    // Annotations (always rendered at the end)
    if (annotations.isNotEmpty()) {
        ChatMessageCitations(annotations = annotations)
    }
}

private val BubbleCorner = 24.dp
private val BubbleTailCorner = 6.dp
private val BubblePressedCorner = 12.dp

/**
 * 消息气泡：大圆角，靠头像一侧的顶角收紧；可点击时按下其余圆角也跟着收紧
 */
@Composable
private fun ChatMessageBubble(
    role: MessageRole,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressProgress by animatePressProgress(interactionSource)
    val corner = lerp(BubbleCorner, BubblePressedCorner, pressProgress)
    val shape = if (role == MessageRole.USER) {
        RoundedCornerShape(topStart = corner, topEnd = BubbleTailCorner, bottomEnd = corner, bottomStart = corner)
    } else {
        RoundedCornerShape(topStart = BubbleTailCorner, topEnd = corner, bottomEnd = corner, bottomStart = corner)
    }
    val contentColor = contentColorFor(color)
    Box(
        modifier = modifier
            .clipDrawing(shape)
            .background(color)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick)
                } else {
                    Modifier
                },
            )
            // MarkdownBlock 自带 4dp 的水平内边距
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/**
 * 只裁剪绘制内容，不像 [clip] 那样把形状设成图层轮廓。
 *
 * 四个角半径不同时图层轮廓是 Path，Compose 判断触摸点是否落在轮廓内时用 Path 求交，
 * 坐标超过 8192px 后 float 精度不够，求交结果为空，长消息下半部分（例如展开的长代码块）就收不到触摸了。
 */
private fun Modifier.clipDrawing(shape: Shape): Modifier = drawWithCache {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    onDrawWithContent {
        clipPath(path) { this@onDrawWithContent.drawContent() }
    }
}

@Composable
private fun ChatMessageCitations(annotations: List<UIMessageAnnotation>) {
    var expand by remember { mutableStateOf(false) }
    val urls = remember(annotations) {
        annotations.mapNotNull { annotation ->
            when (annotation) {
                is UIMessageAnnotation.UrlCitation -> annotation.url
                UIMessageAnnotation.CharacterCardData, UIMessageAnnotation.ExampleMessage -> null
            }
        }
    }
    Column(
        modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChatMessageFileChip(
            text = stringResource(R.string.citations_count, annotations.size),
            onClick = { expand = !expand },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = { FaviconRow(urls = urls, size = 18.dp) },
        )
        if (expand) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }

                                is UIMessageAnnotation.CharacterCardData -> Unit
                                is UIMessageAnnotation.ExampleMessage -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 聊天外观自定义：气泡颜色覆盖（未设置时回退主题色，统一叠加气泡不透明度）。
 */
@Composable
private fun customBubbleColor(
    custom: Long?,
    fallback: Color,
    alpha: Float,
): Color {
    val base = custom?.toComposeColor() ?: fallback
    // 主题显式声明的气泡背景为 transparent 时，不能再用 bubbleOpacity 把 alpha 顶回去，
    // 否则作者刻意做的"文字浮在背景上"会被糊成一块实色气泡。
    if (custom != null && custom == TRANSPARENT_BUBBLE_SENTINEL) return base
    return base.copy(alpha = alpha)
}

/**
 * 气泡背景图：路径为空时不绘制；叠加遮罩颜色可空。
 * 放在 [BoxScope.matchParentSize] 内，不影响气泡按内容测量尺寸。
 */
@Composable
private fun BubbleBackgroundImage(
    path: String,
    overlayColor: Color?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    if (path.isBlank()) return
    AsyncImage(
        model = path,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
    if (overlayColor != null) {
        Box(modifier = modifier.background(overlayColor))
    }
}

/**
 * 把主题里的 background-size 映射为 Compose 的 ContentScale。
 *
 * 官方主题普遍写 `.mes_block::before { width:100%; height:200px; background-size:cover }`，
 * 元素本身是固定高度的小条；而本地气泡高度由文字撑开，长消息会变成很高的条。
 * 此时若仍无条件 Crop，图片就被纵向拉伸——这正是「拉伸得太长、非常难看」的来源。
 * 因此默认改为 Fit（等比完整显示、不裁不拉），仅在主题明确要求时裁切。
 */
private fun bubbleContentScale(size: String?): ContentScale =
    if (size?.contains("cover", ignoreCase = true) == true) ContentScale.Crop else ContentScale.Fit

/**
 * 气泡外框与阴影：对齐官方 `--SmartThemeBorderColor` + `border` 简写、
 * `--SmartThemeShadowColor` + `--shadowWidth`。
 *
 * 实测 553 个主题里气泡边框出现 307 次（1px 110、2px 41）、阴影 337 次，
 * 是仅次于圆角与底图的视觉特征。Compose 的 Surface 不带边框/阴影参数，
 * 这里用 Modifier 按相同圆角绘制，保证边框贴合圆角形状。
 */
private fun Modifier.themeBubbleBorder(
    color: Long?,
    width: Float,
    shadowColor: Long?,
    shadowWidth: Float,
    cornerRadius: Float,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius.dp)
    var m = this
    if (shadowColor != null && shadowWidth > 0f) {
        // 官方阴影是 `0 Ny blur`，向下偏移；这里同样下移，保持观感一致
        m = m.shadow(
            elevation = shadowWidth.dp,
            shape = shape,
            ambientColor = Color(shadowColor.toInt()),
            spotColor = Color(shadowColor.toInt()),
        )
    }
    if (color != null && width > 0f) {
        m = m.border(width = width.dp, color = Color(color.toInt()), shape = shape)
    }
    return m
}
