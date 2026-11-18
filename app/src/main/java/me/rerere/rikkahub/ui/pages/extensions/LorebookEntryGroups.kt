package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.rikkahub.data.model.withOrderedEntries
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection

/** Old books derive names from entries; explicit names also retain empty groups and their order. */
internal fun lorebookEntryGroupNames(book: Lorebook): List<String> =
    (book.entryGroups + book.entries.map { it.group }).filter { it.isNotBlank() }.distinct()

internal fun Lorebook.withLorebookEntries(entries: List<PromptInjection.RegexInjection>): Lorebook = withOrderedEntries(entries)

internal fun createLorebookEntryGroup(book: Lorebook, name: String): Lorebook {
    val group = name.trim()
    require(group.isNotEmpty()) { "分组名称不能为空" }
    require(group !in lorebookEntryGroupNames(book)) { "分组已存在" }
    return book.copy(entryGroups = lorebookEntryGroupNames(book) + group)
}

internal fun groupLabel(group: String) = group.ifBlank { "未分组" }

/** Reorder metadata only: entry IDs, group membership and all per-entry values stay untouched. */
internal fun moveLorebookGroup(book: Lorebook, source: String, target: String, after: Boolean): Lorebook {
    val names = lorebookEntryGroupNames(book)
    if (source == target || source !in names || target !in names) return book
    val remaining = names.filterNot { it == source }.toMutableList()
    remaining.add(remaining.indexOf(target) + if (after) 1 else 0, source)
    return book.copy(entryGroups = remaining)
}

/** Rename membership only; never use one member as a template for the others. */
internal fun renameLorebookExclusiveGroup(book: Lorebook, old: String, name: String): Lorebook {
    val clean = name.trim()
    require(clean.isNotEmpty() && (clean == old || clean !in lorebookEntryGroupNames(book))) { "名称为空或已存在" }
    return book.copy(entryGroups = lorebookEntryGroupNames(book).map { if (it == old) clean else it },
        entries = book.entries.map { if (it.group == old) it.copy(group = clean) else it })
}

/** Dissolving a relationship is not deleting the entities or their competitive configuration. */
internal fun dissolveLorebookExclusiveGroup(book: Lorebook, group: String): Lorebook {
    val released = book.withOrderedEntries(book.entries.map { if (it.group == group) it.copy(group = "", categoryId = null) else it })
    return released.copy(entryGroups = lorebookEntryGroupNames(book) - group)
}
