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
 * - user/bot_mes_blur_tint_color 叠在聊天背景上 → user/assistantBubbleColor
 * - quote/italics_text_color → quoteColor/italicsColor（仅不透明色）
 * - font_scale → fontSizeRatio（clamp 到应用支持的 0.5-2.0）
 * - chat_display=1（气泡模式）→ showAssistantBubble
 * - custom_css 中 .mes/#chat 的 border-radius → bubbleCornerRadius
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

    // 气泡 = 消息色调叠在聊天背景上
    val bubbleBottom = (chatBackground ?: base.chatBackgroundColor ?: LIGHT_BASE).toCssColor()
    val userBubble = userTint?.let { over(it.toCssColor(), bubbleBottom).toArgbLong() }
    val botBubble = botTint?.let { over(it.toCssColor(), bubbleBottom).toArgbLong() }

    return base.copy(
        globalTextColor = text ?: base.globalTextColor,
        chatBackgroundColor = chatBackground ?: base.chatBackgroundColor,
        userBubbleColor = userBubble ?: base.userBubbleColor,
        assistantBubbleColor = botBubble ?: base.assistantBubbleColor,
        quoteColor = parseCssColor(quoteTextColor)?.opaqueHexOrNull()?.takeIf { base.quoteColor != it }
            ?: base.quoteColor,
        italicsColor = parseCssColor(italicsTextColor)?.opaqueHexOrNull()?.takeIf { base.italicsColor != it }
            ?: base.italicsColor,
        fontSizeRatio = fontScale?.takeIf { it > 0.0 }?.toFloat()?.coerceIn(0.5f, 2.0f)
            ?: base.fontSizeRatio,
        showAssistantBubble = when (chatDisplay) {
            1 -> true
            0, 2 -> false
            else -> base.showAssistantBubble
        },
        bubbleCornerRadius = extractBubbleCornerRadius(customCss) ?: base.bubbleCornerRadius,
    )
}

// ── custom_css 提取 ──

/** 逐条匹配 CSS 规则（不含嵌套花括号的规则体；@media 外层规则自然被跳过、内层规则正常匹配） */
private val CSS_RULE = Regex("([^{}]+)\\{([^{}]*)\\}")

/** 选择器是否直接指向 .mes / .mes_block / #chat（不含类名/ID 的其他前后缀，如 .mes_text、#chat_form 不算） */
private val MESSAGE_SELECTOR = Regex("""(^|[\s,>+~])(\.mes\b|\.mes_block\b|#chat\b)(?![\w-])""")

/**
 * 从 custom_css 中提取消息气泡圆角（px 近似为 dp）。
 * 优先取 .mes/.mes_block 上的 border-radius，其次 #chat；多值取最大；百分比忽略，0px（方角）照搬；
 * 结果 clamp 到应用气泡圆角滑条范围 0-28dp。找不到返回 null。
 */
fun extractBubbleCornerRadius(css: String?): Float? {
    if (css.isNullOrBlank()) return null
    for (m in CSS_RULE.findAll(css)) {
        if (!MESSAGE_SELECTOR.containsMatchIn(m.groupValues[1])) continue
        val body = m.groupValues[2]
        val radius = Regex("""border-radius\s*:\s*([^;}!]+)""").find(body)?.groupValues?.get(1) ?: continue
        val px = Regex("""(\d+(?:\.\d+)?)px""").findAll(radius)
            .mapNotNull { it.groupValues[1].toFloatOrNull() }
            .maxOrNull()
            ?: continue
        if (px >= 0f) return px.coerceIn(0f, 28f)
    }
    return null
}

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
    for (m in CSS_RULE.findAll(css)) {
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
    for (m in FONT_FACE.findAll(css)) {
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
