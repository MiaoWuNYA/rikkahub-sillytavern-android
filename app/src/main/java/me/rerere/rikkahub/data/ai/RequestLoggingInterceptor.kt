package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer

// 脱敏日志（移植自 Rikkahub-Revised）：不记录凭据、提示词、Schema 与二进制内容
private const val MAX_LOGGED_REQUEST_BODY_BYTES = 64L * 1024L
private const val MAX_LOGGED_ERROR_BODY_BYTES = 16L * 1024L
private const val REDACTED = "[REDACTED]"

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toSafeMap()
        val requestBody = request.body.readSanitizedBody()

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = sanitizeErrorMessage(e.message)
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.toSafeLogUrl(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toSafeMap()
        if (!response.isSuccessful) {
            error = response.readSafeErrorReason()
        }

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.toSafeLogUrl(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private fun Response.readSafeErrorReason(): String {
        val responseBody = runCatching {
            peekBody(MAX_LOGGED_ERROR_BODY_BYTES).string()
        }.getOrNull()
        val reason = responseBody?.trim()?.takeIf(String::isNotEmpty)
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull(String::isNotEmpty)
            ?: return "HTTP $code ${message.takeIf(String::isNotBlank).orEmpty()}".trim()
        return sanitizeErrorMessage(reason)
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.replace(Regex(" {2,}"), " ")
            ?.take(2048)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "HTTP $code ${message.takeIf(String::isNotBlank).orEmpty()}".trim()
    }
}

/** 响应头只保留安全字段，其余一律脱敏 */
internal fun Headers.toSafeMap(): Map<String, String> = names().associateWith { name ->
    if (name.lowercase() in SAFE_HEADER_FIELDS) get(name).orEmpty() else REDACTED
}

/** URL 查询参数中的敏感字段（key/token 等）脱敏 */
internal fun HttpUrl.toSafeLogUrl(): String {
    if (queryParameterNames.none { it.isSensitiveLogField() }) return toString()
    return newBuilder().apply {
        queryParameterNames
            .filter { it.isSensitiveLogField() }
            .forEach { name -> setQueryParameter(name, REDACTED) }
    }.build().toString()
}

private fun RequestBody?.readSanitizedBody(): String? {
    if (this == null) return null
    val length = runCatching { contentLength() }.getOrDefault(-1L)
    if (length < 0L) return "[request body omitted: unknown length]"
    if (length > MAX_LOGGED_REQUEST_BODY_BYTES) return "[request body omitted: $length bytes]"
    return runCatching {
        val buffer = Buffer()
        writeTo(buffer)
        sanitizeRequestBody(buffer.readUtf8())
    }.getOrElse { "[request body omitted: unreadable]" }
}

internal fun sanitizeRequestBody(body: String): String {
    if (body.isBlank()) return body
    val parsed = runCatching { JsonInstant.parseToJsonElement(body) }.getOrNull()
        ?: return "[request body omitted: ${body.length} characters]"
    return sanitizeJsonForLog(parsed).toString()
}

private fun sanitizeJsonForLog(element: JsonElement, fieldName: String? = null): JsonElement {
    if (fieldName?.isSensitiveLogField() == true) return JsonPrimitive(REDACTED)
    if (fieldName != null && fieldName.lowercase() in SCHEMA_FIELDS) {
        return JsonPrimitive("[SCHEMA omitted]")
    }
    if (fieldName != null && fieldName.lowercase() in TEXT_FIELDS && element is JsonPrimitive) {
        val length = element.contentOrNull?.length ?: 0
        return JsonPrimitive("[TEXT omitted: $length characters]")
    }
    if (fieldName != null && fieldName.lowercase() in BINARY_FIELDS && element is JsonPrimitive) {
        val length = element.contentOrNull?.length ?: 0
        return JsonPrimitive("[BINARY omitted: $length characters]")
    }
    if (fieldName != null && fieldName.lowercase() in SEQUENCE_FIELDS) {
        return when (element) {
            is JsonArray -> JsonPrimitive("[${element.size} sequences omitted]")
            else -> JsonPrimitive("[SEQUENCE omitted]")
        }
    }
    if (fieldName != null && fieldName.lowercase() in URI_FIELDS && element is JsonPrimitive) {
        return JsonPrimitive("[URI omitted]")
    }

    return when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) -> put(key, sanitizeJsonForLog(value, key)) }
        }
        is JsonArray -> buildJsonArray {
            element.forEach { add(sanitizeJsonForLog(it, fieldName)) }
        }
        is JsonPrimitive -> if (
            element.isString && fieldName?.lowercase() !in SAFE_STRING_FIELDS
        ) {
            JsonPrimitive("[STRING omitted: ${element.contentOrNull?.length ?: 0} characters]")
        } else {
            element
        }
        JsonNull -> element
    }
}

private fun String.isSensitiveLogField(): Boolean = lowercase()
    .replace("-", "")
    .replace("_", "") in SENSITIVE_FIELDS

private fun sanitizeErrorMessage(message: String?): String? = message
    ?.replace(
        Regex("(?i)((?:[?&]|\\b)(?:key|api[_-]?key|access[_-]?token|token)=)[^&\\s]+"),
        "$1$REDACTED",
    )
    ?.replace(Regex("(?i)(bearer\\s+)[a-z0-9._~+/-]+"), "$1$REDACTED")
    ?.replace(
        Regex("(?i)([\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token)[\"']?\\s*[:=]\\s*[\"'])[^\"']+"),
        "$1$REDACTED",
    )

private val SENSITIVE_FIELDS = setOf(
    "authorization",
    "proxyauthorization",
    "xgoogapikey",
    "apikey",
    "key",
    "accesstoken",
    "refreshtoken",
    "token",
    "cookie",
    "setcookie",
)
private val TEXT_FIELDS = setOf("text", "prompt", "input", "instructions", "content")
private val BINARY_FIELDS = setOf("data", "base64", "bytes")
private val SEQUENCE_FIELDS = setOf("stopsequences", "stop_sequences")
private val SCHEMA_FIELDS = setOf("responsejsonschema", "response_json_schema", "schema")
private val URI_FIELDS = setOf(
    "fileuri",
    "file_uri",
    "url",
    "uri",
    "imageurl",
    "image_url",
    "audiourl",
    "audio_url",
    "videourl",
    "video_url",
)
private val SAFE_HEADER_FIELDS = setOf(
    "accept",
    "accept-encoding",
    "content-encoding",
    "content-length",
    "content-type",
    "date",
    "server",
    "user-agent",
    "x-request-id",
)
private val SAFE_STRING_FIELDS = setOf(
    "model",
    "role",
    "type",
    "mimetype",
    "mime_type",
    "responsemimetype",
    "response_mime_type",
    "thinkinglevel",
    "thinking_level",
    "threshold",
    "category",
    "mediaresolution",
    "media_resolution",
    "level",
    "aspectratio",
    "aspect_ratio",
    "imagesize",
    "image_size",
    "quality",
    "size",
    "background",
    "outputformat",
    "output_format",
    "reasoningeffort",
    "reasoning_effort",
)
