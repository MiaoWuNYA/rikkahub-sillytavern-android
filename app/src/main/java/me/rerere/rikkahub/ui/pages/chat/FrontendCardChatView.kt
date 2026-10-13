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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.richtext.splitCardSegments
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.rikkahub.utils.toMessageTimeString

/**
 * 前端卡专属模式：整块消息区交给单个 WebView。
 *
 * 前端卡（HTML 文档）在消息列表里逐条挂 WebView 时，硬件层叠加会让 Haze 采样崩溃、
 * 多个 WebView 同时存活的兼容问题也难以穷尽。这里反过来——消息列表本身由 WebView
 * 渲染（assets/frontend-card 的 MD3 页面），前端卡退化成同页 iframe，全屏只有一个
 * WebView 实例；标题栏与输入栏仍由 Compose 提供，交互与普通会话一致。
 *
 * 正则、宏、上下文组装都发生在原生侧（消息进来时已经过 output transformer），
 * 页面只做展示与静态宏展开，避免两套逻辑各算一遍。
 */
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
    modifier: Modifier = Modifier,
) {
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

    val macroJson = remember(messages, assistant, settings) {
        buildMacros(messages, assistant, settings).toString()
    }
    val stateJson = remember(messages, settings, streaming) {
        buildState(messages, assistant, settings, macroJson, themeJson).toString()
    }
    val streamJson = remember(messages, streaming) {
        if (!streaming) null else messages.lastOrNull()?.let { buildMessage(it, assistant, settings).toString() }
    }

    // 发送、停止、重新生成：闭包会被 JS 线程调用，用 State 持有最新实现
    var bridge by remember { mutableStateOf<RikkaNativeBridge?>(null) }
    var ready by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(onSend, onStop, onRegenerate, onOpenLink) {
        bridge?.onSend = onSend
        bridge?.onStop = onStop
        bridge?.onRegenerate = onRegenerate
        bridge?.onOpenLink = onOpenLink
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
                bridge = localBridge
                addJavascriptInterface(localBridge, "rikkaNative")
                loadUrl("file:///android_asset/frontend-card/index.html")
                webViewRef = this
            }
        },
    )

    // 页面加载完成后推主题、宏与上下文；生成结束后回到整表渲染
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.rikka.setTheme(${quote(themeJson)});", null)
        webViewRef?.evaluateJavascript("window.rikka.setState(${quote(stateJson)});", null)
    }

    LaunchedEffect(streamJson, stateJson, ready) {
        if (!ready) return@LaunchedEffect
        if (streamJson != null) {
            webViewRef?.evaluateJavascript("window.rikka.appendStream(${quote(streamJson)});", null)
        } else {
            webViewRef?.evaluateJavascript("window.rikka.finishStream(${quote(stateJson)});", null)
        }
    }
}

/** 传给页面的 JSON 用单引号包一层再交给 evaluateJavascript，内部的引号转义掉即可 */
private fun quote(json: String): String = "'" + json.replace("\\", "\\\\").replace("'", "\\'") + "'"

private fun androidx.compose.ui.graphics.Color.toCssHex(): String =
    String.format("#%06X", toArgb() and 0xFFFFFF)

private class RikkaNativeBridge {
    @Volatile var onSend: ((String) -> Unit)? = null
    @Volatile var onStop: (() -> Unit)? = null
    @Volatile var onRegenerate: (() -> Unit)? = null
    @Volatile var onOpenLink: ((String) -> Unit)? = null

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

    @JavascriptInterface
    fun ready() = Unit
}

private fun buildState(
    messages: List<UIMessage>,
    assistant: Assistant,
    settings: Settings,
    macroJson: String,
    themeJson: String,
): JsonObject = buildJsonObject {
    put("messages", buildJsonArray { messages.forEach { add(buildMessage(it, assistant, settings)) } })
    put("macros", kotlinx.serialization.json.Json.parseToJsonElement(macroJson))
    put("theme", kotlinx.serialization.json.Json.parseToJsonElement(themeJson))
}

/** 单条消息：按段落切成文本块与前端卡块，页面据此决定气泡还是 iframe */
private fun buildMessage(
    message: UIMessage,
    assistant: Assistant,
    settings: Settings,
): JsonObject {
    val isUser = message.role == MessageRole.USER
    val textParts = message.parts.filterIsInstance<UIMessagePart.Text>()
    val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>()
        .joinToString("\n") { it.reasoning }
    val tools = message.parts.filterIsInstance<UIMessagePart.Tool>()

    val blocks = buildJsonArray {
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
                } else if (segment.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", segment)
                        }
                    )
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

/** 静态宏上下文：与酒馆同名宏对齐，够前端卡直接引用 */
private fun buildMacros(
    messages: List<UIMessage>,
    assistant: Assistant,
    settings: Settings,
): JsonObject {
    val persona = settings.personas.find { it.id == settings.activePersonaId }
    val lastMessage = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }
        .orEmpty()
    return buildJsonObject {
        put("char", assistant.name)
        put("user", settings.displaySetting.userNickname.ifBlank { "You" })
        put("persona", persona?.description.orEmpty())
        put("description", assistant.systemPrompt)
        put("scenario", "")
        put("personality", "")
        put("lastMessage", lastMessage)
    }
}

/** 页面只认图片地址；表情与占位交给页面用名字首字兜底 */
private fun Avatar.toDisplayUrl(): String = when (this) {
    is Avatar.Image -> url
    is Avatar.Emoji -> ""
    Avatar.Dummy -> ""
}
