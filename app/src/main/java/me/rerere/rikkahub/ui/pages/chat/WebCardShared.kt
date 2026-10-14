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
import androidx.compose.ui.unit.dp

internal fun androidx.compose.ui.graphics.Color.toCssHex(): String =
    String.format("#%06X", toArgb() and 0xFFFFFF)

internal class RikkaNativeBridge {
    // 回填结果用；由 AndroidView 工厂注入
    @Volatile var webView: WebView? = null

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
                        nodeId = node?.id?.toString().orEmpty(),
                        favorited = node?.isFavorite ?: false,
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
    nodeId: String = "",
    favorited: Boolean = false,
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
        put("nodeId", JsonPrimitive(nodeId))
        put("id", message.id.toString())
        put("role", if (isUser) "user" else "assistant")
        put("name", name)
        put("avatar", avatar)
        put("time", message.createdAt.toJavaLocalDateTime().toMessageTimeString())
        if (reasoning.isNotBlank()) put("reasoning", reasoning)
        put("blocks", blocks)
        put("text", textParts.joinToString("\n") { it.text })
        // 消息操作排与元信息行需要的附带数据
        put("favorited", JsonPrimitive(favorited))
        message.modelSnapshot?.displayName?.takeIf { it.isNotBlank() }?.let { put("model", JsonPrimitive(it)) }
        message.translation?.takeIf { it.isNotBlank() }?.let { put("translation", JsonPrimitive(it)) }
        message.usage?.let { usage ->
            put(
                "usage",
                buildJsonObject {
                    put("prompt", JsonPrimitive(usage.promptTokens))
                    put("completion", JsonPrimitive(usage.completionTokens))
                    put("cached", JsonPrimitive(usage.cachedTokens))
                    put("total", JsonPrimitive(usage.totalTokens))
                },
            )
        }
        // 技术统计行（tok/s、耗时）要按真实时刻算，页面拿到的是毫秒时间戳
        put("createdAt", JsonPrimitive(message.createdAt.toEpochMillis()))
        message.finishedAt?.let { put("finishedAt", JsonPrimitive(it.toEpochMillis())) }
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

// 抽角色卡自带的样式块：前端卡的排版与配色通常写在这里。
//
// 备选开场白自身不带 <style>（实测：整张卡只有 first_mes 里有 style），它们的外观
// 完全依赖这份助手级 CSS，所以这里必须抽全——每个来源里所有 style 块都要，重复的
// 只留一份，且不做长度截断（旧实现 take(20_000) 正好把一张 20200 字符的卡削掉尾巴）。
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
    return sources
        .flatMap { source -> styleRegex.findAll(source).map { it.groupValues[1].trim() } }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("\n")
}

// 去掉单独成行的围栏标记（``` 或 ```lang），保留正文内容
internal fun stripLoneFences(text: String): String = text
    .lines()
    .filterNot { line -> line.trim().matches(Regex("```[a-zA-Z0-9+-]*")) }
    .joinToString("\n")
    .trim()

// 页面算 tok/s 与耗时用的是毫秒时间戳，时区取本机，与原生 Duration 计算一致
private fun kotlinx.datetime.LocalDateTime.toEpochMillis(): Long =
    toJavaLocalDateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
