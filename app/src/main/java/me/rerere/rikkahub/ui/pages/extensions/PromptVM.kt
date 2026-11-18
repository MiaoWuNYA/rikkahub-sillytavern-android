package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

class PromptVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }

    private val lorebookBatchWriter = LorebookBatchWriter { transform ->
        settingsStore.update { latest -> latest.copy(lorebooks = transform(latest.lorebooks)) }
    }

    internal val lorebookCopies = LorebookCopyState()

    internal suspend fun commitLorebookBatch(request: LorebookBatchRequest) = when (val action = request.action) {
        is LorebookBatchAction.Move -> lorebookBatchWriter.commit(request)
        is LorebookBatchAction.Settings -> lorebookBatchWriter.commit(request)
        is LorebookBatchAction.Delete -> lorebookBatchWriter.commit(request)
        is LorebookBatchAction.Copy -> lorebookCopies.commit(action.task, action.ids) { lorebookBatchWriter.commit(request) }
    }
}
