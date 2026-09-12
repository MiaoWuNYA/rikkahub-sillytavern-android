package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings

/**
 * 解析记忆 RAG / 世界书向量检索使用的嵌入模型。
 *
 * 解析顺序：
 * 1. 显式设置的 [Settings.vectorStorageModelId]（设置页"向量检索模型"）；
 * 2. 未设置或已失效时自动回退：优先快速模型所在提供商的第一个嵌入模型，
 *    其次任意提供商的第一个嵌入模型。
 *
 * 注意：快速模型是对话模型，无法生成向量（嵌入接口不认），因此"跟随快速模型"
 * 落地为"跟随快速模型所在提供商的嵌入模型"；全库没有任何嵌入模型时返回 null，
 * 调用方应退回词法（关键词）检索。
 */
fun Settings.resolveEmbeddingModel(): Pair<ProviderSetting, Model>? {
    vectorStorageModelId?.let { id ->
        providers.firstOrNull { p -> p.models.any { it.id == id && it.type == ModelType.EMBEDDING } }
            ?.let { p -> return p to p.models.first { it.id == id } }
    }
    val fastProvider = providers.firstOrNull { p -> p.models.any { it.id == fastModelId } }
    val orderedProviders = if (fastProvider != null) listOf(fastProvider) + (providers - fastProvider) else providers
    orderedProviders.forEach { provider ->
        provider.models.firstOrNull { it.type == ModelType.EMBEDDING }?.let { return provider to it }
    }
    return null
}
