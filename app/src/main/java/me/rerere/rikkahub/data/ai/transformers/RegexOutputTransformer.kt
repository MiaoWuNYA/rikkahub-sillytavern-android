package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import org.koin.core.component.KoinComponent

object RegexOutputTransformer : OutputMessageTransformer, KoinComponent {
    // 按用户轮冻结的每条消息深度：agentic 工具循环每步重跑本 transform，
    // depth = messages.size - index 每步都变，带 depth 条件的正则对同一条消息的
    // 判定可能步间翻转（改写前缀打断缓存）。旧消息的深度首步记下、整轮复用；
    // 本轮新生成的消息不在备忘里，按当前列表实时计算。
    // key = assistantId:conversationId:lastUserMsgId → (messageId → depth)
    private val depthMemo = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, Int>>()

    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (assistant.regexes.isEmpty()) return messages // No regexes, return original messages
        val lastUserMsgId = messages.lastOrNull { it.role == MessageRole.USER }?.id?.toString()
        val memo = if (lastUserMsgId != null) {
            val memoKey = "${ctx.assistant.id}:${ctx.conversationId ?: "no-conversation"}:$lastUserMsgId"
            depthMemo.getOrPut(memoKey) {
                if (depthMemo.size >= 64) depthMemo.clear()
                java.util.concurrent.ConcurrentHashMap()
            }
        } else {
            null
        }
        return messages.mapIndexed { index, message ->
            // 官方深度语义：1 = 最新一条消息
            val depth = memo?.getOrPut(message.id.toString()) { messages.size - index }
                ?: (messages.size - index)
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
