package me.rerere.rikkahub.ui.components.richtext

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.View as ViewIcon
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.webview.WebViewLocalAssets
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.base64Encode

// 块级 HTML 标签：出现在消息任意位置都值得走 WebView（排除 span：引号染色会注入 <span>，
// 排除 p/img：误报率高，纯文本提到也会命中）。
// 含 html/head/body/!DOCTYPE：酒馆前端卡的完整 HTML 文档，第一个块级标签可能藏在 <head> 深处
private val HTML_TAG_ANYWHERE = Regex(
    """<(div|style|table|details|center|section|article|font|iframe|video|audio|h[1-6]|html|head|body|!DOCTYPE)\b""",
    RegexOption.IGNORE_CASE,
)

// ```html 围栏开头的行：前端卡常把整个 HTML 文档包在围栏里导出
private val FENCED_HTML_OPEN = Regex(
    """```[ \t]*html[ \t]*\r?\n""",
    RegexOption.IGNORE_CASE,
)

// 酒馆自定义标签（如 <main tickbubble>、<StatusBlock>、<normal_status>）：
// 只要出现在行首就视为 HTML 内容；排除 <https://...> 这类 markdown 自动链接
private val CUSTOM_TAG_LINE_START = Regex(
    """^[ \t]*</?[a-zA-Z][a-zA-Z0-9_-]*([ \t][^<>\n]*)?/?>""",
    RegexOption.MULTILINE,
)

// 开头可能被塞进零宽字符/BOM，检测前先剥掉
private val INVISIBLE_LEADING = charArrayOf(
    '\u200b', '\ufeff', '\u200e', '\u200f', '\u200c', '\u200d'
)

/**
 * 检测消息是否为 HTML 富文本（酒馆角色卡开场白/正文等）。
 * 这类内容依赖 <style> 标签与 class 选择器 CSS，Compose 管线无法还原，
 * 需要整段交给 WebView（内置 marked.js，markdown 与 HTML 混排也能正常渲染）。
 *
 * 不要求以 < 开头：酒馆状态卡常是"散文 + <div>状态面板"的混排，
 * 且 CSS 可能定义在别的消息里，后续消息只有裸 <div>。
 */
fun isHtmlRichContent(text: String): Boolean {
    val t = text.trim(' ', '\n', '\r', '\t', *INVISIBLE_LEADING)
    return HTML_TAG_ANYWHERE.containsMatchIn(t) || CUSTOM_TAG_LINE_START.containsMatchIn(t) ||
        FENCED_HTML_OPEN.containsMatchIn(t)
}

/**
 * 定位消息中第一处块级 HTML 的位置，返回 (起始下标, 从该处到末尾的内容)。
 * 用于把"散文 + HTML 卡片"混排消息拆开：散文走普通渲染，HTML 卡片折叠展示。
 */
fun findHtmlCard(text: String): Pair<Int, String>? {
    val match = listOfNotNull(
        HTML_TAG_ANYWHERE.find(text),
        CUSTOM_TAG_LINE_START.find(text),
    ).minByOrNull { it.range.first } ?: return null
    return match.range.first to text.substring(match.range.first)
}

/**
 * 酒馆前端卡：first_mes 常是 ```html 围栏包着的完整 HTML 文档。
 * 命中时返回 (围栏前的散文, 剥掉围栏后的完整 HTML 文档)。
 * 文档内部可能嵌 ```yaml 等围栏，所以闭合围栏取"行首 ```"的最后一次出现，
 * 而非懒惰匹配到第一个闭合（否则文档会被截断）。
 */
private val CLOSING_FENCE_LINE = Regex("""^[ \t]*```[ \t]*$""", RegexOption.MULTILINE)

fun findFencedHtmlDocument(text: String): Pair<String, String>? {
    val open = FENCED_HTML_OPEN.find(text) ?: return null
    val afterOpen = open.range.last + 1
    if (afterOpen >= text.length) return null
    val rest = text.substring(afterOpen)
    val close = CLOSING_FENCE_LINE.findAll(rest).lastOrNull() ?: return null
    val doc = rest.substring(0, close.range.first)
        .replace("\r\n", "\n")
        .trim(' ', '\n', '\r', '\t')
        .takeIf { it.isNotEmpty() } ?: return null
    // 只有当内容像 HTML 文档时才按围栏文档处理，避免误吞 ```html 代码演示
    if (!doc.startsWith("<!DOCTYPE", ignoreCase = true) && !doc.startsWith("<html", ignoreCase = true)) {
        return null
    }
    // 截到 </html> 为止：闭合围栏取"最后一次"，后面的散文可能被一起圈进来，
    // HTML 解析器会把 </html> 之后的内容塞进 body 显示出来
    val docEnd = doc.lastIndexOf("</html>", ignoreCase = true)
    val finalDoc = if (docEnd >= 0) doc.substring(0, docEnd + "</html>".length) else doc
    return text.substring(0, open.range.first).trim(' ', '\n', '\r', '\t') to finalDoc
}

/** 是否为完整 HTML 文档（<!DOCTYPE 或 <html> 开头）：整文档直接作为页面加载，不走 marked。 */
fun isFullHtmlDocument(html: String): Boolean {
    val t = html.trimStart(' ', '\n', '\r', '\t', *INVISIBLE_LEADING)
    return t.startsWith("<!DOCTYPE", ignoreCase = true) || t.startsWith("<html", ignoreCase = true)
}

/**
 * 用 WebView 渲染消息内容：marked.js 解析（markdown+HTML 混排），
 * 高度通过 JS 轮询 + ResizeObserver 上报实现自适应。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlWebViewBlock(
    html: String,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    // 内联卡片高度封顶：超出部分 WebView 内部滚动（卡片可交互的前提），
    // 同时保证聊天列表在卡片外仍可正常滚动
    val maxCardHeight = LocalConfiguration.current.screenHeightDp * 0.7f
    // marked.js 直接内联进页面：不依赖运行时 assets 拦截，拦截一旦失败
    // 整页脚本会静默失败变成空白
    val markedJs = remember {
        runCatching {
            context.assets.open("html/marked.min.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    // JS 上报的内容高度（CSS px ≈ Compose dp），0 表示尚未上报
    var contentHeight by remember { mutableIntStateOf(0) }
    // 内容是否超过封顶高度：超过时卡片只显示前 70% 屏高，需给用户一个进入全屏的入口
    val clipped = contentHeight > 0 && with(density) { contentHeight.toDp() } > maxCardHeight.dp
    val page = remember(html, colorScheme, markedJs) {
        buildHtmlBlockPage(html, textColor = colorScheme.onSurface, markedJs = markedJs)
    }
    // 全屏页使用不透明背景：全屏 WebView 默认白底，透明背景 + 深色主题的浅色文字会看不清
    val fullscreenPage = remember(html, colorScheme, markedJs) {
        buildHtmlBlockPage(
            html,
            textColor = colorScheme.onSurface,
            markedJs = markedJs,
            backgroundColor = colorScheme.surface,
        )
    }

    val navController = LocalNavController.current
    val openFullscreen = {
        val contentId = WebViewContentCache.store(context.cacheDir, fullscreenPage)
        navController.navigate(Screen.WebView(contentId = contentId))
    }

    Column(modifier = modifier) {
        AndroidView(
            factory = { context ->
            InteractiveWebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowContentAccess = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(
                    HtmlHeightBridge { h ->
                        if (h > contentHeight || contentHeight == 0) contentHeight = h
                    },
                    "rikkaHost",
                )
                // 点按交给 Compose 的 clickable 进全屏（见下方 clipped 分支），避免 WebView 吞掉点击
                isLongClickable = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        return WebViewLocalAssets.intercept(
                            view.context.applicationContext,
                            request.url
                        ) ?: super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // 加载完成后轮询内容高度，兜底图片异步加载导致的撑高
                        pollContentHeight(view, onHeight = { h ->
                            if (h > contentHeight || contentHeight == 0) contentHeight = h
                        })
                    }
                }
            }
        },
        update = { webView ->
            if (webView.tag != page) {
                webView.tag = page
                webView.loadDataWithBaseURL(
                    WEB_VIEW_BASE_URL,
                    page,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
        onRelease = {
            it.stopLoading()
            it.removeAllViews()
            it.destroy()
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (contentHeight > 0) {
                    Modifier.height(with(density) { contentHeight.toDp() })
                } else {
                    Modifier.heightIn(min = 120.dp)
                }
            )
            .heightIn(max = maxCardHeight.dp),
        )

        // 超过封顶高度时给出显式入口：卡片只显示前 70% 屏高，点击查看完整网页。
        // 不能依赖"点卡片"——内容超出时 WebView 会消费 ACTION_DOWN（内部滚动），
        // Compose 的 clickable 收不到手势；封顶外的按钮则始终可用。
        if (clipped) {
            TextButton(
                onClick = { openFullscreen() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    HugeIcons.ViewIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.html_block_fullscreen),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        // 内联 WebView 为保证聊天列表可滚动不消费触摸事件（只读），
        // 长按选择/内部滚动等交互放到全屏页完成
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            onCollapse?.let {
                IconButton(
                    onClick = it,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        HugeIcons.ArrowUp01,
                        contentDescription = stringResource(R.string.html_card_collapse),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = { openFullscreen() },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    HugeIcons.ViewIcon,
                    contentDescription = stringResource(R.string.html_block_fullscreen),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * HTML 角色卡容器。
 * 完整 HTML 文档（前端卡）默认折叠为按钮：一次渲染 20KB+ 的脚本化文档
 * 是"随缘卡死/闪退"的主要来源。点击折叠按钮直接进入全屏查看器渲染完整网页——
 * 既不触发聊天列表内联渲染大文档的卡顿，也能一次看到完整内容（issue #3）。
 * 混排小卡片照旧默认展开内联渲染。
 */
@Composable
fun HtmlCardBlock(
    html: String,
    modifier: Modifier = Modifier,
) {
    // 完整 HTML 文档（前端卡）默认折叠，混排小卡片照旧默认展开
    val fullDocument = remember(html) { isFullHtmlDocument(html) }
    var expanded by rememberSaveable(html) { mutableStateOf(!fullDocument) }
    if (expanded) {
        HtmlWebViewBlock(html = html, modifier = modifier, onCollapse = { expanded = false })
    } else {
        // 折叠态的完整文档：点击直接进全屏，而不是展开内联（内联仍会封顶 70% 屏高）
        HtmlDocumentLaunchCard(html = html, modifier = modifier, onExpand = { expanded = true })
    }
}

/** 折叠态的完整 HTML 文档卡片：点主体进全屏查看器，点展开按钮则内联渲染 */
@Composable
private fun HtmlDocumentLaunchCard(
    html: String,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val navController = LocalNavController.current
    val markedJs = remember {
        runCatching {
            context.assets.open("html/marked.min.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    val fullscreenPage = remember(html, colorScheme, markedJs) {
        buildHtmlBlockPage(
            html,
            textColor = colorScheme.onSurface,
            markedJs = markedJs,
            backgroundColor = colorScheme.surface,
        )
    }
    val openFullscreen = {
        val contentId = WebViewContentCache.store(context.cacheDir, fullscreenPage)
        navController.navigate(Screen.WebView(contentId = contentId))
    }

    Surface(
        onClick = openFullscreen,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                HugeIcons.Earth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.html_card_expand),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onExpand,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 内联卡片 WebView：可交互（按钮/折叠面板能点），但只在"内容确实超出封顶高度、
 * 需要卡片内滚动"时才消费手势——否则把触摸事件全部交还外层聊天列表，
 * 避免短卡片吞掉滑动手势导致聊天页翻不动（老 PassThroughWebView 无条件放行，
 * 代价是按钮全部点不了）。
 */
private class InteractiveWebView(context: Context) : WebView(context) {
    init {
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private var canScrollInternally = false

    fun updateScrollability() {
        // 高度上报有延迟，松手时再按当前内容判断
        canScrollInternally = computeVerticalScrollRange() > height
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            updateScrollability()
        }
        // 点按永远放行（按钮/折叠面板要能点）；仅当内容超出且是滑动时才消费
        return if (canScrollInternally && event.actionMasked != MotionEvent.ACTION_UP &&
            event.actionMasked != MotionEvent.ACTION_POINTER_UP
        ) {
            super.onTouchEvent(event)
        } else {
            false
        }
    }
}

private const val HEIGHT_JS =
    "(function(){return Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0);})()"

/**
 * 页面加载后周期性读取内容高度：图片懒加载/字体渲染完成都会撑高页面，
 * 只上报变大的值，避免抖动。
 */
private fun pollContentHeight(
    view: WebView,
    onHeight: (Int) -> Unit,
    remaining: Int = 15,
) {
    if (remaining <= 0) return
    view.postDelayed({
        runCatching {
            view.evaluateJavascript(HEIGHT_JS) { value ->
                val h = value?.trim()?.removeSurrounding("\"")?.toDoubleOrNull()?.toInt() ?: 0
                if (h > 0) onHeight(h)
            }
        }
        pollContentHeight(view, onHeight, remaining - 1)
    }, 400)
}

private class HtmlHeightBridge(private val onHeight: (Int) -> Unit) {
    @JavascriptInterface
    fun reportHeight(height: Int) {
        onHeight(height)
    }
}

/**
 * 包装消息为完整页面：透明背景、随主题文字颜色、marked.js 渲染 markdown+HTML 混排。
 * 内容 base64 注入，避免 </script> 等转义问题。
 */
private fun buildHtmlBlockPage(
    html: String,
    textColor: androidx.compose.ui.graphics.Color,
    markedJs: String,
    backgroundColor: androidx.compose.ui.graphics.Color? = null,
): String {
    // CRLF 归一化：酒馆导出的卡常带 \r\n，会把 marked/正则预处理搞乱
    val normalized = html.replace("\r\n", "\n").replace("\r", "\n")
    // 完整 HTML 文档（前端卡）直接作为页面本身加载——包 wrapper 过 marked 会把文档
    // 切碎，DOMParser 搬运又会让 <script> 变成不执行的惰性节点。只注入高度上报脚本
    if (isFullHtmlDocument(normalized)) {
        return buildFullDocPage(normalized.trim(' ', '\n', '\t', *INVISIBLE_LEADING))
    }
    val b64 = normalized.trim(' ', '\n', '\t', *INVISIBLE_LEADING).base64Encode()
    val textArgb = textColor.toArgb()
    val textCss = String.format("#%06X", textArgb and 0xFFFFFF)
    // 全屏页传不透明背景色；内联保持透明以融入聊天气泡
    val bgCss = if (backgroundColor != null) {
        String.format("#%06X", backgroundColor.toArgb() and 0xFFFFFF)
    } else {
        "transparent"
    }
    // marked.js 内联（源码不含 "</script>" 字面量，可安全嵌入）；读不到时退回 assets 拦截加载
    val markedTag = if (markedJs.isNotBlank()) {
        "<script>\n$markedJs\n</script>"
    } else {
        """<script src="/assets/html/marked.min.js"></script>"""
    }
    return """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>HTML</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html, body { margin: 0; padding: 0; background: $bgCss; }
  body { color: $textCss; font-family: sans-serif; word-break: break-word; }
  img { max-width: 100%; }
  code { background: rgba(128,128,128,0.2); padding: 1px 3px; border-radius: 3px; word-break: break-all; overflow-wrap: anywhere; }
  pre { background: rgba(128,128,128,0.15); padding: 8px; border-radius: 6px; overflow-x: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
</head>
<body>
<div id="rikka-root"></div>
$markedTag
<script>
(function() {
  var root = document.getElementById('rikka-root');
  var src = '';
  try {
    src = decodeURIComponent(escape(window.atob('$b64')));
  } catch (e) {
    try { src = window.atob('$b64'); } catch (e2) {}
  }
  function esc(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  try {
    // 酒馆卡的 HTML 段落常整体缩进(4空格)，marked 会将其视为缩进代码块原样显示，先去掉行首缩进
    src = src.replace(/^[ \t]+(<\/?[a-zA-Z]|<!--)/gm, '${'$'}1');
    // 修复 "< img" 这类被塞进空格的标签（酒馆卡常见，否则整个标签当文本显示）
    src = src.replace(/<\s+(img|br|hr|div|span|p|table|thead|tbody|tr|td|th|ul|ol|li|h[1-6]|details|summary|section|article|center|font|blockquote|em|strong|small|sub|sup|button|label)\b/gi, '<${'$'}1');
    // 先把 ``` 围栏代码块转成 <pre>：位于自定义标签（如 <normal_status>/<details>）内部时
    // marked 会把它们吞进 HTML 块原样输出，yaml 面板会显示成带 ``` 的字面文本
    src = src.replace(/```[^\n`]*\n([\s\S]*?)```/g, function(m, code) {
      return '\n<pre><code>' + esc(code) + '</code></pre>\n';
    });
    if (window.marked && window.marked.parse) {
      window.marked.setOptions({ gfm: true, breaks: true });
      root.innerHTML = window.marked.parse(src);
    } else {
      root.innerHTML = src;
    }
  } catch (e) {
    root.innerHTML = '<pre>' + esc(src) + '</pre>';
  }
  // 兜底：万一整段没渲染出来，至少把源码显示出来而不是空白
  if (!root.firstChild) {
    root.innerHTML = '<pre>' + esc(src) + '</pre>';
  }
  function report() {
    var h = Math.max(
      document.documentElement.scrollHeight,
      document.body ? document.body.scrollHeight : 0,
      root ? root.scrollHeight : 0
    );
    if (window.rikkaHost && window.rikkaHost.reportHeight && h > 0) {
      window.rikkaHost.reportHeight(h);
    }
  }
  window.addEventListener('load', report);
  setTimeout(report, 100);
  setTimeout(report, 500);
  setTimeout(report, 1500);
  if (window.ResizeObserver) {
    new ResizeObserver(report).observe(document.documentElement);
  }
})();
</script>
</body>
</html>"""
}

/**
 * 完整 HTML 文档（前端卡）直接作为页面本身加载：包 wrapper 过 marked 会把文档
 * 切碎，DOMParser 搬运又会让 <script> 变成不执行的惰性节点。
 * 只往 </head> 或文档最前面注入高度上报脚本；深色主题下若文档没写背景色
 * （透明底），加一层默认背景防止浅色文字看不清。
 */
private fun buildFullDocPage(doc: String): String {
    val inject = """
<script>
(function() {
  function report() {
    var h = Math.max(
      document.documentElement.scrollHeight,
      document.body ? document.body.scrollHeight : 0
    );
    if (window.rikkaHost && window.rikkaHost.reportHeight && h > 0) {
      window.rikkaHost.reportHeight(h);
    }
  }
  window.addEventListener('load', report);
  setTimeout(report, 100);
  setTimeout(report, 500);
  setTimeout(report, 1500);
  setTimeout(report, 4000);
  if (window.ResizeObserver) {
    new ResizeObserver(report).observe(document.documentElement);
  }
  if (window.ResizeObserver) {
    new ResizeObserver(report).observe(document.body || document.documentElement);
  }
})();
</script>
"""
    val injectAt = doc.indexOf("</head>", ignoreCase = true)
    return if (injectAt >= 0) {
        doc.substring(0, injectAt) + inject + doc.substring(injectAt)
    } else {
        inject + doc
    }
}
