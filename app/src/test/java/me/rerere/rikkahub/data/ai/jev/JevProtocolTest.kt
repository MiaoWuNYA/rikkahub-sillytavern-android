package me.rerere.rikkahub.data.ai.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约测试：拿官方响应样例锁住序列化行为。不联网。
 *
 * 这些字段名很容易在重构时改错（legend 的键是字符串下标、noul 没有 confidence、
 * answers 是按 id 索引的 map），改错了不会崩，只会静默判错——所以必须测。
 */
class JevProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `choice answer parses with probabilities`() {
        val raw = """
            {"model":"jev-1.13.0",
             "answers":{"title":{"type":"choice","choice":"hardware","confidence":0.93,
                                 "probabilities":{"hardware":0.93,"software":0.07}}},
             "usage":{"input_tokens":443,"output_tokens":59}}
        """.trimIndent()
        val response = json.decodeFromString<JevResponse>(raw)
        assertEquals("jev-1.13.0", response.model)
        assertEquals(443, response.usage?.inputTokens)
        val answer = response.answers.getValue("title")
        assertEquals(JevQuestion.TYPE_CHOICE, answer.type)
        assertEquals("hardware", answer.choice)
        assertEquals(0.93, answer.confidence!!, 1e-9)
        assertEquals(2, answer.probabilities?.size)
    }

    @Test
    fun `noul answer has no confidence field`() {
        val raw = """{"answers":{"m0":{"type":"noul","noul":0.81}}}"""
        val answer = json.decodeFromString<JevResponse>(raw).answers.getValue("m0")
        assertEquals(0.81, answer.noul!!, 1e-9)
        // noul 确实不返回 confidence，调用方要靠 |p-0.5|*2 折算
        assertNull(answer.confidence)
    }

    @Test
    fun `score answer exposes legend keyed by string index`() {
        val raw = """
            {"answers":{"mood":{"type":"score","score":2.4,"confidence":0.7,
                                "legend":{"0":"很差","1":"差","2":"一般","3":"好","4":"很好"},
                                "probabilities":{"0":0.05,"2":0.6,"4":0.35}}}}
        """.trimIndent()
        val answer = json.decodeFromString<JevResponse>(raw).answers.getValue("mood")
        assertEquals(2.4, answer.score!!, 1e-9)
        val legend = answer.legend!!
        // 键是字符串下标，取用时自己转 Int
        assertEquals("一般", legend["2"])
        assertEquals(5, legend.size)
        assertTrue(answer.probabilities!!.keys.all { it.toIntOrNull() != null })
    }

    @Test
    fun `unknown answer type and extra fields are dropped without throwing`() {
        val raw = """{"answers":{"x":{"type":"ranking","whatever":1}},"extra_top_level":true}"""
        val response = json.decodeFromString<JevResponse>(raw)
        val answer = response.answers.getValue("x")
        assertEquals("ranking", answer.type)
        assertNull(answer.choice)
        assertNull(answer.score)
        assertNull(answer.noul)
    }

    @Test
    fun `request body only uses the three allowed top level fields`() {
        val request = JevRequest(
            state = buildJsonObject { put("text", JsonPrimitive("hi")) },
            questions = mapOf(
                "q" to JevQuestion(
                    type = JevQuestion.TYPE_NOUL,
                    instructions = JsonPrimitive("是否相关"),
                )
            ),
        )
        val encoded = json.encodeToString(request)
        val keys = Json.parseToJsonElement(encoded)
            .let { it as kotlinx.serialization.json.JsonObject }.keys
        // model 与默认值相同时不序列化（encodeDefaults=false），不能断言它一定存在。
        // 真正要守住的是：不出现 state/model/questions 之外的顶层字段——多一个字段会被服务端拒。
        assertTrue("意外的顶层字段: $keys", keys.all { it in setOf("state", "model", "questions") })
        assertTrue("state 必须存在", "state" in keys)
        assertTrue("questions 必须存在", "questions" in keys)
    }

    @Test
    fun `question ids are request side only`() {
        val request = JevRequest(
            state = JsonPrimitive("s"),
            questions = mapOf("m0" to JevQuestion(type = JevQuestion.TYPE_NOUL)),
        )
        val encoded = json.encodeToString(request)
        // 回归测试：绝不能退化成把 question id 当字段塞进 question 体里
        assertTrue(
            "question id 不能成为 question 自身的字段: $encoded",
            !encoded.contains("\"id\""),
        )
    }

    /** 与 JevClient.equivalentConfidence 保持同一套折算：|p-0.5|*2 */
    @Test
    fun `noul probability maps to equivalent confidence`() {
        fun confidence(p: Double) = kotlin.math.abs(p - 0.5) * 2
        assertEquals(1.0, confidence(1.0), 1e-9)
        assertEquals(0.0, confidence(0.5), 1e-9)
        assertEquals(0.62, confidence(0.81), 1e-9)
    }
}
