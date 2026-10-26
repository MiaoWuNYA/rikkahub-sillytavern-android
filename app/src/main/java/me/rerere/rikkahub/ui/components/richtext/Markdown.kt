package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.rikkahub.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.parseCssColor
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalWorkspaceId
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.ui.markdown.Markdown
import me.rerere.ui.markdown.MarkdownBlock
import me.rerere.ui.markdown.MarkdownComponents
import me.rerere.ui.markdown.MarkdownDefaults
import me.rerere.ui.markdown.MarkdownInline
import me.rerere.ui.markdown.MarkdownInlineScope
import me.rerere.ui.markdown.MarkdownInlineWidget
import me.rerere.ui.markdown.plainText
import org.koin.compose.koinInject
import kotlin.time.Clock

private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)

// 引号染色：匹配半角/全角/中日韩各类引号包裹的内容
// 文本染色用的配对符号。引号与括号必须合成**一个**交替正则一遍扫完：
// 分两遍做时，第一遍注入的 `<span style="color:#x">` 里含引号，第二遍的引号正则会把
// style 属性的引号当成待染色的引号对，从第二处符号起整段错位，页面上就成了
// 「从第二个引号开始把 <span style="…"> 当文本显示」。
private const val BRACKET_OPENERS = "（【「『《‹〔｛{"
private const val BRACKET_PATTERN =
    "（(.+?)）|【(.+?)】|「(.+?)」|『(.+?)』|《(.+?)》|‹(.+?)›|〔(.+?)〕|｛(.+?)｝|\\{(.+?)\\}"
private const val QUOTE_PATTERN =
    "\"(.+?)\"|\u201C(.+?)\u201D|\u00AB(.+?)\u00BB|\uFF02(.+?)\uFF02"
private val TEXT_COLOR_REGEX = Regex("$BRACKET_PATTERN|$QUOTE_PATTERN")

// LaTeX 公式区域：里面的 {} 是公式语法（\begin{cases}、\lim_{x}），染色注入 span 会破坏 Math 渲染；
// 行内 $..$ 也允许跨行：cases 等环境常以单 $ 包裹跨多行，漏匹配公式就会被染色破坏
private val LATEX_SPAN_REGEX = Regex(
    """\$\$.+?\$\$|\$.+?\$|\\\(.+?\\\)|\\\[.+?\\\]""",
    RegexOption.DOT_MATCHES_ALL,
)

// 解析器只认 $ 定界符，\(..\)/\[..\] 需归一化，否则整段按普通文本显示
private val LATEX_PAREN_DELIM_REGEX = Regex("""\\\((.+?)\\\)|\\\[(.+?)\\\]""", RegexOption.DOT_MATCHES_ALL)

private val LATEX_RAW_NEWLINES = Regex("""[ \t]*\n+[ \t]*""")

private fun String.collapseLatexNewlines(): String = replace(LATEX_RAW_NEWLINES, " ").trim()

// 尚未带 open 属性的 <details> 标签
private val DETAILS_OPEN_REGEX = Regex("<details\\b(?!\\s+open\\b)")

internal fun String.normalizeCjkTildes(): String {
    if ('~' !in this) return this
    fun isCjk(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F ||
            ch.code in 0xFF00..0xFFEF || ch.code in 0x2600..0x27BF ||
            ch.code in 0x1F300..0x1FAFF

    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        if (this[i] != '~') { out.append(this[i]); i++; continue }
        var j = i
        while (j < length && this[j] == '~') j++
        val runLength = j - i
        val prev = if (i > 0) this[i - 1] else ' '
        val next = if (j < length) this[j] else ' '
        if (runLength == 1 && (isCjk(prev) || isCjk(next))) {
            out.append(0xFF5E.toChar())
        } else {
            repeat(runLength) { out.append('~') }
        }
        i = j
    }
    return out.toString()
}

// internal 而非 private：波浪线消歧是中文场景的高频修正，
// 规则需要可单测（见 CjkTildeTest），否则以后改动无法验证。
// 模型常把颜色标签转义后再输出（&lt;span style="color:#7EAFC4"&gt;文字&lt;/span&gt;），
// 解析器把它当纯文本，聊天里就出现「看得见颜色代码但不上色」。
// 这里把转义还原成真实标签交给渲染器，代码块内的转义保持原样。
private val ESCAPED_INLINE_TAG_REGEX = Regex(
    "&lt;(/?)(span|font|u|b|i|em|strong|br|sub|sup|small)\\b((?:(?!&gt;).)*?)&gt;",
    RegexOption.IGNORE_CASE,
)

internal fun restoreEscapedInlineTags(content: String): String {
    if ("&lt;" !in content) return content
    val codeBlocks = CODE_BLOCK_REGEX.findAll(content).map { it.range }.toList()
    return ESCAPED_INLINE_TAG_REGEX.replace(content) { match ->
        if (codeBlocks.any { match.range.first in it }) {
            match.value
        } else {
            val attrs = match.groupValues[3]
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
            "<${match.groupValues[1]}${match.groupValues[2]}$attrs>"
        }
    }
}

internal fun preProcessMarkdown(content: String): String {
    // \(..\)/\[..\] → $..$/$$..$$：数学提取器只认 $ 定界符；代码块内保持原样。
    // 公式内的真实换行折叠为空格（LaTeX 换行靠 \\）：多行 cases 折叠成单行后提取器才认
    val codeRanges = CODE_BLOCK_REGEX.findAll(content).map { it.range }.toList()
    val normalized = if ("\\(" in content || "\\[" in content) {
        LATEX_PAREN_DELIM_REGEX.replace(content) { m ->
            if (codeRanges.any { m.range.first in it }) m.value
            else if (m.groupValues[1].isNotEmpty()) "$${m.groupValues[1].collapseLatexNewlines()}$"
            else "$$${m.groupValues[2].collapseLatexNewlines()}$$"
        }
    } else {
        content
    }
    val escaped = restoreEscapedInlineTags(escapeGuideSequence(convertStatusBlocksToTables(normalized)))
    // 酒馆卡常拿 <details> 装工作记忆/状态栏，没有 open 属性时默认展开
    val withOpenDetails = DETAILS_OPEN_REGEX.replace(escaped) { "<details open${it.value.removePrefix("<details")}" }

    // 中文语气波浪线消歧：必须在解析前完成，否则 GFM 已把 `~文字~` 判成删除线
    if ('~' in withOpenDetails) {
        val blocks = CODE_BLOCK_REGEX.findAll(withOpenDetails).map { it.range }.toList()
        return withOpenDetails.normalizeCjkTildesOutside(blocks)
    }
    return withOpenDetails
}

/**
 * 对代码块之外的部分做波浪线消歧，代码块内原样保留
 * （代码里的 `~` 可能是运算符、路径或正则的一部分）。
 */
private fun String.normalizeCjkTildesOutside(codeBlocks: List<IntRange>): String {
    if (codeBlocks.isEmpty()) return normalizeCjkTildes()
    val out = StringBuilder(length)
    var cursor = 0
    for (range in codeBlocks) {
        if (range.first < cursor) continue
        out.append(substring(cursor, range.first).normalizeCjkTildes())
        out.append(substring(range.first, range.last + 1))
        cursor = range.last + 1
    }
    if (cursor < length) out.append(substring(cursor).normalizeCjkTildes())
    return out.toString()
}

@Preview(showBackground = true)
@Composable
private fun MarkdownPreview() {
    MaterialTheme {
        CompositionLocalProvider(LocalSettings provides Settings()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MarkdownBlock(
                    content = "Hi there!", modifier = Modifier.background(Color.Red)
                )
            }
        }
    }
}

/**
 * 应用内使用的 Markdown：在通用的 [Markdown] 组件上接入代码高亮、图片预览、LaTeX 渲染、
 * 表格工具栏和搜索引用角标这些和应用相关的能力；并前置本地酒馆前端卡（HTML 文档）
 * 的 WebView 渲染分支与引号染色、中文波浪线等本地预处理。
 */
@Composable
fun MarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {},
    /**
     * 前端卡的宿主能力包；透传给 [HtmlWebViewBlock]。
     * 为 null 时不注入任何宿主 API，卡会走它自己的「宿主未注入」分支。
     */
    cardHost: CardHostContext? = null,
    /** 当前对话消息快照（官方 swipes 结构），供卡内 `getChatMessages` 读取。 */
    cardMessagesJson: String = "[]",
    // 生成中禁用卡片渲染：半截 HTML 交给 WebView 会渲染出残缺网页
    isStreaming: Boolean = false,
) {
    val settings = LocalSettings.current.displaySetting

    // 酒馆角色卡等 HTML 富文本：把 HTML 卡片拆出来交给 WebView 渲染
    // （支持 <style>/class CSS）。前端卡（```html 围栏包完整 HTML 文档）
    // 优先按围栏拆分，否则 <html> 前的 DOCTYPE/head 会被当成散文渲染出源码。
    // 卡片默认内联展开、高度自适应——对齐官方把卡片内联进消息 DOM 的行为。
    val fencedCard = if (isStreaming) null else findFencedHtmlDocument(content)
    // 裸 HTML 路径要按「散文 / 卡片」交替拆段。
    val segments = if (fencedCard == null && !isStreaming) {
        splitCardSegments(content)
    } else {
        emptyList()
    }
    val hasCard = fencedCard != null || segments.any { it.first }
    if (hasCard) {
        Column(modifier) {
            if (fencedCard != null) {
                val (prose, fenced) = fencedCard
                if (prose.isNotEmpty()) {
                    MarkdownBlock(
                        content = prose,
                        onClickCitation = onClickCitation,
                        cardHost = cardHost,
                        cardMessagesJson = cardMessagesJson,
                        isStreaming = isStreaming,
                    )
                }
                HtmlWebViewBlock(html = fenced, cardHost = cardHost, cardMessagesJson = cardMessagesJson)
                return@Column
            }
            segments.forEach { (isCard, text) ->
                if (isCard) {
                    HtmlWebViewBlock(html = text, cardHost = cardHost, cardMessagesJson = cardMessagesJson)
                } else {
                    MarkdownBlock(
                        content = text,
                        onClickCitation = onClickCitation,
                        cardHost = cardHost,
                        cardMessagesJson = cardMessagesJson,
                        isStreaming = isStreaming,
                    )
                }
            }
        }
        return
    }

    // 文本染色：引号与括号一遍扫完，按配对符号的首字符决定用引号色还是括号色
    // 引号色变量与上游 quotedText 用的 quoteColor 区分开，避免同名遮蔽
    val darkMode = LocalDarkMode.current
    val localQuoteColor = if (settings.enableTextColor) {
        settings.quoteColor.ifBlank { if (darkMode) "#E18A24" else "#C7731E" }
    } else null
    val codeBlocks = CODE_BLOCK_REGEX.findAll(content).map { it.range }.toList()
    val latexSpans = LATEX_SPAN_REGEX.findAll(content).map { it.range }.toList()
    val coloredContent = if (localQuoteColor == null) {
        content
    } else {
        val bracketColor = settings.bracketColor.ifBlank { if (darkMode) "#7EAFC4" else "#5B8DEF" }
        TEXT_COLOR_REGEX.replace(content) { match ->
            val opener = match.value.firstOrNull()
            if (opener == null || codeBlocks.any { match.range.first in it } ||
                latexSpans.any { match.range.first in it && match.range.last <= it.last }
            ) {
                match.value
            } else {
                val color = if (opener in BRACKET_OPENERS) bracketColor else localQuoteColor
                "<span style=\"color:$color\">${match.value}</span>"
            }
        }
    }

    val preprocessed = preProcessMarkdown(coloredContent)

    val enableLatexRendering = settings.enableLatexRendering
    val enableQuoteHighlight = settings.enableQuoteHighlight
    val fontSize = LocalTextStyle.current.merge(style).fontSize
    val density = LocalDensity.current
    val quoteColor = MaterialTheme.colorScheme.tertiary
    val defaultTheme = MarkdownDefaults.theme(style)
    // 斜体着色：主题导入的 emphasis 样式优先，其次用户设置的斜体颜色，最后回退默认灰（与 WebView 渲染一致）
    val emphasis = remember(settings.themeMarkdownStyle, settings.italicsColor) {
        SpanStyle(
            color = settings.themeMarkdownStyle.emphasisColor
                ?: parseCssColor(settings.italicsColor)?.let { Color(it) }
                ?: Color(0xFF919191),
            background = settings.themeMarkdownStyle.emphasisBackground ?: Color.Unspecified,
        )
    }
    val theme = remember(defaultTheme, fontSize, density, enableQuoteHighlight, quoteColor, emphasis) {
        defaultTheme.copy(
            inlineCode = defaultTheme.inlineCode.copy(
                fontFamily = JetbrainsMono,
                fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0",
            ),
            // 段落间距跟随字号，调整字体大小时保持比例
            blockSpacing = if (fontSize.isSp) with(density) { fontSize.toDp() } else defaultTheme.blockSpacing,
            quotedText = SpanStyle(color = quoteColor).takeIf { enableQuoteHighlight },
            emphasis = emphasis,
        )
    }

    val currentOnClickCitation by rememberUpdatedState(onClickCitation)
    val components = remember(enableLatexRendering) {
        MarkdownComponents(
            codeBlock = { block ->
                HighlightCodeBlock(
                    code = block.code,
                    language = block.language ?: "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                    completeCodeBlock = block.closed,
                )
            },
            image = { image, _ ->
                ZoomableAsyncImage(
                    model = rememberWorkspaceImageUrl(image.url),
                    contentDescription = image.alt,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .widthIn(min = 120.dp)
                        .heightIn(min = 120.dp),
                )
            },
            mathBlock = { formula ->
                if (enableLatexRendering) {
                    MathBlock(
                        latex = formula,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = LocalTextStyle.current.fontSize,
                    )
                } else {
                    // 禁用 LaTeX 渲染时，以等宽字体显示原始公式
                    MarkdownDefaults.MathBlock(formula)
                }
            },
            table = { table -> TableCard(table) },
            inlineMath = MarkdownInlineScope::inlineMathWidgets.takeIf { enableLatexRendering },
            inlineLink = { link -> citationWidget(link) { currentOnClickCitation(it) } },
        )
    }

    Markdown(
        content = preprocessed,
        modifier = modifier.padding(horizontal = 4.dp),
        style = style,
        theme = theme,
        components = components,
    )
}

/**
 * 绑定了工作区时, 把 Rootfs 绝对路径 (如 /workspace/out.png) 解析成宿主机上的文件路径;
 * 解析期间返回 null 显示占位图, 找不到文件时原样返回交给 Coil
 *
 * Markdown 解析器会对 src 做百分号编码 (空格、中文等), 需取解码后的 path 并丢掉 query/fragment
 */
@Composable
private fun rememberWorkspaceImageUrl(url: String): String? {
    val workspaceId = LocalWorkspaceId.current
    if (workspaceId == null || !url.startsWith("/") || url.startsWith("//")) return url
    val path = remember(url) { url.toUri().path } ?: return url
    val workspaceRepository: WorkspaceRepository = koinInject()
    val resolved by produceState<String?>(initialValue = null, workspaceId, path) {
        value = workspaceRepository.resolveRootfsFile(workspaceId, path)?.absolutePath ?: url
    }
    return resolved
}

private fun MarkdownInlineScope.inlineMathWidgets(formula: String): List<MarkdownInlineWidget> {
    val fontSize = style.fontSize
    if (!fontSize.isSp) return emptyList()
    return with(density) {
        val fontSizePx = fontSize.toPx()
        // 将过长的行内公式按顶层运算符水平拆分为多段，每段最大宽度限制为字号的两倍，
        // 使其能在文本流中换行，避免单体公式超出可用宽度被挤出屏幕
        val drawables = splitLatex(
            latex = formula,
            maxWidthPx = fontSizePx * 2,
            fontSize = fontSizePx,
            color = contentColor.toArgb(),
        )
        if (drawables.isNotEmpty()) {
            drawables.map { drawable ->
                MarkdownInlineWidget(
                    placeholder = Placeholder(
                        width = drawable.bounds.width().toSp(),
                        height = drawable.bounds.height().toSp(),
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    LatexDrawable(drawable = drawable)
                }
            }
        } else {
            // 拆分失败时回退为单体内联渲染；连尺寸都量不出来说明公式有误，交给组件显示公式源码
            val bounds = assumeLatexSize(latex = formula, fontSize = fontSizePx)
            if (bounds.isEmpty) return emptyList()
            listOf(
                MarkdownInlineWidget(
                    placeholder = Placeholder(
                        width = bounds.width().toSp(),
                        height = bounds.height().toSp(),
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    MathInline(latex = formula, fontSize = fontSize)
                }
            )
        }
    }
}

// 搜索引用在正文里写作 [citation,域名](引用id)，渲染成可点击的角标
private fun citationWidget(link: MarkdownInline.Link, onClick: (String) -> Unit): MarkdownInlineWidget? {
    val text = link.children.plainText()
    if (!text.startsWith("citation,")) return null
    val domain = text.substringAfter("citation,")
    val id = link.destination
    if (id.length != 6) {
        // id 不合法的引用没法跳转，不显示
        return MarkdownInlineWidget(Placeholder(0.sp, 0.sp, PlaceholderVerticalAlign.TextCenter)) {}
    }
    return MarkdownInlineWidget(
        placeholder = Placeholder(
            width = (domain.length * 7).sp,
            height = 1.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        ),
    ) {
        Box(
            modifier = Modifier
                .clickable { onClick(id.trim()) }
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = domain,
                modifier = Modifier.wrapContentSize(),
                style = TextStyle(
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

// 表格卡片（工具栏 + 表格）
@Composable
private fun TableCard(table: MarkdownBlock.Table) {
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val tableCsv = remember(table) { buildTableCsv(table) }
    // HTML 写出的表格没有 Markdown 源码，复制时退回到 CSV
    val tableText = table.source ?: tableCsv

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(tableCsv.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.markdown_table),
                fontSize = 12.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val iconSize = 16.dp
                val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

                Icon(
                    imageVector = HugeIcons.Copy01,
                    contentDescription = "Copy",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            scope.launch {
                                clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("table", tableText)))
                            }
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )

                Icon(
                    imageVector = HugeIcons.Download04,
                    contentDescription = "Download",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            createDocumentLauncher.launch(
                                "table_${
                                    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                                }.csv"
                            )
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }
        }
        MarkdownDefaults.Table(
            table = table,
            outerBorder = null,
            shape = RectangleShape,
        )
    }
}

// 构建CSV内容，对包含逗号/引号/换行的字段进行转义
private fun buildTableCsv(table: MarkdownBlock.Table): String {
    fun escape(field: String): String {
        return if (field.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
    return buildString {
        appendLine(table.header.joinToString(",") { escape(it.content.plainText()) })
        table.rows.forEach { row ->
            appendLine(row.joinToString(",") { escape(it.content.plainText()) })
        }
    }
}
