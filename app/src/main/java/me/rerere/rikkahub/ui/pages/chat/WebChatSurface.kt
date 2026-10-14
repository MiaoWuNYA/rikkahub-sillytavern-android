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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width

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
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenLink: (String) -> Unit,
    onSlash: (String) -> Unit,
    onRequestMenu: (String) -> Unit,
    onInsertSymbol: (String) -> Unit,
    variablesJson: String = "{}",
    onSaveVariables: (String) -> Unit = {},
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
    onClipboard: (String) -> Unit = {},
    onHttpRequest: (String, (String) -> Unit) -> Unit = { _, done -> done("{}") },
    onSelectSwipe: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 有 WebView 存活时，输入栏的模糊/玻璃效果必须让位
    TrackWebViewPresence()
    val assetsManager = androidx.compose.ui.platform.LocalContext.current.assets
    val placeholder = stringResource(R.string.chat_input_placeholder)
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
            put("outline-variant", colorScheme.outlineVariant.toCssHex())
            put("error", colorScheme.error.toCssHex())
        }.toString()
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val safeAreaJson = remember(safeAreaTop, safeAreaBottom, density) {
        buildJsonObject {
            put("top", kotlinx.serialization.json.JsonPrimitive(with(density) { safeAreaTop.roundToPx() }))
            put("bottom", kotlinx.serialization.json.JsonPrimitive(with(density) { safeAreaBottom.roundToPx() }))
        }.toString()
    }
    val appearanceJson = remember(settings.displaySetting) {
        buildAppearanceJson(settings.displaySetting)
    }
    val macroJson = remember(messages, assistant, settings, modelName) {
        buildMacros(messages, assistant, settings, modelName).toString()
    }
    val stateJson = remember(messages, settings, streaming, placeholder) {
        buildState(messages, assistant, settings, macroJson, themeJson, placeholder, extractCardStyle(assistant), nodes).toString()
    }
    val streamJson = remember(messages, streaming) {
        if (!streaming) null else messages.lastOrNull()?.let { buildMessage(it, assistant, settings).toString() }
    }
    val lastJson = remember(messages) {
        messages.lastOrNull()?.let { buildMessage(it, assistant, settings).toString() }
    }
    val idsKey = remember(messages) { messages.joinToString(",") { it.id.toString() } }
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

    LaunchedEffect(onSend, onStop, onRegenerate, onOpenLink, onSlash, onRequestMenu, onInsertSymbol, onMessageAction, onClipboard) {
        bridge?.onSend = onSend
        bridge?.onStop = onStop
        bridge?.onRegenerate = onRegenerate
        bridge?.onOpenLink = onOpenLink
        bridge?.onSlash = onSlash
        bridge?.onRequestMenu = onRequestMenu
        bridge?.onInsertSymbol = onInsertSymbol
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                this.settings.javaScriptEnabled = true
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
                loadUrl("file:///android_asset/webui/index.html")
                webViewRef = this
            }
        },
    )


    LaunchedEffect(bridge, variablesJson, messages, assistant, settings, modelName) {
        bridge?.onSyncRequest = { method, payload ->
            when (method) {
                "getChatMessages" -> onMessagesJson()
                "getVariables" -> variablesJson
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

                "triggerSlash" -> {
                    onSlash(payload)
                    bridge?.resolve(requestId, "")
                }

                else -> bridge?.resolve(requestId, "null")
            }
        }
    }

    LaunchedEffect(ready, idsKey) {
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

    LaunchedEffect(appearanceJson, ready) {
        if (!ready) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.webui.setAppearance(${quoteJs(appearanceJson)});", null)
    }

    LaunchedEffect(toolbarJson, ready) {
        if (!ready) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.webui.setToolbar(${quoteJs(toolbarJson)});", null)
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

// 聊天外观自定义 → 页面样式：
internal fun buildAppearanceJson(display: me.rerere.rikkahub.data.datastore.DisplaySetting): String =
    buildJsonObject {
        put("bubbleRadius", kotlinx.serialization.json.JsonPrimitive(display.bubbleCornerRadius))
        put("bubbleOpacity", kotlinx.serialization.json.JsonPrimitive(display.bubbleOpacity))
        put("fontSizeRatio", kotlinx.serialization.json.JsonPrimitive(display.fontSizeRatio))
        put("showAssistantBubble", kotlinx.serialization.json.JsonPrimitive(display.showAssistantBubble))
        put("imageOverlay", kotlinx.serialization.json.JsonPrimitive(display.bubbleImageOverlayEnabled))
        put("imageSize", kotlinx.serialization.json.JsonPrimitive(display.bubbleBackgroundSize))
        put("borderWidth", kotlinx.serialization.json.JsonPrimitive(display.bubbleBorderWidth))
        put("textColorEnabled", kotlinx.serialization.json.JsonPrimitive(display.enableTextColor))
        put("fontFamily", kotlinx.serialization.json.JsonPrimitive(display.chatFontFamily.name))
        display.userBubbleColor?.let { put("userBubbleColor", kotlinx.serialization.json.JsonPrimitive(argbToCss(it))) }
        display.assistantBubbleColor?.let { put("assistantBubbleColor", kotlinx.serialization.json.JsonPrimitive(argbToCss(it))) }
        display.thinkingBubbleColor?.let { put("thinkingBubbleColor", kotlinx.serialization.json.JsonPrimitive(argbToCss(it))) }
        display.chatBackgroundColor?.let { put("chatBackgroundColor", kotlinx.serialization.json.JsonPrimitive(argbToCss(it))) }
        display.globalTextColor?.let { put("textColor", kotlinx.serialization.json.JsonPrimitive(argbToCss(it))) }
        display.bubbleBorderColor?.let { put("borderColor", kotlinx.serialization.json.JsonPrimitive(argbToCss(it))) }
        display.bubbleShadowColor?.let { put("shadowColor", kotlinx.serialization.json.JsonPrimitive(argbToCss(it))) }
        put("quoteColor", kotlinx.serialization.json.JsonPrimitive(display.quoteColor))
        put("italicsColor", kotlinx.serialization.json.JsonPrimitive(display.italicsColor))
        put("bracketColor", kotlinx.serialization.json.JsonPrimitive(display.bracketColor))
        put("userBubbleImage", kotlinx.serialization.json.JsonPrimitive(display.userBubbleImagePath.toWebImageUrl()))
        put("assistantBubbleImage", kotlinx.serialization.json.JsonPrimitive(display.assistantBubbleImagePath.toWebImageUrl()))
        put("chatBackgroundImage", kotlinx.serialization.json.JsonPrimitive(display.chatBackgroundImagePath.toWebImageUrl()))
    }.toString()

// ARGB 整数 → #RRGGBB
private fun argbToCss(value: Long): String = String.format("#%06X", value.toInt() and 0xFFFFFF)

// 本地路径转成 WebView 能加载的 URL（Coil 能吃的路径 WebVi
internal fun String.toWebImageUrl(): String = when {
    isBlank() -> ""
    startsWith("content://") || startsWith("file://") || startsWith("http://") || startsWith("https://") -> this
    else -> "file://$this"
}
