package me.rerere.rikkahub.data.ai.jev

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val TAG = "JevClient"

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** 会话级 Json：响应里可能出现我们没建模的字段，一律忽略而不是抛异常。 */
private val JevJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/**
 * 调用结果。**Uncertain 和 Failed 都必须走原有逻辑**——Jev 是隐形决策层，
 * 判不出来或者调不通的时候必须静默回退，不能影响对话，也不能弹错误。
 */
sealed interface JevResult<out T> {
    /** 判出来了，且置信度达标 */
    data class Ok<T>(val value: T) : JevResult<T>

    /** 判出来了但置信度低于阈值，交给调用方自己决定要不要当 Ok 用 */
    data class Uncertain(val confidence: Double?, val fallback: Double) : JevResult<Nothing>

    /** 没配置、网络失败、解析失败、鉴权失败……一律归到这里 */
    data class Failed(val reason: String) : JevResult<Nothing>
}

/**
 * 单条判断结果，无论哪种 question 类型的统一视图。
 * confidence 为 null 表示该类型本身不提供（noul）。
 */
data class JevVerdict(
    val type: String,
    val choice: String?,
    val score: Double?,
    val noul: Double?,
    val confidence: Double?,
    val probabilities: Map<String, Double>,
)

/**
 * Jev（TypeSafe System One Model）客户端。
 *
 * 只做判断、不生成文本。设计上永远不出现在用户可选的模型列表里，只是华灯设置里的
 * 一个隐形决策层。所有调用点都必须容忍它失败。
 */
class JevClient(
    private val httpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
) {
    private data class Config(val baseUrl: String, val apiKey: String, val threshold: Double)

    /**
     * 返回 null 表示当前配置不可用（Key 未填或地址非法）。
     * 设置页据此显示黄色提示——开关可以打开，但实际不会生效。
     */
    private suspend fun effectiveConfig(): Config? {
        val h = settingsStore.settingsFlow.value.huadengSettings
        val base = h.jevBaseUrl.trim().trimEnd('/').ifEmpty { JEV_DEFAULT_BASE_URL }
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        val key = h.jevApiKey.trim()
        if (key.isEmpty()) return null
        return Config(base, key, h.jevConfidenceThreshold.toDouble())
    }

    /** 设置页用来判断要不要显示黄色提示 */
    suspend fun isConfigured(): Boolean = effectiveConfig() != null

    /**
     * 发一次判断请求。questions 至少一条。
     *
     * 返回 map 的键与传入的 question id 一致；缺失的 id 表示模型没回答，
     * 调用方应把缺失视为失败而不是当成"否"——避免静默改变行为。
     */
    suspend fun judge(
        state: JsonElement,
        questions: Map<String, JevQuestion>,
    ): Map<String, JevVerdict> {
        val config = effectiveConfig() ?: throw JevUnavailableException("Jev 未配置")
        if (questions.isEmpty()) return emptyMap()
        require(questions.size <= JEV_MAX_QUESTIONS) {
            "单次请求最多 $JEV_MAX_QUESTIONS 个问题，当前 ${questions.size} 个，请分批"
        }

        val body = JevJson.encodeToString(
            JevRequest(state = state, questions = questions)
        )
        val url = "${config.baseUrl}/v1/systemone"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = withContext(Dispatchers.IO) { executeWithRetry(request) }
        val responseBody = withContext(Dispatchers.IO) { response.use { it.body?.string().orEmpty() } }
        if (!response.isSuccessful) {
            throw JevHttpException(response.code, summarizeError(responseBody))
        }

        val parsed = try {
            JevJson.decodeFromString<JevResponse>(responseBody)
        } catch (e: Exception) {
            throw JevProtocolException("响应解析失败: ${e.message}", e)
        }

        val answers = parsed.answers
        return questions.keys.mapNotNull { id ->
            answers[id]?.let { answer ->
                id to JevVerdict(
                    type = answer.type,
                    choice = answer.choice,
                    score = answer.score,
                    noul = answer.noul,
                    confidence = answer.confidence,
                    probabilities = answer.probabilities.orEmpty(),
                )
            }
        }.toMap()
    }

    /**
     * 一次判断 + 阈值判定。低于阈值返回 Uncertain，调用方自行决定是否回退。
     * noul 类型没有 confidence 字段，用概率本身离 0.5 的距离折算一个等效置信度。
     */
    suspend fun judgeOne(
        state: JsonElement,
        id: String,
        question: JevQuestion,
    ): JevResult<JevVerdict> {
        val config = try {
            effectiveConfig() ?: throw JevUnavailableException("Jev 未配置")
        } catch (e: Exception) {
            Log.d(TAG, "judgeOne 未配置: ${e.message}")
            return JevResult.Failed("Jev 未配置")
        }
        val verdict = try {
            judge(state, mapOf(id to question))[id]
                ?: throw JevProtocolException("响应缺少 question id=$id")
        } catch (e: Exception) {
            Log.d(TAG, "judgeOne 失败: ${e.message}")
            return JevResult.Failed(e.message ?: "Jev 调用失败")
        }
        val confidence = verdict.confidence ?: equivalentConfidence(verdict)
        if (confidence != null && confidence < config.threshold) {
            return JevResult.Uncertain(confidence, config.threshold)
        }
        return JevResult.Ok(verdict)
    }

    /** noul 没有 confidence：|p - 0.5| * 2 得到 0..1 的等效把握度 */
    private fun equivalentConfidence(verdict: JevVerdict): Double? {
        val p = verdict.noul ?: return null
        return kotlin.math.abs(p - 0.5) * 2
    }

    /**
     * 起标题。
     *
     * Jev 不生成文本，所以不能让它"写"标题——这里由本地按对话内容裁出一组候选，
     * 让它选一个。候选质量决定结果质量，选不出来时调用方回退到标题模型。
     */
    suspend fun judgeTitle(candidates: List<String>, conversationText: String): JevResult<String> {
        val options = candidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (options.isEmpty()) return JevResult.Failed("没有可用候选标题")
        val result = judgeOne(
            state = JevPrompts.stateOf(conversationText),
            id = "title",
            question = JevPrompts.titleChoice(options),
        )
        return when (result) {
            is JevResult.Ok -> {
                val picked = result.value.choice
                when {
                    picked == null -> JevResult.Failed("响应缺少 choice")
                    picked == TITLE_OTHER_OPTION -> JevResult.Failed("Jev 认为候选都不合适")
                    picked !in options -> JevResult.Failed("Jev 返回了候选之外的选项: $picked")
                    else -> JevResult.Ok(picked)
                }
            }
            is JevResult.Uncertain -> result
            is JevResult.Failed -> result
        }
    }

    private fun executeWithRetry(request: Request): okhttp3.Response {
        var attempt = 0
        while (true) {
            val call = httpClient.newCall(request)
            try {
                val response = call.execute()
                if (!shouldRetry(response.code) || attempt >= JEV_MAX_RETRIES) return response
                val waitMs = retryAfterMs(response) ?: backoffMs(attempt)
                response.close()
                Thread.sleep(waitMs)
                attempt++
            } catch (e: IOException) {
                if (attempt >= JEV_MAX_RETRIES) throw e
                Thread.sleep(backoffMs(attempt))
                attempt++
            }
        }
    }

    private fun shouldRetry(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    /** 优先 retry-after-ms（毫秒），其次 Retry-After（秒） */
    private fun retryAfterMs(response: okhttp3.Response): Long? {
        response.header("retry-after-ms")?.toLongOrNull()?.let { return it.coerceIn(0, JEV_BACKOFF_CAP_MS) }
        response.header("Retry-After")?.toLongOrNull()?.let {
            return (it * 1000).coerceIn(0, JEV_BACKOFF_CAP_MS)
        }
        return null
    }

    /** 500ms 起，上限 5s，带 25% 抖动（照抄官方 SDK 的退避策略） */
    private fun backoffMs(attempt: Int): Long {
        val base = (500L shl attempt).coerceAtMost(JEV_BACKOFF_CAP_MS)
        val jitter = (base * 0.25 * Math.random()).toLong()
        return base + jitter
    }

    /**
     * 错误响应不携带任何部分结果，整个请求失败。这里只给日志留一条可读信息，
     * 422 的 detail 是 FastAPI 风格的数组，能看到具体是哪个字段不合法。
     */
    private fun summarizeError(body: String): String = body.take(500)

    companion object {
        /**
         * 官方没有"问题数量"上限，真正的约束是 64k token/请求。
         * 这里设一个保守上限防止一次请求铺得太开，超了要分批。
         */
        const val JEV_MAX_QUESTIONS = 32
        /** 标题选择里代表"都不合适"的选项名，与 JevPrompts.titleChoice 保持一致 */
        const val TITLE_OTHER_OPTION = "other"
        private const val JEV_MAX_RETRIES = 2
        private const val JEV_BACKOFF_CAP_MS = 5_000L
    }
}

class JevUnavailableException(message: String) : Exception(message)
class JevHttpException(val code: Int, message: String) : Exception("HTTP $code: $message")
class JevProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
