package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** 写入型宏：执行副作用，本身不产生文本，因此显示时应当为空。 */
private val SIDE_EFFECT_MACRO = Regex(
    """\{\{\s*(setvar|setglobalvar|incvar|incglobalvar|decvar|decglobalvar|addvar|addglobalvar|deletevar|flushvar)\s*(?::[^{}]*)?\}\}""",
    RegexOption.IGNORE_CASE,
)

/**
 * 纯包裹标签。
 *
 * 名单刻意短：只收那些**没有语义、纯用来圈正文**的标签。
 * 像 `<StatusBlock>`、`<details>` 这类是有实际含义或样式的，不能动。
 */
private val WRAPPER_TAGS = listOf(
    "正文", "content", "reply", "response", "output",
    "正文内容", "maintext", "main",
    "combat_driver",
)

private val WRAPPER_BLOCK_REGEX = Regex(
    """<(${WRAPPER_TAGS.joinToString("|")})>([\s\S]*?)</\1\s*>""",
    RegexOption.IGNORE_CASE,
)

/** 没有配对的孤立标签（流式生成中途常见）。 */
private val LONE_WRAPPER_REGEX = Regex(
    """</?(?:${WRAPPER_TAGS.joinToString("|")})\s*/?>""",
    RegexOption.IGNORE_CASE,
)

object OutputTagCleanupTransformer : OutputMessageTransformer {

    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.map { it.cleaned() }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.map { it.cleaned() }

    private fun UIMessage.cleaned(): UIMessage {
        if (role != MessageRole.ASSISTANT) return this
        if (parts.none { it is UIMessagePart.Text }) return this

        var changed = false
        val newParts = parts.map { part ->
            if (part !is UIMessagePart.Text) return@map part
            val cleaned = part.text.cleanPresetMarkup()
            if (cleaned != part.text) {
                changed = true
                part.copy(text = cleaned)
            } else {
                part
            }
        }
        return if (changed) copy(parts = newParts) else this
    }
}

internal fun String.cleanPresetMarkup(): String {
    var text = this

    // ① 包裹标签：留下内容
    text = WRAPPER_BLOCK_REGEX.replace(text) { it.groupValues[2] }
    // 剩下的孤立开闭标签（没配对，常见于流式生成中途）直接删
    text = LONE_WRAPPER_REGEX.replace(text, "")

    // ② 无输出的宏：写入型宏在显示上应当不存在
    text = SIDE_EFFECT_MACRO.replace(text, "")

    // 宏删掉后可能留下整行空白，压掉多余空行
    text = text.replace(Regex("\n{3,}"), "\n\n")

    return text.trim()
}
