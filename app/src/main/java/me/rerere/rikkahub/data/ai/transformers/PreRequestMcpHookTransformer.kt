package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.model.GenerationType
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

private const val TAG = "PreRequestMcpHook"

/**
 * 发消息前自动执行一次 MCP 工具，把返回文本作为临时上下文拼进当轮请求。
 *
 * 结果只加在本次请求的消息列表里（插在最新 user 消息之前），不写回对话历史；
 * 触发工具有多步 agentic 请求，按 (助手, 对话, 最新用户消息) 冻结首步结果，避免每步重跑。
 */
class PreRequestMcpHookTransformer(
    private val mcpManager: McpManager,
) : InputMessageTransformer {

    private val frozenResult = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val hook = ctx.settings.huadengSettings
        if (!hook.enablePreRequestHook) return messages
        // 代写/静默生成不注入，避免污染用户视角的回复
        if (ctx.generationType == GenerationType.IMPERSONATE || ctx.generationType == GenerationType.QUIET) {
            return messages
        }
        val toolName = hook.preRequestHookToolName.trim()
        if (toolName.isEmpty()) return messages
        val serverId = runCatching { Uuid.parse(hook.preRequestHookServerId.trim()) }.getOrNull()
            ?: return messages

        val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER } ?: return messages
        val userText = lastUserMessage.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (userText.isEmpty()) return messages

        val turnKey = "${ctx.assistant.id}:${ctx.conversationId}"
        val messageKey = lastUserMessage.id.toString()
        val cached = frozenResult[turnKey]?.takeIf { it.first == messageKey }?.second
        val injected = cached ?: run {
            val result = runCatching {
                mcpManager.callTool(serverId, toolName, buildArgs(hook.preRequestHookArgsTemplate, userText))
            }.onFailure {
                Log.w(TAG, "callTool failed: $toolName", it)
            }.getOrDefault(emptyList())
            val text = result.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            if (frozenResult.size >= 64) frozenResult.clear()
            frozenResult[turnKey] = messageKey to text
            text
        }
        if (injected.isBlank()) return messages

        Log.d(TAG, "inject hook result: tool=$toolName chars=${injected.length}")
        val payload = "## 自动召回上下文（工具 $toolName）\n$injected"
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        return buildList {
            addAll(messages)
            add(lastUserIndex, UIMessage.system(payload))
        }
    }

    /** 模板里的 ${last_user_message} 替换成用户原话后再解析成 JSON 对象；模板非法时退回 {"query": ...} */
    private fun buildArgs(template: String, userText: String): JsonObject {
        val escaped = JsonPrimitive(userText).toString().removeSurrounding("\"")
        val rendered = template
            .replace("\${last_user_message}", escaped)
            .replace("\$last_user_message", escaped)
            .ifBlank { "{}" }
        return runCatching { JsonInstant.parseToJsonElement(rendered).jsonObject }
            .getOrElse { JsonObject(mapOf("query" to JsonPrimitive(userText))) }
    }
}
