package me.rerere.rikkahub.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface BrowserState {
    data object Closed : BrowserState
    data object Opening : BrowserState
    data class Ready(val page: PageInfo) : BrowserState
    data class Error(val code: String, val message: String) : BrowserState
    data object UserBusy : BrowserState
}

val BrowserState.code: String
    get() = when (this) {
        BrowserState.Closed -> "CLOSED"
        BrowserState.Opening -> "OPENING"
        is BrowserState.Ready -> "READY"
        is BrowserState.Error -> "ERROR"
        BrowserState.UserBusy -> "USER_BUSY"
    }

/** 一次页面快照里对模型有用的全部元信息。 */
data class PageInfo(
    val url: String = "",
    val title: String = "",
    val hash: String = "",
    val settled: Boolean = true,
    val scrollY: Int = 0,
    val scrollHeight: Int = 0,
    val viewportHeight: Int = 0,
)

/** 固定短错误码：模型读一个词就知道下一步做什么，比一句英文便宜。 */
object BrowserError {
    const val NOT_OPEN = "NOT_OPEN"
    const val NOT_READY = "NOT_READY"
    const val NOT_FOUND = "NOT_FOUND"
    const val NOT_VISIBLE = "NOT_VISIBLE"
    const val NEED_LOGIN = "NEED_LOGIN"
    const val NEED_USER_ACTION = "NEED_USER_ACTION"
    const val USER_BUSY = "USER_BUSY"
    const val USER_CLOSED = "USER_CLOSED"
    const val RENDERER_CRASHED = "RENDERER_CRASHED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val SENSITIVE_PAGE = "SENSITIVE_PAGE"
    const val DISABLED = "DISABLED"
    const val HTTP_ERROR = "HTTP_ERROR"
    const val SSL_ERROR = "SSL_ERROR"
    const val EXTERNAL_SCHEME = "EXTERNAL_SCHEME"
    const val REQUIRES_FOREGROUND = "REQUIRES_FOREGROUND"
    const val WEBVIEW_UNAVAILABLE = "WEBVIEW_UNAVAILABLE"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val TIMEOUT = "TIMEOUT"
    const val BAD_ARGUMENT = "BAD_ARGUMENT"
}

/** 工具层的失败：带固定码，直接转成错误 JSON，不往模型返回堆栈。 */
class BrowserFailure(val errorCode: String, override val message: String) : Exception(message)

internal fun browserOkJson(
    state: BrowserState,
    page: PageInfo? = null,
    data: JsonObject? = null,
    hint: String? = null,
    tokensEst: Int? = null,
): String = buildJsonObject {
    put("ok", true)
    put("state", state.code)
    page?.let {
        put(
            "page",
            buildJsonObject {
                put("url", it.url)
                put("title", it.title)
                put("hash", it.hash)
                if (!it.settled) put("settled", false)
            }
        )
    }
    data?.let { put("data", it) }
    hint?.let { put("hint", it) }
    tokensEst?.let { put("tokens_est", it) }
}.toString()

internal fun browserErrorJson(
    code: String,
    message: String,
    state: BrowserState = BrowserRuntime.state.value,
    hint: String? = null,
): String = buildJsonObject {
    put("ok", false)
    put("state", state.code)
    put("code", code)
    put("message", message)
    hint?.let { put("hint", it) }
}.toString()

internal fun JsonObject.putIfNotNull(key: String, value: String?): JsonObject {
    if (value == null) return this
    return JsonObject(this + (key to JsonPrimitive(value)))
}
