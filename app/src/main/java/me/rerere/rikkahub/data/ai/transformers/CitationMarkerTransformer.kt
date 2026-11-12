package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

// GPT 联网回复的引用标记：U+E200 开、U+E202 分隔、U+E201 收。
// 正文里是私有区字符，不处理就会显示成一串方块；引用来源本身已在 citations 区块里
private const val CITE_OPEN = '\uE200'
private const val CITE_CLOSE = '\uE201'

private val CITE_BLOCK = Regex("$CITE_OPEN" + "cite" + "[^$CITE_CLOSE]*" + "$CITE_CLOSE")
private val MARKER_CHARS = Regex("[$CITE_OPEN\uE201\uE202]")

/** 删掉正文里的引用标记，流式生成时把没闭合的半截一起藏掉 */
object CitationMarkerTransformer : OutputMessageTransformer {

    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.map { it.stripped(streaming = true) }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.map { it.stripped(streaming = false) }

    private fun UIMessage.stripped(streaming: Boolean): UIMessage {
        if (role != MessageRole.ASSISTANT) return this
        if (parts.none { part -> part is UIMessagePart.Text && part.text.any { it == CITE_OPEN } }) return this
        var changed = false
        val newParts = parts.map { part ->
            if (part !is UIMessagePart.Text) return@map part
            val stripped = part.text.stripCitationMarkers(streaming)
            if (stripped != part.text) {
                changed = true
                part.copy(text = stripped)
            } else {
                part
            }
        }
        return if (changed) copy(parts = newParts) else this
    }

    private fun String.stripCitationMarkers(streaming: Boolean): String {
        var text = CITE_BLOCK.replace(this, "")
        // 流式中途可能只有开标记：先把尾部半截藏起来，等收标记到了再整体删掉
        val open = text.indexOf(CITE_OPEN)
        if (open >= 0) {
            text = if (streaming) text.substring(0, open) else MARKER_CHARS.replace(text, "")
        }
        return text
    }
}
