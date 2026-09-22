package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.ThreeLayerMemoryPolicy
import me.rerere.rikkahub.data.ai.jev.JevClient
import me.rerere.rikkahub.data.ai.jev.JevPrompts
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

/** 按用户轮冻结的检索结果缓存上限（超出清空，防长期驻留） */
private const val FROZEN_TURN_CAP = 64
private const val RAG_MEMORY_PROMPT_CHAR_BUDGET = 3_600
private const val EPISODIC_RECENCY_BOOST = 0.08f
private const val EPISODIC_RECENCY_DECAY_DAYS = 30.0
private const val MILLIS_PER_DAY = 86_400_000.0
/** Jev 单次请求的问题数上限（照 JevClient 的保守值），候选再多就分批并行判 */
private const val JEV_SCREENING_BATCH = 32
/** Jev 筛选最多覆盖多少条候选：全量判会把首 token 延迟拉爆，超出的靠原检索兜底 */
private const val JEV_SCREENING_MAX_CANDIDATES = 96
/**
 * 记忆筛选的概率门槛：noul 的 p 本身就是校准过的"相关概率"，0.5 = 过半就收。
 * 注意不能用等效置信度 |p-0.5|*2 再卡一遍 0.5——两个条件叠加等于实际要求
 * p >= 0.75，中立记忆（称呼、偏好这类）照样全被拦掉，筛选看起来"不生效"。
 */
private const val JEV_MEMORY_PROBABILITY_FLOOR = 0.5

/**
 * 记忆 RAG 检索（移植自 Rikkahub-Revised）：
 * 以最近的用户消息为查询，对记忆做嵌入语义检索（失败时退回词法检索），
 * 将最相关的记忆注入 system 消息。开启后不再全量注入记忆列表。
 */
class MemoryRetrievalTransformer(
    private val repository: MemoryRepository,
    private val providerManager: ProviderManager,
    private val memoryEmbeddingService: me.rerere.rikkahub.data.memory.MemoryEmbeddingService,
    private val jevClient: JevClient,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = withContext(Dispatchers.IO) {
        if (!ctx.assistant.enableMemory || !ctx.assistant.enableMemoryRag) {
            return@withContext messages
        }

        // 按用户轮冻结：agentic 工具循环每步重跑本 transformer，检索/排序结果整轮复用，
        // 避免排序微扰导致注入块内容步间变化（改写前缀打断缓存）
        val turnKey = "${ctx.assistant.id}:${ctx.conversationId ?: "no-conversation"}"
        val lastUserMsgId = messages.lastOrNull { it.role == me.rerere.ai.core.MessageRole.USER }
            ?.id?.toString()
        val frozen = frozenTurnPrompt[turnKey]?.takeIf { it.first == lastUserMsgId }
        if (frozen != null) {
            return@withContext insertAfterLastUserMessage(messages, frozen.second)
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
            if (startupPrompt.isNotBlank()) {
                return@withContext storeFrozenAndInsert(turnKey, lastUserMsgId, messages, startupPrompt)
            }
        }

        val semanticMatches = semanticSearch(ctx, records, query)
            .filter { (_, score) -> score > 0f }
        val baseMatches = when {
            // Jev 接管：直接用 Jev 判相关性，省掉 embedding 调用。
            // 空列表（没配置/批次失败/全不相关）都退回原检索兜底，不能静默少注入。
            ctx.settings.huadengSettings.jevTakeoverMemory -> {
                jevScreening(ctx, records, query).ifEmpty { semanticMatches.ifEmpty { lexicalSearch(records, query) } }
            }
            else -> semanticMatches.ifEmpty { lexicalSearch(records, query) }
        }
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
        // 提示词缓存：检索结果每轮随查询变化，必须注入上下文尾部（紧贴最后一条 USER 消息
        // 之后），只失效尾部前缀。旧实现改写第 0 条 system 消息（前缀最顶部），缓存率直接归零。
        storeFrozenAndInsert(turnKey, lastUserMsgId, messages, contextPrompt)
    }

    /** 按用户轮冻结检索结果：首步存储，后续 agentic 步骤直接复用 */
    private fun storeFrozenAndInsert(
        turnKey: String,
        lastUserMsgId: String?,
        messages: List<UIMessage>,
        prompt: String,
    ): List<UIMessage> {
        if (lastUserMsgId != null) {
            if (frozenTurnPrompt.size >= FROZEN_TURN_CAP) frozenTurnPrompt.clear()
            frozenTurnPrompt[turnKey] = lastUserMsgId to prompt
        }
        return insertAfterLastUserMessage(messages, prompt)
    }

    /**
     * 注入位置固定在最后一条 USER 消息之后：agentic 循环每步在列表末尾追加 assistant
     * 消息，若追加到列表末尾，注入块位置每步后移一格，会脱离步骤 1 已缓存的前缀。
     */
    private fun insertAfterLastUserMessage(messages: List<UIMessage>, prompt: String): List<UIMessage> {
        val idx = messages.indexOfLast { it.role == me.rerere.ai.core.MessageRole.USER } + 1
        return if (idx <= 0) {
            messages + UIMessage.system(prompt)
        } else {
            messages.take(idx) + UIMessage.system(prompt) + messages.drop(idx)
        }
    }

    private suspend fun semanticSearch(
        ctx: TransformerContext,
        records: List<MemorySearchRecord>,
        query: String,
    ): List<Pair<MemorySearchRecord, Float>> {
        // 未显式配置嵌入模型时自动回退（快速模型所在提供商优先）；仍无可用嵌入模型则退回词法检索
        val (providerSetting, model) = ctx.settings.resolveEmbeddingModel() ?: return emptyList()

        // 没有任何与当前模型匹配的向量记录时，这次付费 embedding 调用不可能产生结果，直接跳过
        val matchedRecords = records.any { it.embeddingModelId == model.id.toString() && it.embedding != null }
        if (!matchedRecords) {
            // 全部记忆与当前模型不匹配（如换过嵌入模型）：后台用新模型重建索引，
            // 否则语义检索永远静默退化为词法检索。每 (assistant, model) 只触发一次
            maybeScheduleReindex(ctx, records.isNotEmpty())
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

    /**
     * Jev 相关性筛选，替代 embedding 相似度召回。
     *
     * 返回 null 表示"这次不要用 Jev 的结果"——没配置、整批失败、有任意一批没答上来。
     * 调用方据此回退到原检索路径。这么做是刻意的：Jev 是隐形决策层，
     * 宁可整批退回老逻辑，也不能因为漏答就静默少注入记忆。
     *
     * 候选按创建时间倒序取最近 N 条（DAO 查询本身无序，不排序会变成"只看最旧的
     * 几十条"）；超过上限的剩余记录靠调用方的原检索兜底。零通过时也返回空列表
     * 而不是 null：Jev 判全部不相关是合法结论，交回原检索是为了给宽泛查询兜底，
     * 两种情况调用方都会走 `ifEmpty { ... }`，行为一致。
     */
    private suspend fun jevScreening(
        ctx: TransformerContext,
        records: List<MemorySearchRecord>,
        query: String,
    ): List<Pair<MemorySearchRecord, Float>> = withContext(Dispatchers.IO) {
        val candidates = records
            .sortedByDescending { it.memory.createdAt }
            .take(JEV_SCREENING_MAX_CANDIDATES)
        if (candidates.isEmpty()) return@withContext emptyList()

        // 按 JevClient 的单请求问题数上限分批，批次间并行判，延迟不随批数线性涨
        val batches = candidates.chunked(JEV_SCREENING_BATCH)
        val verdicts = batches.map { batch ->
            async {
                runCatching {
                    jevClient.judge(JevPrompts.stateOf(query), JevPrompts.memoryRelevanceBatch(batch.map { it.memory.content }))
                }
            }
        }.awaitAll()
        // 有任意一批失败就整批退回，避免"部分判过"造成注入内容莫名变少
        if (verdicts.any { it.isFailure }) {
            Log.w(TAG, "Jev 记忆筛选有批次失败，回退原检索")
            return@withContext emptyList()
        }

        val floor = JEV_MEMORY_PROBABILITY_FLOOR
        batches.flatMapIndexed { batchIdx, batch ->
            val answered = verdicts[batchIdx].getOrThrow()
            if (answered.size < batch.size) {
                Log.w(TAG, "Jev 记忆筛选批次 #$batchIdx 漏答 ${batch.size - answered.size} 条")
            }
            batch.mapIndexedNotNull { idx, record ->
                val probability = answered["m$idx"]?.noul ?: return@mapIndexedNotNull null
                if (probability < floor) return@mapIndexedNotNull null
                // Jev 不给相关性分数，用概率本身当排序依据（比"全 1f 再按时间排"更贴意图）
                record to probability.toFloat()
            }
        }.sortedByDescending { (record, probability) ->
            probability + applyEpisodicRecencyBoost(record.memory, 0f, System.currentTimeMillis())
        }.map { (record, _) -> record to 1f }
    }

    private fun maybeScheduleReindex(ctx: TransformerContext, hasRecords: Boolean) {
        if (!hasRecords) return
        val modelId = ctx.settings.resolveEmbeddingModel()?.second?.id?.toString() ?: return
        val key = "${ctx.assistant.id}:$modelId"
        if (!reindexTriggered.add(key)) return
        reindexScope.launch {
            runCatching {
                memoryEmbeddingService.reindexAssistant(ctx.assistant.id.toString(), ctx.settings)
            }.onFailure {
                // 失败要允许下轮重试：否则本次进程内 RAG 永远退化为词法检索
                Log.w(TAG, "Background memory reindex failed", it)
                reindexTriggered.remove(key)
            }
        }
    }

    private val reindexTriggered = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** 按用户轮冻结的检索结果：key = assistantId:conversationId，value = (lastUserMsgId, 注入文本) */
    private val frozenTurnPrompt =
        java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    private val reindexScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

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
