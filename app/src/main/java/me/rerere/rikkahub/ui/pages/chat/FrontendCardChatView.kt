package me.rerere.rikkahub.ui.pages.chat

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.richtext.splitCardSegments
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.rikkahub.utils.toMessageTimeString
import me.rerere.rikkahub.ui.components.richtext.TrackWebViewPresence
import me.rerere.rikkahub.data.model.MessageNode
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp

// 前端卡专属模式：整块消息区交给单个 WebView。
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FrontendCardChatView(
    messages: List<UIMessage>,
    assistant: Assistant,
    settings: Settings,
    streaming: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenLink: (String) -> Unit,
    onSlash: (String) -> Unit = {},
    modelName: String = "",
    // 变量域（local/global）与后端能力：变量落盘、宏走后端引擎
    variablesJson: String = "{}",
    onSaveVariables: (String) -> Unit = {},
    onSubstituteMacros: (suspend (String) -> String)? = null,
    onMessagesJson: () -> String = { "[]" },
    nodes: List<me.rerere.rikkahub.data.model.MessageNode> = emptyList(),
    pageApi: String = "rikka",
    events: kotlinx.coroutines.flow.SharedFlow<WebUiEvent>? = null,
    onSetChatMessages: (String) -> Unit = {},
    onCreateChatMessages: (String) -> Unit = {},
    onDeleteChatMessages: (String) -> Unit = {},
    onMessageAction: (String) -> Unit = {},
    onClipboard: (String) -> Unit = {},
    onHttpRequest: (String, (String) -> Unit) -> Unit = { _, done -> done("{}") },
    // 左边缘横向拖动交给抽屉：WebView 会吞掉触摸，不划出这条通道侧边栏拉不出来
    onEdgeSwipe: () -> Unit = {},
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

    val appearanceJson = remember(settings.displaySetting) {
        buildAppearanceJson(settings.displaySetting)
    }
    val macroJson = remember(messages, assistant, settings, modelName) {
        buildMacros(messages, assistant, settings, modelName).toString()
    }
    val stateJson = remember(messages, settings, streaming) {
        buildState(messages, assistant, settings, macroJson, themeJson, placeholder, extractCardStyle(assistant), nodes).toString()
    }
    val streamJson = remember(messages, streaming) {
        if (!streaming) null else messages.lastOrNull()?.let { buildMessage(it, assistant, settings).toString() }
    }
    val lastJson = remember(messages) {
        messages.lastOrNull()?.let { buildMessage(it, assistant, settings).toString() }
    }
    // 只在消息集合结构变化（条数/顺序）时全量重绘，其余情况逐条更新
    val idsKey = remember(messages) { messages.joinToString(",") { it.id.toString() } }

    // 发送、停止、重新生成：闭包会被 JS 线程调用，用 State 持有最新实现
    val macroScope = rememberCoroutineScope()
    var bridge by remember { mutableStateOf<RikkaNativeBridge?>(null) }
    var ready by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(onSend, onStop, onRegenerate, onOpenLink, onSlash) {
        bridge?.onSend = onSend
        bridge?.onStop = onStop
        bridge?.onRegenerate = onRegenerate
        bridge?.onOpenLink = onOpenLink
        bridge?.onSlash = onSlash
    }

    Box(modifier = modifier.fillMaxSize()) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                this.settings.javaScriptEnabled = true
                this.settings.domStorageEnabled = true
                this.settings.allowFileAccess = true
                this.settings.mediaPlaybackRequiresUserGesture = false
                isVerticalScrollBarEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        // 页内锚点与本地资产照常加载，其余交给系统浏览器
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
                localBridge.pageApi = pageApi
                bridge = localBridge
                addJavascriptInterface(localBridge, "rikkaNative")
                loadUrl("file:///android_asset/frontend-card/index.html")
                webViewRef = this
            }
        },
    )

    // 左边缘手势层：只在边缘 24dp 内接管横向拖动，纵向滚动仍归 WebView
    Box(
        modifier = Modifier
            .align(androidx.compose.ui.Alignment.CenterStart)
            .fillMaxHeight()
            .width(24.dp)
            .pointerInput(Unit) {
                val threshold = 24.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalX = 0f
                    var totalY = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.position - change.previousPosition
                        totalX += delta.x
                        totalY += delta.y
                        if (totalX > threshold && totalX > kotlin.math.abs(totalY) * 1.5f) {
                            change.consume()
                            onEdgeSwipe()
                            break
                        }
                        if (!change.pressed) break
                    }
                }
            },
    )
    }

    // 首帧：主题 + 全量状态
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
            webViewRef?.evaluateJavascript("window.rikka.setLibs(${quote(libs)});", null)
        }
    }

    LaunchedEffect(events, ready) {
        val flow = events ?: return@LaunchedEffect
        flow.collect { event ->
            val payload = event.payloadJson
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
            webViewRef?.evaluateJavascript(
                "window.rikka.emitEvent('${event.name}', '$payload');",
                null,
            )
        }
    }

    LaunchedEffect(ready, idsKey) {
        if (!ready) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.rikka.setAppearance(${quote(appearanceJson)});", null)
        webViewRef?.evaluateJavascript("window.API.setVariables(${quote(variablesJson)});", null)
        webViewRef?.evaluateJavascript("window.rikka.setTheme(${quote(themeJson)});", null)
        webViewRef?.evaluateJavascript("window.rikka.setState(${quote(stateJson)});", null)
    }

    // 流式阶段只追加/更新最后一条
    LaunchedEffect(appearanceJson, ready) {
        if (!ready) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.rikka.setAppearance(${quote(appearanceJson)});", null)
    }

    LaunchedEffect(streamJson, ready) {
        if (!ready || streamJson == null) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.rikka.appendStream(${quote(streamJson)});", null)
    }

    // 生成结束：落定最后一条，不整页重绘，卡片 iframe 得以保留
    LaunchedEffect(streaming, ready) {
        if (!ready || streaming) return@LaunchedEffect
        lastJson?.let { webViewRef?.evaluateJavascript("window.rikka.appendMessage(${quote(it)});", null) }
    }
}

// 传给页面的 JSON 用单引号包一层再交给 evaluateJavascript
private fun quote(json: String): String = "'" + json.replace("\\", "\\\\").replace("'", "\\'") + "'"

internal fun androidx.compose.ui.graphics.Color.toCssHex(): String =
    String.format("#%06X", toArgb() and 0xFFFFFF)

internal class RikkaNativeBridge {
    // 回填结果用；由 AndroidView 工厂注入
    @Volatile var webView: WebView? = null

    // 页面对象名（前端卡页是 rikka，界面页是 webui）
    @Volatile var pageApi: String = "rikka"

    @Volatile var onSend: ((String) -> Unit)? = null
    @Volatile var onStop: (() -> Unit)? = null
    @Volatile var onRegenerate: (() -> Unit)? = null
    @Volatile var onOpenLink: ((String) -> Unit)? = null
    @Volatile var onSlash: ((String) -> Unit)? = null
    @Volatile var onRequestMenu: ((String) -> Unit)? = null
    @Volatile var onInsertSymbol: ((String) -> Unit)? = null
    @Volatile var onMessageAction: ((String) -> Unit)? = null
    @Volatile var onClipboard: ((String) -> Unit)? = null
    // 同步读取类请求：返回 null 表示自己没有实现，交给异步分支
    @Volatile var onSyncRequest: ((method: String, payload: String) -> String?)? = null
    // 需要落盘/改状态类请求：处理完自行调用 [resolve]
    @Volatile var onAsyncRequest: ((method: String, requestId: String, payload: String) -> Unit)? = null

    // 页面统一入口：rikkaNative.request(method, reque
    @JavascriptInterface
    fun request(method: String, requestId: String, payload: String) {
        val sync = onSyncRequest?.invoke(method, payload)
        if (sync != null) {
            resolve(requestId, sync)
        } else if (onAsyncRequest != null) {
            onAsyncRequest?.invoke(method, requestId, payload)
        } else {
            resolve(requestId, "null")
        }
    }

    // 把结果回填给页面的 Promise（转义后包成 JS 字符串字面量）
    fun resolve(requestId: String, result: String) {
        if (requestId.isBlank()) return
        val escaped = result
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        val view = webView ?: return
        val api = pageApi
        view.post {
            view.evaluateJavascript("window.$api.resolve('$requestId', '$escaped');", null)
        }
    }

    @JavascriptInterface
    fun send(text: String) {
        if (text.isNotBlank()) onSend?.invoke(text)
    }

    @JavascriptInterface
    fun stop() {
        onStop?.invoke()
    }

    @JavascriptInterface
    fun regenerate() {
        onRegenerate?.invoke()
    }

    @JavascriptInterface
    fun openLink(url: String) {
        if (url.isNotBlank()) onOpenLink?.invoke(url)
    }

    // WebView 工具条点击：请求原生打开对应菜单
    @JavascriptInterface
    fun requestMenu(name: String) {
        if (name.isNotBlank()) onRequestMenu?.invoke(name)
    }

    // WebView 符号按钮：把符号插进原生输入框
    @JavascriptInterface
    fun insertSymbol(text: String) {
        onInsertSymbol?.invoke(text)
    }

    // 卡片写剪贴板：Android WebView 的 navigator.clipb
    @JavascriptInterface
    fun clipboard(text: String) {
        onClipboard?.invoke(text)
    }

    // 卡片里的 triggerSlash：/send 之类的斜杠命令回传原生处理
    @JavascriptInterface
    fun slash(command: String) {
        if (command.isNotBlank()) onSlash?.invoke(command)
    }

    @JavascriptInterface
    fun ready() = Unit
}

internal fun buildState(
    messages: List<UIMessage>,
    assistant: Assistant,
    settings: Settings,
    macroJson: String,
    themeJson: String,
    placeholder: String,
    cardStyle: String,
    nodes: List<MessageNode> = emptyList(),
): JsonObject = buildJsonObject {
    put(
        "messages",
        buildJsonArray {
            messages.forEach { message ->
                val node = nodes.find { node -> node.messages.any { it.id == message.id } }
                add(
                    buildMessage(
                        message = message,
                        assistant = assistant,
                        settings = settings,
                        swipeCount = node?.messages?.size ?: 1,
                        swipeIndex = node?.selectIndex ?: 0,
                    ),
                )
            }
        },
    )
    put("placeholder", placeholder)
    // 角色卡自带的 <style> 作为卡片主题下发：这类卡片（如 <mainbody
    put("cardStyle", cardStyle)
    put("macros", kotlinx.serialization.json.Json.parseToJsonElement(macroJson))
    put("theme", kotlinx.serialization.json.Json.parseToJsonElement(themeJson))
}

// 单条消息：按段落切成文本块与前端卡块，页面据此决定气泡还是 iframe
internal fun buildMessage(
    message: UIMessage,
    assistant: Assistant,
    settings: Settings,
    swipeCount: Int = 1,
    swipeIndex: Int = 0,
): JsonObject {
    val isUser = message.role == MessageRole.USER
    val textParts = message.parts.filterIsInstance<UIMessagePart.Text>()
    val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>()
        .joinToString("\n") { it.reasoning }
    val tools = message.parts.filterIsInstance<UIMessagePart.Tool>()
    // 附件类 part 也要进页面：图片、音视频、文档
    val attachments = message.parts.filter {
        it is UIMessagePart.Image || it is UIMessagePart.Video ||
            it is UIMessagePart.Audio || it is UIMessagePart.Document
    }

    val blocks = buildJsonArray {
        attachments.forEach { part ->
            when (part) {
                is UIMessagePart.Image -> add(
                    buildJsonObject {
                        put("type", "image")
                        put("url", part.url.toWebImageUrl())
                    },
                )

                is UIMessagePart.Video -> add(
                    buildJsonObject {
                        put("type", "video")
                        put("url", part.url.toWebImageUrl())
                    },
                )

                is UIMessagePart.Audio -> add(
                    buildJsonObject {
                        put("type", "audio")
                        put("url", part.url.toWebImageUrl())
                    },
                )

                is UIMessagePart.Document -> add(
                    buildJsonObject {
                        put("type", "document")
                        put("name", part.fileName)
                        put("url", part.url.toWebImageUrl())
                    },
                )

                else -> Unit
            }
        }
        textParts.forEach { part ->
            var cardIndex = 0
            splitCardSegments(part.text).forEach { (isCard, segment) ->
                if (isCard) {
                    add(
                        buildJsonObject {
                            put("type", "card")
                            put("index", cardIndex++)
                            put("html", segment)
                        }
                    )
                } else {
                    // 卡片段的围栏标记会落在正文段里，单独成行的围栏要剥掉，
                    val cleaned = stripLoneFences(segment)
                    if (cleaned.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", cleaned)
                            }
                        )
                    }
                }
            }
        }
        tools.forEach { tool ->
            add(
                buildJsonObject {
                    put("type", "tool")
                    put("text", tool.toolName)
                }
            )
        }
    }

    val name = if (isUser) {
        settings.displaySetting.userNickname.ifBlank { "You" }
    } else {
        assistant.name.ifBlank { "Assistant" }
    }
    val avatar = if (isUser) {
        settings.displaySetting.userAvatar.toDisplayUrl()
    } else {
        assistant.avatar.toDisplayUrl()
    }

    return buildJsonObject {
        // 备选回复（swipe）：页面据此显示切换入口
        put("swipeCount", JsonPrimitive(swipeCount))
        put("swipeIndex", JsonPrimitive(swipeIndex))
        put("id", message.id.toString())
        put("role", if (isUser) "user" else "assistant")
        put("name", name)
        put("avatar", avatar)
        put("time", message.createdAt.toJavaLocalDateTime().toMessageTimeString())
        if (reasoning.isNotBlank()) put("reasoning", reasoning)
        put("blocks", blocks)
        put("text", textParts.joinToString("\n") { it.text })
        // 页面用它判断内容是否真的变了，避免每次重组都重建 iframe
        put("signature", (message.parts.toString() + reasoning).hashCode().toString())
    }
}

// 静态宏上下文：与酒馆同名宏对齐，够前端卡直接引用
internal fun buildMacros(
    messages: List<UIMessage>,
    assistant: Assistant,
    settings: Settings,
    modelName: String,
): JsonObject {
    val persona = settings.personas.find { it.id == settings.activePersonaId }
    val lastMessage = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }
        .orEmpty()
    // 角色卡字段优先取结构化的酒馆数据，缺失时退回系统提示词，宏名与酒馆保持一致
    val card = assistant.tavernData
    return buildJsonObject {
        put("char", assistant.name)
        put("user", settings.displaySetting.userNickname.ifBlank { "You" })
        put("persona", persona?.description.orEmpty())
        put("description", card?.description?.takeIf { it.isNotBlank() } ?: assistant.systemPrompt)
        put("personality", card?.personality.orEmpty())
        put("scenario", card?.scenario.orEmpty())
        put("mesExamples", card?.mesExample.orEmpty())
        put("charPrompt", card?.systemPrompt.orEmpty())
        put("model", modelName)
        put("lastMessage", lastMessage)
    }
}

// 页面只认图片地址；表情与占位交给页面用名字首字兜底
private fun Avatar.toDisplayUrl(): String = when (this) {
    is Avatar.Image -> url
    is Avatar.Emoji -> ""
    Avatar.Dummy -> ""
}

// 抽角色卡自带的样式块：前端卡的排版与配色通常写在这里
internal fun extractCardStyle(assistant: Assistant): String {
    val sources = buildList {
        add(assistant.systemPrompt)
        assistant.presetMessages.forEach { message ->
            message.parts.filterIsInstance<UIMessagePart.Text>().forEach { add(it.text) }
        }
        // 开场白是自定义标签（<phone>/<wechat>/<人名>）样式的真正来源
        assistant.tavernData?.firstMessage?.let { add(it) }
        assistant.tavernData?.alternateGreetings?.forEach { add(it) }
        assistant.tavernData?.mesExample?.let { add(it) }
        assistant.tavernData?.systemPrompt?.let { add(it) }
        assistant.tavernData?.description?.let { add(it) }
        assistant.tavernData?.scenario?.let { add(it) }
    }
    val styleRegex = Regex("<style\\b[^>]*>([\\s\\S]*?)</style\\s*>", RegexOption.IGNORE_CASE)
    return sources.mapNotNull { styleRegex.find(it)?.groupValues?.get(1) }
        .joinToString("\n")
        .take(20_000)
}

// 去掉单独成行的围栏标记（``` 或 ```lang），保留正文内容
internal fun stripLoneFences(text: String): String = text
    .lines()
    .filterNot { line -> line.trim().matches(Regex("```[a-zA-Z0-9+-]*")) }
    .joinToString("\n")
    .trim()
