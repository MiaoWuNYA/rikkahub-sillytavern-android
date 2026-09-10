package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory

/**
 * 三层记忆协调：核心身份留在系统提示词（固定记忆区），
 * 跨窗口生活流保持增量注入，长期记忆按当前用户消息的相关性挑选召回。
 */
internal object ThreeLayerMemoryPolicy {
    /**
     * 首轮启动记忆：对话第一轮没有可用的相关性查询（历史为空，词项重叠必然打空），
     * 按新旧程度选取最近的记忆注入，让模型开局就读懂用户；后续轮次再按相关性召回。
     */
    fun selectStartupMemories(
        memories: List<AssistantMemory>,
        limit: Int,
        maxChars: Int,
    ): List<AssistantMemory> {
        if (memories.isEmpty() || limit <= 0 || maxChars <= 0) return emptyList()
        val ranked = memories
            .filter { it.content.isNotBlank() }
            .sortedByDescending { it.id }
        val selected = mutableListOf<AssistantMemory>()
        var chars = 0
        for (memory in ranked) {
            if (selected.size >= limit) break
            val nextChars = memory.content.length
            if (selected.isNotEmpty() && chars + nextChars > maxChars) break
            selected += memory
            chars += nextChars
        }
        return selected
    }

    fun selectLongTermMemories(
        memories: List<AssistantMemory>,
        query: String,
        limit: Int,
        maxChars: Int,
    ): List<AssistantMemory> {
        if (query.isBlank() || memories.isEmpty() || limit <= 0 || maxChars <= 0) return emptyList()
        val queryTerms = terms(query)
        if (queryTerms.isEmpty()) return emptyList()

        val ranked = memories.mapNotNull { memory ->
            val content = memory.content.trim()
            if (content.isBlank()) return@mapNotNull null
            val contentTerms = terms(content)
            val overlap = queryTerms.count { it in contentTerms }
            val phraseBonus = if (content.contains(query.trim(), ignoreCase = true)) 4 else 0
            val score = overlap + phraseBonus
            if (score > 0) memory to score else null
        }.sortedWith(
            compareByDescending<Pair<AssistantMemory, Int>> { it.second }
                .thenByDescending { it.first.id }
        )

        val selected = mutableListOf<AssistantMemory>()
        var chars = 0
        for ((memory, _) in ranked) {
            if (selected.size >= limit) break
            val nextChars = memory.content.length
            if (selected.isNotEmpty() && chars + nextChars > maxChars) break
            if (selected.isEmpty() && nextChars > maxChars) {
                selected += memory.copy(content = memory.content.take(maxChars))
                break
            }
            selected += memory
            chars += nextChars
        }
        return selected
    }

    private fun terms(text: String): Set<String> {
        val normalized = text.lowercase()
        val latinTerms = Regex("[\\p{L}\\p{N}_]{2,}")
            .findAll(normalized)
            .map { it.value }
            .filterNot { token -> token.all { it in '\u4e00'..'\u9fff' } }
        val cjk = normalized.filter { it in '\u4e00'..'\u9fff' }
        val cjkTerms = if (cjk.length >= 2) cjk.windowed(2).asSequence() else emptySequence()
        return (latinTerms + cjkTerms).toSet()
    }
}
