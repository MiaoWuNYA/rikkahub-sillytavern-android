package me.rerere.rikkahub.plugin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginFolder
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import java.io.File

/**
 * 插件 ViewModel
 */
class PluginViewModel(
    private val pluginManager: PluginManager
) : ViewModel() {

    // 插件列表
    val plugins: StateFlow<List<PluginInfo>> = pluginManager.plugins

    // 加载状态
    val isLoading: StateFlow<Boolean> = pluginManager.isLoading

    val folders: StateFlow<List<PluginFolder>> = pluginManager.folders

    // 导入状态
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    // 预览导入状态（用于权限确认对话框）
    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    // 操作状态
    private val _operationState = MutableStateFlow<OperationState>(OperationState.Idle)
    val operationState: StateFlow<OperationState> = _operationState.asStateFlow()

    data class PendingImport(
        val manifest: PluginManifest,
        val tempDir: File
    )

    /**
     * 刷新插件列表
     */
    fun refreshPlugins() {
        viewModelScope.launch {
            pluginManager.refreshPlugins()
        }
    }

    /**
     * 预览插件（解析 manifest，不解压到插件目录）
     */
    fun previewPlugin(uri: android.net.Uri) {
        viewModelScope.launch {
            _importState.value = ImportState.Loading
            try {
                val result = pluginManager.previewPlugin(uri)
                if (result.isSuccess) {
                    val pair = result.getOrThrow()
                    _pendingImport.value = PendingImport(pair.first, pair.second)
                    _importState.value = ImportState.Idle
                } else {
                    _importState.value = ImportState.Error(
                        result.exceptionOrNull()?.message ?: "parse plugin failed"
                    )
                }
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 确认导入插件（在 previewPlugin 后调用）
     */
    fun confirmImport() {
        viewModelScope.launch {
            val pending = _pendingImport.value ?: return@launch
            _importState.value = ImportState.Loading
            try {
                val result = pluginManager.confirmImport(pending.manifest, pending.tempDir)
                _importState.value = if (result.isSuccess) {
                    ImportState.Success(result.getOrThrow())
                } else {
                    ImportState.Error(result.exceptionOrNull()?.message ?: "import failed")
                }
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Unknown error")
            } finally {
                _pendingImport.value = null
            }
        }
    }

    /**
     * 取消导入预览
     */
    fun cancelImportPreview() {
        _pendingImport.value?.tempDir?.deleteRecursively()
        _pendingImport.value = null
    }

    /**
     * 获取指定插件
     */
    fun getPlugin(pluginId: String): PluginInfo? {
        return plugins.value.find { it.manifest.id == pluginId }
    }

    /**
     * 删除插件
     */
    fun deletePlugin(pluginId: String) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading
            try {
                val success = pluginManager.deletePlugin(pluginId)
                _operationState.value = if (success) {
                    OperationState.Success("Plugin deleted")
                } else {
                    OperationState.Error("Failed to delete plugin")
                }
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 切换插件启用状态
     */
    fun togglePlugin(pluginId: String, enabled: Boolean) {
        viewModelScope.launch {
            pluginManager.togglePlugin(pluginId, enabled)
        }
    }

    /**
     * 更新插件配置
     */
    fun updatePluginConfig(pluginId: String, config: Map<String, JsonElement>) {
        viewModelScope.launch {
            pluginManager.updatePluginConfig(pluginId, config)
        }
    }

    /**
     * 详情页数据卡片。
     *
     * 卡片内容是插件自己算的（比如今日卡路里），打开详情页时拉一次。失败一律当作
     * 没有卡片——插件没登录、函数写错、超时，都不该在详情页上糊一块红字。
     */
    private val _detailCard = MutableStateFlow<PluginDetailCard?>(null)
    val detailCard: StateFlow<PluginDetailCard?> = _detailCard.asStateFlow()

    fun loadDetailCard(pluginId: String, exportName: String) {
        _detailCard.value = null
        viewModelScope.launch {
            pluginManager.awaitInitialization()
            val result = pluginManager.callExport(pluginId, exportName)
            _detailCard.value = result.getOrNull()?.let { PluginDetailCard.parse(it) }
        }
    }

    fun clearDetailCard() {
        _detailCard.value = null
    }

    /**
     * 获取插件目录
     */
    fun getPluginsDirectory() = pluginManager.getPluginsDirectory()

    /**
     * 重置导入状态
     */
    fun resetImportState() {
        _importState.value = ImportState.Idle
    }

    /**
     * 重置操作状态
     */
    fun resetOperationState() {
        _operationState.value = OperationState.Idle
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading
            try {
                pluginManager.createFolder(name)
                _operationState.value = OperationState.Success("ok")
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(e.message ?: "err")
            }
        }
    }

    fun renameFolder(folderId: String, newName: String) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading
            try {
                pluginManager.renameFolder(folderId, newName)
                _operationState.value = OperationState.Success("ok")
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(e.message ?: "err")
            }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading
            try {
                pluginManager.deleteFolder(folderId)
                _operationState.value = OperationState.Success("ok")
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(e.message ?: "err")
            }
        }
    }

    fun movePluginToFolder(pluginId: String, folderId: String?) {
        viewModelScope.launch {
            pluginManager.movePluginToFolder(pluginId, folderId)
        }
    }

    fun importPlugin(uri: android.net.Uri, folderId: String? = null) {
        viewModelScope.launch {
            _importState.value = ImportState.Loading
            try {
                val result = pluginManager.importPlugin(uri, folderId)
                _importState.value = if (result.isSuccess) {
                    ImportState.Success(result.getOrThrow())
                } else {
                    ImportState.Error(result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 导入状态
     */
    sealed class ImportState {
        data object Idle : ImportState()
        data object Loading : ImportState()
        data class Success(val plugin: PluginInfo) : ImportState()
        data class Error(val message: String) : ImportState()
    }

    /**
     * 操作状态
     */
    sealed class OperationState {
        data object Idle : OperationState()
        data object Loading : OperationState()
        data class Success(val message: String) : OperationState()
        data class Error(val message: String) : OperationState()
    }
}

/**
 * 详情页数据卡片的内容。
 * 插件返回 `{"title", "items": [{"label","value"}], "note"}`，字段都容错处理：
 * 插件作者少写一个字段不该让整张卡片消失。
 */
data class PluginDetailCard(
    val title: String,
    val items: List<Item>,
    val note: String? = null,
) {
    data class Item(val label: String, val value: String)

    companion object {
        fun parse(element: JsonElement): PluginDetailCard? {
            val obj = element as? JsonObject ?: return null
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return null
            val items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val label = (o["label"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val value = (o["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (label.isBlank() && value.isBlank()) null else Item(label, value)
            }
            val note = (obj["note"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            if (items.isEmpty() && note == null) return null
            return PluginDetailCard(title, items, note)
        }
    }
}
