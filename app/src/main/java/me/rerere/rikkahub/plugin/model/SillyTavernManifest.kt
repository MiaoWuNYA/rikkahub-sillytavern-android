package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SillyTavern 插件（酒馆助手那类前端扩展）的 manifest 结构，
 * 与本仓库插件格式不同，导入时映射成纯前端插件。
 */
@Serializable
data class SillyTavernManifest(
    @SerialName("display_name") val displayName: String = "",
    val js: String = "",
    val css: String = "",
    val author: String = "",
    val version: String = "",
    val homePage: String = "",
) {
    fun toPluginManifest(fallbackId: String): PluginManifest {
        val name = displayName.ifBlank { fallbackId }
        val scripts = if (js.isNotBlank()) {
            listOf(PluginFrontendScript(name = name, entry = js))
        } else {
            emptyList()
        }
        return PluginManifest(
            id = toPluginId(name, fallbackId),
            name = name,
            description = homePage.ifBlank { name },
            version = version.ifBlank { "1.0.0" },
            author = author.ifBlank { "unknown" },
            icon = "🧩",
            entry = "",
            frontend = if (scripts.isEmpty()) null else PluginFrontend(scripts),
        )
    }

    companion object {
        private val UNSAFE = Regex("""[\\/:*?"<>|\s]+""")

        fun toPluginId(name: String, fallbackId: String): String {
            val raw = name.ifBlank { fallbackId }.trim()
            return raw.replace(UNSAFE, "_").ifBlank { "st_extension" }
        }
    }
}
