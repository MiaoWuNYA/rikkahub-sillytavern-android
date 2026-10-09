package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 清理模型输出里的「预设标记」，避免它们显示成正文。
 *
 * 社区预设常在提示词里要求模型用自定义标签组织输出，例如：
 *
 *     {{setvar:cotBegin:我们来看看用户的任务}}
 *     <thinking>…推理…</thinking>
 *     <正文>
 *     成都的夏夜闷得像个蒸笼…
 *     </正文>
 *
 * 这些标记是给**机器**看的。项目原来只处理了 `<think>`（见
 * [ThinkTagTransformer]），其余的原样出现在气泡里——用户看到的是
 * 「思考过程、标签、宏全都混在正文里」，而很难意识到那是标签没被识别。
 *
 * 这里只做两件确定安全的事：
 *
 * 1. **无输出的宏**（setvar / incvar / addvar 等写入型）替换为空。
 *    它们在输入侧已有实现（PlaceholderTransformer），但那是
 *    InputMessageTransformer，只管发给模型的内容；模型吐回来的
 *    没人处理，于是原样显示。
 *
 * 2. **纯包裹标签**（正文 / content / reply / response 等）去掉标签、
 *    保留内容。这些标签的唯一作用是把正文圈出来，标签本身没有语义。
 *
 * 刻意不做的：
 *   · 不删标签里的内容——万一判断错了，内容比标签重要得多
 *   · 不碰 `<think>`——那是 ThinkTagTransformer 的职责，重复处理会打架
 *   · 不认任意标签——只认一个明确的名单，避免误删模型真正想输出的内容
 */
/** 写入型宏：执行副作用，本身不产生文本，因此显示时应当为空。 */
private val SIDE_EFFECT_MACRO = Regex(
    // 冒号那段是可选的：flushvar 这类宏不带参数（{{flushvar}}），
    // 强制要求冒号会漏掉它们。其余宏形如 {{setvar:名字:值}}。
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
    // 状态栏里的驱动位：<combat_driver> 无 </combat_driver>
    // 标签本身无语义，值才是要显示的内容
    "combat_driver",
)

/** <正文>…</正文>：把整块替换成内部内容。 */
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

/**
 * 去掉预设标记。
 *
 * 顺序要紧：先剥包裹标签（保留内部内容），再删无输出的宏。
 * 反过来的话，标签里若嵌着宏也能删掉，但内容会先被拆散——
 * 保持一致的处理次序能让结果可预期。
 */
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
