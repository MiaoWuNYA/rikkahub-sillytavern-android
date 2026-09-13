package me.rerere.rikkahub.data.memory

import android.util.Log
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.ai.resolveEmbeddingModel
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryType
import me.rerere.rikkahub.data.repository.MemoryRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "MemoryEmbedding"

/**
 * 记忆写入服务：在新增/编辑记忆后同步生成嵌入向量索引。
 * 嵌入失败不影响记忆本身（语义检索不可用时退回词法检索）。
 */
class MemoryEmbeddingService(
    private val repository: MemoryRepository,
    private val providerManager: ProviderManager,
) {
    suspend fun addMemory(
        assistantId: String,
        content: String,
        settings: Settings,
        type: MemoryType = MemoryType.FACT,
        sourceConversationId: String? = null,
    ): AssistantMemory {
        val memory = repository.addMemory(
            assistantId = assistantId,
            content = content,
            type = type,
            sourceConversationId = sourceConversationId,
        )
        index(memory, settings)
        return memory
    }

    suspend fun updateMemory(
        id: Int,
        content: String,
        settings: Settings,
        type: MemoryType? = null,
    ): AssistantMemory {
        val memory = repository.updateMemory(id = id, content = content, type = type)
        index(memory, settings)
        return memory
    }

    /**
     * 重建某助手全部记忆的向量索引。
     * 触发场景：解析出的嵌入模型发生变化（如更换快速模型/嵌入模型），旧向量与新模型
     * 不匹配会被检索层硬过滤，语义 RAG 会静默退化为词法检索——必须用新模型重建。
     */
    suspend fun reindexAssistant(assistantId: String, settings: Settings): Int {
        val (providerSetting, model) = settings.resolveEmbeddingModel() ?: return 0
        val targetModelId = model.id.toString()
        val records = repository.getMemoryRecordsOfAssistant(assistantId)
            .filter { it.memory.content.isNotBlank() && it.embeddingModelId != targetModelId }
        if (records.isEmpty()) return 0
        var indexed = 0
        // 分批：一次请求嵌入一批，失败只影响该批
        records.chunked(EMBED_BATCH_SIZE).forEach { batch ->
            runCatching {
                providerManager.getProviderByType(providerSetting).generateEmbedding(
                    providerSetting = providerSetting,
                    params = EmbeddingGenerationParams(
                        model = model,
                        input = batch.map { it.memory.content },
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                )
            }.onSuccess { result ->
                batch.forEachIndexed { i, record ->
                    result.embeddings.getOrNull(i)?.takeIf { it.isNotEmpty() && it.all(Float::isFinite) }
                        ?.let { vector ->
                            repository.updateEmbedding(
                                id = record.memory.id,
                                embedding = vector.toByteArray(),
                                modelId = targetModelId,
                                dimension = vector.size,
                            )
                            indexed++
                        }
                }
            }.onFailure { error ->
                Log.w(TAG, "Reindex batch failed (${batch.size} records)", error)
            }
        }
        Log.i(TAG, "Reindexed $indexed/${records.size} memories of $assistantId with model $targetModelId")
        return indexed
    }

    private companion object {
        private const val EMBED_BATCH_SIZE = 64
    }

    private suspend fun index(memory: AssistantMemory, settings: Settings) {
        if (memory.content.isBlank()) return
        runCatching {
            // 未显式配置嵌入模型时自动回退（快速模型所在提供商优先）；无可用嵌入模型则跳过索引
            val (providerSetting, model) = settings.resolveEmbeddingModel() ?: return
            val result = providerManager.getProviderByType(providerSetting).generateEmbedding(
                providerSetting = providerSetting,
                params = EmbeddingGenerationParams(
                    model = model,
                    input = listOf(memory.content),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                )
            )
            val vector = result.embeddings.firstOrNull()
                ?.takeIf { it.isNotEmpty() && it.all(Float::isFinite) }
                ?: error("Embedding provider returned an empty or invalid vector")
            repository.updateEmbedding(
                id = memory.id,
                embedding = vector.toByteArray(),
                modelId = model.id.toString(),
                dimension = vector.size,
            )
        }.onFailure { error ->
            // 未配置嵌入模型或请求失败时，基础记忆仍可用（词法检索兜底）
            Log.w(TAG, "Failed to index memory #${memory.id}; lexical retrieval will be used", error)
        }
    }
}

private fun List<Float>.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    forEach(buffer::putFloat)
    return buffer.array()
}
