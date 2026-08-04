package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.knowledge.KnowledgeBaseService

private const val TAG = "KnowledgeBaseTransformer"

/**
 * 知识库检索注入 Transformer（升级版）
 *
 * 在生成前检索知识库，将相关片段以 XML 格式注入到 system prompt 末尾
 * 支持：RRF融合、Token预算、去重、Query Rewrite
 */
class KnowledgeBaseTransformer(
    private val knowledgeBaseService: KnowledgeBaseService,
) : InputMessageTransformer {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        val settings = ctx.settings
        val kbSettings = settings.kbInjectionSettings

        // 总开关：全局或助理开启都可以
        if (!kbSettings.enabled && !assistant.enableKnowledgeBase) return messages

        val query = messages.lastOrNull()?.toText() ?: return messages
        if (query.isBlank()) return messages

        try {
            // 搜（走 RRF + 去重 + Token预算）；给界面一个明确的阶段提示，避免看起来像卡住
            ctx.processingStatus?.value = "正在检索知识库…"
            val results = knowledgeBaseService.searchForInjection(
                query = query,
                assistantId = assistant.id.toString(),
                settings = settings,
            )

            if (results.isEmpty()) {
                ctx.processingStatus?.value = null
                return messages
            }

            // XML 格式注入
            val contextXml = buildString {
                appendLine()
                appendLine("<knowledge_context>")
                results.forEachIndexed { i, result ->
                    appendLine("""  <source index="${i + 1}" name="${escapeXml(result.source.name)}" type="${escapeXml(result.source.type.name)}">""")
                    appendLine("    ${escapeXml(result.chunk.text)}")
                    appendLine("  </source>")
                }
                appendLine("</knowledge_context>")
                appendLine("请优先使用上面 <knowledge_context> 中提供的资料来回答用户的问题。如果资料不相关，请忽略。")
            }

            // 注入到 system prompt 末尾
            val injected = messages.toMutableList()
            val systemIndex = injected.indexOfFirst { it.role == MessageRole.SYSTEM }
            if (systemIndex >= 0) {
                val sysMsg = injected[systemIndex]
                injected[systemIndex] = sysMsg.copy(
                    parts = sysMsg.parts.toMutableList().apply {
                        add(UIMessagePart.Text(contextXml))
                    }
                )
            } else {
                // 没有 system 消息，在最前面加一条
                injected.add(0, UIMessage(
                    role = MessageRole.SYSTEM,
                    parts = listOf(UIMessagePart.Text(contextXml))
                ))
            }

            Log.d(TAG, "Injected ${results.size} KB chunks via XML format")
            ctx.processingStatus?.value = null
            return injected
        } catch (e: Exception) {
            ctx.processingStatus?.value = null
            Log.e(TAG, "Knowledge base injection failed", e)
            return messages
        }
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

private fun UIMessage.toText(): String =
    parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
