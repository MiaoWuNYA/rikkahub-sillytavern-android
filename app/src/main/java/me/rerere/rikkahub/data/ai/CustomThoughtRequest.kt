package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.CustomThoughtSettings

/** 与显示层共享同一个匹配器；每条助手消息最多一处，不遍历工具输出或原生推理。 */
fun List<UIMessage>.withoutCustomThoughtForRequest(settings: CustomThoughtSettings): List<UIMessage> {
    if (!settings.enabled || !settings.excludeFromRequests) return this
    return map { message ->
        if (message.role != MessageRole.ASSISTANT) return@map message
        var extracted = false
        message.copy(parts = message.parts.map partMap@{ part ->
            if (part !is UIMessagePart.Text || extracted) return@partMap part
            val match = findCustomThought(part.text, settings) ?: return@partMap part
            extracted = true
            part.copy(text = match.removeFrom(part.text))
        })
    }
}

