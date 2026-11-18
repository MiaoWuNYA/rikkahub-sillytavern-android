package me.rerere.rikkahub.data.model

/** Ordinary editor roots only. Competitive groups and Prompt collection retain their own semantics. */
fun Lorebook.orderedNodes(): List<LorebookOrderNode> {
    val groups = categories.map { it.id }
    val singles = entries.filter { it.group.isBlank() && it.categoryId !in groups }.map { LorebookOrderNode(entryId = it.id) }
    val valid = groups.map { LorebookOrderNode(categoryId = it) } + singles
    return (rootOrder.filter { it in valid } + valid).distinct()
}

/** Editor traversal includes each entity once: exclusive members above ordinary roots. */
fun Lorebook.orderedEntries(): List<PromptInjection.RegexInjection> {
    val exclusive = (entryGroups + entries.map { it.group }).filter { it.isNotBlank() }.distinct()
    return exclusive.flatMap { name -> entries.filter { it.group == name } } + orderedOrdinaryEntries()
}

fun Lorebook.orderedOrdinaryEntries(): List<PromptInjection.RegexInjection> {
    val ordinary = entries.filter { it.group.isBlank() }
    val byGroup = ordinary.groupBy { it.categoryId }
    val byId = ordinary.associateBy { it.id }
    return orderedNodes().flatMap { node ->
        node.categoryId?.let { byGroup[it].orEmpty() } ?: listOfNotNull(byId[node.entryId])
    }.distinctBy { it.id }
}

/** Keep organization metadata separate from storage/Prompt ordering and retain empty roots. */
fun Lorebook.withOrderedEntries(values: List<PromptInjection.RegexInjection>): Lorebook {
    if (values == entries) return this
    val updated = values // Existing hidden category metadata is not a reason to overwrite unrelated settings.
    val exclusiveGroups = (entryGroups + entries.map { it.group } + updated.map { it.group }).filter { it.isNotBlank() }.distinct()
    val value = copy(entries = updated, entryGroups = exclusiveGroups)
    val ids = updated.map { it.id }.toSet()
    val categories = categories.map { it.id }.toSet()
    // Hidden exclusive entry roots retain a recoverable ordinary position; they are never displayed.
    val retained = (if (rootOrder.isEmpty()) orderedNodes() else rootOrder).filter { (it.entryId != null && it.entryId in ids) || (it.categoryId != null && it.categoryId in categories) }
    return value.copy(rootOrder = (retained + value.orderedNodes()).distinct())
}

/** Native import creates independent identities in both ID spaces, including root references. */
fun Lorebook.withFreshIds(): Lorebook {
    val ids = entries.associate { it.id to kotlin.uuid.Uuid.random() }
    val categoryIds = categories.associate { it.id to kotlin.uuid.Uuid.random() }
    return copy(id = kotlin.uuid.Uuid.random(),
        entries = entries.map { it.copy(id = ids.getValue(it.id), categoryId = categoryIds[it.categoryId]) },
        categories = categories.map { it.copy(id = categoryIds.getValue(it.id)) },
        rootOrder = rootOrder.map { it.copy(entryId = ids[it.entryId], categoryId = categoryIds[it.categoryId]) })
}
