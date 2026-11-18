package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.rikkahub.data.model.PromptInjection
import kotlin.uuid.Uuid

/** A single drop commits once using the same group-aware insertion planner as staged batches. */
internal fun moveLorebookEntry(
    entries: List<PromptInjection.RegexInjection>,
    fromId: Uuid,
    placement: LorebookEntryPlacement,
): List<PromptInjection.RegexInjection> {
    val entry = entries.firstOrNull { it.id == fromId } ?: return entries
    if (placement.targetId == fromId) return entries
    if (!placement.categoryTarget && !placement.group.isNullOrBlank() && placement.targetId == null)
        return entries.map { if (it.id == fromId) it.copy(group = placement.group, categoryId = null) else it }
    val destination = if (placement.group == entry.group.ifBlank { "" }) placement.copy(group = entry.group) else placement
    return runCatching { insertLorebookBatch(entries.filterNot { it.id == fromId }, listOf(entry), destination, null) }
        .getOrDefault(entries)
}

internal data class LorebookEntryPlacement(val targetId: Uuid?, val after: Boolean, val group: String? = null, val rootGroup: String? = null, val categoryTarget: Boolean = false) {
    companion object {
        fun groupEdge(group: String, atEnd: Boolean) = LorebookEntryPlacement(null, atEnd, group)
    }
}

/** Resolve by ID against the latest draft, never by a potentially stale lazy-list index. */
internal fun insertLorebookEntry(
    entries: List<PromptInjection.RegexInjection>,
    pending: PromptInjection.RegexInjection,
    placement: LorebookEntryPlacement?,
): List<PromptInjection.RegexInjection> {
    if (entries.any { it.id == pending.id }) return entries
    return runCatching { insertLorebookBatch(entries, listOf(pending), placement, null) }.getOrDefault(entries)
}
