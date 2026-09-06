package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import org.koin.core.component.KoinComponent

object RegexOutputTransformer : OutputMessageTransformer, KoinComponent {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (assistant.regexes.isEmpty()) return messages // No regexes, return original messages
        return messages.mapIndexed { index, message ->
            // 官方深度语义：1 = 最新一条消息
            val depth = messages.size - index
            val scope = when (message.role) {
                MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
                else -> return@mapIndexed message // Skip non-assistant messages
            }
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            part.copy(text = part.text.replaceRegexes(assistant, scope, visual = false, depth = depth))
                        }

                        is UIMessagePart.Reasoning -> {
                            part.copy(reasoning = part.reasoning.replaceRegexes(assistant, scope, visual = false, depth = depth))
                        }

                        else -> part
                    }
                }
            )
        }
    }
}
