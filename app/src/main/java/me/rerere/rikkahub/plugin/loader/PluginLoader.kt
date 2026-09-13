package me.rerere.rikkahub.plugin.loader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginInfo
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.uuid.Uuid

/**
 * 插件加载器
 * 负责加载和管理插件生命周期
 */
class PluginLoader(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val settingsStore: SettingsStore? = null
) {

    companion object {
        private const val TAG = "PluginLoader"

        /**
         * 单次插件工具调用的协程级超时。
         *
         * 设为大于 nativeFetch 的最长超时上限 (15s x 逐段)，避免协程层先于网络层触发。
         *
         * 重要限制（如实说明）：withTimeoutOrNull 只能让"等待这次调用结果"提前放弃，
         * 无法真正打断 QuickJS 引擎内部正在执行的同步 JS 代码。因为 callTool 全程跑在
         * 单线程 pluginDispatcher 上，即使外层已放弃等待，这个单线程仍会被卡住的那次
         * 调用占用，直到底层执行真正结束。这是 QuickJS 单线程模型的本质限制，
         * 这一层只是给调用方一个明确的失败信号，不是彻底防死锁。
         */
        const val TOOL_TIMEOUT_MS = 30_000L
    }

    // 单线程调度器，确保所有 QuickJS 操作在同一线程执行
    private val pluginDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "plugin-quickjs").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // 已加载的插件缓存
    private val loadedPlugins = mutableMapOf<String, LoadedPlugin>()

    /**
     * 加载插件
     */
    suspend fun loadPlugin(pluginInfo: PluginInfo): Result<LoadedPlugin> = withContext(pluginDispatcher) {
        try {
            if (loadedPlugins.containsKey(pluginInfo.manifest.id)) {
                doUnloadPlugin(pluginInfo.manifest.id)
            }

            if (!pluginInfo.isEnabled) {
                return@withContext Result.failure(IllegalStateException("Plugin is disabled"))
            }

            val entryFile = pluginInfo.getEntryFile()
            if (!entryFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Entry file not found: ${pluginInfo.manifest.entry}")
                )
            }

            // 为此插件创建独立的 PluginDataStore，并注入沙箱
            val dataStore = PluginDataStore(context, pluginInfo.manifest.id)

            val sandbox = PluginSandbox(okHttpClient, dataStore)
            sandbox.allowedHosts = pluginInfo.manifest.allowedHosts
            sandbox.initialize()

            val resolvedConfig = resolveModelConfig(pluginInfo)
            sandbox.injectConfig(resolvedConfig)

            sandbox.evaluateFile(entryFile)

            val loadedPlugin = LoadedPlugin(
                info = pluginInfo,
                sandbox = sandbox
            )

            val exportedNames = sandbox.getExportedFunctionNames()
            Log.i(TAG, "Plugin ${pluginInfo.manifest.id} exported functions: $exportedNames")

            pluginInfo.manifest.tools.forEach { tool ->
                if (!sandbox.hasFunction(tool.name)) {
                    Log.w(TAG, "Tool '${tool.name}' declared in manifest but not found in exports (available: $exportedNames)")
                } else {
                    Log.i(TAG, "Tool '${tool.name}' registered successfully")
                }
            }

            loadedPlugins[pluginInfo.manifest.id] = loadedPlugin
            Result.success(loadedPlugin)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin ${pluginInfo.manifest.id}", e)
            Result.failure(e)
        }
    }

    private fun doUnloadPlugin(pluginId: String) {
        loadedPlugins.remove(pluginId)?.let { plugin ->
            plugin.sandbox.destroy()
            Log.d(TAG, "Unloaded plugin: $pluginId")
        }
    }

    suspend fun unloadPlugin(pluginId: String) = withContext(pluginDispatcher) {
        doUnloadPlugin(pluginId)
    }

    suspend fun reloadPlugin(pluginInfo: PluginInfo): Result<LoadedPlugin> = loadPlugin(pluginInfo)

    fun getLoadedPlugin(pluginId: String): LoadedPlugin? = loadedPlugins[pluginId]

    // 插件异步加载完成顺序不定：按 id 排序保证工具列表顺序稳定（工具 schema 进请求
    // 前缀，乱序会打断供应商的前缀缓存）
    fun getAllLoadedPlugins(): List<LoadedPlugin> = loadedPlugins.values.sortedBy { it.info.manifest.id }

    fun getEnabledPlugins(): List<LoadedPlugin> = loadedPlugins.values
        .filter { it.info.isEnabled }
        .sortedBy { it.info.manifest.id }

    /**
     * 调用插件工具（带超时保护）
     */
    suspend fun callTool(pluginId: String, toolName: String, params: JsonElement): Result<JsonElement> {
        return withContext(Dispatchers.Default) {
            val result: Result<JsonElement>? = withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                withContext(pluginDispatcher) {
                    val plugin = loadedPlugins[pluginId]
                        ?: return@withContext Result.failure<JsonElement>(
                            IllegalStateException("Plugin not loaded: $pluginId")
                        )

                    if (!plugin.hasTool(toolName)) {
                        return@withContext Result.failure<JsonElement>(
                            IllegalArgumentException("Tool not found: $toolName")
                        )
                    }

                    Result.success(plugin.sandbox.callFunction(toolName, params))
                }
            }
            result ?: Result.failure(
                IllegalStateException("Plugin tool execution timed out after ${TOOL_TIMEOUT_MS}ms: $pluginId/$toolName")
            )
        }
    }

    private fun resolveModelConfig(pluginInfo: PluginInfo): Map<String, JsonElement> {
        val config = pluginInfo.config.toMutableMap()
        val store = settingsStore ?: return config
        val settings = store.settingsFlow.value

        pluginInfo.manifest.config.forEach { field ->
            if (field.type == "model") {
                val modelUuidStr = (config[field.name] as? JsonPrimitive)?.contentOrNull
                if (modelUuidStr.isNullOrBlank()) return@forEach
                try {
                    val modelUuid = Uuid.parse(modelUuidStr)
                    val model = settings.findModelById(modelUuid) ?: return@forEach
                    val provider = model.findProvider(settings.providers) ?: return@forEach

                    val baseUrl: String
                    val apiKey: String
                    when (provider) {
                        is ProviderSetting.OpenAI -> {
                            baseUrl = provider.baseUrl
                            apiKey = provider.apiKey
                        }
                        is ProviderSetting.Google -> {
                            baseUrl = provider.baseUrl
                            apiKey = provider.apiKey
                        }
                        is ProviderSetting.Claude -> {
                            baseUrl = provider.baseUrl
                            apiKey = provider.apiKey
                        }
                    }

                    config[field.name] = JsonPrimitive(model.modelId)
                    config["${field.name}_base_url"] = JsonPrimitive(baseUrl)
                    config["${field.name}_api_key"] = JsonPrimitive(apiKey)
                    Log.d(TAG, "Resolved model config '${field.name}': modelId=${model.modelId}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve model config '${field.name}': ${e.message}")
                }
            }
        }
        return config
    }

    suspend fun unloadAll() = withContext(pluginDispatcher) {
        loadedPlugins.keys.toList().forEach { doUnloadPlugin(it) }
    }
}
