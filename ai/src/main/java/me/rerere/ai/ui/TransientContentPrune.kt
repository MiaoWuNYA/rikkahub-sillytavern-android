package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid

private val PRUNE_JSON = Json { ignoreUnknownKeys = true }

/**
 * 上下文瞬态内容裁剪（省 token）：
 *
 * 网页搜索结果、图片/音视频等"瞬态大块内容"只在最近的几轮对话里有价值，超过轮次后仍随每次
 * 请求全量发送会白白消耗大量 token。本函数把保留窗口之外的消息中的瞬态部分原地替换为占位说明
 * （带消息 ID），消息条数与工具调用结构保持不变——AI 之后需要原文时，可调用 read_history_message
 * 工具按消息 ID 取回（搜索类内容也可重新搜索/抓取）。
 *
 * 只作用于发送给模型的副本，消息存储与 UI 展示不受影响。
 */
const val DEFAULT_PRUNE_KEEP_USER_TURNS = 2

fun List<UIMessage>.pruneOldTransientContent(keepUserTurns: Int = DEFAULT_PRUNE_KEEP_USER_TURNS): List<UIMessage> {
    if (isEmpty()) return this
    // 保留边界 = 倒数第 keepUserTurns 条 USER 消息的下标：它之前的用户轮（含对应的 AI 回复）
    // 全部裁剪，它开始（含）到末尾原样保留
    var seenUserTurns = 0
    var boundary = -1
    for (index in lastIndex downTo 0) {
        if (this[index].role == MessageRole.USER) {
            seenUserTurns++
            if (seenUserTurns == keepUserTurns) {
                boundary = index
                break
            }
        }
    }
    if (boundary <= 0) return this

    val head = subList(0, boundary).map { it.pruneTransientParts() }
    return head + subList(boundary, size)
}

private const val PRUNE_NOTICE =
    "[历史瞬态内容已省略以节省上下文：%s。原始内容不随历史请求发送，如需查看请调用 read_history_message 工具并传入消息 ID %s]"

private fun UIMessage.pruneTransientParts(): UIMessage {
    var changed = false
    val prunedParts = parts.map { part ->
        val pruned: UIMessagePart? = when (part) {
            is UIMessagePart.Image -> transientPlaceholder("图片附件", id)
            is UIMessagePart.Video -> transientPlaceholder("视频附件", id)
            is UIMessagePart.Audio -> transientPlaceholder("音频附件", id)
            is UIMessagePart.ServerTool ->
                // ServerTool 必须整体替换为占位文本：三家 provider 多轮请求都从
                // metadata.call/result 原始协议块重放（不读 output），只改 output 等于没裁剪。
                // 整体移除 call+result 配对是协议安全的（不会留下无 result 的 server_tool_use），
                // 存储与 UI 不受影响，AI 需要原文时可用 read_history_message 取回
                if (isWebContentTool(part.toolName)) {
                    transientPlaceholder("网页内容（${part.toolName}）", id)
                } else null
            is UIMessagePart.Tool ->
                // 保留 tool_call 结构，只把工具结果换成占位（否则 provider 端 tool_calls 与
                // tool result 配对断裂会直接报错）
                if (isWebContentTool(part.toolName)) {
                    part.copy(output = listOf(transientPlaceholder(webToolHint(part), id)))
                } else null
            else -> null
        }
        if (pruned != null) {
            changed = true
            pruned
        } else {
            part
        }
    }
    return if (changed) copy(parts = prunedParts) else this
}

/** 搜索/抓取类工具占位里保留查询意图，便于 AI 决定是否取回 */
private fun webToolHint(part: UIMessagePart.Tool): String {
    if (part.output.isEmpty()) return "网页内容（${part.toolName}）"
    val hint = runCatching {
        PRUNE_JSON.parseToJsonElement(part.input.ifBlank { "{}" }).jsonObject.let { input ->
            input["query"]?.jsonPrimitive?.contentOrNull
                ?: input["keywords"]?.jsonPrimitive?.contentOrNull
                ?: input["url"]?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull()
    return if (hint.isNullOrBlank()) "网页内容（${part.toolName}）" else "网页内容：$hint"
}

private fun isWebContentTool(toolName: String): Boolean = toolName.startsWith("search_") ||
    toolName.startsWith("scrape_") ||
    toolName.startsWith("web_search") ||
    toolName.startsWith("web_fetch")

private fun transientPlaceholder(what: String, messageId: Uuid): UIMessagePart.Text =
    UIMessagePart.Text(PRUNE_NOTICE.format(what, messageId))
