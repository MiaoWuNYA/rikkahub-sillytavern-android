package me.rerere.rikkahub.ui.pages.extensions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.data.model.PromptInjection
import kotlin.uuid.Uuid

internal data class LorebookCopyTask(
    val id: Uuid,
    val sourceBookId: Uuid,
    val entries: List<PromptInjection.RegexInjection>,
) {
    fun preview() = LorebookBatchPreview(id, LorebookBatchMode.COPY, entries)
}

/** Owned by PromptVM, outside the lifetime of any source or destination sheet. */
internal class LorebookCopyState {
    private val lock = Any()
    private val mutablePending = MutableStateFlow<LorebookCopyTask?>(null)
    private val mutableBusy = MutableStateFlow(false)
    val pending = mutablePending.asStateFlow()
    val busy = mutableBusy.asStateFlow()

    fun stage(sourceBookId: Uuid, entries: List<PromptInjection.RegexInjection>) = synchronized(lock) {
        check(!mutableBusy.value && mutablePending.value == null) { "请先完成或取消当前复制任务" }
        require(entries.isNotEmpty() && entries.map { it.id }.distinct().size == entries.size) { "请选择有效条目" }
        mutablePending.value = LorebookCopyTask(Uuid.random(), sourceBookId, entries.toList())
    }

    fun remove(id: Uuid) = synchronized(lock) {
        if (!mutableBusy.value) {
            mutablePending.value = mutablePending.value?.let { task ->
                task.entries.filterNot { it.id == id }.takeIf { it.isNotEmpty() }?.let { task.copy(entries = it) }
            }
        }
    }

    fun cancel() = synchronized(lock) {
        if (!mutableBusy.value) mutablePending.value = null
    }

    suspend fun <T> commit(
        task: LorebookCopyTask,
        ids: Set<Uuid> = task.entries.map { it.id }.toSet(),
        write: suspend () -> T,
    ): T {
        synchronized(lock) {
            check(!mutableBusy.value) { "复制正在保存，请稍候" }
            check(mutablePending.value == task) { "待复制选择已改变，请重新选择落点" }
            require(ids.isNotEmpty() && task.entries.map { it.id }.containsAll(ids)) { "请选择有效条目" }
            mutableBusy.value = true
        }
        try {
            val result = write()
            synchronized(lock) {
                mutablePending.value = task.entries.filterNot { it.id in ids }
                    .takeIf { it.isNotEmpty() }?.let { task.copy(entries = it) }
            }
            return result
        } finally {
            synchronized(lock) { mutableBusy.value = false }
        }
    }
}
