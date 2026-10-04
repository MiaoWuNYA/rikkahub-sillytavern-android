package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RAG 记忆检索必须有真正的相关性下限。
 *
 * 旧实现是 `filter { (_, score) -> score > 0f }`，等于没有门槛：
 * 余弦相似度 0.01（几乎不相关）也照收，再被 `take(RESULT_LIMIT)` 硬凑
 * 成 6 条塞进上下文。用户看到的就是「提取的记忆跟我这段对话毫无关系」，
 * 而且不用决策模型（Jev）时更难用——因为词法兜底路径的中文二元组切分
 * 噪音更大，命中一个常见词就能拿到分数。
 */
class MemoryRelevanceFloorTest {

    private val source: String by lazy {
        listOf(
            File("src/main/java/me/rerere/rikkahub/data/ai/transformers/MemoryRetrievalTransformer.kt"),
            File("app/src/main/java/me/rerere/rikkahub/data/ai/transformers/MemoryRetrievalTransformer.kt"),
        ).firstOrNull { it.exists() }?.readText().orEmpty()
    }

    @Test
    fun `semantic search has a real similarity floor`() {
        assertTrue("找不到 MemoryRetrievalTransformer.kt", source.isNotEmpty())
        assertFalse(
            "语义检索不能再用 score > 0f 这种等于没有的门槛",
            source.contains(".filter { (_, score) -> score > 0f }"),
        )
        assertTrue(
            "语义检索必须按余弦下限过滤",
            source.contains(".filter { (_, score) -> score >= SEMANTIC_SCORE_FLOOR }"),
        )
        assertTrue("缺少 SEMANTIC_SCORE_FLOOR 常量", source.contains("SEMANTIC_SCORE_FLOOR = 0.35f"))
    }

    @Test
    fun `lexical fallback has a floor too`() {
        assertTrue(
            "词法兜底路径也必须过滤，否则只共用一个词也会被召回",
            source.contains("LEXICAL_SCORE_FLOOR"),
        )
        assertTrue(
            "查询词很少时要求全中，避免单个常见词召回一堆无关记忆",
            source.contains("LEXICAL_FEW_TERMS"),
        )
        assertTrue(
            "词法检索的 filter 必须用 effectiveFloor",
            source.contains(".filter { it.second >= effectiveFloor(searchTerms.size) }"),
        )
    }

    @Test
    fun `few term queries require full match`() {
        assertTrue(
            "effectiveFloor 对少词查询返回 1f",
            source.contains("if (termCount < LEXICAL_FEW_TERMS) 1f else LEXICAL_SCORE_FLOOR"),
        )
    }
}
