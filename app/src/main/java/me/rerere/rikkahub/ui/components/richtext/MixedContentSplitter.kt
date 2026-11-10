package me.rerere.rikkahub.ui.components.richtext

/**
 * 混排消息的一段：[MarkdownBody] 正文走原生渲染，[HtmlPanel] 交给 WebView。
 */
sealed interface MixedContentPiece {
    val subIndex: Int

    data class MarkdownBody(val text: String, override val subIndex: Int) : MixedContentPiece

    data class HtmlPanel(
        val html: String,
        val panelTitle: String,
        override val subIndex: Int,
    ) : MixedContentPiece
}

private val PANEL_NAME_COMMENT = Regex(
    """<!--\s*(?:panel|title|name)\s*[:：]\s*([^->]+?)\s*-->""",
    RegexOption.IGNORE_CASE,
)
private val PANEL_NAME_TAG = Regex(
    """<(?:title|summary|h[1-4])[^>]*>([^<]{1,30})</(?:title|summary|h[1-4])>""",
    RegexOption.IGNORE_CASE,
)
private val PANEL_INPUT_TAG = Regex("""<(form|input|select|textarea)\b""", RegexOption.IGNORE_CASE)
private val PANEL_BUTTON_TAG = Regex("""<button\b""", RegexOption.IGNORE_CASE)
private val WHITESPACE_RUN = Regex("""\s+""")

/** 面板可读名：注释 > title/summary/h1-h4 > 关键词 > 序号兜底。 */
fun extractPanelDisplayName(html: String, index: Int = 0): String {
    PANEL_NAME_COMMENT.find(html)?.groupValues?.getOrNull(1)?.trim()
        ?.takeIf { it.isNotEmpty() }?.let { return it.take(24) }
    PANEL_NAME_TAG.find(html)?.groupValues?.getOrNull(1)?.trim()
        ?.takeIf { it.isNotEmpty() }?.let { return WHITESPACE_RUN.replace(it, " ").take(24) }
    return when {
        html.contains("状态栏") || html.contains("stat_data") || html.contains("Mvu.getMvuData") ->
            "角色状态栏"

        html.contains("世界风云") || html.contains("新闻") || html.contains("world", ignoreCase = true) ->
            "世界状态面板"

        html.contains("好感度") || html.contains("羁绊") || html.contains("affection", ignoreCase = true) ->
            "好感与关系面板"

        PANEL_INPUT_TAG.containsMatchIn(html) -> "交互设定面板"
        PANEL_BUTTON_TAG.containsMatchIn(html) -> "交互操作面板"
        else -> "预设渲染面板 #" + (index + 1)
    }
}

private val NON_HTML_CODE_FENCE = Regex(
    """```(?!html\b|htm\b|\s*\r?\n)[a-zA-Z0-9_+-]+[^\n]*\n[\s\S]*?```""",
    RegexOption.IGNORE_CASE,
)
private val SCRIPT_TAG = Regex("""<script[\s>]""", RegexOption.IGNORE_CASE)
private val INLINE_HANDLER_ATTR = Regex("""\son[a-z]{3,}\s*=""", RegexOption.IGNORE_CASE)
private val DOC_TAG = Regex("""<(!doctype\s+html|html[\s>])""", RegexOption.IGNORE_CASE)
private val MEDIA_TAG = Regex("""<(iframe|canvas|svg|audio|video)[\s>]""", RegexOption.IGNORE_CASE)
private val CONTAINER_TAG =
    Regex("""<(div|span|section|details|main|article|table|aside|header|footer)[\s>]""", RegexOption.IGNORE_CASE)
private val STYLE_TAG = Regex("""<(style|link\s+[^>]*rel\s*=\s*['"]?stylesheet)[\s>]""", RegexOption.IGNORE_CASE)
private val STYLE_CUE = Regex(
    """(background(?:-image)?\s*:\s*[^;"']*url\s*\(|display\s*:\s*(?:inline-)?(?:flex|grid)\b)""",
    RegexOption.IGNORE_CASE,
)
private val WIDGET_TAG = Regex("""<(button|input|select|textarea|form)[\s>]""", RegexOption.IGNORE_CASE)
private val STRUCT_OPEN = Regex("""<(div|section|form|details|table)[\s>]""", RegexOption.IGNORE_CASE)
private val STRUCT_CLOSE = Regex("""</(div|section|form|details|table)>""", RegexOption.IGNORE_CASE)
private val FRAGMENT_OPEN =
    Regex("""<(div|section|article|main|aside|header|footer|table|details|form|svg)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val FRAGMENT_CLOSE =
    Regex("""</(div|section|article|main|aside|header|footer|table|details|form|svg)>""", RegexOption.IGNORE_CASE)
private val CLASS_OR_STYLE = Regex("""\b(?:class|style|id)\s*=\s*['"][^'"]+['"]""", RegexOption.IGNORE_CASE)
private val ANY_CLOSE = Regex("""</[a-zA-Z][a-zA-Z0-9_-]*>""")

/**
 * 交互式 HTML 判定（阈值对齐酒馆前端卡的实测分布，见 MixedContentSplitterTest）。
 */
fun looksLikeInteractiveHtml(text: String): Boolean {
    if (text.length < 32) return false
    val cleaned = if ("```" in text) NON_HTML_CODE_FENCE.replace(text, "") else text
    if (cleaned.length < 24) return false
    if (SCRIPT_TAG.containsMatchIn(cleaned)) return true
    if (INLINE_HANDLER_ATTR.containsMatchIn(cleaned)) return true
    if (DOC_TAG.containsMatchIn(cleaned)) return true
    if (MEDIA_TAG.containsMatchIn(cleaned)) return true
    if (cleaned.contains("data-sttts-", ignoreCase = true) || cleaned.contains("sttts-", ignoreCase = true)) {
        return true
    }
    val hasContainer = CONTAINER_TAG.containsMatchIn(cleaned)
    if (hasContainer && STYLE_TAG.containsMatchIn(cleaned)) return true
    if (hasContainer && STYLE_CUE.containsMatchIn(cleaned)) return true
    val hasWidget = WIDGET_TAG.containsMatchIn(cleaned)
    return hasWidget && STRUCT_OPEN.containsMatchIn(cleaned) && STRUCT_CLOSE.containsMatchIn(cleaned)
}

/** 静态结构化 HTML 片段的判定（无 script、样式在内联属性里的卡片）。 */
fun looksLikeHtmlFragment(text: String): Boolean {
    if (text.length < 24) return false
    val cleaned = if ("```" in text) NON_HTML_CODE_FENCE.replace(text, "") else text
    val unwrapped = FENCED_HTML_DOC.matchEntire(cleaned.trim())?.groupValues?.getOrNull(1)?.trim() ?: cleaned.trim()
    if (unwrapped.length < 24) return false
    if (!FRAGMENT_OPEN.containsMatchIn(unwrapped) || !FRAGMENT_CLOSE.containsMatchIn(unwrapped)) return false
    if (CLASS_OR_STYLE.containsMatchIn(unwrapped)) return true
    return ANY_CLOSE.findAll(unwrapped).count() >= 2
}

fun shouldRenderAsHtmlPanel(text: String): Boolean =
    looksLikeInteractiveHtml(text) || looksLikeHtmlFragment(text)

/** 该段是否足够"像一张面板"：沿用卡片判据，再补上参考实现的交互/片段两条通路。 */
fun shouldSplitAsPanel(text: String): Boolean =
    looksLikeCardMarkup(text) || shouldRenderAsHtmlPanel(text)

private val FENCED_HTML_DOC = Regex("""```(?:html|htm)?\s*\n([\s\S]*?)```""", RegexOption.IGNORE_CASE)
private val FENCE_BLOCK = Regex("```[\\s\\S]*?```")
private val TAG_AT = Regex(
    """<(?:!doctype\s+html|html|head|body|style|script|div|section|main|article|details|form|fieldset|table|center|font|iframe|video|audio|canvas|svg|picture|figure|aside|header|footer|h[1-6]|p|ul|ol|dl|blockquote|pre|hr|br|img|link|title|meta)\b""",
    RegexOption.IGNORE_CASE,
)
private val CUSTOM_TAG_LINE_AT = Regex("""(?m)^[ \t]*</?[a-zA-Z][a-zA-Z0-9_-]*(?:[ \t][^<>\n]*)?/?>""")
private val TAG_NAME_OF = Regex("""<([a-zA-Z][a-zA-Z0-9_-]*)""")

private class PanelSpan(val start: Int, val end: Int, val html: String)

/** 从 [tagStart] 处的同名标签找配对闭合位置（不含），支持同名嵌套；找不到返回 -1。 */
internal fun findBalancedTagEnd(text: String, tagStart: Int, tagName: String): Int {
    val openRe = Regex("""<$tagName(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    val closeRe = Regex("""</$tagName\s*[^>]*>""", RegexOption.IGNORE_CASE)
    var depth = 0
    var cursor = tagStart
    while (cursor < text.length) {
        val nextOpen = openRe.find(text, cursor)
        val nextClose = closeRe.find(text, cursor) ?: return -1
        if (nextOpen != null && nextOpen.range.first < nextClose.range.first) {
            if (!nextOpen.value.endsWith("/>")) depth++
            cursor = nextOpen.range.last + 1
        } else {
            depth--
            cursor = nextClose.range.last + 1
            if (depth <= 0) return cursor
        }
    }
    return -1
}

/** 围栏块去掉标记与语言行后的内容。 */
private fun fenceContent(block: String): String {
    val body = block.removePrefix("```")
    val newline = body.indexOf('\n')
    if (newline < 0) return ""
    return body.substring(newline + 1).removeSuffix("```").trimCardEdges()
}

private fun findPanelSpans(text: String): List<PanelSpan> {
    val spans = mutableListOf<PanelSpan>()
    val codeRanges = mutableListOf<IntRange>()
    FENCE_BLOCK.findAll(text).forEach { fence ->
        val inner = fenceContent(fence.value)
        if (inner.isEmpty()) return@forEach
        // 卡片式围栏取围栏内部当面板，代码围栏整体跳过（里面的标签是代码）
        if (shouldSplitAsPanel(inner)) spans += PanelSpan(fence.range.first, fence.range.last + 1, inner)
        else codeRanges += fence.range
    }
    val starts = (TAG_AT.findAll(text).map { it.range.first }.toList() +
        CUSTOM_TAG_LINE_AT.findAll(text).map { it.range.first }.toList())
        .distinct()
        .sorted()
    starts.forEach { start ->
        if (codeRanges.any { start in it }) return@forEach
        if (spans.any { start in it.start until it.end }) return@forEach
        val name = TAG_NAME_OF.find(text, start)?.groupValues?.getOrNull(1) ?: return@forEach
        val end = findBalancedTagEnd(text, start, name).let { if (it < 0) text.length else it }
        if (end <= start) return@forEach
        val html = text.substring(start, end)
        if (!shouldSplitAsPanel(html)) return@forEach
        spans += PanelSpan(start, end, html)
    }
    return spans.sortedBy { it.start }
}

/**
 * 把一条消息拆成 Markdown 正文与 HTML 面板交替的序列。
 *
 * 为什么不能只切一刀：酒馆卡的开场白常是「散文 + 状态栏 + 正文 + 面板」，
 * 整段交给 WebView 会把中间几千字正文当 HTML 源码吞掉；整段交给 Markdown
 * 又会让 <style>、表单失效。分段后正文 0 延迟原生渲染，只有面板挂 WebView。
 */
fun splitMixedMessagePieces(text: String): List<MixedContentPiece> {
    val normalized = normalizeNewlines(text)
    if (normalized.isBlank()) return emptyList()

    // 围栏整篇文档（first_mes 的标准形态）：围栏前是散文，围栏内是文档
    findFencedHtmlDocument(normalized)?.let { (prose, doc) ->
        val pieces = mutableListOf<MixedContentPiece>()
        if (prose.isNotBlank()) pieces += MixedContentPiece.MarkdownBody(prose, 0)
        pieces += MixedContentPiece.HtmlPanel(doc, extractPanelDisplayName(doc, 0), pieces.size)
        return pieces
    }

    val spans = findPanelSpans(normalized)
    if (spans.isEmpty()) {
        return listOf(MixedContentPiece.MarkdownBody(normalized.trimCardEdges(), 0))
    }

    val pieces = mutableListOf<MixedContentPiece>()
    var cursor = 0
    var panelIndex = 0
    for (span in spans) {
        val prose = normalized.substring(cursor, span.start).trimCardEdges()
        if (prose.isNotEmpty()) pieces += MixedContentPiece.MarkdownBody(prose, pieces.size)
        pieces += MixedContentPiece.HtmlPanel(
            html = span.html,
            panelTitle = extractPanelDisplayName(span.html, panelIndex++),
            subIndex = pieces.size,
        )
        cursor = span.end
    }
    if (cursor < normalized.length) {
        val tail = normalized.substring(cursor).trimCardEdges()
        if (tail.isNotEmpty()) pieces += MixedContentPiece.MarkdownBody(tail, pieces.size)
    }
    return pieces
}
