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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.roundToInt

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
 * 卡片内联渲染的最大高度。
 *
 * 不设上限时，一张长卡会把消息列表撑到几屏高，用户滑很久都过不去这条消息；
 * 设得太小又会像以前那样把内容直接裁掉、只能靠小眼睛看全 —— 那正是要修的问题。
 * 因此这里只做「软上限」：超出后卡片高度停在 [CARD_MAX_HEIGHT]，
 * 但 WebView 自身保持可滚动，内容一个不丢，也不需要跳全屏。
 */
private val CARD_MAX_HEIGHT = 520.dp


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
        // 整个高度计算必须统一在 dp 空间。
        //
        // 原实现把 CARD_MAX_HEIGHT(520dp) 先 roundToPx() 换成物理像素再去夹
        // contentHeight（JS 上报的是 CSS px ≈ dp），夹完又 toDp() 换回来 ——
        // 一进一出差了一个 density。3x 屏上"内容 800dp、上限 520dp"的卡片
        // 会被算成 267dp，视口只剩一半，可滚动距离同样缩水，
        // 表现就是"滑一下里面只动 2 毫米"。
        val boundedHeightDp = if (contentHeight > 0) {
            contentHeight.coerceAtMost(CARD_MAX_HEIGHT.value.roundToInt()).dp
        } else {
            null
        }

        // 卡片超出高度上限时由 WebView 自身滚动。
        //
        // 这里刻意**不再**用 Modifier.nestedScroll：消息列表（LazyColumn）与
        // WebView 都是原生 View，它们之间的滑动竞争发生在 Android 视图树的
        // 拦截分发里，Compose 的 nestedScroll 完全插不上手 —— 之前 onPostScroll、
        // onPreScroll 两版都毫无效果，根因就在这里。
        // 现在改由 CardWebView 自己 requestDisallowInterceptTouchEvent，
        // 详见该类的 onTouchEvent。
        val cardWebViewRef = remember { CardWebViewRef() }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 高度跟随内容：上报前留一个最小高度，避免塌陷导致列表跳动
                .then(
                    if (boundedHeightDp != null) {
                        Modifier.height(boundedHeightDp)
                    } else {
                        Modifier.heightIn(min = CARD_MIN_HEIGHT)
                    }
                )
        ) {
            AndroidView(
                factory = { ctx ->
                    CardWebView(ctx).apply {
                        cardWebViewRef.view = this
                        // MATCH_PARENT 高度：与 AndroidView 的 fillMaxSize 一致，
                        // 让 WebView 视口等于卡片高度而不是文档高度（否则内部无可滚区间）。
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
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
                                // 图片/字体异步加载会继续撑高内容，轮询兜底；
                                // 高频窗口结束后接一段低频复查，覆盖脚本延迟渲染
                                pollContentHeight(view) { h -> applyHeight(h) }
                                pollContentHeightSlow(view) { h -> applyHeight(h) }
                            }
                        }
                    }
                },
                update = { webView ->
                    // 保留引用，供全屏/边界判定使用
                    cardWebViewRef.view = webView
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
                // 高度必须填满外层 Box，不能用 wrap_content。
                //
                // 之前是 WRAP_CONTENT：WebView 会按文档完整高度（可能几千 px）自我测量，
                // 而外层 Box 只有 maxHeight 那么高。结果是 WebView 认为"整个文档都在可视区里"，
                // scrollY 恒为 0、内部没有任何可滚区间；外层又只是裁剪，也不滚动。
                // 用户看到的就是"消息里的网页怎么划都不动"。
                // 改成 matchParentSize 后 WebView 视口 = Box 高度，超长内容由它自己滚动。
                // 手势归属由 CardWebView.onTouchEvent 里的
                // requestDisallowInterceptTouchEvent 仲裁：卡片能滚时禁止外层拦截，
                // 滚到边界再放行，因此不会出现"界面抢网页滑动"。
                modifier = Modifier.fillMaxSize(),
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
 * 滚动的唯一正解：**让 WebView 自己吃掉手势，并禁止外层列表抢夺**。
 *
 * 消息列表是 LazyColumn（一个原生 ViewGroup 滚动容器）。手指落在 WebView 上
 * 滑动时，LazyColumn 会在 onInterceptTouchEvent 里把 MOVE 事件全部截走，
 * WebView 只收到一个 DOWN 就再没有后续 —— 表现就是"界面抢网页滑动"，
 * 卡片里几乎纹丝不动。
 *
 * 因此必须在手势按下时调用 requestDisallowInterceptTouchEvent(true)，
 * 让所有祖先容器放弃拦截，把整段手势完整交给 WebView 自己的
 * OverScroller（拖动 + 惯性 + 边界吸附都是它原生的实现，手感最好）。
 *
 * 为什么不用 Modifier.nestedScroll：
 * 它是 Compose 侧的机制，而 WebView 是原生 View、LazyColumn 也是原生容器，
 * 两者之间的手势竞争发生在 Android 原生视图树里，根本不经过 Compose 的
 * nestedScroll 分发 —— 那个 Modifier 在原生容器面前形同虚设。
 * 之前用 onPreScroll/onPostScroll 两版都没效果，根因就在这里。
 *
 * 卡片滚到边界时不再需要手动交还：边界处的"吃不下的位移"由
 * [boundaryHandoff] 判断后放行给外层，避免手势卡死在卡片上。
 */
/**
 * 持有卡片 WebView 的普通引用容器。
 *
 * 仅用于边界放行回调里读取 WebView，不参与重组，
 * 因此刻意不用 mutableStateOf —— 避免滚动过程中产生多余重组。
 */
private class CardWebViewRef {
    var view: WebView? = null
}

/**
 * 卡片 WebView：自行处理手势，并禁止祖先抢占。
 *
 * @param canScrollDown 卡片当前是否还能向下滚（用于边界放行判定）
 */
private class CardWebView(context: Context) : WebView(context) {

    /** 手势按下时记录方向，决定这一整段手势归谁 */
    private var downY = 0f
    private var downScrollY = 0

    init {
        // 卡片超出上限时需要自身可滚动；滚动条隐藏以免破坏卡片外观
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isClickable = true
        isFocusable = true
    }

    // 手势仲裁必须在原生层做，而不是 Compose 层的 nestedScroll。
    //
    // 消息列表是 LazyColumn，它和 WebView 都是原生 View，两者的滑动竞争
    // 发生在 Android 视图树的 onInterceptTouchEvent/dispatchTouchEvent 里，
    // Compose 的 Modifier.nestedScroll 完全插不上手 —— 之前两版
    // （onPostScroll / onPreScroll）因此都没有任何效果。
    //
    // 这里的做法只有一条：按下时禁止祖先拦截，把整段手势交给 WebView 自己。
    // 关键在于**不自己实现滚动**，而是 super.onTouchEvent(event) 让 WebView
    // 原生的 OverScroller 干活（拖动、惯性、边界吸附都是它自带的，手感最好）；
    // 覆写只是为了让祖先在合适的时候重新获得拦截权。
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                downScrollY = scrollY
                // 只有卡片真的还有可滚区间时才禁止祖先拦截。
                //
                // 短卡片（内容没超过高度上限）本来就没得滚，如果也去抢手势，
                // 用户在这条消息上滑动时列表会"卡住"—— 那是更糟的体验。
                // 关键一步：让 LazyColumn 等所有祖先放弃拦截，
                // 否则它们会在第一次 MOVE 时把手势整体截走（"界面抢滑动"）。
                parent?.requestDisallowInterceptTouchEvent(hasScrollableRange())
            }

            MotionEvent.ACTION_MOVE -> {
                // 到达边界后要**放行**，否则手势会卡死在卡片上，
                // 用户继续往上/下划时整个页面反而动不了。
                if (!hasScrollableRange()) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                } else {
                    val pullingDown = event.y > downY
                    val atTop = scrollY <= 0
                    val atBottom = scrollY >= contentHeight - height
                    if ((pullingDown && atTop) || (!pullingDown && atBottom)) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 手势结束，恢复祖先的拦截权
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 判断卡片当前是否还有可滚动空间。
     *
     * 有空间就自己处理整段手势，没空间就完全不抢，让外层列表照常滚动 ——
     * 短卡片（内容没超过高度上限）不应该表现出任何阻断感。
     */
    fun hasScrollableRange(): Boolean = contentHeight > height
}

private const val HEIGHT_JS =
    "(function(){return Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0);})()"

/**
 * 页面加载后周期性读取内容高度。
 *
 * 轮询窗口刻意拉长（前 20 次 350ms 高频，之后降到 1s 继续到约 30s）：
 * 前端卡常靠脚本延迟渲染（等字体/图片/接口返回），早停会把卡片高度定格在
 * 骨架屏的尺寸上，表现为"内容只显示了一部分、下面再也出不来"。
 */
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

/** 高频轮询结束后继续低频复查，覆盖脚本延迟渲染的卡片。 */
private fun pollContentHeightSlow(
    view: WebView,
    remaining: Int = 30,
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
        pollContentHeightSlow(view, remaining - 1, onHeight)
    }, 1000)
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
  /**
   * 量出卡片的真实内容高度。
   *
   * 只取根盒 rect 是不够的：卡片常把内容放进自带 max-height + overflow 的内层
   * 容器（状态栏、日志窗、选项卡面板），这些子元素会溢出让根盒看不出来，
   * 于是卡片被报成一个偏矮的高度、内容被永久截断 —— 这正是"只能显示六七十行"
   * 的成因。因此这里对整棵子树取最大下沿，并同时考虑 scrollHeight/clientHeight。
   */
  function contentHeight() {
    var root = document.getElementById('$CARD_ROOT_ID');
    if (!root) return 0;
    var rect = root.getBoundingClientRect();
    var h = Math.max(rect.height, root.scrollHeight || 0);

    // 遍历整棵子树，取所有元素相对卡片顶部的最大下沿。
    // 内层 max-height + overflow 的子元素自身可能不滚动，但其内容更高，
    // 故 scrollHeight 也要一并算进来。
    var nodes = root.querySelectorAll('*');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var cs;
      try { cs = window.getComputedStyle(el); } catch (e) { continue; }
      // 完全脱离文档流的装饰层不参与撑高（避免把浮层算成内容）
      if (cs && cs.display === 'none') continue;
      var r = el.getBoundingClientRect();
      if (r.height > 0) {
        var bottom = r.bottom - rect.top;
        if (bottom > h) h = bottom;
      }
      // 内层溢出：元素自身高度被 max-height 压住，内容仍更高
      if (el.scrollHeight && el.scrollHeight > (r.height + 1)) {
        var scrollBottom = (r.top - rect.top) + el.scrollHeight;
        if (scrollBottom > h) h = scrollBottom;
      }
    }
    if (document.body) {
      var bh = document.body.scrollHeight || 0;
      if (bh > h) h = bh;
    }
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
  // 字体就绪后重新测量：字体换入会改变换行、进而改变高度
  if (document.fonts && document.fonts.ready && document.fonts.ready.then) {
    try { document.fonts.ready.then(report); } catch (e) {}
  }
  // 卡片内部自身滚动时也要复查高度（内层容器展开/折叠会改变内容高度）
  window.addEventListener('scroll', function(){ report(); }, true);
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
