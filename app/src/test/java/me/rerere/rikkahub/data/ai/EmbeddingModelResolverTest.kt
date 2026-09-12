package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class EmbeddingModelResolverTest {

    private fun embeddingModel(modelId: String = "text-embedding-3-small") =
        Model(modelId = modelId, displayName = modelId, type = ModelType.EMBEDDING)

    private fun chatModel(modelId: String) = Model(modelId = modelId, displayName = modelId, type = ModelType.CHAT)

    @Test
    fun `explicit vectorStorageModelId wins when available`() {
        val embedding = embeddingModel()
        val provider = ProviderSetting.OpenAI(models = listOf(chatModel("gpt"), embedding))
        val settings = Settings(
            providers = listOf(provider),
            vectorStorageModelId = embedding.id,
        )

        val resolved = settings.resolveEmbeddingModel()

        assertEquals(provider.id, resolved?.first?.id)
        assertEquals(embedding.id, resolved?.second?.id)
    }

    @Test
    fun `falls back to embedding model of fast model provider`() {
        val fast = chatModel("fast-chat")
        val fastEmbedding = embeddingModel("fast-provider-embedding")
        val fastProvider = ProviderSetting.OpenAI(name = "Fast", models = listOf(fast, fastEmbedding))
        val otherEmbedding = embeddingModel("other-provider-embedding")
        val otherProvider = ProviderSetting.OpenAI(name = "Other", models = listOf(otherEmbedding))
        val settings = Settings(
            providers = listOf(otherProvider, fastProvider),
            fastModelId = fast.id,
        )

        val resolved = settings.resolveEmbeddingModel()

        assertEquals(fastProvider.id, resolved?.first?.id)
        assertEquals(fastEmbedding.id, resolved?.second?.id)
    }

    @Test
    fun `falls back to any provider with embedding model when fast provider has none`() {
        val fast = chatModel("fast-chat")
        val fastProvider = ProviderSetting.OpenAI(name = "Fast", models = listOf(fast))
        val otherEmbedding = embeddingModel("other-provider-embedding")
        val otherProvider = ProviderSetting.OpenAI(name = "Other", models = listOf(otherEmbedding))
        val settings = Settings(
            providers = listOf(fastProvider, otherProvider),
            fastModelId = fast.id,
        )

        val resolved = settings.resolveEmbeddingModel()

        assertEquals(otherProvider.id, resolved?.first?.id)
        assertEquals(otherEmbedding.id, resolved?.second?.id)
    }

    @Test
    fun `stale explicit model id falls back to auto selection`() {
        val embedding = embeddingModel()
        val provider = ProviderSetting.OpenAI(models = listOf(embedding))
        val settings = Settings(
            providers = listOf(provider),
            vectorStorageModelId = Uuid.random(),
        )

        val resolved = settings.resolveEmbeddingModel()

        assertEquals(embedding.id, resolved?.second?.id)
    }

    @Test
    fun `returns null when no embedding model exists anywhere`() {
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(chatModel("chat")))),
        )

        assertNull(settings.resolveEmbeddingModel())
    }
}
