package me.rerere.rikkahub.plugin.loader

/**
 * 插件工具命名规则（纯逻辑，便于 JVM 单元测试）
 *
 * 目标仓库的 Tool.name 会直接作为 function-calling 的函数名发给模型，
 * 大多数供应商要求 `[a-zA-Z0-9_-]` 且长度有限（OpenAI <= 64），
 * 而插件 id 通常是反向域名（含 `.`），插件声明的工具名也可能含任意字符。
 *
 * 命名规则：
 * - 统一加 `plugin_` 前缀，避免与本地工具 / MCP (`mcp__`) / 技能工具重名
 * - 插件 id 与工具名中非法字符全部替换为 `_`
 * - 当插件 id 被清洗过（有信息丢失，如 `.` 被替换）时，追加插件 id 的
 *   6 位 SHA-256 短哈希，保证不同插件清洗后不会撞名
 */
object PluginToolNaming {

    const val PREFIX = "plugin_"
    private const val MAX_NAME_LENGTH = 64
    private const val HASH_LENGTH = 6

    /**
     * 清洗单个标识符片段：仅保留字母数字下划线，其余替换为 `_`，
     * 去掉首尾 `_`，压缩连续 `_`；清洗结果为空时返回 "unnamed"
     */
    fun sanitize(raw: String): String {
        val replaced = raw.map { c ->
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_') c else '_'
        }.joinToString("")
            .replace("_+".toRegex(), "_")
            .trim('_')
        return replaced.ifEmpty { "unnamed" }
    }

    /**
     * 生成插件 id 的 6 位短哈希（SHA-256 hex 前缀）
     */
    fun shortHash(raw: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
    }

    /**
     * 构建插件工具的对外工具名
     */
    fun buildToolName(pluginId: String, toolName: String): String {
        val sanitizedPlugin = sanitize(pluginId)
        val sanitizedTool = sanitize(toolName)
        // 插件 id 被清洗改动过（如反向域名的 `.` 被替换、连续 `_` 被压缩）时追加短哈希防撞名
        val hashSuffix = if (sanitizedPlugin != pluginId) {
            "_${shortHash(pluginId)}"
        } else ""
        return buildString {
            append(PREFIX)
            append(sanitizedPlugin)
            append(hashSuffix)
            append('_')
            append(sanitizedTool)
        }.take(MAX_NAME_LENGTH).trimEnd('_')
    }

    /**
     * 工具名是否合法（可直接作为 function-calling 函数名使用）
     */
    fun isValidToolName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return false
        return name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
    }
}
