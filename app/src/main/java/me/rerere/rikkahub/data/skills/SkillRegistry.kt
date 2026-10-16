package me.rerere.rikkahub.data.skills

/**
 * 内置 Skill 注册表 — 提供官方 marketplace 入口和搜索
 *
 * 不再硬编码内置 skill 列表。用户通过此注册表获取官方 marketplace URL，
 * 实际的 skill 列表从远程 marketplace.json 拉取。
 */
object SkillRegistry {
    /**
     * 官方 marketplace 地址（克劳德插件目录）
     */
    const val OFFICIAL_MARKETPLACE_URL =
        "https://raw.githubusercontent.com/anthropics/claude-plugins-official/main/.claude-plugin/marketplace.json"

    data class RegistryEntry(
        val name: String,
        val description: String,
        val category: String = "",
        val author: String = "",
        val version: String = "",
        val triggers: List<String> = emptyList(),
        val githubUrl: String = "",
    )

    /**
     * 从远程 marketplace 返回的原始数据直接使用，
     * 本地不再维护静态列表。保留空列表和搜索方法供 UI 占位。
     */
    val entries: List<RegistryEntry> = emptyList()

    fun search(query: String): List<RegistryEntry> {
        val q = query.lowercase()
        return entries.filter {
            it.name.lowercase().contains(q) ||
            it.description.lowercase().contains(q) ||
            it.category.lowercase().contains(q) ||
            it.triggers.any { t -> t.lowercase().contains(q) }
        }
    }

    fun byCategory(): Map<String, List<RegistryEntry>> = entries.groupBy { it.category }
}
