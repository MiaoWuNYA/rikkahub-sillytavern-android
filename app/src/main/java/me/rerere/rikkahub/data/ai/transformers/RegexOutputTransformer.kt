package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.replaceRegexes
import kotlin.uuid.Uuid
import org.koin.core.component.KoinComponent

// 生成循环每步重跑，带 depth 条件的正则会因为 depth 逐轮变化而翻转判定，故按用户轮冻结
// key = assistantId:conversationId:lastUserMsgId → (messageId → depth)
private val depthMemo = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, Int>>()

internal fun List<UIMessage>.replaceRegexesForDisplay(
    assistant: Assistant,
    conversationId: Uuid?,
    visual: Boolean,
    globalRegexes: List<AssistantRegex> = emptyList(),
): List<UIMessage> {
    val regexes = assistant.regexes + globalRegexes
    if (regexes.isEmpty()) return this
    val lastUserMsgId = lastOrNull { it.role == MessageRole.USER }?.id?.toString()
    val memo = if (lastUserMsgId != null) {
        val memoKey = "${assistant.id}:${conversationId ?: "no-conversation"}:$lastUserMsgId"
        depthMemo.getOrPut(memoKey) {
            if (depthMemo.size >= 64) depthMemo.clear()
            java.util.concurrent.ConcurrentHashMap()
        }
    } else {
        null
    }
    return mapIndexed { index, message ->
        // 官方深度语义：1 = 最新一条消息
        val depth = memo?.getOrPut(message.id.toString()) { size - index } ?: (size - index)
        if (message.role != MessageRole.ASSISTANT) return@mapIndexed message
        message.copy(
            parts = message.parts.map { part ->
                when (part) {
                    is UIMessagePart.Text -> part.copy(
                        text = part.text.replaceRegexes(regexes, AssistantAffectScope.ASSISTANT, visual, depth),
                    )

                    is UIMessagePart.Reasoning -> part.copy(
                        reasoning = part.reasoning.replaceRegexes(regexes, AssistantAffectScope.ASSISTANT, visual, depth),
                    )

                    else -> part
                }
            }
        )
    }
}

// 提示词层：结果会写回会话，markdownOnly 的正则（前端卡 HTML）必须跳过

// 流式期间只处理正在生成的最后一条：历史已被写回正则结果，逐 chunk 全量重跑卡顿且会二次替换
internal fun List<UIMessage>.applyStreamingRegexTail(
    assistant: Assistant,
    conversationId: Uuid?,
    globalRegexes: List<AssistantRegex> = emptyList(),
): List<UIMessage> {
    val last = lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT } ?: return this
    return dropLast(1) + listOf(last).replaceRegexesForDisplay(assistant, conversationId, visual = false, globalRegexes)
}

object RegexOutputTransformer : OutputMessageTransformer, KoinComponent {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.replaceRegexesForDisplay(
        ctx.assistant, ctx.conversationId, visual = false, globalRegexes = ctx.settings.globalRegexes,
    )
}

// 纯显示层：结果只进渲染

object RegexVisualTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.replaceRegexesForDisplay(
        ctx.assistant, ctx.conversationId, visual = true, globalRegexes = ctx.settings.globalRegexes,
    )
}
