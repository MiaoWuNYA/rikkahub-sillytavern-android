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
     * 记忆筛选用 noul：判断某条记忆是否与当前对话相关。
     * 每条记忆单独一个 question，id 用 "m0"、"m1"…… 便于批量回填。
     *
     * 阈值刻意放低（0.5）：Jev 是概率校准的，0.5 就是"相关概率过半"。
     * 用常规的 0.7 会把中立但有用的记忆（比如"用户怎么称呼我"这类当前对话
     * 没直接提到、但随时可能用上的身份记忆）全拦在门外，筛选显得"不生效"。
     */
    fun memoryRelevance(memoryContent: String): JevQuestion = JevQuestion(
        type = JevQuestion.TYPE_NOUL,
        instructions = JsonPrimitive(
            """
            这段对话与下面这条记忆是否相关？相关指的是：这条记忆对理解当前对话、
            或对当前该说什么有帮助。身份类记忆（称呼、偏好、习惯）即使当前对话
            没直接提到也算相关。只是同一话题领域但没有实际帮助的，才算不相关。
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
