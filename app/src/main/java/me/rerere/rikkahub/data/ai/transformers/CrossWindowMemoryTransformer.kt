package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.memory.CrossWindowMemoryStore

private const val TAG = "CrossWindowMemory"

/**
 * 跨窗口生活流注入：把同一助手在其他对话中的近期经历（含已压缩摘要）
 * 以独立 system 消息插入最新 user 消息之前。
 * 不进 system prompt：召回内容每轮变化，放在尾部只失效前缀缓存的最小范围。
 */
class CrossWindowMemoryTransformer(
    private val store: CrossWindowMemoryStore,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableCrossWindowMemory) return messages
        val conversationId = ctx.conversationId?.toString() ?: return messages

        val delta = store.consumeForeignDelta(
            assistantId = ctx.assistant.id.toString(),
            conversationId = conversationId,
            maxEntries = ctx.assistant.crossWindowMemoryTailEntries.coerceAtLeast(1),
        )
        if (delta.prompt.isBlank()) return messages
        Log.d(TAG, "inject cross-window delta: entries=${delta.entryCount} chars=${delta.charCount}")

        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) return messages + UIMessage.system(delta.prompt)
        return buildList {
            addAll(messages)
            add(lastUserIndex, UIMessage.system(delta.prompt))
        }
    }
}
