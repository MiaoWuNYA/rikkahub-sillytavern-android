package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.DisplaySetting

/**
 * 酒馆（SillyTavern）UI 主题的结构化表示。
 *
 * 兼容新旧主题格式：所有字段均可空/有默认值，未知字段忽略。
 * 颜色字段为 CSS 颜色字符串（rgba(r,g,b,a) / rgb(r,g,b) / #hex，alpha 为 0-1 小数）。
 * custom_css 无法整体渲染，仅从中提取聊天背景图与气泡圆角参与映射。
 */
@Serializable
data class SillyTavernTheme(
    val name: String? = null,
    // 主文字颜色
    @SerialName("main_text_color") val mainTextColor: String? = null,
    // 斜体文字颜色
    @SerialName("italics_text_color") val italicsTextColor: String? = null,
    // 下划线文字颜色（本仓库无对应字段，忽略）
    @SerialName("underline_text_color") val underlineTextColor: String? = null,
    // 引用文字颜色
    @SerialName("quote_text_color") val quoteTextColor: String? = null,
    // 聊天区背景模糊色调（本仓库无对应字段，忽略）
    @SerialName("blur_tint_color") val blurTintColor: String? = null,
    // 聊天区色调
    @SerialName("chat_tint_color") val chatTintColor: String? = null,
    // 用户消息气泡色调
    @SerialName("user_mes_blur_tint_color") val userMesBlurTintColor: String? = null,
    // AI 消息气泡色调
    @SerialName("bot_mes_blur_tint_color") val botMesBlurTintColor: String? = null,
    // 阴影颜色（本仓库无对应字段，忽略）
    @SerialName("shadow_color") val shadowColor: String? = null,
    // 边框颜色（本仓库无对应字段，忽略）
    @SerialName("border_color") val borderColor: String? = null,
    // 字号缩放
    @SerialName("font_scale") val fontScale: Double? = null,
    // 模糊强度（本仓库无对应字段，忽略；用 Double 兼容 "3" / 3 / 3.0 多种写法）
    @SerialName("blur_strength") val blurStrength: Double? = null,
    // 快速 UI 模式（本仓库无对应字段，忽略）
    @SerialName("fast_ui_mode") val fastUiMode: Boolean? = null,
    // 头像样式（本仓库无对应字段，忽略）
    @SerialName("avatar_style") val avatarStyle: Int? = null,
    // 聊天展示模式（本仓库无对应字段，忽略）
    @SerialName("chat_display") val chatDisplay: Int? = null,
    // 自定义 CSS（Compose 无法渲染，不参与映射）
    @SerialName("custom_css") val customCss: String? = null,
)

private val ThemeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

// 老版主题可能把数值字段写成字符串（如 "font_scale": "1.05"），解析前做一次归一化
private val NUMERIC_THEME_FIELDS = setOf("font_scale", "blur_strength", "chat_width")

/**
 * 解析酒馆主题 JSON。容错老格式：未知字段忽略、数值字段允许字符串形式。
 * @throws IllegalArgumentException 内容不是 JSON 对象或字段类型无法识别时抛出，message 可直接展示
 */
fun parseSillyTavernTheme(json: String): SillyTavernTheme {
    val element = ThemeJson.parseToJsonElement(json)
    val obj = element as? JsonObject
        ?: throw IllegalArgumentException("不是有效的主题文件（顶层不是 JSON 对象）")
    val normalized = JsonObject(obj.map { (key, value) ->
        if (key in NUMERIC_THEME_FIELDS) {
            val primitive = runCatching { value.jsonPrimitive }.getOrNull()
            val number = primitive?.contentOrNull?.toDoubleOrNull()
            if (number != null) key to JsonPrimitive(number) else key to value
        } else {
            key to value
        }
    }.toMap())
    return runCatching {
        ThemeJson.decodeFromJsonElement<SillyTavernTheme>(normalized)
    }.getOrElse {
        throw IllegalArgumentException("主题字段类型无法识别：${it.message.orEmpty()}")
    }
}

/**
 * 解析 CSS 颜色字符串为 ARGB Long。
 * 支持 rgba(r,g,b,a) / rgb(r,g,b)（含现代 `rgb(r g b / a)` 写法与百分比通道）、
 * #RGB / #RGBA / #RRGGBB / #AARRGGBB；alpha∈[0,1] 换算为 0-255。
 * 无法识别返回 null。
 */
fun parseCssColor(input: String?): Long? {
    val trimmed = input?.trim().takeUnless { it.isNullOrEmpty() } ?: return null

    // #hex（# 可省略）。8 位按 CSS 规范是 #RRGGBBAA（酒馆主题大量使用，如 #fbc4c450），
    // 4 位是 #RGBA；应用内部 toColorHexString 的 #AARRGGBB 仅用于自身往返，不走此分支
    if (!trimmed.contains('(')) {
        val hex = trimmed.removePrefix("#")
        if (hex.length in 3..8 && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return when (hex.length) {
                3 -> {
                    val r = hex[0].hexToByte(); val g = hex[1].hexToByte(); val b = hex[2].hexToByte()
                    argb(255, r * 0x11, g * 0x11, b * 0x11)
                }

                4 -> {
                    val r = hex[0].hexToByte(); val g = hex[1].hexToByte()
                    val b = hex[2].hexToByte(); val a = hex[3].hexToByte()
                    argb(a * 0x11, r * 0x11, g * 0x11, b * 0x11)
                }

                6 -> argb(255, hex.substring(0, 2).hexToInt(), hex.substring(2, 4).hexToInt(), hex.substring(4, 6).hexToInt())

                8 -> argb(
                    hex.substring(6, 8).hexToInt(),
                    hex.substring(0, 2).hexToInt(),
                    hex.substring(2, 4).hexToInt(),
                    hex.substring(4, 6).hexToInt(),
                )

                else -> null
            }
        }
        return null
    }

    // rgb()/rgba() 函数形式
    val match = Regex("^rgba?\\((.*)\\)$", RegexOption.IGNORE_CASE).find(trimmed) ?: return null
    val body = match.groupValues[1]
    val (rgbPart, alphaPart) = if ('/' in body) {
        val idx = body.indexOf('/')
        body.substring(0, idx) to body.substring(idx + 1)
    } else {
        body to ""
    }
    val channels = rgbPart.trim().split(Regex("[\\s,]+")).filter { it.isNotBlank() }
    if (channels.size < 3) return null
    val r = parseCssChannel(channels[0]) ?: return null
    val g = parseCssChannel(channels[1]) ?: return null
    val b = parseCssChannel(channels[2]) ?: return null
    val alphaToken = alphaPart.trim().takeIf { it.isNotEmpty() }
        ?: channels.getOrNull(3)?.takeIf { channels.size >= 4 }
    val a = alphaToken?.let { parseCssAlpha(it) ?: return null } ?: 255
    return argb(a, r, g, b)
}

private fun argb(a: Int, r: Int, g: Int, b: Int): Long {
    return ((a.coerceIn(0, 255).toLong() shl 24)
        or (r.coerceIn(0, 255).toLong() shl 16)
        or (g.coerceIn(0, 255).toLong() shl 8)
        or b.coerceIn(0, 255).toLong())
}

private fun Char.hexToByte(): Int = Character.digit(this, 16).also { require(it >= 0) { "非法 hex 字符: $this" } }

/** 解析单个 RGB 通道：支持 0-255 整数与百分比 */
private fun parseCssChannel(token: String): Int? {
    val t = token.trim()
    return if (t.endsWith("%")) {
        t.removeSuffix("%").trim().toFloatOrNull()?.let { (it / 100f * 255f + 0.5f).toInt() }
    } else {
        t.toIntOrNull()
    }
}

/** 解析 alpha：0-1 小数或百分比，返回 0-255 */
private fun parseCssAlpha(token: String): Int? {
    val t = token.trim()
    val ratio = if (t.endsWith("%")) {
        t.removeSuffix("%").trim().toFloatOrNull()?.div(100f)
    } else {
        t.toFloatOrNull()
    } ?: return null
    if (ratio < 0f || ratio > 1f) return null
    return (ratio * 255f + 0.5f).toInt().coerceIn(0, 255)
}

/** ARGB Long → hex 字符串；不透明时省略 alpha（与 DisplaySetting.quoteColor 的 #RRGGBB 约定一致） */
fun Long.toColorHexString(): String {
    val a = ((this shr 24) and 0xFF).toInt()
    val r = ((this shr 16) and 0xFF).toInt()
    val g = ((this shr 8) and 0xFF).toInt()
    val b = (this and 0xFF).toInt()
    return if (a == 255) {
        String.format("#%02X%02X%02X", r, g, b)
    } else {
        String.format("#%02X%02X%02X%02X", a, r, g, b)
    }
}

// ── 颜色合成：酒馆的颜色是多层半透明叠加 ──
//
// 酒馆的渲染层级（自下而上）：背景图（custom_css 提供）→ blur_tint_color → chat_tint_color
// → 消息气泡色调（user/bot_mes_blur_tint_color）。这些 tint 的 alpha 往往很低甚至为 0
// （依赖底层背景图/模糊透出），而应用的气泡颜色会被全局不透明度覆盖 alpha，
// 直接照搬会导致颜色失真（如白字黑底主题变成整片纯黑）。因此在导入时按
// source-over 规则把各层合成为不透明的近似色。

private const val LIGHT_BASE = 0xFFFAFAFAL
private const val DARK_BASE = 0xFF141414L

/** 0-255 域的 RGBA 颜色（浮点，供合成运算） */
private class CssColor(val a: Double, val r: Double, val g: Double, val b: Double)

private fun Long.toCssColor() = CssColor(
    a = ((this shr 24) and 0xFF).toDouble(),
    r = ((this shr 16) and 0xFF).toDouble(),
    g = ((this shr 8) and 0xFF).toDouble(),
    b = (this and 0xFF).toDouble(),
)

private fun CssColor.toArgbLong(): Long {
    fun ch(v: Double): Int = (v + 0.5).toInt().coerceIn(0, 255)
    return argb(ch(a), ch(r), ch(g), ch(b))
}

/** CSS source-over 合成：top 叠在 bottom 之上 */
private fun over(top: CssColor, bottom: CssColor): CssColor {
    val at = top.a / 255.0
    val ab = bottom.a / 255.0
    val outA = at + ab * (1 - at)
    if (outA <= 0.0) return CssColor(0.0, 0.0, 0.0, 0.0)
    fun mix(t: Double, b: Double) = (t * at + b * ab * (1 - at)) / outA
    return CssColor(outA * 255.0, mix(top.r, bottom.r), mix(top.g, bottom.g), mix(top.b, bottom.b))
}

/** 简易感知亮度（0-1），用于按主文字颜色推断底色明暗 */
private fun relativeLuminance(color: Long): Double {
    val r = ((color shr 16) and 0xFF) / 255.0
    val g = ((color shr 8) and 0xFF) / 255.0
    val b = (color and 0xFF) / 255.0
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** 仅接受不透明颜色，输出应用内部使用的 hex 字符串（透明色无法在应用中忠实呈现，保持原值） */
private fun Long.opaqueHexOrNull(): String? =
    takeIf { (it shr 24) and 0xFF == 0xFFL }?.toColorHexString()

/**
 * 把主题映射结果应用到现有 DisplaySetting 上（partial 语义）：
 * 只覆盖解析成功的字段，解析失败/缺失的字段保留 base 原值，其余显示设置不受影响。
 *
 * 映射关系（颜色按酒馆的叠层语义合成为不透明近似色）：
 * - main_text_color → globalTextColor
 * - blur_tint_color + chat_tint_color（叠在按主文字亮度推断的底色上）→ chatBackgroundColor
 * - user/bot_mes_blur_tint_color 叠在聊天背景上 → user/assistantBubbleColor（色调几乎全透明时保留原气泡色，避免气泡隐形）
 * - quote/italics_text_color 叠在聊天背景上 → quoteColor/italicsColor（合成后不透明，保证可见）
 * - font_scale → fontSizeRatio（偏差减半 + clamp，酒馆与app基准字号不同，直接映射会明显偏小）
 * - chat_display=1（气泡模式）→ 开启 AI 气泡；其余值不动用户设置
 * - custom_css 中 .mes/#chat 的 border-radius → bubbleCornerRadius
 * - 无背景图时 blur_tint 推导输入框颜色 → inputFieldColor
 */
fun SillyTavernTheme.applyTo(base: DisplaySetting): DisplaySetting {
    val text = parseCssColor(mainTextColor)
    val blurTint = parseCssColor(blurTintColor)
    val chatTint = parseCssColor(chatTintColor)
    val userTint = parseCssColor(userMesBlurTintColor)
    val botTint = parseCssColor(botMesBlurTintColor)

    // 聊天背景：chat_tint → blur_tint → 底色（酒馆里底层是背景图，这里用亮度推断的中性色近似）
    val chatBackground: Long? = if (chatTint != null || blurTint != null) {
        val bottom = when {
            text != null -> if (relativeLuminance(text) > 0.5) DARK_BASE else LIGHT_BASE
            base.chatBackgroundColor != null -> base.chatBackgroundColor ?: LIGHT_BASE
            else -> LIGHT_BASE
        }
        var acc = bottom.toCssColor()
        blurTint?.let { acc = over(it.toCssColor(), acc) }
        chatTint?.let { acc = over(it.toCssColor(), acc) }
        acc.toArgbLong()
    } else null

    // 气泡 = 消息色调叠在聊天背景上。
    // 酒馆里全透明的气泡色调意味着"文字直接浮在背景上"（背景图由 CSS 提供），
    // 应用没有该背景图时合成结果与聊天背景同色 → 气泡隐形。
    // 色调 alpha 过低或合成结果与背景同色时，保留应用原气泡色。
    val bubbleBottom = (chatBackground ?: base.chatBackgroundColor ?: LIGHT_BASE).toCssColor()
    fun bubbleColor(tint: Long?): Long? = tint
        ?.takeIf { it.toCssColor().a / 255.0 >= BUBBLE_MIN_VISIBLE_ALPHA }
        ?.let { over(it.toCssColor(), bubbleBottom).toArgbLong() }
        ?.takeIf { chatBackground == null || it != chatBackground }
    val userBubble = bubbleColor(userTint)
    val botBubble = bubbleColor(botTint)

    // 引用/斜体等文字特效色：叠在聊天背景上合成不透明色再导入，
    // 保证深浅背景下都可见（酒馆里这些色常带 alpha）
    val accentBottom = (chatBackground ?: base.chatBackgroundColor ?: LIGHT_BASE).toCssColor()
    val quoteColor = parseCssColor(quoteTextColor)
        ?.let { over(it.toCssColor(), accentBottom).toArgbLong() }
        ?.opaqueHexOrNull()?.takeIf { base.quoteColor != it }
        ?: base.quoteColor
    val italicsColor = parseCssColor(italicsTextColor)
        ?.let { over(it.toCssColor(), accentBottom).toArgbLong() }
        ?.opaqueHexOrNull()?.takeIf { base.italicsColor != it }
        ?: base.italicsColor

    // 输入框：酒馆输入区用 blur_tint 叠层；无背景图时推导一个比聊天背景略亮/略暗的输入框色，
    // 有背景图时输入框保持用户原设置（纯色输入框压在图上会很突兀）
    val hasBackgroundImage = !extractBackgroundImageUrl(customCss).isNullOrBlank()
    val inputFieldColor = if (!hasBackgroundImage && (chatBackground != null || blurTint != null)) {
        val inputBg = blurTint?.let { over(it.toCssColor(), bottomBaseForInput(base, text).toCssColor()).toArgbLong() }
            ?: chatBackground
            ?: base.inputFieldColor
        inputBg?.let { mixTowardWhite(it, 0.06f) }
    } else {
        base.inputFieldColor
    }

    // 气泡外框/阴影：官方用 --SmartThemeBorderColor + --SmartThemeShadowColor 控制，
    // 由 CSS 引擎从层叠后的声明里取，避免正则误收 border-top/border-image 等
    val border = extractBubbleBorder(customCss)
    val shadow = extractBubbleShadow(customCss)

    return base.copy(
        globalTextColor = text ?: base.globalTextColor,
        chatBackgroundColor = chatBackground ?: base.chatBackgroundColor,
        userBubbleColor = userBubble ?: base.userBubbleColor,
        assistantBubbleColor = botBubble ?: base.assistantBubbleColor,
        quoteColor = quoteColor,
        italicsColor = italicsColor,
        inputFieldColor = inputFieldColor,
        // 酒馆基准字号与应用不同且主题普遍偏小（中位 0.9），直接映射会明显偏小：
        // 偏差减半并 clamp，保留"偏大/偏小"的意图但防止过小
        fontSizeRatio = fontScale?.takeIf { it > 0.0 }
            ?.let { 1.0 + (it - 1.0) * 0.5 }
            ?.toFloat()?.coerceIn(0.85f, 1.6f)
            ?: base.fontSizeRatio,
        // 仅气泡模式开启 AI 气泡；主题若给 AI 侧气泡铺了底图，也必须开启，
        // 否则底图没有承载元素、等于静默丢失
        showAssistantBubble = if (chatDisplay == 1 || !extractBubbleBackgroundImageUrl(customCss, forUser = false).isNullOrBlank()) {
            true
        } else {
            base.showAssistantBubble
        },
        bubbleCornerRadius = extractBubbleCornerRadius(customCss) ?: base.bubbleCornerRadius,
        // 主题的 background-size 决定底图是裁切还是等比
        bubbleBackgroundSize = extractBubbleBackgroundSize(customCss) ?: base.bubbleBackgroundSize,
        // 图标主题：发送栏/菜单/扩展/停止 + 头像框
        themeIcons = extractThemeIconSet(customCss).takeUnless { it.isEmpty } ?: base.themeIcons,
        // 气泡外框/阴影：官方用 --SmartThemeBorderColor + --SmartThemeShadowColor 控制，
        // 由 CSS 引擎从层叠后的声明里取，避免正则误收 border-top/border-image 等
        bubbleBorderColor = border?.color ?: base.bubbleBorderColor,
        bubbleBorderWidth = border?.width ?: base.bubbleBorderWidth,
        bubbleShadowColor = shadow?.color ?: base.bubbleShadowColor,
        bubbleShadowWidth = shadow?.width ?: base.bubbleShadowWidth,
        // 下划线色（官方 underline_text_color）；与其它文字特效色同样叠在背景上合成
        underlineColor = parseCssColor(underlineTextColor)
            ?.let { over(it.toCssColor(), accentBottom).toArgbLong() }
            ?: base.underlineColor,
    )
}

private const val BUBBLE_MIN_VISIBLE_ALPHA = 0.08

/** 输入框底色：无文字色时回退聊天背景或浅色底 */
private fun bottomBaseForInput(base: DisplaySetting, text: Long?): Long =
    base.chatBackgroundColor ?: if (text != null && relativeLuminance(text) <= 0.5) LIGHT_BASE else DARK_BASE

/** 向白色混合（提升亮度），用于输入框与聊天背景的层次感 */
private fun mixTowardWhite(color: Long, ratio: Float): Long {
    val c = color.toCssColor()
    val white = 255.0
    fun mix(v: Double) = v + (white - v) * ratio
    return CssColor(c.a, mix(c.r), mix(c.g), mix(c.b)).toArgbLong()
}

// ── custom_css 提取 ──

/**
 * 去掉 CSS 注释。
 *
 * 主题作者普遍用注释标注用途（如「ui背景图」「想换壁纸删除这段」），
 * 有模板甚至把占位串写在注释里（先写「你的图片链接」占位，后面才跟真实 url）。
 * 规则正则会把注释和它后面的选择器粘成一段文本，导致两类错误：
 * - 注释里的词被选择器白名单误命中（如注释提到 body，就把图标背景当成聊天背景导入）；
 * - 注释里的示例 url 被当成真实背景图（已实测有主题导入了字面量「你的图片链接」）。
 * 因此所有提取都必须先剥注释。
 */
private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

/** 剥掉注释后的 CSS，供各提取器使用 */
internal fun stripCssComments(css: String): String = CSS_COMMENT.replace(css, "")

/** 逐条匹配 CSS 规则（不含嵌套花括号的规则体；@media 外层规则自然被跳过、内层规则正常匹配） */
internal val CSS_RULE = Regex("([^{}]+)\\{([^{}]*)\\}")

/**
 * 从 custom_css 中提取消息气泡圆角（px 近似为 dp）。
 * 优先取 .mes/.mes_block 上的 border-radius，其次 #chat；多值取最大；百分比忽略，0px（方角）照搬；
 * 结果 clamp 到应用气泡圆角滑条范围 0-28dp。找不到返回 null。
 */
fun extractBubbleCornerRadius(css: String?): Float? {
    if (css.isNullOrBlank()) return null
    // 用 CSS 引擎算层叠结果：主题常把圆角写成变量（实测 138 处 var() 引用）、
    // 或与其它规则争抢同一属性，靠正则逐条扫描会取到被覆盖的旧值。
    val radius = bubbleDeclarations(css)["border-radius"] ?: return null
    if (radius.contains('%')) return PERCENT_RADIUS_DP
    // 四角写法取最大角，而不是首个值。
    // 主题大量使用「只圆某几角」的写法（如 `0px 0px 12px 12px` 只圆下方两角、
    // `12px 12px 0 0` 只圆上方），若按首个值取会得到 0，气泡渲染成完全直角，
    // 看上去就是"主题一丁点都没生效"。Compose 只能整体圆角，取最大值最接近作者意图。
    val corners = radius.trim().split(Regex("""[\s/]+""")).filter { it.isNotBlank() }
    val values = corners.mapNotNull { corner ->
        Regex("""^(\d+(?:\.\d+)?)(?:px)?$""").find(corner)?.groupValues?.get(1)?.toFloatOrNull()
    }
    if (values.isEmpty()) return null
    val px = values.max()
    if (px < 0f) return null
    // 真实主题圆角上限可达 999px（胶囊），不再夹到 28px——
    // 夹取会让「大圆角」主题看起来几乎没圆角，与官方观感不符。
    return px.coerceAtMost(MAX_RADIUS_DP)
}

/**
 * 按 CSS 层叠算出的气泡声明。
 *
 * 「气泡本体」在官方 DOM 里是 `.mes`；主题绝大多数把视觉写在 `.mes` / `.mes_block`
 * 及其伪元素上。这里把三类规则合并参与计算：
 * 1. 命中宿主 `.mes` / `.mes_block`
 * 2. 命中其伪元素 `.mes::before` 等（几何/圆角常写在这里）
 * 3. 带 `is_user` 的变体（侧别相关，取并集以便单侧缺失时回退）
 */
internal fun bubbleDeclarations(css: String, extraVars: Map<String, String> = emptyMap()): Map<String, String> {
    val rules = parseCssRules(css, extraVars)
    return cascadeDeclarations(rules) { selector ->
        containsMessageBubble(selector) && looksLikeBubbleFillOrPlain(selector, rules)
            || containsMessageBubble(selector.substringBefore("::"))
    }
}

/** 伪元素只有在铺满气泡时才参与气泡声明计算；普通元素总是参与 */
private fun looksLikeBubbleFillOrPlain(selector: String, rules: List<CssRule>): Boolean {
    if (!selector.contains("::")) return true
    val all = rules.filter { it.selector == selector }.flatMap { it.declarations }
    val body = all.joinToString(";") { "${it.property}:${it.value}" }
    return looksLikeBubbleFill(selector, body)
}

/**
 * 从已计算的气泡声明里取边框/阴影。
 *
 * 官方用 `--SmartThemeBorderColor` + `border` 简写、`--SmartThemeShadowColor` +
 * `--shadowWidth` 控制气泡外框。主题里 `border: 1px solid var(--SmartThemeBorderColor)`
 * 是极常见写法，靠正则匹配 `border` 会误收 `border-top` / `border-image` 等。
 */
internal fun extractBubbleBorder(css: String?): BubbleBorder? {
    if (css.isNullOrBlank()) return null
    val decls = bubbleDeclarations(css)
    // border 简写：宽度 样式 颜色（顺序任意）
    val shorthand = decls["border"] ?: decls["border-width"]?.let { null as String? }
    val widthFromShorthand = shorthand?.let { v ->
        Regex("""(\d+(?:\.\d+)?)px""").find(v)?.groupValues?.get(1)?.toFloatOrNull()
    }
    val colorFromShorthand = shorthand?.let { v ->
        Regex("""(#[0-9a-fA-F]{3,8}|rgba?\([^)]*\)|hsla?\([^)]*\))""").find(v)
            ?.value?.let { parseCssColor(it) }
    }
    val explicitWidth = decls["border-width"]?.let { v ->
        Regex("""(\d+(?:\.\d+)?)px""").find(v)?.groupValues?.get(1)?.toFloatOrNull()
    }
    val explicitColor = cssColorFrom(decls, "border-color")
    val width = explicitWidth ?: widthFromShorthand ?: 0f
    val color = explicitColor ?: colorFromShorthand
    val hasBorder = decls.containsKey("border") || decls.containsKey("border-width") ||
        decls.containsKey("border-color") || decls.containsKey("border-style")
    if (!hasBorder) return null
    // border: none / 0 表示无边框
    val styleNone = shorthand?.let { Regex("""\bnone\b|\bhidden\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true ||
        decls["border-style"]?.equals("none", ignoreCase = true) == true
    // 零宽度边框在视觉上不存在，即使写了颜色也不该画出边框
    if (styleNone || width <= 0f) return null
    return BubbleBorder(color = color, width = width)
}

/** 气泡阴影：`box-shadow` 的偏移/模糊/颜色 */
internal fun extractBubbleShadow(css: String?): BubbleShadow? {
    if (css.isNullOrBlank()) return null
    val decls = bubbleDeclarations(css)
    val shadow = decls["box-shadow"] ?: return null
    if (shadow.equals("none", ignoreCase = true)) return null
    // 阴影语法为 `[inset] offsetX offsetY [blur] [spread] color`。
    // 必须按「位置」取长度，且裸 0 也是合法长度（`0 6px 20px` 的 offsetX 就是裸 0）——
    // 只匹配带 px 的值会把 offsetX 吞掉，导致模糊半径整体错位一格。
    val lengths = SHADOW_LENGTH.findAll(shadow)
        .mapNotNull { it.groupValues[1].toFloatOrNull() }.toList()
    if (lengths.size < 2) return null
    // 第 3 个长度才是模糊半径；只有两个长度时说明没有模糊，用第二个
    val blur = lengths.getOrNull(2) ?: lengths[1]
    if (blur <= 0f) return null
    val color = Regex("""(#[0-9a-fA-F]{3,8}|rgba?\([^)]*\))""").find(shadow)
        ?.value?.let { parseCssColor(it) }
    return BubbleShadow(color = color, width = blur)
}

/** 阴影里的长度：裸 0 与带单位值都算（`0 6px 20px` 的首个 0 不能漏） */
private val SHADOW_LENGTH = Regex("""(?:^|\s)(-?\d+(?:\.\d+)?)(?:px)?(?=\s|$)""")

/** 气泡外框（官方 --SmartThemeBorderColor + border 简写） */
internal data class BubbleBorder(val color: Long?, val width: Float)

/** 气泡阴影（官方 --SmartThemeShadowColor + --shadowWidth） */
internal data class BubbleShadow(val color: Long?, val width: Float)

/** 百分比圆角统一按大圆角处理（50% 即胶囊） */
private const val PERCENT_RADIUS_DP = 24f

/** 允许的圆角上限：超过此值视觉上已是胶囊，再大无意义且会裁掉内容 */
private const val MAX_RADIUS_DP = 40f

/** 聊天背景所在元素的优先级：#bg1（酒馆专用背景层）> body > .bg1 > #chat > #main */
private val BACKGROUND_SELECTORS = listOf(
    0 to Regex("""(^|[\s,>+~])#bg1(?![\w-])"""),
    1 to Regex("""(^|[\s,>+~])body(?![\w-])"""),
    2 to Regex("""(^|[\s,>+~])\.bg1(?![\w-])"""),
    3 to Regex("""(^|[\s,>+~])#chat(?![\w-])"""),
    4 to Regex("""(^|[\s,>+~])#main(?![\w-])"""),
)

private val BACKGROUND_URL = Regex(
    """background(?:-image)?\s*:\s*[^;{}]*url\(\s*(['"]?)([^)'"]+)\1\s*\)""",
    RegexOption.IGNORE_CASE,
)

/**
 * 从 custom_css 中提取聊天背景图地址（http(s) URL 或 data URI）。
 * 只认 body/#bg1/.bg1/#chat/#main 上的 background/background-image，避免误抓头像框等装饰图。
 * 找不到返回 null。
 */
fun extractBackgroundImageUrl(css: String?): String? {
    if (css.isNullOrBlank()) return null
    var bestPriority = Int.MAX_VALUE
    var bestUrl: String? = null
    for (m in CSS_RULE.findAll(stripCssComments(css))) {
        val selector = m.groupValues[1]
        val priority = BACKGROUND_SELECTORS.firstOrNull { it.second.containsMatchIn(selector) }?.first
            ?: continue
        if (priority > bestPriority) continue
        val url = BACKGROUND_URL.find(m.groupValues[2])?.groupValues?.get(2)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: continue
        if (priority < bestPriority || bestUrl == null) {
            bestPriority = priority
            bestUrl = url
        }
    }
    return bestUrl
}

/**
 * 消息气泡自身的背景图。
 *
 * 实测 553 个主题里有 165 个把纹理/图片直接铺在 `.mes` / `.mes_block` 上（如"bjd""蝶"系列），
 * 这是主题最显眼的特征之一。
 *
 * 伪元素必须一并处理：官方主题最主流的写法是
 *
 *     .mes_block::before { position:absolute; top:0; left:0; width:100%; height:200px;
 *                          background-size: cover; background-position: center; }
 *     .mes[is_user="false"] .mes_block::before { background-image: url(...); }
 *     .mes[is_user="true"]  .mes_block::before { background-image: url(...); }
 *
 * 注意几何声明（size/position）与实际图片（background-image）**分处两条规则**，
 * 且用 `is_user` 区分两侧。只匹配"同一条规则里既有选择器又有 url"会全部漏掉——
 * 实测 436 个带气泡底图的主题里有 188 个因此完全导不进来。
 * 因此这里按「同选择器文本合并声明」再取图。
 *
 * @param forUser true 取用户侧气泡，false 取 AI 侧；主题若只写了 .mes（未区分 is_user）
 *                则两侧都用同一张。
 */
fun extractBubbleBackgroundImageUrl(css: String?, forUser: Boolean): String? {
    if (css.isNullOrBlank()) return null
    val merged = mergeRulesBySelector(css)
    // 先汇总各宿主元素上的铺满几何：几何与图片常写在两条不同规则里，
    // 例如 .mes_block::before{width:100%;height:200px} 与
    //      .mes[is_user="true"] .mes_block::before{background-image:url(...)}
    val fillingHosts = merged.filter { (sel, body) -> looksLikeBubbleFill(sel, body) }
        .map { it.first }
        .map(::bubbleHostKey)
        .toSet()
    var generic: String? = null      // .mes / .mes_block（不分侧，纯元素选择器）
    var specific: String? = null     // .mes[is_user="true"] 之类

    for ((selector, body) in merged) {
        if (!containsMessageBubble(selector)) continue
        // 几何与图片通常分处两条规则：几何写在裸宿主上（.mes_block::before{width:100%}），
        // 图片写在带 is_user 的变体上（.mes[is_user="true"] .mes_block::before{background-image}）。
        // 因此只要该规则自身带几何、或其「去 is_user 后的宿主选择器」带几何，就算铺满型。
        if (!looksLikeBubbleFill(selector, body) && bubbleHostKey(selector) !in fillingHosts) continue
        val url = BACKGROUND_URL.find(body)?.groupValues?.get(2)?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("var(") } ?: continue
        val wantUser = USER_SIDE_SELECTOR.containsMatchIn(selector)
        val wantBot = BOT_SIDE_SELECTOR.containsMatchIn(selector)
        when {
            wantUser && forUser -> specific = specific ?: url
            wantBot && !forUser -> specific = specific ?: url
            !wantUser && !wantBot -> generic = generic ?: url
        }
    }
    return specific ?: generic
}

/**
 * 气泡底图的缩放方式。
 *
 * 主题里 `background-size: cover` 配 `height: 200px` 是很常见的写法，
 * 官方页面里靠元素自身高度约束；本地气泡高度由文字撑开，
 * 直接用 cover 会把图压成扁条，所以取到值后由调用方决定如何落版。
 */
fun extractBubbleBackgroundSize(css: String?): String? {
    if (css.isNullOrBlank()) return null
    for ((selector, body) in mergeRulesBySelector(css)) {
        if (!containsMessageBubble(selector)) continue
        val v = Regex("""background-size\s*:\s*([^;}!]+)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        // 多图主题（逗号分隔）取第一个，避免把多段尺寸串当成一个值
        return v.split(',').first().trim()
    }
    return null
}

/**
 * 把同一选择器的多条规则合并成一条（后者覆盖前者，符合 CSS 层叠）。
 *
 * 主题作者常把一条视觉规则拆成多段书写，例如 `.mes_block::before` 先写几何、
 * 再由 `.mes[is_user="false"] .mes_block::before` 单独写图。不合并就只能看到半条。
 */
private fun mergeRulesBySelector(css: String): List<Pair<String, String>> {
    val order = LinkedHashMap<String, StringBuilder>()
    for (m in CSS_RULE.findAll(stripCssComments(css))) {
        val selector = m.groupValues[1].trim().replace(Regex("""\s+"""), " ")
        if (selector.isEmpty()) continue
        val acc = order.getOrPut(selector) { StringBuilder() }
        acc.append(m.groupValues[2]).append(';')
    }
    return order.map { it.key to it.value.toString() }
}

/**
 * 选择器是否指向消息气泡本体。
 *
 * 伪元素要单独判断：官方主题里 `.mes_block::before` / `.mes::before` 这类伪元素
 * 正是最主流的气泡底图承载者（实测带图规则 847 条，其中 562 条带铺满型几何声明），
 * 一律排除会丢掉绝大多数主题。但 `.avatar::after` 这类纯装饰也必须排除。
 *
 * 判据不是「有没有 `::`」，而是宿主是不是气泡本体 + 声明里有没有铺满意图：
 * `content:''` / `position:absolute` / `width:100%` / `height:Npx` 占 ≥2 项即认定为底图，
 * 只有 url 没有几何的伪元素视为角标、装饰。
 */
private fun containsMessageBubble(selector: String): Boolean {
    val withoutPseudo = selector.substringBefore("::")
    if (withoutPseudo.isEmpty()) return false
    // 剥离属性选择器，避免 .mes[is_user="false"] 里的引号干扰判定
    val bare = withoutPseudo.replace(Regex("""\[[^\]]*\]"""), "")
    return MESSAGE_BUBBLE_SELECTOR.containsMatchIn(bare)
}

/** 伪元素是否在「铺满气泡」（而非贴一个角标） */
private fun looksLikeBubbleFill(selector: String, body: String): Boolean {
    if (!selector.contains("::")) return true
    var score = 0
    if (Regex("""content\s*:\s*['"]""").containsMatchIn(body)) score++
    if (Regex("""position\s*:\s*absolute""", RegexOption.IGNORE_CASE).containsMatchIn(body)) score++
    if (Regex("""width\s*:\s*100%""").containsMatchIn(body)) score++
    if (Regex("""height\s*:\s*[\d.]+(px|%)""").containsMatchIn(body)) score++
    return score >= 2
}

/**
 * 归一化「气泡宿主」标识，用于跨规则比对。
 *
 * 必须剥掉 `is_user` 属性、多余空白与伪元素之前的前置选择器差异，
 * 使 `.mes[is_user="true"] .mes_block::before` 与 `.mes_block::before`
 * 归到同一个 key 上——几何与前缀写法不同但指向同一块气泡。
 */
private fun bubbleHostKey(selector: String): String =
    selector
        .replace(USER_SIDE_SELECTOR, "")
        .replace(BOT_SIDE_SELECTOR, "")
        // 去掉空属性残留与可能的 `[` 悬空
        .replace(Regex("""\[\s*\]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        // 只保留伪元素之前的「最后一段宿主」+ 伪元素本身，忽略前置上下文
        .let { normalized ->
            val pseudo = normalized.substringAfter("::", missingDelimiterValue = "")
            val head = normalized.substringBefore("::").trim().split(' ').lastOrNull().orEmpty()
            if (pseudo.isEmpty()) head else "$head::$pseudo"
        }

/** 气泡选择器：.mes / .mes_block（排除 .mes_text、.mes_buttons 等子元素） */
private val MESSAGE_BUBBLE_SELECTOR =
    Regex("""(^|[\s,>+~])(\.mes|\.mes_block)(?![\w-])""")

/** 酒馆用 is_user 属性区分消息归属 */
private val USER_SIDE_SELECTOR = Regex("""is_user\s*=\s*['"]?true""", RegexOption.IGNORE_CASE)
private val BOT_SIDE_SELECTOR = Regex("""is_user\s*=\s*['"]?false""", RegexOption.IGNORE_CASE)

/** @font-face 块（font-family + src url + format） */
private val FONT_FACE = Regex("""@font-face\s*\{([^}]*)\}""", RegexOption.IGNORE_CASE)

/** Android Typeface 只认 ttf/otf（woff/woff2 无法原生加载），按 URL 扩展名或 format 判断 */
private val USABLE_FONT_EXT = Regex("""\.(ttf|otf)(\?|#|$)""", RegexOption.IGNORE_CASE)
private val USABLE_FONT_FORMAT = Regex("""format\(\s*['"]?(truetype|opentype)""", RegexOption.IGNORE_CASE)
private val CSS_URL = Regex("""url\(\s*(['"]?)([^)'"]+)\1\s*\)""", RegexOption.IGNORE_CASE)

/**
 * 从 custom_css 的 @font-face 中提取主题字体。
 * @return url 为 ttf/otf 字体地址（http/https 或 data URI）；family 为 font-family 名称（可能为 null）
 */
fun extractThemeFontUrl(css: String?): String? = extractThemeFont(css)?.url

/** 同 [extractThemeFontUrl]，同时带出 font-family 名称 */
fun extractThemeFont(css: String?): ThemeFont? {
    if (css.isNullOrBlank()) return null
    for (m in FONT_FACE.findAll(stripCssComments(css))) {
        val body = m.groupValues[1]
        val url = CSS_URL.find(body)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
            ?: continue
        val usable = USABLE_FONT_EXT.containsMatchIn(url) ||
            USABLE_FONT_FORMAT.containsMatchIn(body) ||
            url.startsWith("data:font/", ignoreCase = true)
        if (!usable) continue
        val family = Regex("""font-family\s*:\s*['"]?([^;'"]+)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return ThemeFont(family = family, url = url)
    }
    return null
}

data class ThemeFont(
    val family: String?,
    val url: String,
)

// ── 图标主题（发送栏 / 头像框） ──

/**
 * 酒馆主题对 UI 图标的自定义。
 *
 * 官方把发送栏、抽屉、扩展菜单等做成纯 CSS 的元素（如 `#send_but` 就是带
 * FontAwesome 字体的 div），所以主题可以直接改写它们。实测 553 个主题里：
 * 452 个改过发送栏、510 个带头像框、483 个动过下拉/模型区。
 *
 * 主题的改写手段按频率排序为：换色（276）> 隐藏（140）> 换字号（127）> 换图（111）。
 * 其中「换图」是作者明确挑选的素材，保真度最高，因此优先映射为图标图片；
 * 「换色」「换字号」映射到图标的 tint 与尺寸；「隐藏」映射为不显示该按钮。
 */
@Serializable
data class ThemeIconSet(
    /** 发送按钮图标（官方 #send_but） */
    val sendImageUrl: String? = null,
    /** 发送按钮颜色（官方 #send_but 的 color） */
    val sendTint: Long? = null,
    /** 发送按钮字号→图标尺寸缩放 */
    val sendScale: Float? = null,
    /** 主题是否隐藏了发送按钮 */
    val hideSend: Boolean = false,

    /** 选项/菜单按钮图标（官方 #options_button） */
    val optionsImageUrl: String? = null,
    val optionsTint: Long? = null,
    val optionsScale: Float? = null,
    val hideOptions: Boolean = false,

    /** 扩展菜单按钮图标（官方 #extensionsMenuButton） */
    val extensionsImageUrl: String? = null,
    val extensionsTint: Long? = null,
    val extensionsScale: Float? = null,
    val hideExtensions: Boolean = false,

    /** 停止/中断按钮图标（官方 #mes_stop） */
    val stopImageUrl: String? = null,
    val stopTint: Long? = null,
    val stopScale: Float? = null,

    /** 用户侧头像框（官方 .mes[is_user="true"] .avatar::before） */
    val userAvatarFrameUrl: String? = null,
    /** AI 侧头像框 */
    val botAvatarFrameUrl: String? = null,
    /** 不分侧的头像框（主题只写了 .avatar::before） */
    val avatarFrameUrl: String? = null,
    /** 头像框相对头像的放大倍率（官方常用 112px 框套 50px 头像） */
    val avatarFrameScale: Float? = null,
) {
    /** 是否有任何图标被主题定制 */
    val isEmpty: Boolean
        get() = sendImageUrl == null && optionsImageUrl == null && extensionsImageUrl == null &&
            stopImageUrl == null && avatarFrameUrl == null &&
            userAvatarFrameUrl == null && botAvatarFrameUrl == null &&
            !hideSend && !hideOptions && !hideExtensions &&
            sendTint == null && optionsTint == null && extensionsTint == null && stopTint == null

    /** 取指定侧的头像框，带回退 */
    fun avatarFrame(forUser: Boolean): String? =
        (if (forUser) userAvatarFrameUrl else botAvatarFrameUrl) ?: avatarFrameUrl
}

/** 图标宿主元素 ID → 主题里的选择器（对齐官方 index.html） */
private val ICON_TARGETS = listOf(
    "send" to "#send_but",
    "options" to "#options_button",
    "extensions" to "#extensionsMenuButton",
    "stop" to "#mes_stop",
)

/**
 * 从 custom_css 提取图标主题。
 *
 * 每个按钮独立解析：先按选择器定位规则，再依次读取 background-image（换图）、
 * color（换色）、font-size（换尺寸）、display:none（隐藏）。
 * `background-image: none` 表示作者清掉了默认图标，此时不当作图片，但保留其它属性。
 */
fun extractThemeIconSet(css: String?): ThemeIconSet {
    if (css.isNullOrBlank()) return ThemeIconSet()
    val merged = mergeRulesBySelector(css)

    /** 收集所有命中该 id 的规则体（含伪元素写法） */
    fun bodiesFor(id: String): List<String> = merged
        .filter { (sel, _) ->
            val bare = sel.replace(Regex("""\[[^\]]*\]"""), "")
            Regex(Regex.escape(id) + """(?![\w-])""").containsMatchIn(bare)
        }
        .map { it.second }

    fun imageOf(bodies: List<String>): String? {
        for (body in bodies) {
            // 显式 none 代表清除默认图标，不再向后找
            if (Regex("""background-image\s*:\s*none""", RegexOption.IGNORE_CASE).containsMatchIn(body)) return null
            val u = Regex(
                """background(?:-image)?\s*:\s*[^;{}]*url\(\s*(['"]?)([^)'"]+)\1\s*\)""",
                RegexOption.IGNORE_CASE,
            ).find(body)?.groupValues?.get(2)?.trim()
            if (!u.isNullOrEmpty() && !u.startsWith("var(")) return u
        }
        return null
    }

    fun colorOf(bodies: List<String>): Long? {
        for (body in bodies) {
            val c = Regex("""(?:^|;)\s*color\s*:\s*([^;}!]+)""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim() ?: continue
            parseCssColor(c)?.let { return it }
        }
        return null
    }

    fun scaleOf(bodies: List<String>): Float? {
        for (body in bodies) {
            val v = Regex("""font-size\s*:\s*([\d.]+)px""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            // 官方默认图标约 20px；换算成相对倍率并限制在合理区间
            return (v / 20f).coerceIn(0.6f, 2.5f)
        }
        return null
    }

    fun hiddenOf(bodies: List<String>): Boolean =
        bodies.any { Regex("""display\s*:\s*none""", RegexOption.IGNORE_CASE).containsMatchIn(it) }

    val sendB = bodiesFor(ICON_TARGETS[0].second)
    val optB = bodiesFor(ICON_TARGETS[1].second)
    val extB = bodiesFor(ICON_TARGETS[2].second)
    val stopB = bodiesFor(ICON_TARGETS[3].second)

    // 头像框：.avatar::before / ::after 上带 url 的规则，按 is_user 分侧
    var userFrame: String? = null
    var botFrame: String? = null
    var anyFrame: String? = null
    var frameScale: Float? = null
    for ((sel, body) in merged) {
        if (!Regex("""\.avatar\s*::(?:before|after)""").containsMatchIn(sel)) continue
        if (Regex("""background-image\s*:\s*none""", RegexOption.IGNORE_CASE).containsMatchIn(body)) continue
        val url = Regex(
            """background(?:-image)?\s*:\s*[^;{}]*url\(\s*(['"]?)([^)'"]+)\1\s*\)""",
            RegexOption.IGNORE_CASE,
        ).find(body)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("var(") }
            ?: continue
        when {
            USER_SIDE_SELECTOR.containsMatchIn(sel) -> userFrame = userFrame ?: url
            BOT_SIDE_SELECTOR.containsMatchIn(sel) -> botFrame = botFrame ?: url
            else -> anyFrame = anyFrame ?: url
        }
        if (frameScale == null) {
            // 框比头像大是常态（112px 框 / 50px 头像），换算成倍率上限 2.4
            Regex("""width\s*:\s*(\d+)px""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.toFloatOrNull()
                ?.let { frameScale = (it / 50f).coerceIn(0.8f, 2.4f) }
        }
    }

    return ThemeIconSet(
        sendImageUrl = imageOf(sendB),
        sendTint = colorOf(sendB),
        sendScale = scaleOf(sendB),
        hideSend = hiddenOf(sendB),
        optionsImageUrl = imageOf(optB),
        optionsTint = colorOf(optB),
        optionsScale = scaleOf(optB),
        hideOptions = hiddenOf(optB),
        extensionsImageUrl = imageOf(extB),
        extensionsTint = colorOf(extB),
        extensionsScale = scaleOf(extB),
        hideExtensions = hiddenOf(extB),
        stopImageUrl = imageOf(stopB),
        stopTint = colorOf(stopB),
        stopScale = scaleOf(stopB),
        userAvatarFrameUrl = userFrame,
        botAvatarFrameUrl = botFrame,
        avatarFrameUrl = anyFrame,
        avatarFrameScale = frameScale,
    )
}
