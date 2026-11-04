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
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.model.PluginFolder
import me.rerere.rikkahub.plugin.model.PluginFrontendPayload
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import me.rerere.rikkahub.plugin.provider.PluginProviderRegistry
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.runtime.PluginRegexRegistry
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 插件管理器
 * 统一管理插件的生命周期
 */
class PluginManager(
    private val context: Context,
    private val scanner: PluginScanner,
    private val loader: PluginLoader,
    private val repository: PluginRepository,
    private val providerRegistry: PluginProviderRegistry? = null,
    private val settingsService: me.rerere.rikkahub.plugin.settings.PluginSettingsService? = null,
) {
    companion object {
        /** 单个前端脚本体积上限：整包扩展动辄数 MB，注入 WebView 会卡死页面。 */
        const val MAX_FRONTEND_SCRIPT_BYTES = 1024 * 1024

        private const val MAX_READ_BYTES = 512 * 1024
    }

    /**
     * 调用插件导出的函数（详情页数据卡片用）。
     * 转发到 loader，让 UI 层不必直接依赖 loader。
     */
    suspend fun callExport(pluginId: String, functionName: String): Result<JsonElement> =
        loader.callExport(pluginId, functionName, kotlinx.serialization.json.JsonObject(emptyMap()))

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 汇总启用插件的前端脚本，供前端卡 WebView 注入。 */
    suspend fun buildFrontendScriptsJson(): String = withContext(Dispatchers.IO) {
        awaitInitialization()
        val payload = _plugins.value.filter { it.isEnabled }.flatMap { info ->
            info.manifest.frontend?.scripts.orEmpty()
                .filter { it.enabled && it.entry.isNotBlank() && it.scriptKey() !in info.disabledFrontendScripts }
                .mapNotNull { script ->
                    val file = File(info.directory, script.entry)
                    if (!file.isFile || file.length() > MAX_FRONTEND_SCRIPT_BYTES) return@mapNotNull null
                    runCatching {
                        PluginFrontendPayload(
                            id = info.manifest.id + "::" + script.scriptKey(),
                            pluginId = info.manifest.id,
                            pluginName = info.manifest.name,
                            name = script.name.ifBlank { file.nameWithoutExtension },
                            info = script.info,
                            source = file.readText(),
                            buttons = script.buttons,
                        )
                    }.getOrNull()
                }
        }
        json.encodeToString(payload)
    }

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

    suspend fun refreshPlugins() = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            val scannedPlugins = scanner.scanPlugins()
            val assignments = repository.getFolderAssignments()
            val pluginsWithConfig = scannedPlugins.map { plugin ->
                val savedConfig = repository.getPluginConfig(plugin.manifest.id)
                val isEnabled = repository.isPluginEnabled(plugin.manifest.id)
                val folderId = assignments[plugin.manifest.id]
                val disabledScripts = repository.getDisabledFrontendScripts(plugin.manifest.id)
                plugin.copy(
                    config = savedConfig,
                    isEnabled = isEnabled,
                    folderId = folderId,
                    disabledFrontendScripts = disabledScripts,
                )
            }
            _plugins.value = pluginsWithConfig
            // 插件声明的供应商跟着插件生命周期走：这里一次同步覆盖导入、卸载、启用与停用
            providerRegistry?.sync(pluginsWithConfig)
            pluginsWithConfig.filter { it.isEnabled }.forEach { plugin ->
                if (loader.getLoadedPlugin(plugin.manifest.id) == null) {
                    loadPlugin(plugin)
                }
            }
            // 插件设置补丁在这里收敛：新插件生效，被删掉的插件回滚
            settingsService?.reconcile(pluginsWithConfig)
            PluginRegexRegistry.update(pluginsWithConfig)
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
        return withContext(Dispatchers.IO) {
            try {
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
    }

    suspend fun importPlugin(uri: Uri, folderId: String? = null): Result<PluginInfo> {
        return withContext(Dispatchers.IO) {
            try {
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
    }

    suspend fun deletePlugin(pluginId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
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
    }

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun sanitizePluginId(raw: String): String {
        val id = raw.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-', '.')
        require(id.isNotBlank()) { "插件 id 不能为空" }
        return id
    }

    private fun safeResolve(dir: File, relativePath: String): File {
        val base = dir.canonicalFile
        val target = File(base, relativePath).canonicalFile
        require(target.path == base.path || target.path.startsWith(base.path + File.separator)) {
            "路径越出插件目录: $relativePath"
        }
        return target
    }

    private fun requirePluginDir(pluginId: String): File {
        val info = _plugins.value.find { it.manifest.id == pluginId }
        val dir = info?.directory ?: File(scanner.pluginsDir, pluginId)
        require(dir.isDirectory) { "插件不存在: $pluginId" }
        return dir
    }

    /** 新建插件目录与 manifest.json，返回扫描后的插件信息 */
    suspend fun createPlugin(
        id: String,
        name: String,
        description: String,
        icon: String = "🧩",
        author: String = "user",
        entry: String = "index.js",
    ): Result<PluginInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val safeId = sanitizePluginId(id)
            val dir = File(scanner.pluginsDir, safeId)
            require(!dir.exists()) { "插件已存在: $safeId" }
            require(dir.mkdirs()) { "无法创建插件目录: ${dir.absolutePath}" }
            val manifest = PluginManifest(
                id = safeId,
                name = name.ifBlank { safeId },
                description = description,
                version = "1.0.0",
                author = author.ifBlank { "user" },
                icon = icon.ifBlank { "🧩" },
                entry = entry,
            )
            File(dir, PluginScanner.MANIFEST_FILE).writeText(prettyJson.encodeToString(manifest))
            refreshPlugins()
            _plugins.value.find { it.manifest.id == safeId } ?: error("插件创建后未能扫描到: $safeId")
        }
    }

    /** 写插件目录内的文件；写 manifest 会重新加载该插件 */
    suspend fun writePluginFile(pluginId: String, relativePath: String, content: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = requirePluginDir(pluginId)
                val target = safeResolve(dir, relativePath)
                target.parentFile?.mkdirs()
                target.writeText(content)
                if (target.name == PluginScanner.MANIFEST_FILE) {
                    loader.unloadPlugin(pluginId)
                    refreshPlugins()
                }
                target
            }
        }

    suspend fun readPluginFile(pluginId: String, relativePath: String = PluginScanner.MANIFEST_FILE): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = requirePluginDir(pluginId)
                val target = safeResolve(dir, relativePath)
                require(target.isFile) { "文件不存在: $relativePath" }
                require(target.length() <= MAX_READ_BYTES) { "文件过大: ${target.length()} bytes" }
                target.readText()
            }
        }

    suspend fun listPluginFiles(pluginId: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = requirePluginDir(pluginId)
            dir.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(dir).invariantSeparatorsPath + " (${it.length()}B)" }
                .sorted()
                .toList()
        }
    }

    /** 打包插件目录为 zip，返回缓存里的文件路径，供分享或再次导入 */
    suspend fun exportPlugin(pluginId: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val info = _plugins.value.find { it.manifest.id == pluginId } ?: error("插件不存在: $pluginId")
            val dir = info.directory
            require(dir.isDirectory) { "插件目录不存在: ${dir.absolutePath}" }
            val outDir = File(context.cacheDir, "plugin_exports").apply { mkdirs() }
            val name = "${sanitizePluginId(pluginId)}-${info.manifest.version.ifBlank { "1.0.0" }}.zip"
            val zipFile = File(outDir, name)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val entryName = file.relativeTo(dir).invariantSeparatorsPath
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            zipFile
        }
    }

    /** 从本地 zip 路径导入插件（供 AI 与文件管理器共用） */
    suspend fun importPluginFromPath(path: String): Result<PluginInfo> {
        val file = File(path)
        if (!file.isFile) return Result.failure(IllegalArgumentException("文件不存在: $path"))
        return importPlugin(Uri.fromFile(file))
    }

    suspend fun togglePlugin(pluginId: String, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            val plugin = _plugins.value.find { it.manifest.id == pluginId } ?: return@withContext
            if (enabled) {
                val pluginToLoad = plugin.copy(isEnabled = true, loadError = null)
                loader.unloadPlugin(pluginId)
                loadPlugin(pluginToLoad)
            } else {
                loader.unloadPlugin(pluginId)
            }
            repository.setPluginEnabled(pluginId, enabled)
            updatePluginState(pluginId) { it.copy(isEnabled = enabled, loadError = null) }
            // 停用的插件不再提供供应商，启用的重新挂上
            providerRegistry?.sync(_plugins.value)
        }
    }

    suspend fun updatePluginConfig(pluginId: String, config: Map<String, JsonElement>) {
        withContext(Dispatchers.IO) {
            repository.savePluginConfig(pluginId, config)
            updatePluginState(pluginId) { it.copy(config = config) }
            val plugin = _plugins.value.find { it.manifest.id == pluginId }
            if (plugin?.isEnabled == true) {
                loader.unloadPlugin(pluginId)
                loadPlugin(plugin.copy(config = config))
            }
            // 声明里的 ${config.x} 可能正是代理、密钥，配置一改供应商就得跟着刷新
            providerRegistry?.sync(_plugins.value)
        }
    }

    /** 单独启用/停用某个前端脚本（不重新加载插件，只在下次下发时生效）。 */
    suspend fun setFrontendScriptEnabled(pluginId: String, scriptKey: String, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            repository.setFrontendScriptEnabled(pluginId, scriptKey, enabled)
            updatePluginState(pluginId) { info ->
                val next = info.disabledFrontendScripts.toMutableSet()
                if (enabled) next.remove(scriptKey) else next.add(scriptKey)
                info.copy(disabledFrontendScripts = next)
            }
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
