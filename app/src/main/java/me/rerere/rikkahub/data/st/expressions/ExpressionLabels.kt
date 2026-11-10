package me.rerere.rikkahub.data.st.expressions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ExpressionLabels {

    /** 官方 `DEFAULT_EXPRESSIONS`：28 个标签（GoEmotions 全集），顺序与官方一致 */
    val ALL: List<String> = listOf(
        "admiration",
        "amusement",
        "anger",
        "annoyance",
        "approval",
        "caring",
        "confusion",
        "curiosity",
        "desire",
        "disappointment",
        "disapproval",
        "disgust",
        "embarrassment",
        "excitement",
        "fear",
        "gratitude",
        "grief",
        "joy",
        "love",
        "nervousness",
        "optimism",
        "pride",
        "realization",
        "relief",
        "remorse",
        "sadness",
        "surprise",
        "neutral",
    )

    /** 官方 `DEFAULT_FALLBACK_EXPRESSION` */
    const val DEFAULT_FALLBACK: String = "joy"

    /** 官方 `OPTION_NO_FALLBACK`：兜底 = 不显示任何立绘 */
    const val OPTION_NONE: String = "#none"

    /** 官方 `OPTION_EMOJI_FALLBACK`：兜底 = emoji（rikkaST 暂无 emoji 立绘资源，仅保留语义） */
    const val OPTION_EMOJI: String = "#emoji"

    /** 官方 `DEFAULT_LLM_PROMPT`（index.js:43，逐字） */
    const val DEFAULT_LLM_PROMPT: String =
        "Ignore previous instructions. Classify the emotion of the last message. " +
            "Output just one word, e.g. \"joy\" or \"anger\". " +
            "Choose only one of the following labels: {{labels}}"

    /** 官方 `getLlmPrompt`：`{{labels}}` → `"joy", "anger", ...` */
    fun buildPrompt(labels: List<String>): String =
        DEFAULT_LLM_PROMPT.replace("{{labels}}", labels.joinToString(", ") { "\"$it\"" })

    fun sampleText(text: String): String = text.replace("*", "").replace("\"", "").trim()

    fun resolveDisplayLabel(
        classified: String?,
        fallback: String,
        availableLabels: Collection<String>,
    ): String? {
        if (classified != null && classified in availableLabels) return classified
        if (fallback == OPTION_NONE || fallback == OPTION_EMOJI) return null
        return fallback.takeIf { it in availableLabels }
    }

    /** 是否为合法标签（大小写不敏感；官方 `labels.includes(response)` 为小写精确） */
    fun matchLabel(candidate: String): String? {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return null
        return ALL.firstOrNull { it.equals(trimmed, ignoreCase = true) }
    }

    fun labelFromFileName(fileName: String): String? {
        val base = fileName.substringBeforeLast('.').trim().lowercase()
        if (base.isEmpty()) return null
        val head = base.substringBefore('-').substringBefore('.').trim()
        return ALL.firstOrNull { it == head }
    }

    fun parseResponse(response: String?, labels: List<String> = ALL): String? {
        val raw = response?.trim().orEmpty()
        if (raw.isEmpty() || labels.isEmpty()) return null

        // 1) 官方结构化输出：{"emotion":"joy"}
        runCatching {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val emotion = obj["emotion"]?.jsonPrimitive?.content
            if (!emotion.isNullOrBlank()) {
                labels.firstOrNull { it.equals(emotion.trim(), ignoreCase = true) }?.let { return it }
            }
        }

        // 2) 去掉推理块/代码围栏（官方 removeReasoningFromString 的本地等价）
        val cleaned = raw
            .replace(Regex("(?is)<(think|thinking|reasoning|analysis)>.*?</\\1>"), " ")
            .replace(Regex("(?s)```[a-zA-Z0-9_-]*\\s*"), " ")
            .replace("```", " ")
            .trim()

        // 3) 精确匹配（容忍首尾引号/标点/空白）
        val normalized = cleaned.lowercase().trim()
            .trim('"', '\'', '.', ',', '!', '?', '*', '`', ':', ';', ' ')
        labels.firstOrNull { it == normalized }?.let { return it }

        // 4) 包含匹配（官方 fuse 之后的 includes 兜底；长标签优先，避免 "joy" 命中 "enjoy"）
        val lower = cleaned.lowercase()
        labels.sortedByDescending { it.length }.firstOrNull { label ->
            Regex("(?<![a-z])" + Regex.escape(label) + "(?![a-z])").containsMatchIn(lower)
        }?.let { return it }

        // 5) 取第一个词再试一次（模型偶尔带句号/中文标点）
        val firstWord = lower.split(Regex("[^a-z]+")).firstOrNull { it.isNotBlank() }
        return labels.firstOrNull { it == firstWord }
    }
}
