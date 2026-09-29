package me.rerere.rikkahub.ui.components.richtext

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import me.rerere.hugeicons.stroke.View as ViewIcon
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.webview.WebViewLocalAssets
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.base64Encode

/**
 * 酒馆 HTML 前端卡渲染。
 *
 * 设计对齐 SillyTavern 官方：官方把卡片**内联渲染进消息 DOM**（`.mes_text`），
 * 高度由内容天然决定，因此没有折叠按钮、没有手势仲裁、没有高度上报。
 *
 * 本实现用 WebView 承载卡片（Android 上没有等价的"内联 DOM"方案），
 * 因此必须把官方免费获得的三件事显式做出来：
 *
 * 1. **高度自适应**：JS 上报内容高度，卡片高度跟随内容，不再硬封顶。
 * 2. **手势协调**：只有内容确实超出可视区时，WebView 才消费竖向滑动；
 *    否则把手势交还外层聊天列表——两者都能滚，且不会互相抢。
 * 3. **样式隔离**：卡片 CSS 统一加隔离根前缀（见 HtmlCardDocument），
 *    防止全局选择器污染聊天页。
 *
 * 官方在同一位置的视觉是"卡片完整铺开、跟着页面滚"，这里照此实现。
 */

/** 卡片未完成首次测量时的最小高度，避免高度上报前的塌陷与闪烁。 */
private val CARD_MIN_HEIGHT = 80.dp


/**
 * 渲染消息中的 HTML 卡片。
 *
 * @param html 卡片 HTML（完整文档或 HTML 片段）
 * @param modifier 外层修饰符
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
    val navController = LocalNavController.current

    val markedJs = remember {
        runCatching {
            context.assets.open("html/marked.min.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    // JS 上报的内容高度（CSS px ≈ Compose dp）；0 表示尚未上报
    var contentHeight by remember(html) { mutableIntStateOf(0) }
    // 高度接受器：允许双向修正（卡内切换 tab / 折叠面板会真的变矮），
    // 但忽略 2px 内的抖动，避免图片加载/动画导致列表反复跳动。
    val applyHeight: (Int) -> Unit = remember(html) {
        { h ->
            if (h > 0 && kotlin.math.abs(h - contentHeight) > 2) {
                contentHeight = h
            }
        }
    }

    val inlinePage = remember(html, colorScheme, markedJs) {
        buildCardPage(
            html = html,
            textColor = colorScheme.onSurface,
            markedJs = markedJs,
            backgroundColor = null,
        )
    }
    // 全屏页用不透明背景：全屏 WebView 默认白底，深色主题下浅色文字会看不清
    val fullscreenPage = remember(html, colorScheme, markedJs) {
        buildCardPage(
            html = html,
            textColor = colorScheme.onSurface,
            markedJs = markedJs,
            backgroundColor = colorScheme.surface,
        )
    }

    val openFullscreen = {
        val contentId = WebViewContentCache.store(context.cacheDir, fullscreenPage)
        navController.navigate(Screen.WebView(contentId = contentId))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 高度跟随内容：上报前留一个最小高度，避免塌陷导致列表跳动
                .then(
                    if (contentHeight > 0) {
                        Modifier.height(with(density) { contentHeight.toDp() })
                    } else {
                        Modifier.heightIn(min = CARD_MIN_HEIGHT)
                    }
                )
        ) {
            AndroidView(
                factory = { ctx ->
                    CardWebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowContentAccess = true
                        settings.loadWithOverviewMode = false
                        settings.useWideViewPort = false
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        addJavascriptInterface(HeightBridge(applyHeight), "rikkaHost")
                        isLongClickable = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? = WebViewLocalAssets.intercept(
                                view.context.applicationContext,
                                request.url,
                            ) ?: super.shouldInterceptRequest(view, request)

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                // 图片/字体异步加载会继续撑高内容，轮询兜底
                                pollContentHeight(view) { h -> applyHeight(h) }
                            }
                        }
                    }
                },
                update = { webView ->
                    if (webView.tag != inlinePage) {
                        webView.tag = inlinePage
                        webView.loadDataWithBaseURL(
                            WEB_VIEW_BASE_URL,
                            inlinePage,
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
                modifier = Modifier.fillMaxWidth(),
            )

            // 上报前显示占位，避免空白卡片
            if (contentHeight == 0) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 操作条：全屏查看（官方没有等价物，但移动端放大看细节确实有用）；
        // 折叠入口仅在调用方明确要求时出现（旧调用点兼容）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onCollapse?.let {
                IconButton(onClick = it, modifier = Modifier.size(32.dp)) {
                    Icon(
                        HugeIcons.ArrowUp01,
                        contentDescription = stringResource(R.string.html_card_collapse),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = openFullscreen, modifier = Modifier.size(32.dp)) {
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
 * 内联卡片 WebView。
 *
 * 手势策略：由于卡片高度**始终等于内容高度**（见 heightReportScript 上报 +
 * 外层按上报值设高），这个 WebView 自身永远没有可滚动余量——也就根本不需要
 * 自己滚动。因此正确的做法是：
 *
 * - 竖向滑动一律交还外层聊天列表（返回 false），让列表接管滚动；
 * - 点击/长按仍交给 WebView，保证卡内按钮、折叠面板可交互。
 *
 * 这正是官方把卡片内联进 `.mes_text` 后天然得到的行为：内容随页面滚动，
 * 不存在"卡片和列表抢手势"的问题。原先靠 computeVerticalScrollRange() 猜测
 * 是否消费滑动的实现，在高度尚未上报时会误判并吞掉手势，是滑动异常的根源。
 */
private class CardWebView(context: Context) : WebView(context) {
    init {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        // 交给外层列表处理，自身不显示过度滚动光晕
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private var downX = 0f
    private var downY = 0f
    /** 是否已判定为"交给外层列表滚动"，判定后本手势不再回传给 WebView */
    private var forwardingToParent = false
    /** 抬手时是否为一次点击（需要让 WebView 收到完整事件序列才能触发卡内交互） */
    private var possibleTap = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                forwardingToParent = false
                possibleTap = true
                // 按下时必须消费，否则收不到后续 MOVE/UP，卡内点击会失效
                return super.onTouchEvent(event)
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = kotlin.math.abs(event.y - downY)
                val dx = kotlin.math.abs(event.x - downX)
                if (dy > 8f || dx > 8f) possibleTap = false
                // 竖向位移占主导 → 判定为列表滚动，把剩余事件让给外层
                if (!forwardingToParent && dy > 10f && dy > dx) {
                    forwardingToParent = true
                    requestDisallowInterceptTouchEvent(false)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                forwardingToParent = false
            }
        }

        // 已判定为滚动：不再消费，外层列表接管
        if (forwardingToParent && event.actionMasked != MotionEvent.ACTION_UP) {
            return false
        }
        return super.onTouchEvent(event)
    }
}

private const val HEIGHT_JS =
    "(function(){return Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0);})()"

/** 页面加载后周期性读取内容高度，只上报变大值避免抖动。 */
private fun pollContentHeight(
    view: WebView,
    remaining: Int = 20,
    onHeight: (Int) -> Unit = {},
) {
    if (remaining <= 0) return
    view.postDelayed({
        runCatching {
            view.evaluateJavascript(HEIGHT_JS) { value ->
                val h = value?.trim()?.removeSurrounding("\"")?.toDoubleOrNull()?.toInt() ?: 0
                if (h > 0) onHeight(h)
            }
        }
        pollContentHeight(view, remaining - 1, onHeight)
    }, 350)
}

private class HeightBridge(private val onHeight: (Int) -> Unit) {
    @JavascriptInterface
    fun reportHeight(height: Int) {
        onHeight(height)
    }
}

/**
 * 构造卡片页面。
 *
 * 完整 HTML 文档（前端卡）与 HTML 片段走不同路径：
 * - 完整文档：拆出 head/body/style，把 style 作用域化后重新组装成
 *   「隔离容器 + body 内容」，这样卡片样式不会外泄，也能与外层页面共存。
 * - 片段：交给 marked.js 渲染 markdown + HTML 混排。
 */
internal fun buildCardPage(
    html: String,
    textColor: androidx.compose.ui.graphics.Color,
    markedJs: String,
    backgroundColor: androidx.compose.ui.graphics.Color?,
): String {
    val normalized = normalizeNewlines(html).trimCardEdges()
    return if (isFullHtmlDocument(normalized)) {
        buildCardDocumentPage(normalized, textColor, backgroundColor)
    } else {
        buildFragmentPage(normalized, textColor, markedJs, backgroundColor)
    }
}

/** 卡片基础样式：隔离边界 + 主题配色 + 溢出兜底。 */
private fun cardBaseCss(
    textColor: androidx.compose.ui.graphics.Color,
    backgroundColor: androidx.compose.ui.graphics.Color?,
): String {
    val textCss = String.format("#%06X", textColor.toArgb() and 0xFFFFFF)
    val bgCss = if (backgroundColor != null) {
        String.format("#%06X", backgroundColor.toArgb() and 0xFFFFFF)
    } else {
        "transparent"
    }
    return """
  html, body { margin: 0; padding: 0; background: $bgCss; }
  body { color: $textCss; font-family: sans-serif; word-break: break-word; overflow-x: hidden; }
  #$CARD_ROOT_ID { position: relative; overflow-x: hidden; width: 100%; box-sizing: border-box; }
  #$CARD_ROOT_ID * { max-width: 100%; box-sizing: border-box; }
  #$CARD_ROOT_ID img, #$CARD_ROOT_ID video, #$CARD_ROOT_ID canvas { max-width: 100%; height: auto; }
  #$CARD_ROOT_ID table { max-width: 100%; }
  #$CARD_ROOT_ID pre { overflow-x: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
  #$CARD_ROOT_ID code { word-break: break-all; overflow-wrap: anywhere; }
"""
}

/** 高度上报脚本：load + 若干延时 + ResizeObserver 兜底。 */
private fun heightReportScript(extraDelays: String = "1500, 4000"): String = """
<script>
(function() {
  function contentHeight() {
    var root = document.getElementById('$CARD_ROOT_ID');
    if (!root) return 0;
    // 用内容盒的边界而非 scrollHeight：scrollHeight 会被视口最小高度撑大，
    // 导致卡片被报成一个空屏高度、下面留一大片空白。
    var rect = root.getBoundingClientRect();
    var h = rect.height;
    // 卡片里有 position:absolute 的装饰层时，rect 可能不包含它们，取子元素最大值兜底
    var maxChild = 0;
    for (var i = 0; i < root.children.length; i++) {
      var c = root.children[i];
      if (!c.getBoundingClientRect) continue;
      var cr = c.getBoundingClientRect();
      var bottom = (cr.bottom - rect.top);
      if (bottom > maxChild) maxChild = bottom;
    }
    if (maxChild > h) h = maxChild;
    return Math.ceil(h);
  }
  function report() {
    var h = contentHeight();
    if (window.rikkaHost && window.rikkaHost.reportHeight && h > 0) {
      window.rikkaHost.reportHeight(h);
    }
  }
  window.addEventListener('load', report);
  setTimeout(report, 60);
  setTimeout(report, 300);
  setTimeout(report, 900);
  [$extraDelays].forEach(function(d){ setTimeout(report, d); });
  if (window.ResizeObserver) {
    var root = document.getElementById('$CARD_ROOT_ID');
    if (root) {
      try { new ResizeObserver(report).observe(root); } catch (e) {}
    }
    if (document.body) {
      try { new ResizeObserver(report).observe(document.body); } catch (e) {}
    }
  }
  // 图片异步加载完成后重新上报
  document.addEventListener('load', function(e){
    if (e.target && (e.target.tagName === 'IMG' || e.target.tagName === 'VIDEO')) report();
  }, true);
})();
</script>
"""

/**
 * 完整 HTML 文档卡：把文档压平成「隔离容器 + 作用域化 CSS + body 内容」。
 *
 * 这与官方把卡片内联进 `.mes_text` 的语义一致——样式被限制在卡片内部，
 * 且卡片参与外层布局（而不是像原来那样原样注入整篇文档）。
 */
internal fun buildCardDocumentPage(
    doc: String,
    textColor: androidx.compose.ui.graphics.Color,
    backgroundColor: androidx.compose.ui.graphics.Color?,
): String {
    val parsed = parseCardDocument(doc)
    val scopedCss = scopeCardCss(parsed.styleCss)
    val baseCss = cardBaseCss(textColor, backgroundColor)
    val bodyB64 = parsed.bodyHtml.base64Encode()
    val headB64 = parsed.headHtml.base64Encode()
    val cssB64 = scopedCss.base64Encode()

    return """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>
$baseCss
</style>
<style id="rikka-card-css"></style>
</head>
<body>
<div id="$CARD_ROOT_ID"></div>
<script>
(function() {
  function b64(s) {
    try { return decodeURIComponent(escape(window.atob(s))); }
    catch (e) { try { return window.atob(s); } catch (e2) { return ''; } }
  }
  var root = document.getElementById('$CARD_ROOT_ID');

  // 卡片自带 CSS：已作用域化，插入后只影响卡片内部。
  // 再把 100vh/100vw 换成卡片自身的像素尺寸：卡片装饰层在官方是铺满浏览器视口，
  // 塞进聊天卡片后必须跟随卡片高度，否则会盖住整屏或把卡片撑爆。
  try {
    var css = b64('$cssB64');
    css = normalizeViewportUnits(css);
    document.getElementById('rikka-card-css').textContent = css;
  } catch (e) {}

  /**
   * position:fixed → absolute（卡片内不再逃出容器），
   * vh/vw → 按卡片实际尺寸换算的 px（留待布局完成后由 measureHeight 兜底）。
   */
  function normalizeViewportUnits(css) {
    var H = window.innerHeight || 800;
    var W = window.innerWidth || 400;
    return css
      .replace(/position\s*:\s*fixed/gi, 'position:absolute')
      .replace(/(\d+(?:\.\d+)?)vh/gi, function (m, v) {
        return Math.round(H * parseFloat(v) / 100) + 'px';
      })
      .replace(/(\d+(?:\.\d+)?)vw/gi, function (m, v) {
        return Math.round(W * parseFloat(v) / 100) + 'px';
      });
  }

  // head 里的 meta/link 等（style 已抽离）
  try {
    var headHtml = b64('$headB64');
    if (headHtml.trim()) {
      var tpl = document.createElement('template');
      tpl.innerHTML = headHtml;
      var frag = tpl.content;
      // <script>/<base> 不在 head 里重放，避免二次执行与 base 劫持
      frag.querySelectorAll('script, base, title, style').forEach(function(n){ n.remove(); });
      document.head.appendChild(frag);
    }
  } catch (e) {}

  // body 内容：<template> 里的 <script> 是惰性的，直接 appendChild 不会执行
  // （实测 template+appendChild 脚本不运行，重建节点后才运行）。
  // 前端卡的交互（折叠面板/切换/计算）全靠卡内脚本，必须逐个重建节点激活。
  try {
    var bodyHtml = b64('$bodyB64');
    var tpl2 = document.createElement('template');
    tpl2.innerHTML = bodyHtml;
    var frag = tpl2.content;
    activateScripts(frag);
    root.appendChild(frag);
  } catch (e) {
    root.innerHTML = '<pre></pre>';
  }

  /**
   * 把片段里的 <script> 替换为新建的同内容节点，使其在插入文档后真正执行。
   * 保留原始属性（type/src/async 等）。
   */
  function activateScripts(frag) {
    var scripts = Array.prototype.slice.call(frag.querySelectorAll('script'));
    scripts.forEach(function (old) {
      var s = document.createElement('script');
      for (var i = 0; i < old.attributes.length; i++) {
        s.setAttribute(old.attributes[i].name, old.attributes[i].value);
      }
      s.textContent = old.textContent;
      old.parentNode.replaceChild(s, old);
    });
  }
})();
</script>
${heightReportScript()}
</body>
</html>"""
}

/**
 * HTML 片段卡：交给 marked.js 渲染 markdown + HTML 混排，
 * 内容放进隔离容器以复用同一套作用域规则。
 */
internal fun buildFragmentPage(
    html: String,
    textColor: androidx.compose.ui.graphics.Color,
    markedJs: String,
    backgroundColor: androidx.compose.ui.graphics.Color?,
): String {
    val b64 = html.base64Encode()
    val baseCss = cardBaseCss(textColor, backgroundColor)
    val markedTag = if (markedJs.isNotBlank()) {
        // marked.min.js 源码不含字面量 "</script>"，可安全内联
        "<script>\n$markedJs\n</script>"
    } else {
        """<script src="/assets/html/marked.min.js"></script>"""
    }

    return """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title>HTML</title>
<style>
$baseCss
  #$CARD_ROOT_ID code { background: rgba(128,128,128,0.2); padding: 1px 3px; border-radius: 3px; }
  #$CARD_ROOT_ID pre { background: rgba(128,128,128,0.15); padding: 8px; border-radius: 6px; }
</style>
</head>
<body>
<div id="$CARD_ROOT_ID"></div>
$markedTag
<script>
(function() {
  var root = document.getElementById('$CARD_ROOT_ID');
  var src = '';
  try { src = decodeURIComponent(escape(window.atob('$b64'))); }
  catch (e) { try { src = window.atob('$b64'); } catch (e2) {} }

  function esc(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  try {
    // 酒馆卡的 HTML 段落常整体缩进(4空格)，marked 会把缩进行当代码块原样显示
    src = src.replace(/^[ \t]+(<\/?[a-zA-Z]|<!--)/gm, '${'$'}1');
    // 修复 "< img" 这类被塞进空格的标签（酒馆卡常见）
    src = src.replace(/<\s+(img|br|hr|div|span|p|table|thead|tbody|tr|td|th|ul|ol|li|h[1-6]|details|summary|section|article|center|font|blockquote|em|strong|small|sub|sup|button|label)\b/gi, '<${'$'}1');
    // 先把 ``` 围栏转成 <pre>：位于自定义标签内部时 marked 会吞掉围栏，
    // 导致 yaml 面板显示成带 ``` 的字面文本
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
  // 兜底：渲染失败时至少显示源码而不是空白
  if (!root.firstChild) {
    root.innerHTML = '<pre>' + esc(src) + '</pre>';
  }
})();
</script>
${heightReportScript()}
</body>
</html>"""
}
