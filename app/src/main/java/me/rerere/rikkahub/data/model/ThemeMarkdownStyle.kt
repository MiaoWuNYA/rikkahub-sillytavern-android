package me.rerere.rikkahub.data.model

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

/**
 * 气泡**内层**元素的主题样式。
 *
 * 酒馆主题对这些元素都写了独立样式，且远不止改颜色 —— 实测冬之誓主题里：
 *
 *     .mes_text blockquote   { background:#f0f0f080; border-inline-start:3px solid #c8c8c866;
 *                              border-radius:0 10px 10px 0; padding:10px 12px }
 *     .mes_text code         { background:#ccedfc26; color:#7ab8d9; border-radius:4px }
 *     .mes_text pre          { background:#fff; border:1px solid #dcdcdc66; border-radius:12px }
 *     .mes_text mark         { background:#ccedfc4d; border-radius:3px }
 *     .mes_reasoning         { background:#f8f9fa66; border-block-start:1px solid #dcdcdc33 }
 *
 * 旧实现只导入了 `quote_text_color` 这类**纯色** token，上面这些底色、圆角全部丢失，
 * 用户看到的就是"主题的气泡内样式没生效，只有文字变色"。
 *
 * 字段用可序列化的原始类型（ARGB Long / dp Float），Compose 类型由 getter 转换，
 * 这样能直接持久化进 `DisplaySetting` 而无需额外的中间层。
 */
@Serializable
data class ThemeMarkdownStyle(
    /** 行内代码 `` `code` `` */
    val inlineCodeBackgroundArgb: Long? = null,
    val inlineCodeColorArgb: Long? = null,
    val inlineCodeCornerRadiusDp: Float? = null,

    /** 代码块 ``` */
    val codeBlockBackgroundArgb: Long? = null,
    val codeBlockColorArgb: Long? = null,
    val codeBlockCornerRadiusDp: Float? = null,
    val codeBlockBorderColorArgb: Long? = null,
    val codeBlockBorderWidthDp: Float? = null,

    /** 引用块 > */
    val quoteBackgroundArgb: Long? = null,
    val quoteColorArgb: Long? = null,
    val quoteCornerRadiusDp: Float? = null,
    val quoteBorderColorArgb: Long? = null,
    val quoteBorderWidthDp: Float? = null,
    /** 引用块左侧竖线宽度（主题用 border-inline-start 表达） */
    val quoteAccentWidthDp: Float? = null,

    /** 高亮 <mark> */
    val markBackgroundArgb: Long? = null,
    val markColorArgb: Long? = null,
    val markCornerRadiusDp: Float? = null,

    /** 斜体/强调 *em* */
    val emphasisBackgroundArgb: Long? = null,
    val emphasisColorArgb: Long? = null,
    val emphasisCornerRadiusDp: Float? = null,

    /** 思维链区域 */
    val reasoningBackgroundArgb: Long? = null,
    val reasoningColorArgb: Long? = null,
    val reasoningCornerRadiusDp: Float? = null,
) {
    val inlineCodeBackground: Color? get() = inlineCodeBackgroundArgb?.let { Color(it) }
    val inlineCodeColor: Color? get() = inlineCodeColorArgb?.let { Color(it) }
    val inlineCodeCornerRadius: Dp? get() = inlineCodeCornerRadiusDp?.dp

    val codeBlockBackground: Color? get() = codeBlockBackgroundArgb?.let { Color(it) }
    val codeBlockColor: Color? get() = codeBlockColorArgb?.let { Color(it) }
    val codeBlockCornerRadius: Dp? get() = codeBlockCornerRadiusDp?.dp
    val codeBlockBorderColor: Color? get() = codeBlockBorderColorArgb?.let { Color(it) }
    val codeBlockBorderWidth: Dp? get() = codeBlockBorderWidthDp?.dp

    val quoteBackground: Color? get() = quoteBackgroundArgb?.let { Color(it) }
    val quoteColor: Color? get() = quoteColorArgb?.let { Color(it) }
    val quoteCornerRadius: Dp? get() = quoteCornerRadiusDp?.dp
    val quoteBorderColor: Color? get() = quoteBorderColorArgb?.let { Color(it) }
    val quoteBorderWidth: Dp? get() = quoteBorderWidthDp?.dp
    val quoteAccentWidth: Dp? get() = quoteAccentWidthDp?.dp

    val markBackground: Color? get() = markBackgroundArgb?.let { Color(it) }
    val markColor: Color? get() = markColorArgb?.let { Color(it) }
    val markCornerRadius: Dp? get() = markCornerRadiusDp?.dp

    val emphasisBackground: Color? get() = emphasisBackgroundArgb?.let { Color(it) }
    val emphasisColor: Color? get() = emphasisColorArgb?.let { Color(it) }
    val emphasisCornerRadius: Dp? get() = emphasisCornerRadiusDp?.dp

    val reasoningBackground: Color? get() = reasoningBackgroundArgb?.let { Color(it) }
    val reasoningColor: Color? get() = reasoningColorArgb?.let { Color(it) }
    val reasoningCornerRadius: Dp? get() = reasoningCornerRadiusDp?.dp

    /** 没有任何主题内层样式时为 true，渲染层可走原路径 */
    val isEmpty: Boolean
        get() = inlineCodeBackgroundArgb == null && inlineCodeColorArgb == null &&
            codeBlockBackgroundArgb == null && codeBlockColorArgb == null &&
            quoteBackgroundArgb == null && quoteColorArgb == null &&
            markBackgroundArgb == null && markColorArgb == null &&
            emphasisBackgroundArgb == null && emphasisColorArgb == null &&
            reasoningBackgroundArgb == null && reasoningColorArgb == null
}

/** 全局气泡内层样式；未导入主题时为空样式，渲染层逐项回退到自己原有的默认值 */
val LocalThemeMarkdownStyle = staticCompositionLocalOf { ThemeMarkdownStyle() }

/** 空样式的便捷常量 */
val EmptyThemeMarkdownStyle = ThemeMarkdownStyle()

/** 便于提取端用 Compose 颜色构造 */
internal fun Color?.toArgbOrNull(): Long? = this?.toArgb()?.toLong()?.and(0xFFFFFFFFL)
