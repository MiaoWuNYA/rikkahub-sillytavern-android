package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.jev.JevClient
import me.rerere.rikkahub.data.ai.jev.JevPrompts
import me.rerere.rikkahub.data.ai.jev.JevQuestion
import me.rerere.rikkahub.data.ai.jev.JevResult

/**
 * judge 工具：把 Jev 挂给主力模型，让它在生成过程中也能发起判断。
 *
 * Jev 只判断不生成，所以这个工具返回的是结构化的判断结果而不是文本。模型常见的用法是
 * 在长篇写作中途问一句「这段是否跑题了」「用户现在是什么情绪」，避免为此单独跑一轮生成。
 *
 * 请求失败一律返回 [JevResult.Failed] 的说明而不是抛异常——工具报错会让模型把所有注意力
 * 转到错误处理上，而这里失败本该是无声的。
 */
fun createJudgeTool(jevClient: JevClient): Tool = Tool(
    name = "judge",
    description = "" +
        "Ask a fast judgment model a yes/no, multiple-choice, or rating question about a piece of text. " +
        "It only judges — it never writes content. Returns the answer with a confidence score.\n" +
        "Use for quick checks mid-generation (off-topic? which option fits? rate the current mood). " +
        "Do not use it to summarize or generate text.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("state", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to judge. Include everything the judgment needs — the judge sees nothing else.")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive(JevQuestion.TYPE_NOUL))
                        add(JsonPrimitive(JevQuestion.TYPE_CHOICE))
                        add(JsonPrimitive(JevQuestion.TYPE_SCORE))
                    })
                    put("description", "noul = yes/no; choice = pick one option; score = rate against 2-10 levels")
                })
                put("instructions", buildJsonObject {
                    put("type", "string")
                    put("description", "What to decide. Be explicit — the question id is not sent to the judge.")
                })
                put("options", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Required when type=choice. 2-255 option names (the option names are also the answer values).")
                })
                put("levels", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Required when type=score. 2-10 descriptions, lowest first. The score returned is the 0-based level index plus its probability weight.")
                })
            },
            required = listOf("state", "type", "instructions"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val state = obj["state"]?.jsonPrimitive?.contentOrNull
            ?: error("state required")
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
            ?: error("type required")
        val instructions = obj["instructions"]?.jsonPrimitive?.contentOrNull
            ?: error("instructions required")

        val criteria = buildCriteria(obj, type)
            ?: error("type=$type 需要提供 options（choice）或 levels（score）")

        val question = JevQuestion(
            type = type,
            instructions = JevPrompts.text(instructions),
            criteria = criteria,
        )

        val payload = when (val result = jevClient.judgeOne(JevPrompts.stateOf(state), "q", question)) {
            is JevResult.Ok -> buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("type", JsonPrimitive(result.value.type))
                result.value.choice?.let { put("choice", JsonPrimitive(it)) }
                result.value.score?.let { put("score", JsonPrimitive(it)) }
                result.value.noul?.let { put("noul", JsonPrimitive(it)) }
                result.value.confidence?.let { put("confidence", JsonPrimitive(it)) }
                if (result.value.probabilities.isNotEmpty()) {
                    put("probabilities", buildJsonObject {
                        result.value.probabilities.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    })
                }
            }
            is JevResult.Uncertain -> buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("reason", JsonPrimitive("confidence below threshold"))
                result.confidence?.let { put("confidence", JsonPrimitive(it)) }
                put("threshold", JsonPrimitive(result.fallback))
                put("note", JsonPrimitive("判断不可靠，请自行判断，不要把这个结果当结论"))
            }
            is JevResult.Failed -> buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("reason", JsonPrimitive(result.reason))
                put("note", JsonPrimitive("判断服务不可用，请自行判断，不要重试这个工具"))
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

/** 按 type 把 options / levels 转成 Jev 的 criteria 结构 */
private fun buildCriteria(obj: kotlinx.serialization.json.JsonObject, type: String): JsonElement? =
    when (type) {
        JevQuestion.TYPE_CHOICE -> {
            val options = obj["options"]?.jsonArrayOrNull() ?: return null
            if (options.isEmpty()) return null
            buildJsonObject {
                options.forEach { option -> put(option, JsonPrimitive("选项：$option")) }
            }
        }
        JevQuestion.TYPE_SCORE -> {
            val levels = obj["levels"]?.jsonArrayOrNull() ?: return null
            if (levels.size < 2) return null
            buildJsonArray { levels.forEach { add(JsonPrimitive(it)) } }
        }
        else -> buildJsonObject {
            put("true", JsonPrimitive("成立"))
            put("false", JsonPrimitive("不成立"))
        }
    }

private fun JsonElement.jsonArrayOrNull(): List<String>? =
    (this as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.filter { it.isNotBlank() }

private val JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content
