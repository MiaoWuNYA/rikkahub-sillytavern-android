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
    // agentic 工具循环每步重跑本 transformer，而 consumeForeignDelta 取出即清空：
    // 首步注入、后续步消失会让请求前缀每步分叉。按 (助手, 对话, 最新用户消息)
    // 冻结首步取出的 delta，一轮内每步注入同一份
    private val frozenDelta = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableCrossWindowMemory) return messages
        val conversationId = ctx.conversationId?.toString() ?: return messages

        val turnKey = "${ctx.assistant.id}:$conversationId"
        val lastUserMsgId = messages.lastOrNull { it.role == MessageRole.USER }?.id?.toString()
        val prompt = if (lastUserMsgId != null) {
            val frozen = frozenDelta[turnKey]?.takeIf { it.first == lastUserMsgId }
            if (frozen != null) {
                frozen.second
            } else {
                val delta = store.consumeForeignDelta(
                    assistantId = ctx.assistant.id.toString(),
                    conversationId = conversationId,
                    maxEntries = ctx.assistant.crossWindowMemoryTailEntries.coerceAtLeast(1),
                )
                frozenDelta[turnKey] = lastUserMsgId to delta.prompt
                delta.prompt
            }
        } else {
            store.consumeForeignDelta(
                assistantId = ctx.assistant.id.toString(),
                conversationId = conversationId,
                maxEntries = ctx.assistant.crossWindowMemoryTailEntries.coerceAtLeast(1),
            ).prompt
        }
        if (prompt.isBlank()) return messages
        Log.d(TAG, "inject cross-window delta: chars=${prompt.length}")

        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) return messages + UIMessage.system(prompt)
        return buildList {
            addAll(messages)
            add(lastUserIndex, UIMessage.system(prompt))
        }
    }
}
