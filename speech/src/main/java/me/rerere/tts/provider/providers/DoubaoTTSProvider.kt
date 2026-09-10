package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "DoubaoTTSProvider"

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val JSON = Json { ignoreUnknownKeys = true }

/**
 * 豆包语音合成大模型 (火山引擎 openspeech) 适配器。
 *
 * 走 [POST baseUrl] HTTP 非流式接口 (unidirectional), 鉴权用
 * `X-Api-Key` (新版控制台 / Agent Plan 的 ark-xxx Key) + `X-Api-Resource-Id`
 * (seed-tts-2.0 等, 决定模型版本与计费)。
 *
 * 响应体是**多个 JSON 对象直接拼接**而成的流 (非合法整体 JSON), 结构实测:
 * - `{"code":0,"message":"","data":"<base64>"}`: 音频分块, base64 解码后顺序拼接
 * - 中间可能夹杂 sentence/words 元数据对象 (无 data 字段)
 * - 末尾 `{"code":20000000,"message":"OK"}`: 成功结束标志
 * - 其余非 0/20000000 的 code 为错误 (如 55000000 音色与资源 ID 不匹配)
 *
 * Agent Plan 用户必须用 `/api/v3/plan/tts/unidirectional` 专属路径,
 * 标准控制台用户用 `/api/v3/tts/unidirectional`, 两者 Key 不通用。
 *
 * 官方文档: https://www.volcengine.com/docs/6561/1329505
 */
class DoubaoTTSProvider : TTSProvider<TTSProviderSetting.Doubao> {
    private val httpClient = OkHttpClient.Builder()
        // 一次性合成可能比较慢 (长文本), 给足读超时
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Doubao,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = buildJsonObject {
            putJsonObject("user") { put("uid", "rikkahub") }
            putJsonObject("req_params") {
                put("text", request.text)
                put("speaker", providerSetting.speaker)
                putJsonObject("audio_params") {
                    put("format", providerSetting.format)
                    put("sample_rate", providerSetting.sampleRate)
                }
                if (providerSetting.speechRate != 1.0f) {
                    put("speech_rate", providerSetting.speechRate)
                }
            }
        }

        Log.i(TAG, "generateSpeech: speaker=${providerSetting.speaker} format=${providerSetting.format}")

        val httpRequest = Request.Builder()
            .url(providerSetting.baseUrl.trimEnd('/'))
            .addHeader("X-Api-Key", providerSetting.apiKey)
            .addHeader("X-Api-Resource-Id", providerSetting.resourceId)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            val errorBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
            throw TTSProviderException(
                message = "Doubao TTS request failed: HTTP ${response.code} ${response.message}. body=$errorBody",
                statusCode = response.code
            )
        }

        val body = response.body?.string().orEmpty()

        // 解析拼接式 JSON 流: 顺序收集 data 分块, 末尾 code 为最终状态
        val audio = StringBuilder()
        var finalCode = Int.MIN_VALUE
        var finalMessage = ""
        for (obj in splitConcatenatedJsonObjects(body)) {
            val code = obj["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (code != null) {
                finalCode = code
                finalMessage = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            obj["data"]?.jsonPrimitive?.contentOrNull?.let { audio.append(it) }
        }

        if (finalCode != 0 && finalCode != 20000000) {
            throw TTSProviderException(
                message = "Doubao TTS error: code=$finalCode message=$finalMessage"
                    .let { if (audio.isEmpty()) it else "$it (partial audio discarded)" },
                statusCode = 400
            )
        }

        val audioBytes = if (audio.isEmpty()) byteArrayOf() else runCatching {
            Base64.getMimeDecoder().decode(audio.toString())
        }.getOrElse {
            Log.w(TAG, "generateSpeech: base64 decode failed", it)
            byteArrayOf()
        }

        if (audioBytes.isEmpty()) {
            throw TTSProviderException(
                message = "Doubao TTS returned no audio: code=$finalCode message=$finalMessage",
                statusCode = 400
            )
        }

        val audioFormat = when (providerSetting.format.lowercase()) {
            "mp3" -> AudioFormat.MP3
            "wav" -> AudioFormat.WAV
            "pcm" -> AudioFormat.PCM
            "ogg" -> AudioFormat.OGG
            "opus" -> AudioFormat.OPUS
            "aac" -> AudioFormat.AAC
            else -> AudioFormat.MP3
        }

        emit(
            AudioChunk(
                data = audioBytes,
                format = audioFormat,
                sampleRate = providerSetting.sampleRate,
                isLast = true,
                metadata = mapOf(
                    "provider" to "doubao",
                    "resourceId" to providerSetting.resourceId,
                    "speaker" to providerSetting.speaker,
                )
            )
        )
    }
}

/**
 * 拆分拼接式 JSON 对象流 (多个 {} 直接连在一起, 中间可能有换行)。
 * 手动扫描大括号配对并感知字符串/转义, 逐个交给 kotlinx JSON 解析,
 * 解析失败的单个对象直接跳过 (容错)。
 */
private fun splitConcatenatedJsonObjects(source: String): Sequence<kotlinx.serialization.json.JsonObject> = sequence {
    var depth = 0
    var start = -1
    var inString = false
    var escaped = false
    source.forEachIndexed { i, c ->
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            return@forEachIndexed
        }
        when (c) {
            '"' -> { inString = true; escaped = false }
            '{' -> { if (depth == 0) start = i; depth++ }
            '}' -> {
                depth--
                if (depth == 0 && start >= 0) {
                    val segment = source.substring(start, i + 1)
                    start = -1
                    val parsed = runCatching {
                        JSON.parseToJsonElement(segment) as? kotlinx.serialization.json.JsonObject
                    }.getOrNull()
                    if (parsed != null) yield(parsed)
                }
                if (depth < 0) depth = 0
            }
        }
    }
}
