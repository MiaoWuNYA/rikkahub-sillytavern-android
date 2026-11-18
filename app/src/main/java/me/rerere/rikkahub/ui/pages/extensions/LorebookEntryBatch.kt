package me.rerere.rikkahub.ui.pages.extensions

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.orderedEntries
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import kotlin.uuid.Uuid

internal sealed interface LorebookBatchAction {
    data class Move(val ids: Set<Uuid>) : LorebookBatchAction
    data class Settings(val ids: Set<Uuid>, val patch: LorebookSettingsPatch) : LorebookBatchAction
    data class Delete(val ids: Set<Uuid>) : LorebookBatchAction
    data class Copy(
        val task: LorebookCopyTask,
        val ids: Set<Uuid> = task.entries.map { it.id }.toSet(),
    ) : LorebookBatchAction
}

internal data class LorebookBatchRequest(
    val draft: Lorebook,
    val expected: Lorebook?,
    val action: LorebookBatchAction,
    val placement: LorebookEntryPlacement? = null,
    val atEnd: Boolean? = null,
)

internal fun orderedLorebookSelection(
    entries: List<PromptInjection.RegexInjection>,
    ids: Set<Uuid>,
): List<PromptInjection.RegexInjection> = entries.filter { it.id in ids }

/** Resolve the drop against the remaining entries. No lazy-list index adjustment is needed. */
internal fun insertLorebookBatch(
    remaining: List<PromptInjection.RegexInjection>,
    selected: List<PromptInjection.RegexInjection>,
    placement: LorebookEntryPlacement?,
    atEnd: Boolean?,
): List<PromptInjection.RegexInjection> {
    require(selected.isNotEmpty()) { "没有可插入的条目，请重新选择" }
    if (placement?.categoryTarget == true) return insertCategoryEntries(remaining, selected, placement, atEnd)
    val groups = selected.map { it.group.ifBlank { "" } }.distinct()
    val destination = placement?.group
    require(destination != null || groups.size == 1) { "跨组操作请明确选择目标分组" }
    val inserted = if (destination == null) selected else selected.map { if (it.group == destination) it else it.copy(group = destination, categoryId = if (destination.isNotBlank()) null else it.categoryId) }
    require((remaining + selected).map { it.id }.distinct().size == remaining.size + selected.size) {
        "条目 ID 冲突，未修改世界书"
    }
    val index = when {
        atEnd != null -> if (atEnd) remaining.size else 0
        placement != null -> {
            val group = destination ?: groups.single()
            if (placement.targetId == null) {
                require(destination != null) { "请选择目标分组" }
                val matching = remaining.indices.filter { remaining[it].group.ifBlank { "" } == group.ifBlank { "" } }
                if (matching.isEmpty()) remaining.size else if (placement.after) matching.last() + 1 else matching.first()
            } else {
            val target = remaining.indexOfFirst { it.id == placement.targetId && it.group.ifBlank { "" } == group.ifBlank { "" } }
            require(target >= 0) { "插入目标已改变或不属于同一分组，请重新选择落点" }
            target + if (placement.after) 1 else 0
            }
        }
        else -> {
            require(remaining.none { it.group.ifBlank { "" } == groups.single() }) { "请先选择插入位置" }
            remaining.size
        }
    }
    return remaining.take(index) + inserted + remaining.drop(index)
}

internal fun applyLorebookBatch(books: List<Lorebook>, request: LorebookBatchRequest): List<Lorebook> {
    val current = books.firstOrNull { it.id == request.draft.id }
    require(current == request.expected) { "目标世界书已被修改，请重新打开后选择落点；暂存条目仍保留" }
    val entries = when (val action = request.action) {
        is LorebookBatchAction.Settings -> {
            require(action.ids.isNotEmpty() && request.draft.entries.count { it.id in action.ids } == action.ids.size) { "设置选择已改变，请重新选择" }
            require(action.patch.fields.isNotEmpty()) { "请选择要修改的字段" }
            request.draft.entries.map { if (it.id in action.ids) action.patch.apply(it) else it }
        }
        is LorebookBatchAction.Delete -> {
            require(action.ids.isNotEmpty() && request.draft.entries.count { it.id in action.ids } == action.ids.size) {
                "删除选择已改变，请重新选择条目"
            }
            request.draft.entries.filterNot { it.id in action.ids }
        }
        is LorebookBatchAction.Move -> {
            val selected = orderedLorebookSelection(request.draft.orderedEntries(), action.ids)
            require(selected.size == action.ids.size) { "选中的条目已删除，请取消并重新选择" }
            val placement = request.placement
            if (placement != null && !placement.categoryTarget && !placement.group.isNullOrBlank() && placement.targetId == null)
                request.draft.entries.map { if (it.id in action.ids) it.copy(group = placement.group, categoryId = null) else it }
            else insertLorebookBatch(request.draft.entries.filterNot { it.id in action.ids }, selected, placement, request.atEnd)
        }
        is LorebookBatchAction.Copy -> {
            require(action.task.sourceBookId != request.draft.id) { "请选择另一份世界书作为复制目标" }
            require(books.any { it.id == action.task.sourceBookId }) { "来源世界书已不存在，请取消后重新选择" }
            require(action.task.entries.map { it.id }.distinct().size == action.task.entries.size) { "待复制条目 ID 重复，请重新选择" }
            require(action.ids.isNotEmpty() && action.task.entries.map { it.id }.containsAll(action.ids)) {
                "待复制选择已改变，请重新选择落点"
            }
            val occupied = (books.flatMap { it.entries } + request.draft.entries + action.task.entries)
                .mapTo(mutableSetOf()) { it.id }
            val copies = orderedLorebookSelection(action.task.entries, action.ids).map { entry ->
                var id = Uuid.random()
                while (!occupied.add(id)) id = Uuid.random()
                entry.copy(id = id, categoryId = null)
            }
            insertLorebookBatch(request.draft.entries, copies, request.placement, request.atEnd)
        }
    }
    val placedIds = when (val action = request.action) {
        is LorebookBatchAction.Move -> action.ids
        is LorebookBatchAction.Copy -> entries.map { it.id }.toSet() - request.draft.entries.map { it.id }.toSet()
        else -> emptySet()
    }
    val edited = request.draft.withPlacedEntries(entries, placedIds, request.placement)
    return if (current == null) books + edited else books.map { if (it.id == edited.id) edited else it }
}

/** Wait for persistence before consuming a task. A failed write restores only this transaction's target. */
internal class LorebookBatchWriter(
    private val update: suspend ((List<Lorebook>) -> List<Lorebook>) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun commit(request: LorebookBatchRequest): Lorebook = mutex.withLock {
        var applied: Lorebook? = null
        try {
            update { latest ->
                val updated = applyLorebookBatch(latest, request)
                applied = updated.first { it.id == request.draft.id }
                updated
            }
            checkNotNull(applied)
        } catch (failure: Exception) {
            // SettingsStore publishes memory before disk I/O. Do not leave an unconfirmed insertion
            // there after an I/O error, and never overwrite a newer edit made by another caller.
            applied?.let { written ->
                withContext(NonCancellable) {
                    runCatching {
                        update { latest ->
                            if (latest.firstOrNull { it.id == written.id } != written) latest
                            else if (request.expected == null) latest.filterNot { it.id == written.id }
                            else latest.map { if (it.id == written.id) request.expected else it }
                        }
                    }.exceptionOrNull()?.let(failure::addSuppressed)
                }
            }
            throw failure
        }
    }
}
