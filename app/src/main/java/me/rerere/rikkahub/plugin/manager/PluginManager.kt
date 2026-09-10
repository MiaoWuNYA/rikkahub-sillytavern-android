package me.rerere.rikkahub.plugin.manager

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.model.PluginFolder
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import java.io.File

/**
 * 插件管理器
 * 统一管理插件的生命周期
 */
class PluginManager(
    private val context: Context,
    private val scanner: PluginScanner,
    private val loader: PluginLoader,
    private val repository: PluginRepository,
) {
    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _folders = MutableStateFlow<List<PluginFolder>>(emptyList())
    val folders: StateFlow<List<PluginFolder>> = _folders.asStateFlow()

    /**
     * 用于跟踪首次初始化是否完成
     * 解决竞态条件：ChatService 在插件还没加载完时就调用 getTools()
     */
    private val initializationDeferred = CompletableDeferred<Unit>()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshFolders()
                refreshPlugins()
            } finally {
                initializationDeferred.complete(Unit)
            }
        }
    }

    /**
     * 等待插件初始化完成
     * 在需要确保插件已加载的场景调用（如 ChatService 发送消息前）
     */
    suspend fun awaitInitialization() {
        initializationDeferred.await()
    }

    suspend fun refreshPlugins() {
        _isLoading.value = true
        try {
            val scannedPlugins = scanner.scanPlugins()
            val assignments = repository.getFolderAssignments()
            val pluginsWithConfig = scannedPlugins.map { plugin ->
                val savedConfig = repository.getPluginConfig(plugin.manifest.id)
                val isEnabled = repository.isPluginEnabled(plugin.manifest.id)
                val folderId = assignments[plugin.manifest.id]
                plugin.copy(config = savedConfig, isEnabled = isEnabled, folderId = folderId)
            }
            _plugins.value = pluginsWithConfig
            pluginsWithConfig.filter { it.isEnabled }.forEach { plugin ->
                if (loader.getLoadedPlugin(plugin.manifest.id) == null) {
                    loadPlugin(plugin)
                }
            }
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun refreshFolders() {
        _folders.value = repository.getFolders().sortedBy { it.sortOrder }
    }

    private suspend fun loadPlugin(plugin: PluginInfo) {
        loader.loadPlugin(plugin).fold(
            onSuccess = {
                // 加载成功时清除之前的错误信息
                updatePluginState(plugin.manifest.id) {
                    it.copy(loadError = null)
                }
            },
            onFailure = { error ->
                // 加载失败时不自动禁用插件，保留 isEnabled = true 和错误信息
                // 这样下次 refreshPlugins() 时可以自动重试加载
                updatePluginState(plugin.manifest.id) {
                    it.copy(loadError = error.message)
                }
            }
        )
    }

    /**
     * 预览插件（不解压到插件目录，仅解析 manifest）
     */
    suspend fun previewPlugin(uri: Uri): Result<Pair<PluginManifest, File>> {
        return try {
            scanner.previewFromZip(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 确认导入插件（在 previewPlugin 后调用）
     */
    suspend fun confirmImport(manifest: PluginManifest, tempDir: File): Result<PluginInfo> {
        return try {
            val result = scanner.completeImport(manifest, tempDir)
            result.fold(
                onSuccess = { pluginInfo ->
                    repository.savePlugin(pluginInfo)
                    loadPlugin(pluginInfo)
                    refreshPlugins()
                    Result.success(pluginInfo)
                },
                onFailure = { error -> Result.failure(error) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importPlugin(uri: Uri, folderId: String? = null): Result<PluginInfo> {
        return try {
            val result = scanner.previewFromZip(uri).fold(
                onSuccess = { (manifest, tempDir) ->
                    scanner.completeImport(manifest, tempDir)
                },
                onFailure = { error -> Result.failure(error) }
            )
            result.fold(
                onSuccess = { pluginInfo ->
                    repository.savePlugin(pluginInfo)
                    if (folderId != null) {
                        repository.setPluginFolder(pluginInfo.manifest.id, folderId)
                    }
                    loadPlugin(pluginInfo)
                    refreshPlugins()
                    Result.success(pluginInfo)
                },
                onFailure = { error -> Result.failure(error) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePlugin(pluginId: String): Boolean {
        return try {
            loader.unloadPlugin(pluginId)
            val deleted = scanner.deletePlugin(pluginId)
            repository.removePlugin(pluginId)
            repository.setPluginFolder(pluginId, null)
            refreshPlugins()
            deleted
        } catch (e: Exception) {
            false
        }
    }

    suspend fun togglePlugin(pluginId: String, enabled: Boolean) {
        val plugin = _plugins.value.find { it.manifest.id == pluginId } ?: return
        if (enabled) {
            // 重新加载插件（先清除错误状态）
            val pluginToLoad = plugin.copy(isEnabled = true, loadError = null)
            loader.unloadPlugin(pluginId)
            loadPlugin(pluginToLoad)
        } else {
            loader.unloadPlugin(pluginId)
        }
        repository.setPluginEnabled(pluginId, enabled)
        updatePluginState(pluginId) { it.copy(isEnabled = enabled, loadError = null) }
    }

    suspend fun updatePluginConfig(pluginId: String, config: Map<String, JsonElement>) {
        repository.savePluginConfig(pluginId, config)
        updatePluginState(pluginId) { it.copy(config = config) }
        val plugin = _plugins.value.find { it.manifest.id == pluginId }
        if (plugin?.isEnabled == true) {
            loader.unloadPlugin(pluginId)
            loadPlugin(plugin.copy(config = config))
        }
    }

    suspend fun getPluginConfig(pluginId: String): Map<String, JsonElement> {
        return repository.getPluginConfig(pluginId)
    }

    fun getPluginsDirectory(): File = scanner.pluginsDir

    fun getPlugin(pluginId: String): PluginInfo? = _plugins.value.find { it.manifest.id == pluginId }

    suspend fun reloadAllPlugins() {
        loader.unloadAll()
        refreshPlugins()
    }

    /**
     * 调用插件工具函数（代理到 PluginLoader）
     */
    suspend fun callTool(pluginId: String, toolName: String, params: JsonElement): Result<JsonElement> {
        return loader.callTool(pluginId, toolName, params)
    }

    suspend fun createFolder(name: String): PluginFolder {
        val folder = repository.addFolder(name)
        refreshFolders()
        return folder
    }

    suspend fun renameFolder(folderId: String, newName: String) {
        repository.renameFolder(folderId, newName)
        refreshFolders()
    }

    suspend fun deleteFolder(folderId: String) {
        repository.deleteFolder(folderId)
        refreshFolders()
        refreshPlugins()
    }

    suspend fun movePluginToFolder(pluginId: String, folderId: String?) {
        repository.setPluginFolder(pluginId, folderId)
        updatePluginState(pluginId) { it.copy(folderId = folderId) }
    }

    fun getPluginsByFolder(folderId: String?): List<PluginInfo> {
        return _plugins.value.filter { it.folderId == folderId }
    }

    private fun updatePluginState(pluginId: String, transform: (PluginInfo) -> PluginInfo) {
        _plugins.value = _plugins.value.map { plugin ->
            if (plugin.manifest.id == pluginId) transform(plugin) else plugin
        }
    }
}
