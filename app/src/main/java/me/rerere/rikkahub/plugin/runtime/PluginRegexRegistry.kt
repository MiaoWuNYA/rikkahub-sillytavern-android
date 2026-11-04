package me.rerere.rikkahub.plugin.runtime

import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.plugin.model.PluginInfo
import kotlin.uuid.Uuid

/**
 * 启用插件的正则：并入全局正则，插件卸载/删除后自然失效。
 */
object PluginRegexRegistry {
    @Volatile
    private var current: List<AssistantRegex> = emptyList()

    val regexes: List<AssistantRegex>
        get() = current

    fun update(plugins: List<PluginInfo>) {
        current = plugins.filter { it.isEnabled }.flatMap { info ->
            info.manifest.regexes.map { regex ->
                AssistantRegex(
                    id = stableId(info.manifest.id, regex.name, regex.findRegex),
                    name = regex.name.ifBlank { info.manifest.name },
                    enabled = true,
                    findRegex = regex.findRegex,
                    replaceString = regex.replaceString,
                    affectingScope = setOf(AssistantAffectScope.USER, AssistantAffectScope.ASSISTANT),
                    visualOnly = regex.visualOnly,
                    promptOnly = regex.promptOnly,
                )
            }
        }
    }

    private fun stableId(pluginId: String, name: String, findRegex: String): Uuid {
        val hash = (pluginId + "|" + name + "|" + findRegex).hashCode().toLong()
        return Uuid.fromLongs(hash, hash xor 0x5f5f5f5f)
    }
}
