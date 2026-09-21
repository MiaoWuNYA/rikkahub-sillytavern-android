package me.rerere.rikkahub.data.ai.jev

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * TypeSafe System One Model（Jev）请求/响应模型。
 *
 * 协议要点（照官方 SDK 里 vendored 的 OpenAPI schema，勿凭印象改）：
 * - 请求体只有 state / model / questions 三个顶层字段，多一个字段会被拒。
 * - questions 是 **map** 不是数组；question id 不会发给模型，只用于取回答案。
 * - type 必须小写（noul / choice / score），写成 Noul 会得到一个有误导性的通用 400。
 * - state 与 instructions 可以是 string | object | array（EntryType），所以用 JsonElement。
 */

const val JEV_DEFAULT_BASE_URL = "https://api.typesafe.ai"
const val JEV_DEFAULT_MODEL = "jev-latest"

/**
 * model 不给默认值：序列化统一用 `encodeDefaults = false`，带默认值的字段会被整段省略，
 * 而服务端把 model 列为必填——省掉它换来的是 422 "Field required"。
 * 这里让它在类型层面就必填，杜绝再被漏掉。
 */
@Serializable
data class JevRequest(
    val state: JsonElement,
    val model: String,
    val questions: Map<String, JevQuestion>,
)

/**
 * 三种 question 共用一个 data class 而不是 sealed class：
 * 它们字段名重叠（type / instructions / criteria），拆开反而要在序列化时做多态分发，
 * 而 criteria 的类型差异（对象 vs 数组）本来就要在构造侧保证，序列化层管不了。
 */
@Serializable
data class JevQuestion(
    val type: String,
    val instructions: JsonElement? = null,
    val criteria: JsonElement? = null,
) {
    companion object {
        const val TYPE_NOUL = "noul"
        const val TYPE_CHOICE = "choice"
        const val TYPE_SCORE = "score"
    }
}

@Serializable
data class JevResponse(
    val model: String? = null,
    val answers: Map<String, JevAnswer> = emptyMap(),
    val usage: JevUsage? = null,
)

@Serializable
data class JevUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)

/**
 * 三种回答也是共用一个类：
 * confidence 只存在于 choice / score 上（noul 没有），所以这里可空，
 * 由调用方按 type 决定能不能用。
 */
@Serializable
data class JevAnswer(
    val type: String = "",
    /** noul：yes 的概率，0..1 */
    val noul: Double? = null,
    /** choice：argmax 出来的选项名 */
    val choice: String? = null,
    /** score：档位的概率加权均值，档位从 0 开始算，所以是个小数 */
    val score: Double? = null,
    val confidence: Double? = null,
    /** score 的回显：{"0": "描述", "1": "描述"}，键是字符串下标 */
    val legend: Map<String, String>? = null,
    val probabilities: Map<String, Double>? = null,
)
