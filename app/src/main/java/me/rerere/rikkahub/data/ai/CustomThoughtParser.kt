package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.CustomThoughtSettings

data class CustomThoughtMatch(
    val start: Int,
    val endExclusive: Int,
    val content: String,
) {
    fun removeFrom(text: String): String = text.removeRange(start, endExclusive)
}

/** 字面量、区分大小写，只提取最先出现的完整非空标签对。未闭合的流式正文原样保留。 */
fun findCustomThought(text: String, settings: CustomThoughtSettings): CustomThoughtMatch? {
    if (!settings.enabled) return null
    return settings.rules.asSequence()
        .filter { it.enabled && it.startTag.isNotBlank() && it.endTag.isNotBlank() }
        .mapNotNull { rule ->
            var offset = 0
            var match: CustomThoughtMatch? = null
            while (offset < text.length) {
                val start = text.indexOf(rule.startTag, offset)
                if (start < 0) break
                val contentStart = start + rule.startTag.length
                val end = text.indexOf(rule.endTag, contentStart)
                if (end < 0) break
                val content = text.substring(contentStart, end)
                if (content.isNotBlank()) {
                    match = CustomThoughtMatch(start, end + rule.endTag.length, content)
                    break
                }
                offset = end + rule.endTag.length
            }
            match
        }
        .minByOrNull { it.start }
}

/** 防止 Markdown 把配置标签当 HTML 吞掉；代码围栏及行内代码保持原样。 */
fun visibleThoughtTags(text: String, settings: CustomThoughtSettings): String {
    val tags = (settings.rules.flatMap { listOf(it.startTag, it.endTag) } +
        listOf("<think>", "</think>", "<thinking>", "</thinking>", "<thought>", "</thought>"))
        .filter { it.contains('<') || it.contains('>') }.distinct().sortedByDescending { it.length }
    val tagPattern = Regex("(?<!\\\\)(?:" + tags.joinToString("|") { Regex.escape(it) } + ")")
    fun escape(segment: String): String {
        if (tags.isEmpty()) return segment
        return tagPattern.replace(segment) { it.value.replace("<", "\\<").replace(">", "\\>") }
    }
    return buildString {
        var cursor = 0
        THOUGHT_CODE_SPANS.findAll(text).forEach { match ->
            append(escape(text.substring(cursor, match.range.first)))
            append(match.value)
            cursor = match.range.last + 1
        }
        append(escape(text.substring(cursor)))
    }
}

private val THOUGHT_CODE_SPANS = Regex("(`{3,}|~{3,})[^\\n]*\\n[\\s\\S]*?(?:\\n\\1[^\\n]*(?:\\n|$)|$)|(`+)[^`]*?\\2")
