package me.rerere.rikkahub.data.qq

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * QQ Bot API v2 HTTP 客户端.
 *
 * 只负责 HTTP 部分: 拿 access_token / 拿 gateway 地址 / 发消息.
 * WebSocket 连接 (收消息 + 心跳) 在 QqBotService 里用 OkHttpClient.newWebSocket 做.
 *
 * 协议参考: QQ 开放平台 bot.q.qq.com wiki.
 *  - 认证: POST https://bots.qq.com/app/getAppAccessToken {appId, clientSecret} → {access_token, expires_in}
 *  - OpenAPI 基址: https://api.sgroup.qq.com (v2 群聊私聊用 /v2 路径)
 *  - 请求头: Authorization: QQBot {access_token}
 *  - gateway: GET /gateway → {url: "wss://..."}
 *  - 发私聊消息(被动回复): POST /v2/users/{openid}/messages {content, msg_type:0, msg_id}
 */
class QqBotClient(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /**
     * 用 AppID + Secret 换 access_token.
     * 返回 TokenResult(access_token, expires_in 秒). 失败抛 QqApiException.
     */
    suspend fun getAccessToken(appId: String, appSecret: String): TokenResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("appId", appId)
                put("clientSecret", appSecret)
            }
            val resp = post(
                "$ACCESS_TOKEN_BASE/getAppAccessToken",
                body,
                token = null, // 拿 token 不需要鉴权
            )
            TokenResult(
                accessToken = resp["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: error("getAppAccessToken: missing access_token, resp=$resp"),
                expiresIn = resp["expires_in"]?.jsonPrimitive?.longOrNull ?: 7200L,
            )
        }

    /**
     * 拿 WebSocket 网关地址. 返回 wss:// URL.
     */
    suspend fun getGateway(token: String): String = withContext(Dispatchers.IO) {
        val resp = get("$OPENAPI_BASE/gateway", token)
        resp["url"]?.jsonPrimitive?.contentOrNull
            ?: error("gateway: missing url, resp=$resp")
    }

    /**
     * 发送私聊消息 (被动回复).
     *  - [openid]: 用户 openid (从入站消息 author.id 取)
     *  - [content]: 回复文本
     *  - [msgId]: 入站消息 id, 被动回复必须带 (5 分钟内有效)
     */
    suspend fun sendPrivateMessage(
        token: String,
        openid: String,
        content: String,
        msgId: String,
    ): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("content", content)
            put("msg_type", 0) // 0 = 文本
            put("msg_id", msgId)
        }
        val resp = post("$OPENAPI_BASE/v2/users/$openid/messages", body, token)
        Log.d(TAG, "sendMessage resp: $resp")
        resp["id"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    // ==================== HTTP 工具 ====================

    private fun authRequest(token: String?): Request.Builder = Request.Builder().apply {
        if (token != null) header("Authorization", "QQBot $token")
    }

    private suspend fun get(url: String, token: String): JsonObject {
        val request = authRequest(token).url(url).get().build()
        val response = httpClient.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        Log.d(TAG, "GET $url -> ${response.code}: ${text.take(500)}")
        if (!response.isSuccessful) throw QqApiException(response.code, text)
        return json.parseToJsonElement(text).jsonObject
    }

    private suspend fun post(url: String, body: JsonObject, token: String?): JsonObject {
        val bodyStr = json.encodeToString(JsonObject.serializer(), body)
        val request = authRequest(token).url(url).post(
            bodyStr.toRequestBody("application/json".toMediaType())
        ).build()
        val response = httpClient.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        Log.d(TAG, "POST $url -> ${response.code}: ${text.take(500)}")
        if (!response.isSuccessful) throw QqApiException(response.code, text)
        return json.parseToJsonElement(text).jsonObject
    }

    companion object {
        private const val TAG = "QqBotClient"
        private const val ACCESS_TOKEN_BASE = "https://bots.qq.com/app"
        // v2 群聊/私聊 OpenAPI 基址
        private const val OPENAPI_BASE = "https://api.sgroup.qq.com"
    }
}

class QqApiException(val code: Int, val body: String) :
    RuntimeException("QQ Bot API $code: ${body.take(500)}")

data class TokenResult(
    val accessToken: String,
    val expiresIn: Long, // 秒
)

/**
 * 判断缓存的 access_token 是否已过期（提前 [EXPIRE_MARGIN_MS] 毫秒判过期，留刷新余量）。
 * 纯函数，便于 JVM 单元测试。
 */
fun isQqAccessTokenExpired(
    accessToken: String,
    accessTokenExpireAt: Long,
    nowMs: Long,
    expireMarginMs: Long = 60_000L,
): Boolean {
    if (accessToken.isBlank()) return true
    return accessTokenExpireAt - expireMarginMs <= nowMs
}
