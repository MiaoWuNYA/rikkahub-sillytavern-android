package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.rikkahub.data.model.*
import kotlin.uuid.Uuid

internal fun createLorebookCategory(book: Lorebook, name: String): Lorebook {
    val clean = name.trim()
    require(clean.isNotEmpty()) { "分类名称不能为空" }
    require(book.categories.none { it.name == clean }) { "分类名称已存在" }
    val category = LorebookCategory(name = clean)
    return book.copy(categories = book.categories + category,
        rootOrder = book.orderedNodes() + LorebookOrderNode(categoryId = category.id))
}

internal fun renameLorebookCategory(book: Lorebook, id: Uuid, name: String): Lorebook {
    val clean = name.trim()
    require(clean.isNotEmpty() && book.categories.none { it.id != id && it.name == clean }) { "名称为空或已存在" }
    require(book.categories.any { it.id == id }) { "分类已不存在" }
    return book.copy(categories = book.categories.map { if (it.id == id) it.copy(name = clean) else it })
}

internal fun deleteLorebookCategory(book: Lorebook, id: Uuid): Lorebook {
    val children = book.orderedEntries().filter { it.categoryId == id }
    val nodes = book.orderedNodes().flatMap { if (it.categoryId == id) children.map { e -> LorebookOrderNode(entryId = e.id) } else listOf(it) }
    return book.copy(categories = book.categories.filterNot { it.id == id }, rootOrder = nodes,
        entries = book.entries.map { if (it.categoryId == id) it.copy(categoryId = null) else it })
}

internal fun moveLorebookCategory(book: Lorebook, id: Uuid, placement: LorebookEntryPlacement): Lorebook {
    val nodes = book.orderedNodes()
    val from = LorebookOrderNode(categoryId = id)
    val targetEntry = book.entries.firstOrNull { it.id == placement.targetId }
    val targetCategory = targetEntry?.categoryId ?: placement.rootGroup?.let { Uuid.parse(it) }
        ?: placement.group?.takeIf { it.isNotBlank() }?.let { Uuid.parse(it) }
    val target = if (targetCategory != null) LorebookOrderNode(categoryId = targetCategory) else LorebookOrderNode(entryId = targetEntry?.id)
    if (from !in nodes || target !in nodes || from == target) return book
    val order = nodes.filterNot { it == from }.toMutableList()
    order.add(order.indexOf(target) + if (placement.after) 1 else 0, from)
    return book.copy(rootOrder = order)
}

/** An explicit ordinary destination releases competitive membership, keeping all trigger settings. */
internal fun insertCategoryEntries(remaining: List<PromptInjection.RegexInjection>, selected: List<PromptInjection.RegexInjection>,
    placement: LorebookEntryPlacement, atEnd: Boolean?): List<PromptInjection.RegexInjection> {
    require((remaining + selected).map { it.id }.distinct().size == remaining.size + selected.size) { "条目 ID 冲突" }
    val destination = placement.group?.takeIf { it.isNotBlank() }?.let { Uuid.parse(it) }
    val inserted = selected.map { it.copy(group = "", categoryId = destination) }
    val anchor = placement.rootGroup?.let { Uuid.parse(it) }
    val index = when {
        placement.targetId != null -> {
            val index = remaining.indexOfFirst { it.id == placement.targetId && it.group.isBlank() && it.categoryId == destination }
            require(index >= 0) { "分类落点已改变，请重新选择" }
            index + if (placement.after) 1 else 0
        }
        else -> {
            val matching = remaining.indices.filter { remaining[it].group.isBlank() && remaining[it].categoryId == (anchor ?: destination) }
            if (matching.isEmpty()) remaining.size else if (atEnd ?: placement.after) matching.last() + 1 else matching.first()
        }
    }
    return remaining.take(index) + inserted + remaining.drop(index)
}

/** Apply a stable-ID ordinary root placement without deriving it from Prompt storage order. */
internal fun Lorebook.withPlacedEntries(entries: List<PromptInjection.RegexInjection>, ids: Set<Uuid>, placement: LorebookEntryPlacement?): Lorebook {
    val updated = withOrderedEntries(entries)
    if (placement?.categoryTarget != true) return updated
    val destination = placement.group?.takeIf { it.isNotBlank() }?.let { Uuid.parse(it) }
    require(destination == null || categories.any { it.id == destination }) { "目标分类已不存在" }
    if (destination != null) return updated // Internal order is the entry storage subsequence.
    val moved = entries.filter { it.id in ids }.map { LorebookOrderNode(entryId = it.id) }
    val order = updated.orderedNodes().filterNot { it in moved }.toMutableList()
    val targetEntry = entries.firstOrNull { it.id == placement.targetId }
    val anchor = placement.rootGroup?.let { LorebookOrderNode(categoryId = Uuid.parse(it)) }
        ?: targetEntry?.let { if (it.categoryId != null) LorebookOrderNode(categoryId = it.categoryId) else LorebookOrderNode(entryId = it.id) }
    val at = if (anchor == null) { if (placement.after) order.size else 0 } else {
        require(anchor in order) { "目标位置已改变" }
        order.indexOf(anchor) + if (placement.after) 1 else 0
    }
    order.addAll(at, moved)
    return updated.copy(rootOrder = order)
}

/** Root shortcuts are relative to ordinary entries too, not just other categories. */
internal fun moveLorebookCategoryToEdge(book: Lorebook, id: Uuid, atEnd: Boolean): Lorebook {
    val node = LorebookOrderNode(categoryId = id)
    require(book.categories.any { it.id == id }) { "分类已不存在" }
    val remaining = book.orderedNodes().filterNot { it == node }
    return book.copy(rootOrder = if (atEnd) remaining + node else listOf(node) + remaining)
}
