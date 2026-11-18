package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.orderedOrdinaryEntries

/** Final presentation only: scanning, competition and probability checks have already completed. */
internal fun orderOrdinaryLorebookOutput(
    prioritized: List<PromptInjection>,
    books: List<Lorebook>,
): List<PromptInjection> {
    val result = prioritized.toMutableList()
    for (book in books) {
        // Old books without organization metadata retain their original priority-based output.
        if (book.rootOrder.isEmpty() && book.categories.isEmpty()) continue
        val ranks = book.orderedOrdinaryEntries().mapIndexed { index, entry -> entry.id to index }.toMap()
        val slots = prioritized.indices.filter { index ->
            val entry = prioritized[index]
            entry is PromptInjection.RegexInjection && entry.group.isBlank() && entry.id in ranks
        }.groupBy { index -> outputBlock(prioritized[index]) }
        for (indices in slots.values) {
            val ordered = indices.map { prioritized[it] }.sortedBy { ranks.getValue(it.id) }
            indices.forEachIndexed { index, slot -> result[slot] = ordered[index] }
        }
    }
    return result
}

/** Match the existing message builder; different destinations must never exchange entries. */
private data class LorebookOutputBlock(
    val position: InjectionPosition,
    val depth: Int?,
    val role: MessageRole?,
    val outlet: String?,
)

private fun outputBlock(entry: PromptInjection): LorebookOutputBlock {
    val inlineSystem = entry.position == InjectionPosition.BEFORE_SYSTEM_PROMPT ||
        entry.position == InjectionPosition.AFTER_SYSTEM_PROMPT
    val outlet = entry.position == InjectionPosition.OUTLET
    return LorebookOutputBlock(
        position = entry.position,
        depth = entry.injectDepth.takeIf { entry.position == InjectionPosition.AT_DEPTH },
        role = entry.role.takeUnless { inlineSystem || outlet },
        outlet = (entry as? PromptInjection.RegexInjection)?.outletName?.trim().takeIf { outlet },
    )
}
