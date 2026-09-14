package me.rerere.rikkahub.plugin.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.plugin.loader.LoadedPlugin
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.loader.PluginToolNaming
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginToolDefinition
import me.rerere.rikkahub.plugin.crypto.PluginCrypto
import java.io.File

/**
 * 插件工具提供者
 * 将插件工具转换为 AI 可用的 Tool 对象
 *
 * 安全约定：
 * - 插件代码不受信任，所有插件工具 needsApproval 统一为 true（每次调用需用户确认）
 * - 工具名统一加 `plugin_` 前缀（见 [PluginToolNaming]），防止与本地工具 / MCP 重名
 */
class PluginToolProvider(
    private val pluginLoader: PluginLoader,
    private val pluginManager: PluginManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 获取所有插件提供的工具
     * 会等待插件初始化完成，确保竞态条件下不会返回空列表
     */
    suspend fun getTools(): List<Tool> {
        // 等待插件初始化完成，避免竞态条件导致工具列表为空
        pluginManager.awaitInitialization()
        return pluginLoader.getAllLoadedPlugins().flatMap { plugin ->
            plugin.info.manifest.tools.map { toolDef ->
                createTool(plugin, toolDef)
            }
        }
    }

    /**
     * 获取所有启用插件的系统提示词。
     * 返回格式为 "【插件: {name}】\n{prompt}" 的列表。
     * 支持内联字符串和 file: 前缀的文件引用。
     */
    suspend fun getPluginSystemPrompts(): List<String> {
        pluginManager.awaitInitialization()
        val allPlugins = pluginLoader.getAllLoadedPlugins()
        android.util.Log.i("PluginToolProvider", "getPluginSystemPrompts: ${allPlugins.size} loaded plugins")
        return allPlugins.mapNotNull { plugin ->
            val parts = mutableListOf<String>()

            // 主提示词
            val prompt = plugin.info.manifest.systemPrompt?.takeIf { it.isNotBlank() }
            if (prompt != null) {
                val resolved = resolvePluginFile(plugin, prompt) ?: return@mapNotNull null
                parts.add(resolved)
            }

            // 可选分段（通过 config 开关控制）
            // skills 分段不做分段级开关（由 individual skill config keys 控制），
            // 其余分段：config 值缺失或非 boolean 时一律视为关闭
            plugin.info.manifest.sections.forEach { section ->
                if (section.name != "skills") {
                    val configKey = "enable_${section.name}"
                    val sectionEnabled = plugin.info.getConfigValue(configKey)
                        ?.let { it is kotlinx.serialization.json.JsonPrimitive && it.content.toBooleanStrictOrNull() ?: false }
                        ?: false
                    if (!sectionEnabled) return@forEach
                }

                val resolved = resolvePluginFile(plugin, section.file) ?: return@forEach

                // 技能包分段：按 individual skill config keys 过滤
                if (section.name == "skills") {
                    val skillChunks = parseSkillChunks(resolved)
                    if (skillChunks.isNotEmpty()) {
                        val enabledSkills = skillChunks.filter { (skillName, _) ->
                            val skillKey = "enable_$skillName"
                            val skillEnabled = plugin.info.getConfigValue(skillKey)
                                ?.let { it is kotlinx.serialization.json.JsonPrimitive && it.content.toBooleanStrictOrNull() ?: false }
                                ?: false
                            skillEnabled
                        }
                        if (enabledSkills.isNotEmpty()) {
                            val skillNames = enabledSkills.joinToString("、") { (name, _) -> name }
                            val preamble = "你已解锁以下专项能力模块，当用户问题涉及相关领域时应主动参考对应模块内容来回答：$skillNames"
                            parts.add("【${section.label}】\n$preamble\n\n${enabledSkills.joinToString("\n\n") { it.second }}")
                        }
                    }
                } else {
                    parts.add("【${section.label}】\n$resolved")
                }
            }

            if (parts.isEmpty()) return@mapNotNull null
            android.util.Log.i("PluginToolProvider", "Plugin ${plugin.id}: ${parts.size} sections, total ${parts.sumOf { it.length }} chars")
            parts.joinToString("\n\n")
        }
    }

    /**
     * 解析插件文件引用（enc:/file:/内联）
     */
    private fun resolvePluginFile(plugin: LoadedPlugin, ref: String): String? {
        return try {
            when {
                ref.startsWith("enc:") -> {
                    val file = File(plugin.info.directory, ref.removePrefix("enc:"))
                    if (file.exists()) PluginCrypto.decryptFile(file) else {
                        android.util.Log.w("PluginToolProvider", "Plugin ${plugin.id}: enc file not found: ${file.absolutePath}")
                        null
                    }
                }
                ref.startsWith("file:") -> {
                    val file = File(plugin.info.directory, ref.removePrefix("file:"))
                    if (file.exists()) file.readText(Charsets.UTF_8) else {
                        android.util.Log.w("PluginToolProvider", "Plugin ${plugin.id}: file not found: ${file.absolutePath}")
                        null
                    }
                }
                else -> ref
            }
        } catch (e: Exception) {
            android.util.Log.e("PluginToolProvider", "Plugin ${plugin.id}: failed to resolve: $ref", e)
            null
        }
    }

    /**
     * 解析技能包内容为独立技能块
     * 每个技能以 "---" + YAML frontmatter 开头，包含 name 字段
     */
    private fun parseSkillChunks(content: String): List<Pair<String, String>> {
        val chunks = mutableListOf<Pair<String, String>>()
        // 源文件混合了 \n 与 \r\n 行尾，分隔符需同时兼容
        val parts = content.split(Regex("\r?\n---\r?\n"))
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val nameMatch = Regex("^name:\\s*(.+)", RegexOption.MULTILINE).find(trimmed)
            if (nameMatch != null) {
                val skillName = nameMatch.groupValues[1].trim().trim('"', '\'')
                chunks.add(skillName to "---\n$trimmed")
            }
        }
        return chunks
    }

    /**
     * 获取指定插件的工具
     */
    suspend fun getPluginTools(pluginId: String): List<Tool> {
        pluginManager.awaitInitialization()
        val plugin = pluginLoader.getLoadedPlugin(pluginId) ?: return emptyList()
        return plugin.info.manifest.tools.map { toolDef ->
            createTool(plugin, toolDef)
        }
    }

    /**
     * 创建 Tool 对象
     */
    private fun createTool(plugin: LoadedPlugin, toolDef: PluginToolDefinition): Tool {
        return Tool(
            name = PluginToolNaming.buildToolName(plugin.id, toolDef.name),
            description = buildDescription(plugin, toolDef),
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildParameters(toolDef),
                    required = toolDef.parameters.filter { it.required }.map { it.name }
                )
            },
            execute = { params ->
                executeTool(plugin, toolDef, params)
            }
        )
    }

    /**
     * 构建工具描述
     */
    private fun buildDescription(plugin: LoadedPlugin, toolDef: PluginToolDefinition): String {
        val sb = StringBuilder()
        sb.appendLine(toolDef.description)
        sb.appendLine()
        sb.appendLine("Provided by plugin: ${plugin.info.manifest.name} (${plugin.info.manifest.id})")
        return sb.toString().trim()
    }

    /**
     * 构建参数定义
     */
    private fun buildParameters(toolDef: PluginToolDefinition): JsonObject {
        return buildJsonObject {
            toolDef.parameters.forEach { param ->
                put(param.name, buildJsonObject {
                    put("type", param.type)
                    if (param.description != null) {
                        put("description", param.description)
                    }
                    // 根据类型添加额外信息
                    when (param.type) {
                        "array" -> {
                            put("items", buildJsonObject {
                                put("type", "string")
                            })
                        }
                        // object 类型暂不展开 properties 定义
                    }
                })
            }
        }
    }

    /**
     * 执行工具
     */
    private suspend fun executeTool(
        plugin: LoadedPlugin,
        toolDef: PluginToolDefinition,
        params: JsonElement
    ): List<UIMessagePart> {
        val result = pluginLoader.callTool(
            pluginId = plugin.id,
            toolName = toolDef.name,
            params = params
        )

        return result.fold(
            onSuccess = { jsonElement ->
                val resultStr = json.encodeToString(JsonElement.serializer(), jsonElement)
                listOf(UIMessagePart.Text(resultStr))
            },
            onFailure = { error ->
                val errorObj = buildJsonObject {
                    put("success", false)
                    put("error", error.message ?: "Unknown error")
                }
                listOf(UIMessagePart.Text(errorObj.toString()))
            }
        )
    }
}
