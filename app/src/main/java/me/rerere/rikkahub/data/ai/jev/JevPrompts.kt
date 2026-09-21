package me.rerere.rikkahub.data.ai.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 三个用途的 instructions / criteria 构造。
 *
 * 写这些提示词时记住 Jev 的定位：它只做判断，不做生成，也看不到除 state 之外的任何上下文。
 * question id 不会发给模型，所有语义必须写在 instructions 里。
 */
object JevPrompts {

    /**
     * 标题生成用 choice：让 Jev 从若干候选里挑一个，而不是让它写标题。
     *
     * 这是刻意的设计——Jev 不生成文本，让它"写"标题只会得到垃圾。
     * 所以先由本地按对话内容裁出几个候选（取消息首句、关键词），再让 Jev 选。
     */
    fun titleChoice(candidates: List<String>): JevQuestion = JevQuestion(
        type = JevQuestion.TYPE_CHOICE,
        instructions = JsonPrimitive(
            """
            从下面这些候选标题里，选出最能概括这段对话主题的一个。
            候选是用机械规则从对话里裁出来的，可能包含标点残留或半句话，忽略这些瑕疵，
            只看哪个最贴近对话真正在聊什么。若都不合适，选 "other"。
            """.trimIndent()
        ),
        criteria = buildJsonObject {
            candidates.forEach { put(it, JsonPrimitive("候选标题：$it")) }
            put("other", JsonPrimitive("以上候选都不能概括对话主题"))
        },
    )

    /**
     * 记忆筛选用 noul：判断某条记忆是否与当前对话相关。
     * 每条记忆单独一个 question，id 用 "m0"、"m1"…… 便于批量回填。
     */
    fun memoryRelevance(memoryContent: String): JevQuestion = JevQuestion(
        type = JevQuestion.TYPE_NOUL,
        instructions = JsonPrimitive(
            """
            这段对话与下面这条记忆是否相关？相关指的是：这条记忆对理解当前对话、
            或对当前该说什么有帮助。只是同一话题领域但没有实际帮助的，算不相关。
            记忆内容：$memoryContent
            """.trimIndent()
        ),
        criteria = buildJsonObject {
            put("true", JsonPrimitive("这条记忆对当前对话有帮助"))
            put("false", JsonPrimitive("这条记忆对当前对话没有帮助"))
        },
    )

    /** 批量记忆筛选：把 n 条记忆编成 m0..m{n-1}，返回的键就是回填用的 question id */
    fun memoryRelevanceBatch(memories: List<String>): Map<String, JevQuestion> =
        memories.mapIndexed { index, content -> "m$index" to memoryRelevance(content) }.toMap()

    /** judge 工具对外暴露的通用判断入口，三种类型都支持 */
    fun generic(
        type: String,
        instructions: String,
        criteria: JsonElement?,
    ): JevQuestion = JevQuestion(
        type = type,
        instructions = JsonPrimitive(instructions),
        criteria = criteria,
    )

    /** 把任意字符串安全地包成 JsonPrimitive */
    fun text(value: String): JsonElement = JsonPrimitive(value)

    /** state 的最简构造：把一段文本作为 state 发过去 */
    fun stateOf(text: String): JsonElement = JsonPrimitive(text)

    /** 供调试：把 state 读回文本 */
    fun previewState(state: JsonElement): String =
        Json.encodeToString(JsonElement.serializer(), state)
}
