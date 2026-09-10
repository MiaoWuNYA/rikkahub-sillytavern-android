package me.rerere.rikkahub.data.weixin

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 微信 iLink Bot API 客户端.
 *
 * 直连 ilinkai.weixin.qq.com 的 HTTP/JSON 协议, 无需第三方 SDK.
 *
 * 用双 OkHttpClient: shortClient 处理登录/发消息 (15s 超时),
 * pollClient 专用长轮询 getupdates (38s readTimeout, 服务器最多 hold 35s).
 */
class WeixinBotClient(
    sharedClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val shortClient: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val pollClient: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 长轮询: 服务器最多 hold 35s, 客户端给 38s 余量
        .readTimeout(38, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // ==================== 登录 ====================

    /**
     * 获取登录二维码. 用 bot_type=3.
     * 返回 qrcode (用于后续查状态) 和 qrcode_img_content (二维码内容, 通常是个 URL, 可用 ZXing 渲染成图).
     */
    suspend fun getQrcode(baseUrl: String = DEFAULT_BASE_URL): QrCodeResult = withContext(Dispatchers.IO) {
        val resp = apiGet(baseUrl, "ilink/bot/get_bot_qrcode?bot_type=$BOT_TYPE")
        QrCodeResult(
            qrcode = resp["qrcode"]?.jsonPrimitive?.contentOrNull
                ?: error("get_qrcode: missing qrcode field"),
            qrcodeImgContent = resp["qrcode_img_content"]?.jsonPrimitive?.contentOrNull
                ?: error("get_qrcode: missing qrcode_img_content field"),
        )
    }

    /**
     * 查询扫码状态. 轮询调用, 直到 status == "confirmed".
     * status: "wait" | "scaned" | "expired" | "confirmed".
     * confirmed 时返回 bot_token / baseurl / ilink_bot_id / ilink_user_id.
     *
     * 注意: 这个接口也是长轮询 (服务器在 wait 状态会 hold 住), 用 pollClient (38s 超时).
     * 超时按 "wait" 处理, 调用方继续轮询.
     */
    suspend fun getQrcodeStatus(
        qrcode: String,
        baseUrl: String = DEFAULT_BASE_URL,
    ): QrStatusResult = withContext(Dispatchers.IO) {
        val resp = try {
            apiGetLong(baseUrl, "ilink/bot/get_qrcode_status?qrcode=${urlEncode(qrcode)}")
        } catch (e: java.net.SocketTimeoutException) {
            // 长轮询超时 = 还在等待扫码, 返回 wait
            return@withContext QrStatusResult(status = "wait", botToken = null, baseUrl = null, botId = null, userId = null)
        }
        QrStatusResult(
            status = resp["status"]?.jsonPrimitive?.contentOrNull ?: "wait",
            botToken = resp["bot_token"]?.jsonPrimitive?.contentOrNull,
            baseUrl = resp["baseurl"]?.jsonPrimitive?.contentOrNull,
            botId = resp["ilink_bot_id"]?.jsonPrimitive?.contentOrNull,
            userId = resp["ilink_user_id"]?.jsonPrimitive?.contentOrNull,
        )
    }

    // ==================== 收消息 ====================

    /**
     * 长轮询获取新消息. 服务器最多 hold 35s, 有消息立即返回.
     * [getUpdatesBuf] 是上次返回的游标 (首次传空字符串), 必须每次更新, 否则重复收消息.
     * 超时 (无消息) 时返回空列表 + 原游标.
     */
    suspend fun getUpdates(
        token: String,
        baseUrl: String = DEFAULT_BASE_URL,
        getUpdatesBuf: String,
    ): UpdatesResult = withContext(Dispatchers.IO) {
        val resp = apiPost(
            baseUrl,
            "ilink/bot/getupdates",
            buildJsonObject {
                put("get_updates_buf", getUpdatesBuf)
            },
            token,
            usePollClient = true,
        )
        UpdatesResult(
            ret = resp?.get("ret")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            msgs = resp?.get("msgs")?.jsonArray?.map { it.jsonObject } ?: emptyList(),
            getUpdatesBuf = resp?.get("get_updates_buf")?.jsonPrimitive?.contentOrNull ?: getUpdatesBuf,
        )
    }

    // ==================== 发消息 ====================

    /**
     * 发送文本消息. context_token 必须来自入站消息, 否则消息关联不上对话.
     * 返回 client_id.
     */
    suspend fun sendTextMessage(
        token: String,
        baseUrl: String = DEFAULT_BASE_URL,
        toUserId: String,
        text: String,
        contextToken: String,
    ): String = withContext(Dispatchers.IO) {
        val clientId = "rikkahub-${UUID.randomUUID()}"
        apiPost(
            baseUrl,
            "ilink/bot/sendmessage",
            buildJsonObject {
                putJsonObject("msg") {
                    put("from_user_id", "")
                    put("to_user_id", toUserId)
                    put("client_id", clientId)
                    put("message_type", 2) // BOT 发出
                    put("message_state", 2) // FINISH (完整消息)
                    put("context_token", contextToken)
                    putJsonArray("item_list") {
                        add(buildJsonObject {
                            put("type", 1) // TEXT
                            putJsonObject("text_item") {
                                put("text", text)
                            }
                        })
                    }
                }
            },
            token,
        )
        clientId
    }

    // ==================== HTTP 工具 ====================

    /**
     * X-WECHAT-UIN: 随机 uint32 → 十进制字符串 → base64. 每次请求都变, 防重放.
     */
    private fun randomWechatUin(): String {
        val uint32 = SECURE_RANDOM.nextInt().toLong() and 0xFFFFFFFFL
        return Base64.encodeToString(uint32.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun buildHeaders(token: String?): JsonObject = buildJsonObject {
        put("Content-Type", "application/json")
        put("AuthorizationType", "ilink_bot_token")
        put("X-WECHAT-UIN", randomWechatUin())
        if (token != null) {
            put("Authorization", "Bearer $token")
        }
    }

    private suspend fun apiGet(baseUrl: String, path: String): JsonObject {
        val url = "${baseUrl.trimEnd('/')}/$path"
        val request = Request.Builder().url(url).get().build()
        val response = shortClient.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        Log.d(TAG, "GET $path -> ${response.code}: ${text.take(500)}")
        if (!response.isSuccessful) {
            response.close()
            throw WeixinApiException(response.code, text)
        }
        return json.parseToJsonElement(text).jsonObject
    }

    /** 长轮询版 GET (用 pollClient, 38s 超时). 用于 get_qrcode_status 这类 hold 住的接口. */
    private suspend fun apiGetLong(baseUrl: String, path: String): JsonObject {
        val url = "${baseUrl.trimEnd('/')}/$path"
        val request = Request.Builder().url(url).get().build()
        val response = pollClient.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        Log.d(TAG, "GET(long) $path -> ${response.code}: ${text.take(500)}")
        if (!response.isSuccessful) {
            response.close()
            throw WeixinApiException(response.code, text)
        }
        return json.parseToJsonElement(text).jsonObject
    }

    /**
     * @param usePollClient true 用长轮询客户端 (38s 超时), false 用短客户端.
     * 长轮询超时 (SocketTimeoutException) 返回 null, 调用方按正常空消息处理.
     */
    private suspend fun apiPost(
        baseUrl: String,
        endpoint: String,
        body: JsonObject,
        token: String,
        usePollClient: Boolean = false,
    ): JsonObject? {
        val url = "${baseUrl.trimEnd('/')}/$endpoint"
        // 自动注入 base_info.channel_version (协议要求)
        val payload = JsonObject(body.toMutableMap().apply {
            this["base_info"] = buildJsonObject { put("channel_version", CHANNEL_VERSION) }
        })
        val bodyStr = json.encodeToString(JsonElement.serializer(), payload)
        val headers = buildHeaders(token)
        val request = Request.Builder().url(url).post(
            bodyStr.toRequestBody("application/json".toMediaType())
        ).apply {
            headers.forEach { (k, v) -> header(k, v.jsonPrimitive.content) }
        }.build()

        val client = if (usePollClient) pollClient else shortClient
        return try {
            val response = client.newCall(request).execute()
            val text = response.body?.string().orEmpty()
            Log.d(TAG, "POST $endpoint -> ${response.code}: ${text.take(500)}")
            if (!response.isSuccessful) {
                throw WeixinApiException(response.code, text)
            }
            json.parseToJsonElement(text).jsonObject
        } catch (e: java.net.SocketTimeoutException) {
            // 长轮询超时, 正常
            if (usePollClient) null else throw e
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "WeixinBotClient"
        const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
        private const val BOT_TYPE = "3"
        private const val CHANNEL_VERSION = "1.0.2"
        private val SECURE_RANDOM = SecureRandom()
    }
}

class WeixinApiException(val code: Int, val body: String) :
    RuntimeException("Weixin API $code: ${body.take(500)}")

/**
 * 判断异常信息是否表示登录会话已过期/无效（需要用户重新扫码登录）。
 * 纯函数，便于 JVM 单元测试。
 */
fun isWeixinTokenExpiredError(message: String): Boolean {
    return message.contains("session timeout", ignoreCase = true) ||
        message.contains("-14") ||
        message.contains("401")
}

// ==================== 数据类 ====================

data class QrCodeResult(
    val qrcode: String,        // 用于 getQrcodeStatus 轮询
    val qrcodeImgContent: String, // 二维码内容 (URL), 用 ZXing 渲染成图
)

data class QrStatusResult(
    val status: String,        // wait | scaned | expired | confirmed
    val botToken: String?,
    val baseUrl: String?,
    val botId: String?,
    val userId: String?,
)

data class UpdatesResult(
    val ret: Int,
    val msgs: List<JsonObject>,
    val getUpdatesBuf: String,
)

// ==================== 入站消息解析辅助 ====================

/** 消息 item 类型 (item_list[].type). */
object WeixinItemType {
    const val TEXT = 1
    const val IMAGE = 2
    const val VOICE = 3
    const val FILE = 4
    const val VIDEO = 5
}

/** 消息方向 (message_type). 1=用户发给BOT, 2=BOT发出. */
object WeixinMessageType {
    const val INBOUND = 1
    const val OUTBOUND = 2
}

/**
 * 从入站消息的 item_list 提取纯文本描述.
 * 非文字消息返回占位描述, 方便 AI 至少知道用户发了什么类型.
 */
fun extractInboundText(msg: JsonObject): String {
    val itemList = msg["item_list"]?.jsonArray ?: return "[空消息]"
    for (item in itemList) {
        val obj = item.jsonObject
        when (obj["type"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()) {
            WeixinItemType.TEXT -> {
                val text = obj["text_item"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                if (!text.isNullOrEmpty()) return text
            }
            WeixinItemType.VOICE -> {
                val text = obj["voice_item"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                return "[语音] $text"
            }
            WeixinItemType.IMAGE -> return "[图片]"
            WeixinItemType.FILE -> {
                val name = obj["file_item"]?.jsonObject?.get("file_name")?.jsonPrimitive?.contentOrNull
                return "[文件] ${name ?: ""}"
            }
            WeixinItemType.VIDEO -> return "[视频]"
        }
    }
    return "[空消息]"
}
