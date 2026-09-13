package me.rerere.ai.ui

/**
 * 中转站兼容：修复 Gemini 经 OpenAI 兼容中转时 reasoning_content 吞掉正文的问题。
 *
 * 某些中转站会把实际回复放进 reasoning_content 字段，content 只剩
 * "response" 前缀伪影或为空。流式过程中无法提前判定（思考阶段 content
 * 本来就为空，逐条判断会把正常思考误提升为正文），因此只在流结束后
 * 对整条回复评估一次：正文确认为空而推理有实质内容时，把推理提升为正文。
 */
private val RESPONSE_PREFIX_REGEX =
    Regex("(?i)^response(?=\\s|:|\$|[\\u4e00-\\u9fff\\u3040-\\u30ff])\\s*:?\\s*")

fun List<UIMessage>.fixProxyPromotedReply(): List<UIMessage> {
    val last = lastOrNull()?.takeIf { it.role == me.rerere.ai.core.MessageRole.ASSISTANT } ?: return this
    // 工具调用轮次：思考模型一轮结束时只有思考+工具调用、正文为空是正常形态，
    // 此时把思考提升为正文会把 CoT 变成持久化的假回复、丢失 reasoning 元数据
    if (last.parts.any { it is UIMessagePart.Tool || it is UIMessagePart.ServerTool }) return this
    val reasoning = last.parts.filterIsInstance<UIMessagePart.Reasoning>()
        .joinToString("") { it.reasoning }
    if (reasoning.isBlank()) return this
    val text = last.parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }
    // 正文剥离 response 前缀后为空 → 正文不存在，推理即正文
    if (RESPONSE_PREFIX_REGEX.replace(text, "").trimStart().isNotEmpty()) return this
    val promoted = UIMessagePart.Text(RESPONSE_PREFIX_REGEX.replace(reasoning, "").trimStart())
    val kept = last.parts.filter { it !is UIMessagePart.Text && it !is UIMessagePart.Reasoning }
    // 正文保持在工具调用之前（原顺序：推理在前、文本在其后、工具最后）
    val insertIdx = kept.indexOfFirst { it is UIMessagePart.Tool || it is UIMessagePart.ServerTool }
        .takeIf { it >= 0 } ?: kept.size
    val parts = kept.toMutableList().apply { add(insertIdx, promoted) }
    return dropLast(1) + last.copy(parts = parts)
}
