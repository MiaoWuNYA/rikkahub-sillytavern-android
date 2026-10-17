package me.rerere.rikkahub.plugin.scanner

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import me.rerere.rikkahub.plugin.model.SillyTavernManifest
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 插件扫描器
 * 负责扫描、导入和管理插件目录
 *
 * 移植自 Tumin；插件目录沿用 /storage/emulated/0/<应用名>/plugins/ 语义，
 * 与本仓库 skills 目录（/Rikkahub/skills）保持一致，使用 /Rikkahub/plugins。
 */
class PluginScanner(
    private val context: Context,
) {
    companion object {
        const val PLUGINS_DIR = "Rikkahub/plugins"
        const val MANIFEST_FILE = "manifest.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 获取插件根目录
     * 使用公共外部存储 /storage/emulated/0/Rikkahub/plugins/
     */
    val pluginsDir: File
        get() = File(Environment.getExternalStorageDirectory(), PLUGINS_DIR)

    /**
     * 确保插件目录存在
     */
    fun ensurePluginsDir(): File = pluginsDir.apply { mkdirs() }

    /**
     * 扫描所有插件
     */
    fun scanPlugins(): List<PluginInfo> {
        val dir = pluginsDir
        android.util.Log.i("PluginScanner", "scanPlugins: dir=${dir.absolutePath}, exists=${dir.exists()}, isDir=${dir.isDirectory}")
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }
        val subDirs = dir.listFiles { file -> file.isDirectory }
        android.util.Log.i("PluginScanner", "scanPlugins: found ${subDirs?.size ?: 0} subdirectories")
        subDirs?.forEach { d ->
            android.util.Log.i("PluginScanner", "  - ${d.name}: manifest=${File(d, MANIFEST_FILE).exists()}")
        }
        return subDirs
            ?.mapNotNull { pluginDir -> loadPluginInfo(pluginDir) }
            ?: emptyList()
    }

    /**
     * 加载单个插件信息
     */
    fun loadPluginInfo(pluginDir: File): PluginInfo? {
        val manifestFile = File(pluginDir, MANIFEST_FILE)
        if (!manifestFile.exists()) {
            return null
        }

        return try {
            val content = manifestFile.readText()
            val manifest = parseManifest(content, pluginDir.name)

            PluginInfo(
                manifest = manifest,
                directory = pluginDir,
                isEnabled = true, // 默认启用
            )
        } catch (e: Exception) {
            // 解析失败，返回错误状态的插件
            PluginInfo(
                manifest = PluginManifest(
                    id = pluginDir.name,
                    name = pluginDir.name,
                    description = "load error: ${e.message}",
                    version = "error",
                    author = "unknown",
                    icon = "⚠️",
                    entry = "",
                ),
                directory = pluginDir,
                isEnabled = false,
                loadError = e.message,
            )
        }
    }

    /**
     * 从 ZIP 文件预览插件（不解压到插件目录，仅解析 manifest）
     * 返回 manifest 和临时目录，供调用方展示权限确认对话框。
     * 调用方确认后应调用 [completeImport] 完成导入；取消时应清理临时目录。
     */
    fun previewFromZip(uri: Uri): Result<Pair<PluginManifest, File>> {
        return try {
            val tempFile = File(context.cacheDir, "plugin_preview_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return Result.failure(IllegalStateException("cannot read file"))

            val tempDir = File(context.cacheDir, "plugin_preview_${System.currentTimeMillis()}")
            unzip(tempFile, tempDir)

            val manifestFile = findManifest(tempDir)
                ?: run {
                    tempFile.delete()
                    tempDir.deleteRecursively()
                    return Result.failure(IllegalArgumentException("manifest.json not found"))
                }

            val manifest = parseManifest(manifestFile.readText(), manifestFile.parentFile?.name ?: "st_extension")

            // 预览阶段保留 tempFile 和 tempDir，供后续 completeImport 使用
            Result.success(manifest to tempDir)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 完成插件导入（在 previewFromZip 后调用）
     * @param manifest 预览阶段解析的 manifest
     * @param tempDir 预览阶段解压的临时目录
     */
    fun completeImport(manifest: PluginManifest, tempDir: File): Result<PluginInfo> {
        return try {
            val existingPlugins = scanPlugins()
            if (existingPlugins.any { it.manifest.id == manifest.id }) {
                tempDir.deleteRecursively()
                return Result.failure(IllegalArgumentException("plugin ${manifest.id} already exists"))
            }

            val manifestFile = findManifest(tempDir)
                ?: run {
                    tempDir.deleteRecursively()
                    return Result.failure(IllegalArgumentException("manifest.json not found"))
                }

            val entryFile = File(manifestFile.parentFile, manifest.entry)
            if (manifest.entry.isNotBlank() && !entryFile.exists()) {
                tempDir.deleteRecursively()
                return Result.failure(IllegalArgumentException("entry file not found: ${manifest.entry}"))
            }

            val pluginDir = File(ensurePluginsDir(), manifest.id)
            android.util.Log.i("PluginScanner", "completeImport: target=${pluginDir.absolutePath}")
            if (pluginDir.exists()) {
                pluginDir.deleteRecursively()
            }
            manifestFile.parentFile?.copyRecursively(pluginDir, overwrite = true)
            tempDir.deleteRecursively()

            // 验证文件已写入
            val copiedFiles = pluginDir.listFiles()
            android.util.Log.i("PluginScanner", "completeImport: copied ${copiedFiles?.size ?: 0} files to ${pluginDir.absolutePath}")
            copiedFiles?.forEach { f -> android.util.Log.i("PluginScanner", "  - ${f.name} (${f.length()} bytes)") }

            loadPluginInfo(pluginDir)?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("cannot load plugin info"))
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            Result.failure(e)
        }
    }

    /**
     * 删除插件
     */
    fun deletePlugin(pluginId: String): Boolean {
        val pluginDir = File(pluginsDir, pluginId)
        return if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        } else {
            false
        }
    }

    /**
     * 获取插件目录
     */
    fun getPluginDir(pluginId: String): File {
        return File(pluginsDir, pluginId)
    }

    /**
     * 解析 manifest：本仓库格式用严格解析；酒馆助手那类前端扩展按 SillyTavern 格式映射成前端插件。
     */
    private fun parseManifest(content: String, fallbackId: String): PluginManifest {
        val obj = json.parseToJsonElement(content) as? JsonObject
        val isSillyTavern = obj != null &&
            (obj.containsKey("display_name") || (obj.containsKey("js") && !obj.containsKey("name")))
        return if (isSillyTavern) {
            json.decodeFromString(SillyTavernManifest.serializer(), content).toPluginManifest(fallbackId)
        } else {
            json.decodeFromString(PluginManifest.serializer(), content)
        }
    }

    /**
     * 解压 ZIP 文件（带 Zip Slip 防护：拒绝解压到目标目录之外的条目）
     */
    private fun unzip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        val canonicalDest = destDir.canonicalPath + File.separator
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: java.util.zip.ZipEntry? = zis.nextEntry
            while (entry != null) {
                val file = File(destDir, entry.name)
                if (!file.canonicalPath.startsWith(canonicalDest) &&
                    file.canonicalPath != destDir.canonicalPath
                ) {
                    throw SecurityException("zip entry outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { output ->
                        zis.copyTo(output)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    /**
     * 查找 manifest.json 文件
     * 优先在根目录查找，然后在子目录中查找
     */
    private fun findManifest(dir: File): File? {
        // 优先在根目录找
        val rootManifest = File(dir, MANIFEST_FILE)
        if (rootManifest.exists()) {
            return rootManifest
        }

        // 在子目录中查找
        return dir.listFiles { file -> file.isDirectory }
            ?.asSequence()
            ?.map { subdir -> File(subdir, MANIFEST_FILE) }
            ?.firstOrNull { it.exists() }
    }
}
