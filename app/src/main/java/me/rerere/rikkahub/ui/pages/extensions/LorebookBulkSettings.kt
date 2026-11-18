package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection

/** Null means untouched, including when selected entries have different original values. */
internal data class LorebookSettingsPatch(
    val priority: Int? = null,
    val position: InjectionPosition? = null,
    val depth: Int? = null,
    val probability: Int? = null,
    val enabled: Boolean? = null,
    val overrides: Map<EntrySettingKey, Any?> = emptyMap(),
) {
    val fields: List<String> get() = listOfNotNull(
        priority?.let { "优先级" }, position?.let { "注入位置" }, depth?.let { "注入深度" },
        probability?.let { "注入概率" }, enabled?.let { "启用状态" },
    ).plus(overrides.keys.map { it.label }).distinct()
    fun apply(entry: PromptInjection.RegexInjection): PromptInjection.RegexInjection {
        require(probability == null || probability in 0..100) { "概率必须为 0–100" }
        val base = entry.copy(priority = priority ?: entry.priority, position = position ?: entry.position,
            injectDepth = depth ?: entry.injectDepth, probability = probability ?: entry.probability,
            enabled = enabled ?: entry.enabled)
        return overrides.entries.fold(base) { current, (key, value) ->
            require(key.accepts(value)) { "${key.label}的值无效" }
            key.write(current, value)
        }
    }
}
