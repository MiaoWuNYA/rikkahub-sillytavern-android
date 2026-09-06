package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 提示词查看器：缓存每次生成实际发送给模型的完整消息列表（按对话隔离，只留最后一次），
 * 供聊天抽屉"查看提示词"调试入口渲染成 HTML 页面。
 * 与 WebViewContentCache 同思路：内存缓存，进程被杀即失效（调试用途，无需持久化）。
 */
object PromptDebugCache {
    private val cache = ConcurrentHashMap<String, String>()

    fun store(conversationId: Uuid?, messages: List<UIMessage>) {
        if (messages.isEmpty()) return
        cache[conversationId?.toString() ?: ""] = buildHtml(messages)
    }

    fun get(conversationId: Uuid?): String? = cache[conversationId?.toString() ?: ""]

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun roleStyle(role: MessageRole): Pair<String, String> = when (role) {
        MessageRole.SYSTEM -> "system" to "#b45309"   // amber-700
        MessageRole.USER -> "user" to "#1d4ed8"       // blue-700
        MessageRole.ASSISTANT -> "assistant" to "#047857" // emerald-700
        else -> "tool" to "#7c3aed"                   // violet-700
    }

    private fun buildHtml(messages: List<UIMessage>): String {
        val body = StringBuilder()
        var totalChars = 0
        messages.forEachIndexed { index, message ->
            val (roleName, roleColor) = roleStyle(message.role)
            val textParts = message.parts.filterIsInstance<UIMessagePart.Text>()
            val reasoningParts = message.parts.filterIsInstance<UIMessagePart.Reasoning>()
            val toolParts = message.parts.filterIsInstance<UIMessagePart.Tool>()
            val content = textParts.joinToString("\n") { it.text }
            totalChars += content.length
            body.append(
                """
                <div class="msg">
                  <div class="head" style="color: $roleColor">#${index + 1} · ${roleName.uppercase()}${
                    message.name?.takeIf { it.isNotBlank() }?.let { " · ${escapeHtml(it)}" } ?: ""
                }</div>
                  ${if (content.isNotBlank()) """<pre class="text">${escapeHtml(content)}</pre>""" else ""}
                  ${
                    reasoningParts.filter { it.reasoning.isNotBlank() }.joinToString("") { r ->
                        """<details class="reasoning"><summary>思考过程</summary><pre>${escapeHtml(r.reasoning)}</pre></details>"""
                    }
                }
                  ${
                    toolParts.joinToString("") { t ->
                        """<details class="tool"><summary>工具调用 · ${escapeHtml(t.toolName)}</summary><pre>${escapeHtml(t.input)}</pre></details>"""
                    }
                }
                </div>
                """.trimIndent()
            )
        }
        val header = """
            <div class="summary">
              共 ${messages.size} 条消息 · 约 $totalChars 字符
            </div>
        """.trimIndent()
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <title>Prompt</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html, body { margin: 0; padding: 0; background: #f8fafc; }
              body { color: #0f172a; font-family: sans-serif; padding: 12px; }
              .summary { color: #475569; font-size: 13px; padding: 8px 4px; }
              .msg { background: #fff; border: 1px solid #e2e8f0; border-radius: 10px; margin-bottom: 10px; overflow: hidden; }
              .head { font-size: 12px; font-weight: 700; letter-spacing: 0.05em; padding: 8px 12px; background: #f1f5f9; }
              .text { white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: 13.5px; line-height: 1.55; margin: 0; padding: 10px 12px; }
              pre { white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: 12.5px; line-height: 1.5; margin: 0; padding: 8px 12px; color: #334155; }
              details { border-top: 1px dashed #e2e8f0; }
              summary { padding: 8px 12px; font-size: 12px; color: #64748b; cursor: pointer; }
              .reasoning pre { color: #64748b; }
              .tool pre { font-family: monospace; }
            </style>
            </head>
            <body>
            $header
            $body
            </body>
            </html>
        """.trimIndent()
        return html
    }
}
