package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.ThreeLayerMemoryPolicy
import me.rerere.rikkahub.data.ai.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.resolveEmbeddingModel
import me.rerere.rikkahub.data.model.MemoryType
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemorySearchRecord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.uuid.Uuid

private const val TAG = "MemoryRetrieval"

/**
 * 查询向量短 TTL 记忆化：agentic 工具循环每步都会重跑输入 transformer，
 * 同一查询的 embedding 是付费 API 调用，单轮内（几秒内）复用同一结果即可。
 */
private const val QUERY_VECTOR_TTL_MS = 10_000L
private val queryVectorLock = Any()
private var queryVectorKey: String? = null
private var queryVectorValue: List<Float>? = null
private var queryVectorAt: Long = 0L
private const val RESULT_LIMIT = 6
private const val RAG_MEMORY_PROMPT_CHAR_BUDGET = 3_600
private const val EPISODIC_RECENCY_BOOST = 0.08f
private const val EPISODIC_RECENCY_DECAY_DAYS = 30.0
private const val MILLIS_PER_DAY = 86_400_000.0

/**
 * 记忆 RAG 检索（移植自 Rikkahub-Revised）：
 * 以最近的用户消息为查询，对记忆做嵌入语义检索（失败时退回词法检索），
 * 将最相关的记忆注入 system 消息。开启后不再全量注入记忆列表。
 */
class MemoryRetrievalTransformer(
    private val repository: MemoryRepository,
    private val providerManager: ProviderManager,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = withContext(Dispatchers.IO) {
        if (!ctx.assistant.enableMemory || !ctx.assistant.enableMemoryRag) {
            return@withContext messages
        }
        val query = messages.asReversed()
            .firstOrNull { it.role == me.rerere.ai.core.MessageRole.USER }
            ?.toText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext messages

        val assistantId = if (ctx.assistant.useGlobalMemory) {
            MemoryRepository.GLOBAL_MEMORY_ID
        } else {
            ctx.assistant.id.toString()
        }
        val records = repository.getMemoryRecordsOfAssistant(assistantId)
            .filter { record ->
                record.memory.content.isNotBlank() &&
                    (ctx.assistant.enableEpisodicMemory || record.memory.type == MemoryType.FACT)
            }
        if (records.isEmpty()) return@withContext messages

        // 首轮启动记忆：还没有用户消息得到过回复、没有相关性检索可用，自动注入最近的记忆，
        // 让模型开局就读懂用户；后续轮次再按当前消息的相关性检索。
        // 判定按用户消息数（<=1）：角色卡开场白是 ASSISTANT 消息，若按"无 ASSISTANT"
        // 判定，酒馆对话的第一轮会被误判为后续轮次而跳过启动记忆
        val isFirstTurn = messages.count { it.role == me.rerere.ai.core.MessageRole.USER } <= 1
        if (isFirstTurn) {
            val startup = ThreeLayerMemoryPolicy.selectStartupMemories(
                memories = records.map { it.memory },
                limit = RESULT_LIMIT,
                maxChars = RAG_MEMORY_PROMPT_CHAR_BUDGET,
            )
            val startupPrompt = if (startup.isNotEmpty()) buildMemoryPrompt(
                memories = startup,
                includeEpisodic = true,
                maxChars = RAG_MEMORY_PROMPT_CHAR_BUDGET,
            ) else ""
            if (startupPrompt.isNotBlank()) return@withContext messages + UIMessage.system(startupPrompt)
        }

        val semanticMatches = semanticSearch(ctx, records, query)
            .filter { (_, score) -> score > 0f }
        val baseMatches = semanticMatches.ifEmpty { lexicalSearch(records, query) }
        val nowMs = System.currentTimeMillis()
        val selected = baseMatches
            .map { (record, score) ->
                record to applyEpisodicRecencyBoost(record.memory, score, nowMs)
            }
            .sortedByDescending { (_, score) -> score }
            .take(RESULT_LIMIT)
        if (selected.isEmpty()) return@withContext messages

        val contextPrompt = buildMemoryPrompt(
            memories = selected.map { it.first.memory },
            includeEpisodic = true,
            maxChars = RAG_MEMORY_PROMPT_CHAR_BUDGET,
        )
        if (contextPrompt.isBlank()) return@withContext messages
        // 提示词缓存：检索结果每轮随查询变化，必须注入上下文尾部（本 transformer 在
        // 注入链最后执行，追加即落在最后一条消息之后），只失效尾部前缀。
        // 旧实现改写第 0 条 system 消息（前缀最顶部），缓存率直接归零。
        messages + UIMessage.system(contextPrompt)
    }

    private suspend fun semanticSearch(
        ctx: TransformerContext,
        records: List<MemorySearchRecord>,
        query: String,
    ): List<Pair<MemorySearchRecord, Float>> {
        // 未显式配置嵌入模型时自动回退（快速模型所在提供商优先）；仍无可用嵌入模型则退回词法检索
        val (providerSetting, model) = ctx.settings.resolveEmbeddingModel() ?: return emptyList()

        // 没有任何与当前模型匹配的向量记录时，这次付费 embedding 调用不可能产生结果，直接跳过
        if (records.none { it.embeddingModelId == model.id.toString() && it.embedding != null }) {
            return emptyList()
        }
        return runCatching {
            val queryVector = getOrEmbedQuery(providerSetting, model, ctx.assistant.id, query)
                ?: return@runCatching emptyList()

            records.mapNotNull { record ->
                if (record.embeddingModelId != model.id.toString()) return@mapNotNull null
                val vector = record.embedding?.toFloatArray() ?: return@mapNotNull null
                if (record.embeddingDimension != vector.size) return@mapNotNull null
                val score = cosineSimilarity(queryVector, vector)
                if (score.isFinite()) record to score else null
            }.sortedByDescending { it.second }
        }.getOrElse { error ->
            Log.w(TAG, "Embedding retrieval failed; using lexical fallback", error)
            emptyList()
        }
    }

    private suspend fun getOrEmbedQuery(
        providerSetting: ProviderSetting,
        model: Model,
        assistantId: Uuid,
        query: String,
    ): List<Float>? {
        val cacheKey = "${model.id}:${assistantId}:$query"
        val nowMs = System.currentTimeMillis()
        synchronized(queryVectorLock) {
            if (cacheKey == queryVectorKey && nowMs - queryVectorAt < QUERY_VECTOR_TTL_MS) {
                return queryVectorValue
            }
        }
        val vector = providerManager.getProviderByType(providerSetting).generateEmbedding(
            providerSetting = providerSetting,
            params = EmbeddingGenerationParams(
                model = model,
                input = listOf(query),
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            )
        ).embeddings.firstOrNull() ?: return null
        synchronized(queryVectorLock) {
            queryVectorKey = cacheKey
            queryVectorValue = vector
            queryVectorAt = nowMs
        }
        return vector
    }

    private fun lexicalSearch(
        records: List<MemorySearchRecord>,
        query: String,
    ): List<Pair<MemorySearchRecord, Float>> {
        val terms = query.lowercase()
            .split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.length >= 2 }
        val cjkTerms = query.lowercase()
            .filter(Char::isCjk)
            .windowed(size = 2, step = 1, partialWindows = false)
        val searchTerms = (terms + cjkTerms).distinct()
        return records.map { record ->
            val text = record.memory.content.lowercase()
            val score = if (searchTerms.isEmpty()) {
                if (text.contains(query.lowercase())) 1f else 0f
            } else {
                searchTerms.count(text::contains).toFloat() / searchTerms.size
            }
            record to score
        }.filter { it.second > 0f }.sortedByDescending { it.second }
    }
}

internal fun applyEpisodicRecencyBoost(
    memory: me.rerere.rikkahub.data.model.AssistantMemory,
    score: Float,
    nowMs: Long,
): Float {
    if (score <= 0f || memory.type != MemoryType.EPISODIC || memory.createdAt <= 0L) return score
    val ageDays = ((nowMs - memory.createdAt).coerceAtLeast(0L) / MILLIS_PER_DAY)
    val boost = EPISODIC_RECENCY_BOOST * exp(-ageDays / EPISODIC_RECENCY_DECAY_DAYS).toFloat()
    return score + boost
}

private fun Char.isCjk(): Boolean = this in '぀'..'ヿ' ||
    this in '㐀'..'䶿' ||
    this in '一'..'鿿'

private fun ByteArray.toFloatArray(): FloatArray {
    if (size % 4 != 0) return FloatArray(0)
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buffer.float }
}

private fun cosineSimilarity(left: List<Float>, right: FloatArray): Float {
    if (left.size != right.size || left.isEmpty()) return 0f
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    left.indices.forEach { index ->
        val l = left[index].toDouble()
        val r = right[index].toDouble()
        dot += l * r
        leftNorm += l * l
        rightNorm += r * r
    }
    val denominator = sqrt(leftNorm * rightNorm)
    return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
}
