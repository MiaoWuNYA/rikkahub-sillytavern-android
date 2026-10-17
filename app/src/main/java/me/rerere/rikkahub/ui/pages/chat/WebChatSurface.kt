package me.rerere.rikkahub.ui.pages.chat

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.richtext.TrackWebViewPresence

private const val WEBUI_URL = "file:///android_asset/webui/index.html"

// 原生 → 页面的事件（酒馆事件名 + 参数 JSON 数组）
data class WebUiEvent(val name: String, val payloadJson: String)

// 界面 WebView 化：消息区与输入框上方那排功能按钮交给 WebView 渲
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebChatSurface(
    messages: List<UIMessage>,
    assistant: Assistant,
    settings: Settings,
    streaming: Boolean,
    modelName: String,
    searchEnabled: Boolean,
    searchVisible: Boolean,
    reasoningEnabled: Boolean,
    reasoningVisible: Boolean,
    symbolVisible: Boolean,
    bracketSymbol: String,
    moreVisible: Boolean,
    // 朗读状态：页面那排操作按钮与原生共用同一个 TTS 单例
    speaking: Boolean = false,
    ttsAvailable: Boolean = true,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenLink: (String) -> Unit,
    onSlash: (String) -> Unit,
    onRequestMenu: (String) -> Unit,
    onInsertSymbol: (String) -> Unit,
    variablesJson: String = "{}",
    onSaveVariables: (String) -> Unit = {},
    // 前端插件脚本（仅本模式下发），json 数组，含脚本源码
    frontendScriptsJson: String = "[]",
    // 酒馆助手兼容渲染设置
    helperSettingsJson: String = "{}",
    onSetVariables: (String) -> Unit = {},
    onSubstituteMacros: (suspend (String) -> String)? = null,
    onMessagesJson: () -> String = { "[]" },
    nodes: List<me.rerere.rikkahub.data.model.MessageNode> = emptyList(),
    events: kotlinx.coroutines.flow.SharedFlow<WebUiEvent>? = null,
    // 被原生标题栏与输入栏遮住的区域：页面据此让出上下留白（WebView 本身铺满全
    safeAreaTop: androidx.compose.ui.unit.Dp = 0.dp,
    safeAreaBottom: androidx.compose.ui.unit.Dp = 0.dp,
    onSetChatMessages: (String) -> Unit = {},
    onCreateChatMessages: (String) -> Unit = {},
    onDeleteChatMessages: (String) -> Unit = {},
    onMessageAction: (String) -> Unit = {},
    onOpenImage: (String) -> Unit = {},
    onClipboard: (String) -> Unit = {},
    onHttpRequest: (String, (String) -> Unit) -> Unit = { _, done -> done("{}") },
    onSelectSwipe: (String) -> Unit = {},
    onWorldbook: (String, (String) -> Unit) -> Unit = { _, done -> done("null") },
    modifier: Modifier = Modifier,
) {
    // 有 WebView 存活时，输入栏的模糊/玻璃效果必须让位
    TrackWebViewPresence()
    val assetsManager = androidx.compose.ui.platform.LocalContext.current.assets
    // 模型图标内联进页面：<img> 引 svg 拿不到 currentColor，深色主题下会变成黑块
    val iconCache = remember(assetsManager) { mutableMapOf<String, String?>() }
    val iconText: (String) -> String? = { path ->
        if (iconCache.containsKey(path)) iconCache[path] else readCardIcon(assetsManager, path).also { iconCache[path] = it }
    }
    val placeholder = stringResource(R.string.chat_input_placeholder)
    val labels = WebLabels(stringResource(R.string.translating), stringResource(R.string.translation_text))
    val colorScheme = MaterialTheme.colorScheme
    val themeJson = remember(colorScheme) {
        buildJsonObject {
            put("primary", colorScheme.primary.toCssHex())
            put("on-primary", colorScheme.onPrimary.toCssHex())
            put("primary-container", colorScheme.primaryContainer.toCssHex())
            put("on-primary-container", colorScheme.onPrimaryContainer.toCssHex())
            put("secondary-container", colorScheme.secondaryContainer.toCssHex())
            put("on-secondary-container", colorScheme.onSecondaryContainer.toCssHex())
            put("surface", colorScheme.surface.toCssHex())
            put("surface-container", colorScheme.surfaceContainer.toCssHex())
            put("surface-container-high", colorScheme.surfaceContainerHigh.toCssHex())
            put("on-surface", colorScheme.onSurface.toCssHex())
            put("on-surface-variant", colorScheme.onSurfaceVariant.toCssHex())
            put("outline", colorScheme.outline.toCssHex())
            put("outline-variant", colorScheme.outlineVariant.toCssHex())
            put("error", colorScheme.error.toCssHex())
        }.toString()
    }
    // 下发 dp 数值：WebView 的 CSS px 与 Compose 的 dp 同量级，传物理像素会被 density 放大
    val safeAreaJson = remember(safeAreaTop, safeAreaBottom) {
        buildJsonObject {
            put("top", kotlinx.serialization.json.JsonPrimitive(safeAreaTop.value))
            put("bottom", kotlinx.serialization.json.JsonPrimitive(safeAreaBottom.value))
        }.toString()
    }
    val darkMode = me.rerere.rikkahub.ui.theme.LocalDarkMode.current
    val appearanceJson = remember(settings.displaySetting, darkMode) {
        buildAppearanceJson(settings.displaySetting, darkMode)
    }
    val macroJson = remember(messages, assistant, settings, modelName) {
        buildMacros(messages, assistant, settings, modelName).toString()
    }
    val stateJson = remember(messages, nodes, settings, streaming, placeholder, labels) {
        buildState(
            messages = messages,
            assistant = assistant,
            settings = settings,
            macroJson = macroJson,
            themeJson = themeJson,
            placeholder = placeholder,
            cardStyle = extractCardStyle(assistant),
            nodes = nodes,
            iconText = iconText,
            labels = labels,
            generating = streaming,
        ).toString()
    }
    // 流式帧只认助手消息：用户刚发完时最后一条是用户消息，那种情况下不打光标
    val streamingMessage = remember(messages, streaming) {
        if (!streaming) null
        else messages.lastOrNull()?.takeIf { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
    }
    val streamJson = remember(streamingMessage, assistant, settings) {
        streamingMessage?.let { buildMessage(it, assistant, settings, iconText = iconText, labels = labels).toString() }
    }
    val lastJson = remember(messages) {
        messages.lastOrNull()?.let { buildMessage(it, assistant, settings, iconText = iconText, labels = labels).toString() }
    }
    // 下发时机只看渲染元数据：正文增长走流式增量，收藏/翻译/分支这类字段没有事件可依赖
    // 生成起止必须进 key：页面靠这次下发开关流式指示，漏掉就会挂在那儿不灭
    val stateKey = remember(messages, nodes, streaming) {
        val nodeOf = mutableMapOf<kotlin.uuid.Uuid, me.rerere.rikkahub.data.model.MessageNode>()
        nodes.forEach { node -> node.messages.forEach { msg -> nodeOf[msg.id] = node } }
        messages.joinToString(";") { message ->
            val node = nodeOf[message.id]
            listOf(
                message.id,
                node?.isFavorite == true,
                node?.selectIndex ?: 0,
                node?.messages?.size ?: 1,
                message.translation?.hashCode() ?: 0,
                message.parts.size,
            ).joinToString("|")
        } + "|" + streaming
    }
    val toolbarJson = remember(
        modelName, searchEnabled, searchVisible, reasoningEnabled, reasoningVisible,
        symbolVisible, bracketSymbol, moreVisible,
    ) {
        buildJsonObject {
            put("modelName", modelName)
            put("searchEnabled", searchEnabled)
            put("searchVisible", searchVisible)
            put("reasoningEnabled", reasoningEnabled)
            put("reasoningVisible", reasoningVisible)
            put("symbolVisible", symbolVisible)
            put("bracketSymbol", bracketSymbol)
            put("moreVisible", moreVisible)
        }.toString()
    }

    val macroScope = rememberCoroutineScope()
    var bridge by remember { mutableStateOf<RikkaNativeBridge?>(null) }
    var ready by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(onSend, onStop, onRegenerate, onOpenLink, onSlash, onRequestMenu, onInsertSymbol, onOpenImage) {
        bridge?.onSend = onSend
        bridge?.onStop = onStop
        bridge?.onRegenerate = onRegenerate
        bridge?.onOpenLink = onOpenLink
        bridge?.onSlash = onSlash
        bridge?.onRequestMenu = onRequestMenu
        bridge?.onInsertSymbol = onInsertSymbol
        bridge?.onOpenImage = onOpenImage
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                this.settings.javaScriptEnabled = true
                // 便于真机排查渲染问题
                android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                this.settings.domStorageEnabled = true
                this.settings.allowFileAccess = true
                this.settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("file:///android_asset/") || url.startsWith("about:")) return false
                        bridge?.onOpenLink?.invoke(url)
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        ready = true
                    }
                }
                val localBridge = RikkaNativeBridge()
                localBridge.webView = this
                localBridge.pageApi = "webui"
                bridge = localBridge
                addJavascriptInterface(localBridge, "rikkaNative")
                loadUrl(WEBUI_URL)
                webViewRef = this
            }
        },
        update = { view ->
            val url = view.url
            if (url.isNullOrEmpty() || url == "about:blank" || url.startsWith("chrome-error")) {
                view.loadUrl(WEBUI_URL)
            }
        },
    )


    LaunchedEffect(bridge, variablesJson, messages, assistant, settings, modelName) {
        bridge?.onSyncRequest = { method, payload ->
            when (method) {
                "getChatMessages" -> onMessagesJson()
                "getVariables" -> variablesJson
                "getWorldbookNames" -> {
                    kotlinx.serialization.json.buildJsonArray {
                        settings.lorebooks.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) }
                    }.toString()
                }
                "getGlobalWorldbookNames" -> {
                    kotlinx.serialization.json.buildJsonArray {
                        settings.huadengSettings.globalWorldbookNames.forEach {
                            add(kotlinx.serialization.json.JsonPrimitive(it))
                        }
                    }.toString()
                }
                "getCharWorldbookNames" -> {
                    val bound = assistant.lorebookIds.mapNotNull { id -> settings.lorebooks.find { it.id == id }?.name }
                    kotlinx.serialization.json.buildJsonObject {
                        put("primary", bound.firstOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                        put("additional", kotlinx.serialization.json.buildJsonArray {
                            bound.drop(1).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        })
                    }.toString()
                }
                "getChatWorldbookName" -> "null"
                "getWorldbook" -> {
                    val name = payload.trim().trim('"')
                    val book = settings.lorebooks.find { it.name == name }
                    if (book == null) "null" else lorebookEntriesToJson(book.entries).toString()
                }
                else -> null
            }
        }
        bridge?.onAsyncRequest = { method, requestId, payload ->
            when (method) {
                "substituteMacros" -> {
                    val job = macroScope.launch {
                        val result = onSubstituteMacros?.invoke(payload) ?: payload
                        bridge?.resolve(requestId, result)
                    }
                    job.invokeOnCompletion { }
                }

                "setChatMessages" -> {
                    onSetChatMessages(payload)
                    bridge?.resolve(requestId, "null")
                }

                "createChatMessages" -> {
                    onCreateChatMessages(payload)
                    bridge?.resolve(requestId, "null")
                }

                "deleteChatMessages" -> {
                    onDeleteChatMessages(payload)
                    bridge?.resolve(requestId, "null")
                }

                "selectSwipe" -> {
                    onSelectSwipe(payload)
                    bridge?.resolve(requestId, "null")
                }

                "httpRequest" -> {
                    onHttpRequest(payload) { result ->
                        bridge?.resolve(requestId, result)
                    }
                }

                "messageAction" -> {
                    onMessageAction(payload)
                    bridge?.resolve(requestId, "null")
                }

                "saveVariables" -> {
                    onSaveVariables(payload)
                    bridge?.resolve(requestId, payload)
                }

                "setVariables" -> {
                    onSetVariables(payload)
                    bridge?.resolve(requestId, "null")
                }

                "triggerSlash" -> {
                    onSlash(payload)
                    bridge?.resolve(requestId, "")
                }

                "worldbook" -> {
                    onWorldbook(payload) { result -> bridge?.resolve(requestId, result) }
                }

                else -> bridge?.resolve(requestId, "null")
            }
        }
    }

    LaunchedEffect(ready, stateKey) {
        if (!ready) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.webui.setSafeArea(${quoteJs(safeAreaJson)});", null)
        webViewRef?.evaluateJavascript("window.webui.setAppearance(${quoteJs(appearanceJson)});", null)
        webViewRef?.evaluateJavascript("window.API.setVariables(${quoteJs(variablesJson)});", null)
        webViewRef?.evaluateJavascript("window.webui.setTheme(${quoteJs(themeJson)});", null)
        webViewRef?.evaluateJavascript("window.webui.setState(${quoteJs(stateJson)});", null)
        webViewRef?.evaluateJavascript("window.webui.setToolbar(${quoteJs(toolbarJson)});", null)
    }

    // 原生事件 → 页面 → 卡片（生成开始/结束、消息增删改）
    LaunchedEffect(events, ready) {
        val flow = events ?: return@LaunchedEffect
        flow.collect { event ->
            val payload = event.payloadJson
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
            webViewRef?.evaluateJavascript(
                "window.webui.emitEvent('${event.name}', '$payload');",
                null,
            )
        }
    }


    // 卡片运行环境：jQuery 与 toastr 随包内置
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        val libs = runCatching {
            val payload = buildJsonObject {
                listOf(
                    "jquery" to "lib/jquery.min.js",
                    "toastr" to "lib/toastr.min.js",
                    // 运行环境补丁：卡片与页面都用它补齐旧 WebView 缺失的标准方法
                    "compat" to "webui/compat.js",
                ).forEach { (name, path) ->
                    val bytes = assetsManager.open(path).use { it.readBytes() }
                    put(name, kotlinx.serialization.json.JsonPrimitive(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
                }
            }.toString()
            payload
        }.getOrNull()
        if (libs != null) {
            webViewRef?.evaluateJavascript("window.webui.setLibs(${quoteJs(libs)});", null)
        }
    }

    LaunchedEffect(safeAreaJson, ready) {
        if (!ready) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.webui.setSafeArea(${quoteJs(safeAreaJson)});", null)
    }

    // 前端插件脚本：仅在本模式下发，页面按 id 去重后增量注入
    LaunchedEffect(frontendScriptsJson, ready) {
        if (!ready) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.webui.setPlugins(${quoteJs(frontendScriptsJson)});", null)
    }

    LaunchedEffect(helperSettingsJson, ready) {
        if (!ready) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.webui.setHelperSettings(${quoteJs(helperSettingsJson)});", null)
    }

    LaunchedEffect(appearanceJson, ready) {
        if (!ready) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.webui.setAppearance(${quoteJs(appearanceJson)});", null)
    }

    LaunchedEffect(toolbarJson, ready) {
        if (!ready) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.webui.setToolbar(${quoteJs(toolbarJson)});", null)
    }

    LaunchedEffect(speaking, ttsAvailable, ready) {
        if (!ready) return@LaunchedEffect
        val json = buildJsonObject {
            put("speaking", speaking)
            put("available", ttsAvailable)
        }.toString()
        webViewRef?.evaluateJavascript("window.webui.setSpeaking(${quoteJs(json)});", null)
    }

    LaunchedEffect(streamJson, ready) {
        if (!ready || streamJson == null) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.webui.appendStream(${quoteJs(streamJson)});", null)
    }

    LaunchedEffect(streaming, ready) {
        if (!ready || streaming) return@LaunchedEffect
        lastJson?.let { webViewRef?.evaluateJavascript("window.webui.appendMessage(${quoteJs(it)});", null) }
    }
}

// 传给页面的 JSON 用单引号包一层，内部的引号与反斜杠转义掉
internal fun quoteJs(json: String): String =
    "'" + json.replace("\\", "\\\\").replace("'", "\\'") + "'"

// 主题色统一在 WebChatSurface 里转换（前端卡页面复用同一份实现）
@Suppress("unused")
private fun Color.hexForWeb(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

// 聊天外观自定义 → 页面 CSS 变量：设置里凡影响消息区外观的字段都在这里下发，页面不读设置
// 引号/括号/斜体的兜底色与原生一致：设置留空时按深浅色各取一套内置值
internal fun buildAppearanceJson(
    display: me.rerere.rikkahub.data.datastore.DisplaySetting,
    darkMode: Boolean = false,
): String =
    buildJsonObject {
        val md = display.themeMarkdownStyle
        fun color(name: String, value: Long?) {
            value?.let { put(name, kotlinx.serialization.json.JsonPrimitive(argbToCss(it))) }
        }

        fun asset(name: String, value: String?) {
            value?.takeIf { it.isNotBlank() }?.let {
                put(name, kotlinx.serialization.json.JsonPrimitive(it.toWebImageUrl()))
            }
        }

        // —— 气泡与排版 ——
        put("bubbleRadius", kotlinx.serialization.json.JsonPrimitive(display.bubbleCornerRadius))
        put("bubbleOpacity", kotlinx.serialization.json.JsonPrimitive(display.bubbleOpacity))
        put("fontSizeRatio", kotlinx.serialization.json.JsonPrimitive(display.fontSizeRatio))
        put("showAssistantBubble", kotlinx.serialization.json.JsonPrimitive(display.showAssistantBubble))
        put("showUserAvatar", kotlinx.serialization.json.JsonPrimitive(display.showUserAvatar))
        put("imageOverlay", kotlinx.serialization.json.JsonPrimitive(display.bubbleImageOverlayEnabled))
        put("imageSize", kotlinx.serialization.json.JsonPrimitive(display.bubbleBackgroundSize))
        put("borderWidth", kotlinx.serialization.json.JsonPrimitive(display.bubbleBorderWidth))
        put("shadowWidth", kotlinx.serialization.json.JsonPrimitive(display.bubbleShadowWidth))
        put("textColorEnabled", kotlinx.serialization.json.JsonPrimitive(display.enableTextColor))
        put("fontFamily", kotlinx.serialization.json.JsonPrimitive(display.chatFontFamily.name))
        put("customFontName", kotlinx.serialization.json.JsonPrimitive(display.chatCustomFontName))
        asset("customFontUrl", display.chatCustomFontPath)

        // —— 元信息显示开关 ——
        put("showTime", kotlinx.serialization.json.JsonPrimitive(display.showDateTimeInMessage))
        put("showModelIcon", kotlinx.serialization.json.JsonPrimitive(display.showModelIcon))
        put("showModelName", kotlinx.serialization.json.JsonPrimitive(display.showModelName))
        put("showTokenUsage", kotlinx.serialization.json.JsonPrimitive(display.showTokenUsage))

        // —— 推理与代码块 ——
        put("showThinking", kotlinx.serialization.json.JsonPrimitive(display.showThinkingContent))
        put("autoCloseThinking", kotlinx.serialization.json.JsonPrimitive(display.autoCloseThinking))
        put("codeWrap", kotlinx.serialization.json.JsonPrimitive(display.codeBlockAutoWrap))
        put("codeCollapse", kotlinx.serialization.json.JsonPrimitive(display.codeBlockAutoCollapse))
        put("codeFontScale", kotlinx.serialization.json.JsonPrimitive(display.codeBlockFontSizeRatio))
        put("lineNumbers", kotlinx.serialization.json.JsonPrimitive(display.showLineNumbers))
        put("latex", kotlinx.serialization.json.JsonPrimitive(display.enableLatexRendering))

        // —— 滚动与跳转 ——
        put("autoScroll", kotlinx.serialization.json.JsonPrimitive(display.enableAutoScroll))
        put("messageJumper", kotlinx.serialization.json.JsonPrimitive(display.showMessageJumper))
        put("jumperLeft", kotlinx.serialization.json.JsonPrimitive(display.messageJumperOnLeft))

        // —— 颜色覆盖层（null = 跟随主题）——
        color("primaryColor", display.primaryColor)
        color("userBubbleColor", display.userBubbleColor)
        color("assistantBubbleColor", display.assistantBubbleColor)
        color("thinkingBubbleColor", display.thinkingBubbleColor)
        color("chatBackgroundColor", display.chatBackgroundColor)
        color("textColor", display.globalTextColor)
        color("borderColor", display.bubbleBorderColor)
        color("shadowColor", display.bubbleShadowColor)
        color("quoteBackgroundColor", display.quoteBackgroundColor)
        color("underlineColor", display.underlineColor)
        put(
            "quoteColor",
            kotlinx.serialization.json.JsonPrimitive(display.quoteColor.ifBlank { if (darkMode) "#E18A24" else "#C7731E" }),
        )
        put("italicsColor", kotlinx.serialization.json.JsonPrimitive(display.italicsColor.ifBlank { "#919191" }))
        put(
            "bracketColor",
            kotlinx.serialization.json.JsonPrimitive(display.bracketColor.ifBlank { if (darkMode) "#7EAFC4" else "#5B8DEF" }),
        )

        // —— 气泡/背景图 ——
        asset("userBubbleImage", display.userBubbleImagePath)
        asset("assistantBubbleImage", display.assistantBubbleImagePath)
        asset("chatBackgroundImage", display.chatBackgroundImagePath)

        // —— 主题导入的 Markdown 元素样式（代码框/引用块/高亮/斜体/思维链）——
        put("mdInlineCodeBg", kotlinx.serialization.json.JsonPrimitive(md.inlineCodeBackgroundArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdInlineCodeColor", kotlinx.serialization.json.JsonPrimitive(md.inlineCodeColorArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdInlineCodeRadius", kotlinx.serialization.json.JsonPrimitive(md.inlineCodeCornerRadiusDp))
        put("mdCodeBg", kotlinx.serialization.json.JsonPrimitive(md.codeBlockBackgroundArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdCodeColor", kotlinx.serialization.json.JsonPrimitive(md.codeBlockColorArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdCodeRadius", kotlinx.serialization.json.JsonPrimitive(md.codeBlockCornerRadiusDp))
        put("mdCodeBorder", kotlinx.serialization.json.JsonPrimitive(md.codeBlockBorderColorArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdCodeBorderWidth", kotlinx.serialization.json.JsonPrimitive(md.codeBlockBorderWidthDp))
        put("mdQuoteBg", kotlinx.serialization.json.JsonPrimitive(md.quoteBackgroundArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdQuoteColor", kotlinx.serialization.json.JsonPrimitive(md.quoteColorArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdQuoteRadius", kotlinx.serialization.json.JsonPrimitive(md.quoteCornerRadiusDp))
        put("mdQuoteBorder", kotlinx.serialization.json.JsonPrimitive(md.quoteBorderColorArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdQuoteBorderWidth", kotlinx.serialization.json.JsonPrimitive(md.quoteBorderWidthDp))
        put("mdQuoteAccentWidth", kotlinx.serialization.json.JsonPrimitive(md.quoteAccentWidthDp))
        put("mdMarkBg", kotlinx.serialization.json.JsonPrimitive(md.markBackgroundArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdMarkColor", kotlinx.serialization.json.JsonPrimitive(md.markColorArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdMarkRadius", kotlinx.serialization.json.JsonPrimitive(md.markCornerRadiusDp))
        put("mdEmphasisBg", kotlinx.serialization.json.JsonPrimitive(md.emphasisBackgroundArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdEmphasisColor", kotlinx.serialization.json.JsonPrimitive(md.emphasisColorArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdEmphasisRadius", kotlinx.serialization.json.JsonPrimitive(md.emphasisCornerRadiusDp))
        put("mdReasoningBg", kotlinx.serialization.json.JsonPrimitive(md.reasoningBackgroundArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdReasoningColor", kotlinx.serialization.json.JsonPrimitive(md.reasoningColorArgb?.let { argbToCss(it) }.orEmpty()))
        put("mdReasoningRadius", kotlinx.serialization.json.JsonPrimitive(md.reasoningCornerRadiusDp))

        // —— 主题头像框（用户侧与助手侧分开，缺省回退到不分侧的框）——
        asset("userAvatarFrame", display.themeIcons.avatarFrame(true))
        asset("botAvatarFrame", display.themeIcons.avatarFrame(false))
        put("avatarFrameScale", kotlinx.serialization.json.JsonPrimitive(display.themeIcons.avatarFrameScale))
    }.toString()

// 模型图标：单色 svg 补 currentColor，否则内联后按黑渲染、深色主题下看不见
private fun readCardIcon(assets: android.content.res.AssetManager, path: String): String? = runCatching {
    val svg = assets.open("icons/$path").use { it.readBytes().decodeToString() }
    if (Regex("""<svg\b[^>]*\bfill\s*=""").containsMatchIn(svg)) svg
    else svg.replaceFirst(Regex("""<svg\b"""), """<svg fill="currentColor"""")
}.getOrNull()

// ARGB 整数 → #RRGGBB
private fun argbToCss(value: Long): String = String.format("#%06X", value.toInt() and 0xFFFFFF)

// 本地路径转成 WebView 能加载的 URL（Coil 能吃的路径 WebVi
internal fun String.toWebImageUrl(): String = when {
    isBlank() -> ""
    startsWith("content://") || startsWith("file://") || startsWith("http://") || startsWith("https://") -> this
    else -> "file://$this"
}
