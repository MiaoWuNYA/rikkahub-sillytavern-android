package me.rerere.rikkahub.ui.components.richtext

/**
 * 酒馆前端卡（HTML 角色卡）的文档归一化与 CSS 作用域隔离。
 *
 * 对齐 SillyTavern 官方的处理链路（public/script.js 的 messageFormatting +
 * public/scripts/chats.js 的 encodeStyleTags/decodeStyleTags）：
 *
 * 1. 卡片文档里的 <style> 原样注入会污染外层页面（`body{}`、`*{}` 等全局规则），
 *    官方的做法是给消息内元素的 class 统一加 `custom-` 前缀，并把 CSS 选择器
 *    改写成 `.mes_text .custom-xxx`，从而把样式**限定在这一条消息内部**。
 *    我们用一个隔离根 `#rikka-card-root` 作为同等的命名空间。
 *
 * 2. 官方把卡片内联渲染进消息的 DOM 节点（`.mes_text`），高度天然由内容决定，
 *    因此不存在"高度上报 / 手势仲裁 / 折叠按钮"这套东西。我们的 WebView 方案
 *    必须自己解决高度，但样式隔离这层可以完整对齐官方语义。
 *
 * 3. 卡片常带 `position: fixed` 的装饰层与 `100vh` 尺寸——在官方那是铺满浏览器
 *    视口的壁纸，塞进聊天列表的卡片里会变成"盖住整屏/撑爆卡片"。归一化时把这些
 *    改成跟随卡片容器，才能得到官方在同一位置上的视觉结果。
 */

/** 卡片内容被包裹进这个 id 的容器，作为 CSS 隔离与作用域锚点。 */
internal const val CARD_ROOT_ID = "rikka-card-root"

/** 前导零宽字符/BOM：检测前先剥掉，避免卡在首字符导致识别失败。 */
internal val INVISIBLE_LEADING = charArrayOf(
    '\u200b', '\ufeff', '\u200e', '\u200f', '\u200c', '\u200d'
)

/** 统一行尾：酒馆导出的卡普遍带 \r\n，会干扰围栏正则与 marked 解析。 */
internal fun normalizeNewlines(text: String): String =
    text.replace("\r\n", "\n").replace("\r", "\n")

/** 剥掉首尾空白与前导不可见字符。 */
internal fun String.trimCardEdges(): String =
    trim(' ', '\n', '\r', '\t', *INVISIBLE_LEADING)

// 行首为块级 HTML 标签，说明这里开始进入卡片区域
private val BLOCK_TAG_AT = Regex(
    """<(div|table|details|center|section|article|font|iframe|video|audio|canvas|svg|picture|figure|form|fieldset|main|aside|nav|header|footer|h[1-6]|p|ul|ol|dl|blockquote|pre|hr|br|img|style|link|script|html|head|body|title|meta|!DOCTYPE)\b""",
    RegexOption.IGNORE_CASE,
)

// 自定义标签（<StatusBlock>、<main tickbubble> 等酒馆卡惯用写法）出现在行首
private val CUSTOM_TAG_LINE_START = Regex(
    """^[ \t]*</?[a-zA-Z][a-zA-Z0-9_-]*([ \t][^<>\n]*)?/?>""",
    RegexOption.MULTILINE,
)

/**
 * 自定义标签包着某种块状内容（HTML 标签、或 ``` 围栏）。
 *
 * 实测（宝可梦卡 first_mes）两种真实形态：
 *
 *     <UI>
 *     <details><summary>…</summary>…</details>      ← 内部有成对 HTML
 *     </UI>
 *
 *     <normal_status>                              ← 内部只有 ```yaml 围栏
 *     ```yaml
 *     『时间』: 夜 23:03
 *     ```
 *     </normal_status>
 *
 * 旧实现只认第一种（要求内部出现两个成对标签），于是状态栏那种
 * 「自定义标签 + 围栏」被判成非卡片，整个开场白控件不再解析。
 * 现在把围栏也计入块状内容。
 */
private val CUSTOM_TAG_WRAPPED_BLOCK = Regex(
    """<([a-zA-Z][a-zA-Z0-9_-]*)>[^<]*(?:<(?:[a-zA-Z][\w-]*)\b|```)[\s\S]*?</\1\s*>""",
    RegexOption.IGNORE_CASE,
)

// 围栏开头。
//
// 原实现只认 ```html，但裸 ``` 同样常见——酒馆卡把整篇 HTML 文档直接塞进
// 无语言标注的围栏里，例如：
//     ```
//     <html lang="en"><head><meta charset="UTF-8" /><style>…</style>…
//     ```
// 此时旧的 FENCED_HTML_OPEN 不匹配，findFencedHtmlDocument 返回 null，
// 于是整段被交给 splitCardSegments 按「散文 / 卡片」拆，
// <title>、<meta>、<style> 全被当正文渲染成源码，卡片样式整体失效。
//
// 放宽为「可选的 html 标注」即可。安全性由调用方的 isFullHtmlDocument
// 兜底：只有围栏内容确实是完整 HTML 文档（<!DOCTYPE / <html>…</html>）
// 才会被当成卡片，普通代码块（```python 等）不会误判。
private val FENCED_HTML_OPEN = Regex(
    """```[ \t]*(?:html)?[ \t]*\n""",
    RegexOption.IGNORE_CASE,
)

// 独占一行的闭合围栏
private val CLOSING_FENCE_LINE = Regex("""^[ \t]*```[ \t]*$""", RegexOption.MULTILINE)

/** 消息里是否含 HTML 富文本（供上层决定渲染路径）。 */
fun isHtmlRichContent(text: String): Boolean {
    val t = normalizeNewlines(text).trimCardEdges()
    return BLOCK_TAG_AT.containsMatchIn(t) ||
        CUSTOM_TAG_LINE_START.containsMatchIn(t) ||
        FENCED_HTML_OPEN.containsMatchIn(t)
}

/**
 * 定位第一处 HTML 卡片起点，返回 (起始下标, 从该处到结尾的片段)。
 *
 * 必须与 [isHtmlRichContent] 的宽判定区分开：这里是「真的要把消息渲染成网页」的
 * 决策点，一旦误判，正常回复会整条变成 WebView。
 *
 * 实测到的误判来源：注入类插件会要求模型「首行用 ``` 命名交付物」并「缺失细节时
 * 自造占位符」（如 `<TARGET>`、`<HOST>`）。模型据此把正文写成 HTML 代码块或
 * 直接输出占位符角标，旧实现只要任意一行以标签开头就返回卡片，于是整条回复被
 * 当成前端卡渲染，表现就是「回复的消息变成网页显示」。
 *
 * 因此裸 HTML（无围栏）路径要求是**文档级**内容；只有行首标签不算数。
 *
 * 注意卡片通常不是从消息开头开始：宝可梦卡这类开场白是
 * 「一大段散文 + `<normal_status>` 状态栏 + `<UI>` 面板」，
 * 若从第一个标签起整体交给 WebView，中间几千字正文会被当成 HTML 源码吞掉。
 * 所以这里从每个候选起点向右截取，取**第一个能通过判定**的片段。
 */
/**
 * 把消息拆成「散文 / 卡片」交替的段落。
 *
 * 为什么不能只找一个起点：酒馆卡的开场白经常是
 * 「`<normal_status>` 状态栏 + 大段正文 + `<UI>` 面板 + `<special_status>`」，
 * 卡片块被正文隔开。若只取第一个能通过判定的起点并吞掉其后全部内容，
 * 中间一千多字正文会变成 HTML 源码显示在 WebView 里。
 *
 * 返回每个段落的 (是否是卡片, 文本)，按原顺序拼接即等于原文。
 */
fun splitCardSegments(text: String): List<Pair<Boolean, String>> {
    val normalized = normalizeNewlines(text)
    val starts = (BLOCK_TAG_AT.findAll(normalized).map { it.range.first }.toList() +
        CUSTOM_TAG_LINE_START.findAll(normalized).map { it.range.first }.toList())
        .distinct()
        .sorted()
    val segments = mutableListOf<Pair<Boolean, String>>()
    var cursor = 0
    var i = 0
    while (i < starts.size) {
        val start = starts[i]
        if (start < cursor) { i++; continue }
        val rest = normalized.substring(start)
        if (!looksLikeCardMarkup(rest)) { i++; continue }
        // 该卡片块的结束位置：自定义标签的闭合处；没有就取到文末
        val selfClose = CUSTOM_TAG_SELF_CLOSE.find(rest)
        val end = selfClose?.range?.last?.plus(start + 1) ?: normalized.length
        val piece = normalized.substring(start, end).trimCardEdges()
        if (piece.isEmpty()) { i++; continue }
        if (start > cursor) {
            val prose = normalized.substring(cursor, start).trimCardEdges()
            if (prose.isNotEmpty()) segments += false to prose
        }
        segments += true to piece
        cursor = end
        i++
    }
    if (cursor < normalized.length) {
        val tail = normalized.substring(cursor).trimCardEdges()
        if (tail.isNotEmpty()) segments += false to tail
    }
    return segments
}

/**
 * 定位第一处 HTML 卡片起点，返回 (起始下标, 从该处到结尾的片段)。
 *
 * 与 [splitCardSegments] 的区别：这里只回答「卡片从哪开始」，
 * 用于只需要一个边界、不需要多段拆分的场景。
 */
fun findHtmlCard(text: String): Pair<Int, String>? {
    val normalized = normalizeNewlines(text)
    val starts = (BLOCK_TAG_AT.findAll(normalized).map { it.range.first }.toList() +
        CUSTOM_TAG_LINE_START.findAll(normalized).map { it.range.first }.toList())
        .distinct()
        .sorted()
    for (start in starts) {
        val candidate = normalized.substring(start)
        if (looksLikeCardMarkup(candidate)) return start to candidate
    }
    return null
}

/** 自定义标签在本片段内的闭合位置（用于判断它与后续区块是否相连） */
private val CUSTOM_TAG_SELF_CLOSE = Regex(
    // 非贪婪：必须停在**本标签**的第一个闭合处。
    // 贪婪会让 <UI>…</UI> 一路吃到后面的 <special_status>…</special_status>，
    // 把相邻卡片块吞并成一个，后续块就再也切不出来了。
    """^<([a-zA-Z][a-zA-Z0-9_-]*)>[\s\S]*?</\1\s*>""",
    RegexOption.IGNORE_CASE,
)

/**
 * 裸 HTML（无围栏）能否当作卡片渲染。
 *
 * 两条通路：
 * - 文档级内容（`<!DOCTYPE`/`<html>…</html>`）无条件接受；
 * - 片段级内容必须「看起来是一张卡」——有闭合结构、并且带卡片特征。
 *
 * 卡片特征按可靠性排序：
 * 1. `<style>`（前端卡必有）；
 * 2. 自定义标签包着成对的 HTML —— 酒馆卡最典型的写法是
 *    `<UI><details><summary>…</summary>…</details></UI>`、
 *    `<StatusBlock><table>…</table></StatusBlock>`。
 *    **只看块级标签数量是不够的**：`<UI>` 里常只有一个 `<details>`，
 *    达不到「≥3 个块级标签」的门槛，于是这类开场白控件会被整体拒掉，
 *    表现就是「宝可梦卡的开场白控件直接失效不解析」。
 *    自定义标签 + 内部成对标签才是真正的判据。
 * 3. 多个块级标签；
 * 4. 多个带 class 的成对标签。
 *
 * 这样既保住「开场白 + 状态面板」这类酒馆卡写法，又不会让散文里的
 * 零散标签（含插件要求模型自造的 `<TARGET>` 之类占位符）把整条消息变成网页。
 */
internal fun looksLikeCardMarkup(html: String): Boolean {
    if (isFullHtmlDocument(html)) return true
    val t = html.trimCardEdges()
    if (t.isEmpty()) return false
    // 出现在行首的孤立自定义占位符（<TARGET>、<HOST>）不是卡片
    if (CUSTOM_TAG_LINE_START.matches(t)) return false
    // 必须有闭合标签，否则只是残缺片段
    if (!Regex("""</[a-zA-Z][\w-]*\s*>""").containsMatchIn(t)) return false
    val hasStyle = Regex("""<style\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)
    if (hasStyle) return true
    // 自定义标签包裹块状内容：酒馆卡的状态面板/UI 控件
    if (CUSTOM_TAG_WRAPPED_BLOCK.containsMatchIn(t)) return true
    val blockTags = BLOCK_TAG_AT.findAll(t).count()
    val classedPairs = Regex("""<([a-zA-Z][\w-]*)[^>]*\bclass\s*=""", RegexOption.IGNORE_CASE)
        .findAll(t).count()
    return blockTags >= 3 || classedPairs >= 2
}

/**
 * 判定是否为完整 HTML 文档（前端卡）：以 `<!DOCTYPE` 或 `<html` 开头，
 * 或含 `</html>` 之类的文档级闭合标签。
 */
fun isFullHtmlDocument(html: String): Boolean {
    val t = normalizeNewlines(html).trimCardEdges()
    if (t.startsWith("<!DOCTYPE", ignoreCase = true)) return true
    if (t.startsWith("<html", ignoreCase = true)) return true
    // 前面可能有注释/说明，往后找文档级标签
    if (Regex("""<html[\s>]""", RegexOption.IGNORE_CASE).containsMatchIn(t) &&
        Regex("""</html\s*>""", RegexOption.IGNORE_CASE).containsMatchIn(t)
    ) {
        return true
    }
    return false
}

/**
 * 围栏前端卡：first_mes 常是 ```html 围栏包着的完整 HTML 文档。
 * 返回 (围栏前的散文, 剥掉围栏后的文档)。
 *
 * 文档内部通常还嵌 ```yaml 状态栏，所以闭合围栏取**最后一次**行首 ```，
 * 否则文档会被第一个内嵌围栏截断。
 */
fun findFencedHtmlDocument(text: String): Pair<String, String>? {
    val normalized = normalizeNewlines(text)
    val open = FENCED_HTML_OPEN.find(normalized) ?: return null
    val afterOpen = open.range.last + 1
    if (afterOpen >= normalized.length) return null

    val rest = normalized.substring(afterOpen)
    val close = CLOSING_FENCE_LINE.findAll(rest).lastOrNull()
    // 没有闭合围栏时，退化为"到文本末尾"，视为完整文档
    val doc = (if (close != null) rest.substring(0, close.range.first) else rest).trimCardEdges()
    if (doc.isEmpty()) return null
    if (!isFullHtmlDocument(doc)) return null

    // 截到 </html>：闭合围栏取"最后一次"可能把围栏后的散文一起圈进来
    val docEnd = doc.lastIndexOf("</html>", ignoreCase = true)
    val finalDoc = if (docEnd >= 0) doc.substring(0, docEnd + "</html>".length) else doc
    return normalized.substring(0, open.range.first).trimCardEdges() to finalDoc
}

/**
 * 把卡片文档拆成 (文档级 head 内容, body 内容)，并抽出所有 `<style>` 文本。
 * 用于把文档"压平"成可放进隔离容器的一段 HTML。
 */
internal data class CardDocument(
    val headHtml: String,
    val bodyHtml: String,
    val styleCss: String,
)

private val STYLE_BLOCK = Regex("""<style\b[^>]*>([\s\S]*?)</style\s*>""", RegexOption.IGNORE_CASE)
private val LINK_STYLESHEET = Regex("""<link\b[^>]*rel\s*=\s*["']?stylesheet["']?[^>]*>""", RegexOption.IGNORE_CASE)
private val HEAD_BLOCK = Regex("""<head\b[^>]*>([\s\S]*?)</head\s*>""", RegexOption.IGNORE_CASE)
private val BODY_BLOCK = Regex("""<body\b([^>]*)>([\s\S]*?)</body\s*>""", RegexOption.IGNORE_CASE)
private val DOCTYPE = Regex("""<!DOCTYPE[^>]*>""", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("""</?html\b[^>]*>""", RegexOption.IGNORE_CASE)

internal fun parseCardDocument(doc: String): CardDocument {
    val normalized = normalizeNewlines(doc).trimCardEdges()
    val styles = mutableListOf<String>()
    STYLE_BLOCK.findAll(normalized).forEach { styles += it.groupValues[1] }

    val head = HEAD_BLOCK.find(normalized)?.groupValues?.get(1).orEmpty()
    val bodyMatch = BODY_BLOCK.find(normalized)
    val body = bodyMatch?.groupValues?.get(2)
        ?: run {
            // 没有 <body>：去掉 head/doctype/html 外壳后整段都算 body
            normalized
                .replace(HEAD_BLOCK, "")
                .replace(DOCTYPE, "")
                .replace(HTML_TAG, "")
        }

    val headRest = head.replace(STYLE_BLOCK, "")
    return CardDocument(
        headHtml = headRest,
        bodyHtml = body,
        styleCss = styles.joinToString("\n"),
    )
}

/**
 * 把卡片 CSS 作用域化，对齐官方 `decodeStyleTags`：选择器统一加上隔离根前缀，
 * 并剥离外部资源引用（`@import` / 绝对 URL），避免卡片把样式泄漏到外层页面。
 */
internal fun scopeCardCss(css: String, rootSelector: String = "#$CARD_ROOT_ID"): String {
    if (css.isBlank()) return ""
    val cleaned = css
        // @import 会引入外部样式表，直接剥掉
        .replace(Regex("""@import[^;]*;""", RegexOption.IGNORE_CASE), "")
        // @charset 无意义
        .replace(Regex("""@charset[^;]*;""", RegexOption.IGNORE_CASE), "")

    val out = StringBuilder()
    var i = 0
    while (i < cleaned.length) {
        val ch = cleaned[i]
        // 跳过注释
        if (ch == '/' && i + 1 < cleaned.length && cleaned[i + 1] == '*') {
            val end = cleaned.indexOf("*/", i + 2)
            i = if (end < 0) cleaned.length else end + 2
            continue
        }
        // 规则块
        val brace = cleaned.indexOf('{', i)
        if (brace < 0) {
            out.append(cleaned.substring(i))
            break
        }
        val selector = cleaned.substring(i, brace).trim()
        val close = matchBrace(cleaned, brace)
        if (close < 0) {
            out.append(cleaned.substring(i))
            break
        }
        val bodyRaw = cleaned.substring(brace + 1, close)

        if (selector.startsWith("@")) {
            // @media/@supports 等：递归处理内部规则，at-rule 头原样保留
            val inner = scopeCardCss(bodyRaw, rootSelector)
            out.append(selector).append('{').append(inner).append('}')
        } else if (selector.isNotEmpty()) {
            out.append(scopeSelectorList(selector, rootSelector))
                .append('{')
                .append(bodyRaw)
                .append('}')
        }
        i = close + 1
    }
    return out.toString()
}

/** 找到与 [openIndex] 处 `{` 配对的 `}`，正确处理嵌套与字符串。 */
private fun matchBrace(text: String, openIndex: Int): Int {
    var depth = 0
    var i = openIndex
    var quote = '\u0000'
    while (i < text.length) {
        val c = text[i]
        if (quote != '\u0000') {
            if (c == '\\') i++ else if (c == quote) quote = '\u0000'
        } else {
            when (c) {
                '"', '\'' -> quote = c
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        i++
    }
    return -1
}

/**
 * 给逗号分隔的选择器列表逐个加作用域前缀。
 * `body`/`html`/`:root` 这类根选择器映射到隔离容器自身，
 * `*` 映射到容器内所有元素，其余直接前缀化。
 */
internal fun scopeSelectorList(selectors: String, rootSelector: String = "#$CARD_ROOT_ID"): String =
    splitTopLevel(selectors).joinToString(",") { scopeSingle(it.trim(), rootSelector) }

private fun scopeSingle(selector: String, rootSelector: String): String {
    if (selector.isEmpty()) return selector
    // :root / html / body → 容器自身
    val rootLike = Regex("""^(html|body|:root)\b""", RegexOption.IGNORE_CASE)
    if (rootLike.containsMatchIn(selector)) {
        return rootLike.replaceFirst(selector, rootSelector)
    }
    // 以 * 开头 → 容器内所有元素
    if (selector.startsWith("*")) {
        return "$rootSelector ${selector.removePrefix("*").trim()}".trim()
    }
    // 已经是作用域内的选择器（卡片自带前缀或嵌套调用）则不重复加
    if (selector.startsWith(rootSelector)) return selector
    return "$rootSelector $selector"
}

/** 按顶层逗号切分（忽略括号内的逗号，如 :is(a, b)）。 */
private fun splitTopLevel(text: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    var quote = '\u0000'
    for (i in text.indices) {
        val c = text[i]
        if (quote != '\u0000') {
            if (c == '\\') continue
            if (c == quote) quote = '\u0000'
            continue
        }
        when (c) {
            '"', '\'' -> quote = c
            '(', '[' -> depth++
            ')', ']' -> depth--
            ',' -> if (depth == 0) {
                parts += text.substring(start, i)
                start = i + 1
            }
        }
    }
    parts += text.substring(start)
    return parts.filter { it.isNotBlank() }
}
