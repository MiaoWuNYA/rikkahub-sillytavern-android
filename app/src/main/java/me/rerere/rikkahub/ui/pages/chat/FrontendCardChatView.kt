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
    onSlash: (String) -> Unit = {},
    modelName: String = "",
    modifier: Modifier = Modifier,
) {
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

    val macroJson = remember(messages, assistant, settings, modelName) {
        buildMacros(messages, assistant, settings, modelName).toString()
    }
    val stateJson = remember(messages, settings, streaming) {
        buildState(messages, assistant, settings, macroJson, themeJson, placeholder).toString()
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

    // 首帧：主题 + 全量状态
    LaunchedEffect(ready, idsKey) {
        if (!ready) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.rikka.setTheme(${quote(themeJson)});", null)
        webViewRef?.evaluateJavascript("window.rikka.setState(${quote(stateJson)});", null)
    }

    // 流式阶段只追加/更新最后一条
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

/** 传给页面的 JSON 用单引号包一层再交给 evaluateJavascript，内部的引号转义掉即可 */
private fun quote(json: String): String = "'" + json.replace("\\", "\\\\").replace("'", "\\'") + "'"

private fun androidx.compose.ui.graphics.Color.toCssHex(): String =
    String.format("#%06X", toArgb() and 0xFFFFFF)

private class RikkaNativeBridge {
    @Volatile var onSend: ((String) -> Unit)? = null
    @Volatile var onStop: (() -> Unit)? = null
    @Volatile var onRegenerate: (() -> Unit)? = null
    @Volatile var onOpenLink: ((String) -> Unit)? = null
    @Volatile var onSlash: ((String) -> Unit)? = null

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

    /** 卡片里的 triggerSlash：/send 之类的斜杠命令回传原生处理 */
    @JavascriptInterface
    fun slash(command: String) {
        if (command.isNotBlank()) onSlash?.invoke(command)
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
    placeholder: String,
): JsonObject = buildJsonObject {
    put("messages", buildJsonArray { messages.forEach { add(buildMessage(it, assistant, settings)) } })
    put("placeholder", placeholder)
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

/** 页面只认图片地址；表情与占位交给页面用名字首字兜底 */
private fun Avatar.toDisplayUrl(): String = when (this) {
    is Avatar.Image -> url
    is Avatar.Emoji -> ""
    Avatar.Dummy -> ""
}
