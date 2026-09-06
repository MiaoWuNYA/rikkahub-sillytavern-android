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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.hugeicons.HugeIcons
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
// 排除 p/img：误报率高，纯文本提到也会命中）
private val HTML_TAG_ANYWHERE = Regex(
    """<(div|style|table|details|center|section|article|font|iframe|video|audio|h[1-6])\b""",
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
    return HTML_TAG_ANYWHERE.containsMatchIn(t) || CUSTOM_TAG_LINE_START.containsMatchIn(t)
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
    // marked.js 直接内联进页面：不依赖运行时 assets 拦截，拦截一旦失败
    // 整页脚本会静默失败变成空白
    val markedJs = remember {
        runCatching {
            context.assets.open("html/marked.min.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    // JS 上报的内容高度（CSS px ≈ Compose dp），0 表示尚未上报
    var contentHeight by remember { mutableIntStateOf(0) }
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

    Column(modifier = modifier) {
        AndroidView(
            factory = { context ->
            PassThroughWebView(context).apply {
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
            ),
        )

        // 内联 WebView 为保证聊天列表可滚动不消费触摸事件（只读），
        // 点击/长按选择/内部滚动等交互放到全屏页完成
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
                onClick = {
                    val contentId = WebViewContentCache.store(context.cacheDir, fullscreenPage)
                    navController.navigate(Screen.WebView(contentId = contentId))
                },
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
 * HTML 角色卡折叠容器：默认只显示一个按钮，点击后才展开渲染 WebView。
 * 避免大卡片直接铺在聊天流里，也避免 WebView 触摸穿透影响普通文本的交互。
 */
@Composable
fun HtmlCardBlock(
    html: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    if (expanded) {
        HtmlWebViewBlock(html = html, modifier = modifier, onCollapse = { expanded = false })
    } else {
        Surface(
            onClick = { expanded = true },
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
                )
            }
        }
    }
}

/**
 * 不消费触摸事件的 WebView：内容高度自适应后内部无需滚动，
 * 把滑动手势交还给外层聊天列表（LazyColumn），否则聊天页无法上下翻动。
 * 代价是卡内 <details>/链接不可点击——聊天场景下翻页优先。
 */
private class PassThroughWebView(context: Context) : WebView(context) {
    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
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
    val b64 = html.trim(' ', '\n', '\r', '\t', *INVISIBLE_LEADING).base64Encode()
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
