package me.rerere.rikkahub.plugin.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.datastore.SettingsPatcher
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import me.rerere.rikkahub.plugin.model.PluginSettingPatch
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

/**
 * 插件设置补丁：把插件声明的设置写进应用设置，并记录基线用于回滚。
 *
 * 基线存在插件仓库（DataStore）而不是插件目录：用户删掉插件目录就能救砖，
 * 下次启动时 [reconcile] 会把改动回滚到基线。
 */
class PluginSettingsService(
    private val settingsStore: SettingsStore,
    private val repository: PluginRepository,
    private val scanner: PluginScanner,
) {
    companion object {
        const val SYSTEM_TWEAKS_ID = "system-tweaks"
        private const val BASELINE_PREFIX = "plugin_settings_baseline_"
        private const val APPLIED_PREFIX = "plugin_settings_applied_"
        private const val INDEX_KEY = "plugin_settings_index"
    }

    /** 启用插件的补丁在启动/刷新时收敛：新插件应用，消失的插件回滚 */
    suspend fun reconcile(plugins: List<PluginInfo>) {
        withContext(Dispatchers.IO) {
            val enabled = plugins.filter { it.isEnabled && it.manifest.settings.isNotEmpty() }
            val enabledIds = enabled.map { it.manifest.id }.toSet()
            val indexed = indexedPluginIds()
            indexed.filter { it !in enabledIds }.forEach { id ->
                runCatching { restorePlugin(id) }
            }
            enabled.forEach { plugin ->
                runCatching { applyPlugin(plugin) }
            }
            repository.putMeta(
                INDEX_KEY,
                (indexed.filter { it in enabledIds } + enabledIds).distinct().joinToString("\n")
            )
        }
    }

    /** 应用插件的设置补丁；同一插件重复调用结果一致 */
    suspend fun applyPlugin(plugin: PluginInfo): Int = withContext(Dispatchers.IO) {
        val patches = plugin.manifest.settings
        if (patches.isEmpty()) return@withContext 0
        // 同一份补丁只应用一次：之后用户手改设置不会被插件拉回去
        val fingerprint = plugin.manifest.version + "#" + patches.joinToString("|") { "${it.path}=${it.value}" }
        if (repository.getMeta(APPLIED_PREFIX + plugin.manifest.id) == fingerprint) return@withContext 0
        val baselineKey = BASELINE_PREFIX + plugin.manifest.id
        val baseline = repository.getMeta(baselineKey)
            ?.let { runCatching { JsonInstant.decodeFromString<JsonObject>(it) }.getOrNull() }
            ?: JsonObject(emptyMap())
        var nextBaseline = baseline
        var applied = 0
        settingsStore.update { current ->
            var tree: JsonObject = SettingsPatcher.encode(current)
            patches.forEach { patch ->
                if (!nextBaseline.containsKey(patch.path)) {
                    runCatching { SettingsPatcher.read(tree, patch.path) }
                        .getOrNull()
                        ?.let { nextBaseline = JsonObject(nextBaseline + (patch.path to it)) }
                }
                tree = runCatching { SettingsPatcher.write(tree, patch.path, patch.value) as JsonObject }
                    .getOrElse { error ->
                        android.util.Log.w("PluginSettings", "bad patch ${patch.path}: ${error.message}")
                        tree
                    }
                applied++
            }
            SettingsPatcher.decode(tree)
        }
        repository.putMeta(baselineKey, JsonInstant.encodeToString(nextBaseline))
        repository.putMeta(APPLIED_PREFIX + plugin.manifest.id, fingerprint)
        val indexed = indexedPluginIds()
        if (plugin.manifest.id !in indexed) {
            repository.putMeta(INDEX_KEY, (indexed + plugin.manifest.id).joinToString("\n"))
        }
        applied
    }

    /** 回滚某个插件改过的设置 */
    suspend fun restorePlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val baselineKey = BASELINE_PREFIX + pluginId
        val baseline = repository.getMeta(baselineKey)
            ?.let { runCatching { JsonInstant.decodeFromString<JsonObject>(it) }.getOrNull() }
            ?: return@withContext false
        if (baseline.isEmpty()) {
            repository.removeMeta(baselineKey)
            return@withContext false
        }
        settingsStore.update { current ->
            var tree: JsonObject = SettingsPatcher.encode(current)
            baseline.forEach { (path, value) ->
                tree = runCatching { SettingsPatcher.write(tree, path, value) as JsonObject }.getOrElse { tree }
            }
            SettingsPatcher.decode(tree)
        }
        repository.removeMeta(baselineKey)
        repository.removeMeta(APPLIED_PREFIX + pluginId)
        val indexed = indexedPluginIds() - pluginId
        repository.putMeta(INDEX_KEY, indexed.joinToString("\n"))
        true
    }

    /**
     * 把一次设置改动记进 system-tweaks 插件，用户删掉这个插件目录即可回滚。
     * 返回插件是否可用（manifest 写入失败时不阻塞设置本身生效）。
     */
    suspend fun recordIntent(path: String, value: JsonElement): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(scanner.pluginsDir, SYSTEM_TWEAKS_ID)
            val created = !dir.isDirectory
            dir.mkdirs()
            val manifestFile = File(dir, PluginScanner.MANIFEST_FILE)
            val manifest = if (manifestFile.isFile) {
                runCatching { JsonInstant.decodeFromString<PluginManifest>(manifestFile.readText()) }.getOrNull()
            } else {
                null
            }
            val base = manifest ?: PluginManifest(
                id = SYSTEM_TWEAKS_ID,
                name = "系统调优",
                description = "由 AI 修改设置时自动生成的补丁包，删掉它并重启即可回滚",
                version = "1.0.0",
                author = "RikkaHub",
                icon = "🛠️",
            )
            // 只留最后一次改动：重复应用会把用户之后的手动修改覆盖回去
            val merged = listOf(PluginSettingPatch(path = path, value = value))
            val next = base.copy(settings = merged)
            manifestFile.writeText(JsonInstant.encodeToString(next))
            created
        }.getOrElse { false }
    }

    private suspend fun indexedPluginIds(): List<String> =
        repository.getMeta(INDEX_KEY)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
}
