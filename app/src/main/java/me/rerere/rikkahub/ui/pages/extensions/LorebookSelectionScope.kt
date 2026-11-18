package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.rikkahub.data.model.PromptInjection
import kotlin.uuid.Uuid

/** Scope is supplied by the user's range selector, never inferred from checked entries. */
internal fun toggleLorebookScope(
    entries: List<PromptInjection.RegexInjection>,
    selected: Set<Uuid>,
    group: String?,
): Set<Uuid> {
    val scoped = lorebookScopeIds(entries, group)
    return if (selected.containsAll(scoped)) selected - scoped else selected + scoped
}

/** Null is the all-entry scope, separate from every possible user-defined group name. */
internal fun lorebookScopeIds(entries: List<PromptInjection.RegexInjection>, group: String?): Set<Uuid> =
    entries.filter { group == null || it.group.ifBlank { "" } == group.ifBlank { "" } }.map { it.id }.toSet()

/** Explicit range identity is independent of every checkbox and user-defined group name. */
internal sealed interface LorebookSelectionScope {
    data object All : LorebookSelectionScope
    data object Unclassified : LorebookSelectionScope
    data class Exclusive(val name: String) : LorebookSelectionScope
    data class Category(val id: Uuid) : LorebookSelectionScope
}

internal fun lorebookScopeIds(entries: List<PromptInjection.RegexInjection>, scope: LorebookSelectionScope): Set<Uuid> =
    entries.filter { entry -> when (scope) {
        LorebookSelectionScope.All -> true
        LorebookSelectionScope.Unclassified -> entry.group.isBlank() && entry.categoryId == null
        is LorebookSelectionScope.Exclusive -> entry.group == scope.name
        is LorebookSelectionScope.Category -> entry.group.isBlank() && entry.categoryId == scope.id
    } }.map { it.id }.toSet()

internal fun toggleLorebookScope(entries: List<PromptInjection.RegexInjection>, selected: Set<Uuid>, scope: LorebookSelectionScope): Set<Uuid> {
    val scoped = lorebookScopeIds(entries, scope)
    return if (selected.containsAll(scoped)) selected - scoped else selected + scoped
}
