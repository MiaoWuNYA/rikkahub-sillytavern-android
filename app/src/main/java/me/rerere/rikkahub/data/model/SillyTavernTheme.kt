package me.rerere.rikkahub.data.model

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
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
    // 边框颜色
    @SerialName("border_color") val borderColor: String? = null,
    // 阴影宽度（官方 --shadowWidth，单位 px）
    @SerialName("shadow_width") val shadowWidth: Int? = null,
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

/**
 * 构建酒馆官方的 CSS 变量表，供 [parseCssRules] / [mergeRulesBySelector] 展开变量时使用。
 *
 * 为什么必须有这一层：主题 CSS 大量直接写 `var(--SmartThemeChatTintColor)`、
 * `var(--ui-color-main)`、`var(--chat-background-color)` 这类**官方运行时注入**的变量，
 * 主题文件里并不定义它们。我们若不提供，`var()` 展开不出来，整条声明被丢弃，
 * 表现就是"气泡完全不生效、所有主题背景都不生效"。
 *
 * 实测：534 个主题里 457 个（86%）引用了至少一个主题自身未定义的变量；
 * `--SmartThemeBodyColor`(166)、`--SmartThemeChatTintColor`(103)、
 * `--SmartThemeBorderColor`(93) 位列前茅。
 *
 * 取值来源是本主题的 JSON 字段（main_text_color 等），即酒馆把这些字段
 * 注入成同名变量的那份数据。
 */
fun SillyTavernTheme.officialCssVariables(): Map<String, String> {
    val vars = LinkedHashMap<String, String>()

    fun put(vararg names: String, value: String?) {
        val v = value?.takeIf { it.isNotBlank() } ?: return
        names.forEach { vars[it] = v }
    }

    // 文字色
    put("--SmartThemeBodyColor", value = mainTextColor)
    put("--SmartThemeEmColor", value = italicsTextColor)
    put("--SmartThemeUnderlineColor", value = underlineTextColor)
    put("--SmartThemeQuoteColor", value = quoteTextColor)

    // 底色 / 色调：气泡与聊天背景的主要来源
    put("--SmartThemeBlurTintColor", value = blurTintColor)
    put("--SmartThemeChatTintColor", value = chatTintColor)
    put("--SmartThemeUserMesBlurTintColor", value = userMesBlurTintColor)
    put("--SmartThemeBotMesBlurTintColor", value = botMesBlurTintColor)

    // 边框 / 阴影
    put("--SmartThemeBorderColor", value = borderColor)
    put("--SmartThemeShadowColor", value = shadowColor)

    // 几何
    put("--SmartThemeShadowWidth", value = shadowWidth?.let { "${it}px" })
    put("--shadowWidth", value = shadowWidth?.let { "${it}px" })
    put("--SmartThemeBlurStrength", value = blurStrength?.toString())
    put("--blurStrength", value = blurStrength?.toString())
    put("--SmartThemeFontScale", value = fontScale?.toString())

    // 常用别名：不同主题作者写法不一，统一补齐
    // （--ui-color-main / --chat-background-color / --text-color-sec 等）
    (vars["--SmartThemeBotMesBlurTintColor"]
        ?: vars["--SmartThemeBlurTintColor"])?.let { vars["--ui-color-main"] = it }
    (vars["--SmartThemeChatTintColor"]
        ?: vars["--SmartThemeBlurTintColor"])?.let { vars["--chat-background-color"] = it }
    vars["--SmartThemeEmColor"]?.let { vars["--text-color-sec"] = it }

    return vars
}

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
/**
 * 把主题带来的外观字段全部清回默认，用于「恢复默认外观」。
 *
 * 为什么需要单独一个函数：applyTo 对每个字段都写成
 * `extractXxx() ?: base.xxx`，语义是"主题没写这项就保留当前值"。
 * 这个语义保证了导入残缺主题不会把已有样式洗掉，但也导致
 * **无法回到默认** —— 导入主题 A 后，再导入不含引用块样式的主题 B，
 * 引用块样式仍停留在 A 的值。
 *
 * 这里把所有由主题写入的字段显式置为默认值，与 applyTo 的"保留"语义互补：
 * 用户想清空时走这条路，主题残缺时走 applyTo 的保留分支。
 */
fun resetThemeAppearance(base: DisplaySetting): DisplaySetting {
    val defaults = DisplaySetting()
    return base.copy(
        // 主题内层样式（引用块/代码块/高亮/斜体/思维链的底色与几何）
        themeMarkdownStyle = ThemeMarkdownStyle(),
        // 主题可能写入的配色与几何
        bubbleCornerRadius = defaults.bubbleCornerRadius,
        bubbleBackgroundSize = defaults.bubbleBackgroundSize,
        themeIcons = ThemeIconSet(),
        bubbleBorderColor = null,
        bubbleBorderWidth = defaults.bubbleBorderWidth,
        bubbleShadowColor = null,
        bubbleShadowWidth = defaults.bubbleShadowWidth,
    )
}

fun SillyTavernTheme.applyTo(base: DisplaySetting): DisplaySetting {
    // 酒馆官方注入的变量表。主题大量引用 --SmartTheme* / --ui-color-main /
    // --chat-background-color 等并未在主题里定义的变量；不提供它们，
    // var() 展开不出来会导致整条声明被丢弃（实测 457/534 个主题受影响）。
    val themeVars = officialCssVariables()
    val text = parseCssColor(mainTextColor)
    val blurTint = parseCssColor(blurTintColor)
    val chatTint = parseCssColor(chatTintColor)
    val userTint = parseCssColor(userMesBlurTintColor)
    val botTint = parseCssColor(botMesBlurTintColor)

    // 聊天背景：优先取 custom_css 里聊天宿主上的 background（纯色或渐变主色），
    // 其次才回落到 chat_tint → blur_tint 字段。
    //
    // 顺序不能反：字段常常为空或近乎透明，而主题主流写法是把底色/渐变直接写在
    // CSS 的 .mes_block / #sheld / body 上。只读字段会让这些主题的底色整体丢失，
    // 表现就是"只有纯色背景才可以生效"——实际只有恰好设了那两个字段的主题才生效。
    val cssChatBgColor = extractChatBackgroundColor(customCss, themeVars)
        ?: extractChatBackgroundGradientColor(customCss, themeVars)
    val fieldChatBackground: Long? = if (chatTint != null || blurTint != null) {
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
    val chatBackground: Long? = cssChatBgColor ?: fieldChatBackground

    // 气泡底色：优先取 custom_css 里 .mes/.mes_block 的 background，其次才是字段 token。
    // 实测 452/534 个主题把气泡背景写在 CSS 里（其中 113 个是显式 transparent、
    // 64 个是渐变），只读 user_mes_blur_tint_color 会让绝大多数主题的气泡颜色丢失。
    val cssBubbleBg = extractBubbleBackgroundColor(customCss, themeVars)

    /**
     * CSS 侧结果优先，且**允许透明**。
     *
     * `transparent` 是主题的明确意图（冬之誓系列等 113 个主题就靠它做"文字浮在背景上"），
     * 旧实现把「合成后与背景同色」一律当成没算出来并回退默认色，
     * 等于把作者刻意做的透明气泡换成实心色块 —— 这正是"气泡背景失效"的观感来源。
     */
    fun cssBubbleColor(forUser: Boolean): Long? {
        val bg = cssBubbleBg ?: return null
        val side = if (forUser) bg.forUser else bg.forBot
        val pick = side ?: bg.generic ?: return null
        return if (pick.transparent) TRANSPARENT_BUBBLE else pick.color
    }

    // 字段 token 回退：叠在聊天背景上合成不透明色，alpha 过低视为无效
    val bubbleBottom = (chatBackground ?: base.chatBackgroundColor ?: LIGHT_BASE).toCssColor()
    fun tokenBubbleColor(tint: Long?): Long? = tint
        ?.takeIf { it.toCssColor().a / 255.0 >= BUBBLE_MIN_VISIBLE_ALPHA }
        ?.let { over(it.toCssColor(), bubbleBottom).toArgbLong() }
        ?.takeIf { chatBackground == null || it != chatBackground }

    val userBubble = cssBubbleColor(forUser = true) ?: tokenBubbleColor(userTint)
    val botBubble = cssBubbleColor(forUser = false) ?: tokenBubbleColor(botTint)

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
    val border = extractBubbleBorder(customCss, themeVars)
    val shadow = extractBubbleShadow(customCss, themeVars)

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
        showAssistantBubble = if (chatDisplay == 1 || !extractBubbleBackgroundImageUrl(customCss, forUser = false, themeVars).isNullOrBlank()) {
            true
        } else {
            base.showAssistantBubble
        },
        bubbleCornerRadius = extractBubbleCornerRadius(customCss, themeVars) ?: base.bubbleCornerRadius,
        // 主题的 background-size 决定底图是裁切还是等比
        bubbleBackgroundSize = extractBubbleBackgroundSize(customCss, themeVars) ?: base.bubbleBackgroundSize,
        // 图标主题：发送栏/菜单/扩展/停止 + 头像框
        themeIcons = extractThemeIconSet(customCss, themeVars).takeUnless { it.isEmpty } ?: base.themeIcons,
        // 气泡内层（代码框/引用块/高亮/斜体/思维链）的底色与几何，主题几乎都写了。
        // 提取不到时保留用户当前设置，避免"导入一个没写这些样式的主题"把已有样式清空。
        themeMarkdownStyle = extractThemeMarkdownStyle(customCss)
            .takeUnless { it.isEmpty }
            ?: base.themeMarkdownStyle,
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

/** 透明气泡的哨兵值：告知渲染层"不要画底色"，而不是回退默认色 */
const val TRANSPARENT_BUBBLE = 0L

/** 单侧气泡背景（纯色或显式透明） */
internal data class CssBackgroundColor(
    val color: Long? = null,
    val transparent: Boolean = false,
)

/** 气泡背景：两侧可分别指定，`generic` 为不分侧的写法 */
internal data class CssBubbleBackground(
    val forUser: CssBackgroundColor? = null,
    val forBot: CssBackgroundColor? = null,
    val generic: CssBackgroundColor? = null,
)

/**
 * 从 custom_css 的 `.mes` / `.mes_block` 上取气泡背景色。
 *
 * 主题两种写法都要支持：
 * - `background-color: #fff` / `background-color: transparent`
 * - `background: <简写>`（可含渐变、多个空格分隔的层）
 *
 * 渐变无法在 Compose 侧还原成一个颜色，此时取渐变里的**主色**近似
 * （第一个颜色停靠点），比整条丢弃更接近作者观感。
 */
internal fun extractBubbleBackgroundColor(
    css: String?,
    extraVars: CssVariables = emptyMap(),
): CssBubbleBackground? {
    if (css.isNullOrBlank()) return null
    val merged = mergeRulesBySelector(css, extraVars)
    var generic: CssBackgroundColor? = null
    var user: CssBackgroundColor? = null
    var bot: CssBackgroundColor? = null
    // 主题普遍是双层的：`.mes` 是外层容器（常被显式清成 transparent），
    // `.mes_block` 才是真正承载视觉的气泡本体（如冬之誓 `.mes_block{background:#fff}`）。
    // 若按出现顺序取第一条，外层那条 transparent 会赢下来，气泡就被判成透明，
    // 观感上等于"主题的气泡样式完全没生效"。因此给内层更高优先级。
    var genericDepth = -1
    var userDepth = -1
    var botDepth = -1

    for ((selector, body) in merged) {
        if (!containsMessageBubble(selector)) continue
        if (!looksLikeBubbleFill(selector, body)) continue
        val parsed = parseBackgroundColor(body) ?: continue
        val depth = bubbleLayerDepth(selector)
        val wantUser = USER_SIDE_SELECTOR.containsMatchIn(selector)
        val wantBot = BOT_SIDE_SELECTOR.containsMatchIn(selector)
        when {
            wantUser && depth > userDepth -> { user = parsed; userDepth = depth }
            wantBot && depth > botDepth -> { bot = parsed; botDepth = depth }
            !wantUser && !wantBot && depth > genericDepth -> { generic = parsed; genericDepth = depth }
        }
    }
    if (generic == null && user == null && bot == null) return null
    return CssBubbleBackground(forUser = user, forBot = bot, generic = generic)
}

/**
 * 气泡层级深度：选择器里 `.mes` 之后每多一层就 +1。
 *
 * `.mes` = 0（外层容器）、`.mes .mes_block` / `.mes_block` = 1（气泡本体）。
 * 越靠内越接近用户看到的那块背景，取值时优先。
 */
private fun bubbleLayerDepth(selector: String): Int {
    val bare = selector.substringBefore("::").replace(Regex("""\[[^\]]*\]"""), "")
    return bare.split(',').maxOfOrNull { part ->
        part.trim().split(Regex("""\s+""")).count { it.isNotEmpty() }
    } ?: 0
}

/** 从声明体里解析背景色；返回 null 表示这条规则没写背景（继续看下一条） */
private fun parseBackgroundColor(body: String): CssBackgroundColor? {
    // 主题几乎处处写 !important（实测冬之誓系列每条背景都带），
    // 正则取到的值会连带 " !important" 一起进来，直接送给颜色解析必然失败。
    // 这里统一剥掉再解析，否则整条规则被当成"没写背景"静默丢弃。
    fun clean(v: String?): String? = v
        ?.replace(Regex("""!important""", RegexOption.IGNORE_CASE), "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    // 长属性优先：与 CSS 层叠一致，长属性会覆盖简写里的对应分量
    val longColor = clean(
        Regex("""(?:^|;)\s*background-color\s*:\s*([^;}]*)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)
    )
    val longImage = clean(
        Regex("""(?:^|;)\s*background-image\s*:\s*([^;}]*)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)
    )
    val shorthand = clean(
        Regex("""(?:^|;)\s*background\s*:\s*([^;}]*)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)
    )

    longColor?.let { v ->
        if (isTransparentKeyword(v)) return CssBackgroundColor(transparent = true)
        parseCssColor(v)?.let { return CssBackgroundColor(color = it) }
    }
    // background-image: url(...) 时不产生纯色，交给底图通道处理
    if (longImage != null && !isNoneKeyword(longImage)) return null
    if (longImage != null && isNoneKeyword(longImage)) {
        // image:none 且没写 color → 视作透明
        if (longColor == null && (shorthand == null || isTransparentKeyword(shorthand))) {
            return CssBackgroundColor(transparent = true)
        }
    }

    shorthand?.let { v ->
        if (isTransparentKeyword(v)) return CssBackgroundColor(transparent = true)
        if (isNoneKeyword(v)) return CssBackgroundColor(transparent = true)
        // url(...) 作底图时不取色（由 extractBubbleBackgroundImageUrl 负责）
        if (Regex("""url\s*\(""", RegexOption.IGNORE_CASE).containsMatchIn(v)) return null
        // 渐变：取第一个颜色停靠点近似主色
        if (Regex("""(linear|radial|conic|repeating)-gradient""", RegexOption.IGNORE_CASE).containsMatchIn(v)) {
            gradientPrimaryColor(v)?.let { return CssBackgroundColor(color = it) }
            return null
        }
        parseCssColor(v)?.let { return CssBackgroundColor(color = it) }
    }
    return null
}

/** 渐变里的第一个颜色停靠点（用于无纯色时的近似） */
private fun gradientPrimaryColor(value: String): Long? {
    val start = value.indexOf('(')
    if (start < 0) return null
    val inner = value.substring(start + 1).substringBeforeLast(')')
    // 按逗号切，跳过角度/位置等非颜色段，取第一个能解析成颜色的
    var depth = 0
    val segs = mutableListOf<String>()
    val cur = StringBuilder()
    for (ch in inner) {
        when (ch) {
            '(' -> { depth++; cur.append(ch) }
            ')' -> { depth--; cur.append(ch) }
            ',' -> if (depth == 0) { segs += cur.toString(); cur.clear() } else cur.append(ch)
            else -> cur.append(ch)
        }
    }
    if (cur.isNotEmpty()) segs += cur.toString()
    for (s in segs) {
        val t = s.trim()
        // 纯角度/百分比不是颜色
        if (Regex("""^[\d.]+(deg|grad|rad|turn|%)?$""", RegexOption.IGNORE_CASE).matches(t)) continue
        // 色标常带位置："#ffeedd 0%" / "rgb(0 0 0) 40%" / "#fff 12px"。
        // 位置必须剥掉再解析，否则整段解析失败、渐变主题取不到任何颜色。
        val bare = t.replace(
            Regex("""\s+[\d.]+(?:px|%|em|rem)?\s*$""", RegexOption.IGNORE_CASE),
            "",
        ).trim()
        parseCssColor(bare)?.let { return it }
        parseCssColor(t)?.let { return it }
    }
    return null
}

private fun isTransparentKeyword(v: String): Boolean =
    Regex("""^\s*(transparent|unset|initial|inherit)\s*$""", RegexOption.IGNORE_CASE).matches(v)

private fun isNoneKeyword(v: String): Boolean =
    Regex("""^\s*(none|unset|initial)\s*$""", RegexOption.IGNORE_CASE).matches(v)

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

/**
 * 剥掉没有规则体的 at 语句：`@import ...;` / `@charset ...;` / `@namespace ...;`。
 *
 * 必须做这一步：`CSS_RULE` 用 `([^{}]+)\{` 抓选择器，而 `@import` 后面没有花括号，
 * 于是它会被并进**下一条真正规则的选择器**里。实测 389/534 个主题用 @import 引远程
 * 字体，导致 `@import url(...);\n\n:root` 被当成一个选择器，`:root` 再也不是独立作用域，
 * 整块 CSS 变量读不到 —— 表现为 `var(--paper)` 原样留在声明里，气泡背景被丢弃。
 *
 * 只在规则解析前调用；`extractThemeFontImportUrl` 另行从原文取 @import 地址。
 */
internal fun stripBlocklessAtRules(css: String): String =
    BLOCKLESS_AT_RULE.replace(css) { m ->
        // 保留 @media/@supports 等有块的前缀（它们前面带 `{` 的由 CSS_RULE 处理），
        // 这里只清掉整条以分号结束的语句，用等长空白替换以免下标错位
        " ".repeat(m.value.length)
    }

/** `@import` / `@charset` / `@namespace`（不含大括号、以分号结束） */
private val BLOCKLESS_AT_RULE =
    Regex("""@(?:import|charset|namespace)\b[^;{}]*;""", RegexOption.IGNORE_CASE)

/** 逐条匹配 CSS 规则（不含嵌套花括号的规则体；@media 外层规则自然被跳过、内层规则正常匹配） */
internal val CSS_RULE = Regex("([^{}]+)\\{([^{}]*)\\}")

/**
 * 从 custom_css 中提取消息气泡圆角（px 近似为 dp）。
 * 优先取 .mes/.mes_block 上的 border-radius，其次 #chat；多值取最大；百分比忽略，0px（方角）照搬；
 * 结果 clamp 到应用气泡圆角滑条范围 0-28dp。找不到返回 null。
 */
fun extractBubbleCornerRadius(
    css: String?,
    extraVars: Map<String, String> = emptyMap(),
): Float? {
    if (css.isNullOrBlank()) return null
    // 用 CSS 引擎算层叠结果：主题常把圆角写成变量（实测 138 处 var() 引用）、
    // 或与其它规则争抢同一属性，靠正则逐条扫描会取到被覆盖的旧值。
    val radius = bubbleDeclarations(css, extraVars)["border-radius"] ?: return null
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
internal fun extractBubbleBorder(
    css: String?,
    extraVars: Map<String, String> = emptyMap(),
): BubbleBorder? {
    if (css.isNullOrBlank()) return null
    val decls = bubbleDeclarations(css, extraVars)
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
internal fun extractBubbleShadow(
    css: String?,
    extraVars: Map<String, String> = emptyMap(),
): BubbleShadow? {
    if (css.isNullOrBlank()) return null
    val decls = bubbleDeclarations(css, extraVars)
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
/**
 * 气泡圆角上限（dp）。
 *
 * 同时约束「主题导入时的提取结果」与「设置页滑条上限」，两处必须共用同一个值，
 * 否则主题圆角会被滑条静默夹小，看起来像圆角程度不对。
 */
const val MAX_BUBBLE_RADIUS_DP = 40f

/**
 * 气泡边框粗细上限（dp）。
 *
 * 主题里的边框实测多为 1-3px，少数"粗描边"风格会到 8-12px；
 * 上限给 12 既能完整表达主题，也不会让滑条失去调节精度。
 */
const val MAX_BUBBLE_BORDER_DP = 12f

/**
 * 气泡阴影范围上限（dp）。
 *
 * 由 `--shadowWidth` 与 `box-shadow` 的模糊半径共同决定，实测常见 2-24px。
 */
const val MAX_BUBBLE_SHADOW_DP = 24f

private const val MAX_RADIUS_DP = MAX_BUBBLE_RADIUS_DP

/**
 * 聊天背景所在元素的优先级。
 *
 * 实测这批主题把背景图挂在**各种**宿主上，远不止 body/#chat：
 * 「去海边」用 `.drawer-content`(侧栏) 与 `#sheld`(聊天主容器)，
 * 「独自青青」用 `#send_form`(输入框) 与 `#top-bar`(顶栏)。
 * 只认最初的 5 个选择器会让 362/534 个主题的背景图完全导不进来。
 *
 * 优先级数字越小越优先：越靠前的越接近"整个聊天区"的语义。
 */
private val BACKGROUND_SELECTORS = listOf(
    0 to Regex("""(^|[\s,>+~])#bg1(?![\w-])"""),
    1 to Regex("""(^|[\s,>+~])body(?![\w-])"""),
    2 to Regex("""(^|[\s,>+~])\.bg1(?![\w-])"""),
    // #sheld 是酒馆的聊天滚动主容器，等价于我们的"聊天背景"
    3 to Regex("""(^|[\s,>+~])#sheld(?![\w-])"""),
    4 to Regex("""(^|[\s,>+~])#chat(?![\w-])"""),
    5 to Regex("""(^|[\s,>+~])#main(?![\w-])"""),
    6 to Regex("""(^|[\s,>+~])#chat_container(?![\w-])"""),
    7 to Regex("""(^|[\s,>+~])\.chat(?![\w-])"""),
    // 「去海边」等主题把主视觉铺在侧栏容器上，这是它们在移动端最醒目的一块背景
    8 to Regex("""(^|[\s,>+~])\.drawer-content(?![\w-])"""),
)

/**
 * 仅供「聊天背景色」使用的宿主选择器：额外包含输入框与顶栏这类局部容器。
 *
 * 与背景图共用一份表会互相拖累：背景图要求宿主足够"大"（整块聊天区），
 * 而底色可以退而取输入框/顶栏的色作为整体基调的近似。
 */
private val CHAT_BACKGROUND_SELECTORS = BACKGROUND_SELECTORS + listOf(
    8 to Regex("""(^|[\s,>+~])#send_form(?![\w-])"""),
    9 to Regex("""(^|[\s,>+~])#top-bar(?![\w-])"""),
    10 to Regex("""(^|[\s,>+~])\.drawer-content(?![\w-])"""),
)

private val BACKGROUND_URL = Regex(
    """background(?:-image)?\s*:\s*[^;{}]*url\(\s*(['"]?)([^)'"]+)\1\s*\)""",
    RegexOption.IGNORE_CASE,
)

/** 背景里的渐变（linear-/radial-/conic-gradient），整条取出用于近似 */
private val BACKGROUND_GRADIENT = Regex(
    """background(?:-image)?\s*:\s*([^;{}]*?(?:linear|radial|conic)-gradient\([^;{}]*)\)""",
    RegexOption.IGNORE_CASE,
)

/**
 * 从 custom_css 提取聊天区背景色（纯色）。
 *
 * 为什么必须走 CSS：主题把底色写在 `.mes_block` / `#sheld` / `body` 的 `background`
 * 上是主流做法，而字段 `chat_tint_color` / `blur_tint_color` 常常为空或近乎透明。
 * 只读字段会让绝大多数主题的底色丢失，表现就是"只有纯色背景才可以生效"
 * ——实际只有恰好写了那两个字段的主题才生效。
 *
 * 只认 [CHAT_BACKGROUND_SELECTORS] 里的宿主，避免把气泡底色当成聊天底色。
 */
internal fun extractChatBackgroundColor(
    css: String?,
    extraVars: CssVariables = emptyMap(),
): Long? {
    if (css.isNullOrBlank()) return null
    val merged = mergeRulesBySelector(css, extraVars)
    var bestPriority = Int.MAX_VALUE
    var best: Long? = null
    for ((selector, body) in merged) {
        // 气泡自身的规则一律排除，否则气泡色会被当成聊天底色
        if (containsMessageBubble(selector)) continue
        val priority = CHAT_BACKGROUND_SELECTORS
            .firstOrNull { it.second.containsMatchIn(selector) }?.first ?: continue
        if (priority > bestPriority) continue
        val color = parseBackgroundColor(body)?.takeIf { !it.transparent }?.color ?: continue
        if (priority < bestPriority || best == null) {
            bestPriority = priority
            best = color
        }
    }
    return best
}

/**
 * 从 custom_css 提取聊天区背景渐变。
 *
 * Compose 侧无法还原任意 CSS 渐变，取渐变里的**主色**（第一个颜色停靠点）作为近似，
 * 比整条丢弃更接近作者观感 —— 这与气泡的渐变处理策略保持一致。
 *
 * @return 主色 ARGB，找不到返回 null。
 */
internal fun extractChatBackgroundGradientColor(
    css: String?,
    extraVars: CssVariables = emptyMap(),
): Long? {
    if (css.isNullOrBlank()) return null
    val merged = mergeRulesBySelector(css, extraVars)
    var bestPriority = Int.MAX_VALUE
    var best: Long? = null
    for ((selector, body) in merged) {
        if (containsMessageBubble(selector)) continue
        val priority = CHAT_BACKGROUND_SELECTORS
            .firstOrNull { it.second.containsMatchIn(selector) }?.first ?: continue
        if (priority > bestPriority) continue
        val gradient = BACKGROUND_GRADIENT.find(body)?.groupValues?.get(1) ?: continue
        val color = gradientPrimaryColor(gradient) ?: continue
        if (priority < bestPriority || best == null) {
            bestPriority = priority
            best = color
        }
    }
    return best
}

/**
 * 从 custom_css 中提取聊天背景图地址（http(s) URL 或 data URI）。
 * 只认 body/#bg1/.bg1/#chat/#main 上的 background/background-image，避免误抓头像框等装饰图。
 * 找不到返回 null。
 */
fun extractBackgroundImageUrl(css: String?): String? {
    if (css.isNullOrBlank()) return null
    // 必须先剔除无块 at-rule：CSS_RULE 匹配不了 `@import url(...);`，
    // 它会被并进紧随其后的那条规则的选择器里，导致背景图所在的
    // `body { background: url(...) }` 选择器被污染、优先级判定失效。
    // 534 个主题里 389 个（73%）带 @import，这是"主题背景图导不进来"的成因。
    var bestPriority = Int.MAX_VALUE
    var bestUrl: String? = null
    for (m in CSS_RULE.findAll(stripBlocklessAtRules(stripCssComments(css)))) {
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
fun extractBubbleBackgroundImageUrl(
    css: String?,
    forUser: Boolean,
    extraVars: CssVariables = emptyMap(),
): String? {
    if (css.isNullOrBlank()) return null
    val merged = mergeRulesBySelector(css, extraVars)
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
fun extractBubbleBackgroundSize(
    css: String?,
    extraVars: CssVariables = emptyMap(),
): String? {
    if (css.isNullOrBlank()) return null
    for ((selector, body) in mergeRulesBySelector(css, extraVars)) {
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
private fun mergeRulesBySelector(
    css: String,
    extraVars: CssVariables = emptyMap(),
): List<Pair<String, String>> {
    val clean = stripBlocklessAtRules(stripCssComments(css))
    // 变量必须先展开：主题普遍把颜色写成 var(--paper) 这类变量，
    // 不展开会让 parseCssColor 收到字面量 "var(--paper)" 直接失败，
    // 表现为"主题写了背景色但导不进来"。
    //
    // extraVars 是酒馆官方注入的变量表（--SmartThemeBodyColor 等）。
    // 主题自身定义优先：`extraVars + ownVars` 里 ownVars 在右、覆盖前者。
    val vars = extraVars + parseCssVariables(clean)
    val order = LinkedHashMap<String, StringBuilder>()
    for (m in CSS_RULE.findAll(clean)) {
        val selector = m.groupValues[1].trim().replace(Regex("""\s+"""), " ")
        if (selector.isEmpty()) continue
        val acc = order.getOrPut(selector) { StringBuilder() }
        val body = splitDeclarations(m.groupValues[2]).joinToString(";") { decl ->
            val idx = decl.indexOf(':')
            if (idx <= 0) decl else {
                val prop = decl.substring(0, idx).trim()
                val value = expandVariables(decl.substring(idx + 1).trim(), vars)
                "$prop: $value"
            }
        }
        acc.append(body).append(';')
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
    // 只认「被样式的那个元素」是气泡本身，而不是祖先链里出现过 .mes。
    // 反例：`.mes[is_user='true'] .mesAvatarWrapper` 的目标是头像/名牌容器，
    // 它整体铺一张 250px 图，命中铺满判定后会被当成气泡底图导入 ——
    // 结果是气泡被糊上一张名牌图，主题真正的 background-color 反而看不出来。
    return targetsBubbleItself(bare)
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

/**
 * 选择器链里「最后一个复合选择器」是否为气泡本身。
 *
 * 关键：必须看**被样式的目标元素**（选择器最后一段），而不是祖先链里出现过
 * `.mes`。`.mes[is_user] .mesAvatarWrapper` 的目标是名牌容器，
 * `.mes_block .mes_text` 的目标是正文，两者都不是气泡。
 */
private fun targetsBubbleItself(bare: String): Boolean {
    // 逗号分隔的每个选择器分别判定，任一命中即算
    return bare.split(',').any { part ->
        val last = part.trim()
            .substringBefore('>').substringBefore('+').substringBefore('~')
            .trim()
            .split(Regex("""\s+"""))
            .lastOrNull()
            .orEmpty()
        // 末段必须是纯 .mes / .mes_block（可带属性选择器，已被剥离）
        last == ".mes" || last == ".mes_block"
    }
}

/** 酒馆用 is_user 属性区分消息归属 */
private val USER_SIDE_SELECTOR = Regex("""is_user\s*=\s*['"]?true""", RegexOption.IGNORE_CASE)
private val BOT_SIDE_SELECTOR = Regex("""is_user\s*=\s*['"]?false""", RegexOption.IGNORE_CASE)

/** @font-face 块（font-family + src url + format） */
private val FONT_FACE = Regex("""@font-face\s*\{([^}]*)\}""", RegexOption.IGNORE_CASE)

/**
 * `@import url("...")` 引入的远程字体样式表。
 *
 * 实测 534 个带 custom_css 的主题里有 389 个（73%）用 @import 引第三方字体
 * （最常见是 zeoseven 的思源/京华系列）。旧实现只看 @font-face，这些主题的字体完全没有入口。
 */
private val FONT_IMPORT = Regex(
    """@import\s+(?:url\(\s*)?['"]?(https?://[^'")\s;]+)['"]?\s*\)?\s*;""",
    RegexOption.IGNORE_CASE,
)

/** 主题给正文/消息指定的字体族，用于决定"这个主题到底想用什么字体" */
private val BODY_FONT_FAMILY = Regex(
    """(?:^|[,}\s])(?:body|html|\.mes\b[^{},]*(?:\s+\S+)?)[^{},]*\{[^}]*?font-family\s*:\s*([^;}]+)""",
    RegexOption.IGNORE_CASE,
)

/** 提取 font-family 列表里的首选族名（跳过 sans-serif 这类通用族与 emoji 字体） */
private val GENERIC_FONT_FAMILIES = setOf(
    "serif", "sans-serif", "monospace", "cursive", "fantasy",
    "system-ui", "ui-serif", "ui-sans-serif", "ui-monospace", "ui-rounded",
    "inherit", "initial", "unset", "revert",
)

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
    val clean = stripCssComments(css)

    // 主题声明的字体族（body/.mes 上的 font-family），用于给 @import 场景定名
    val declaredFamily = BODY_FONT_FAMILY.findAll(clean)
        .flatMap { it.groupValues[1].split(',') }
        .map { it.trim().trim('"', '\'').trim() }
        .firstOrNull { it.isNotEmpty() && it.lowercase() !in GENERIC_FONT_FAMILIES }

    // 1) 优先本地 @font-face：作者自带 ttf/otf，能直接下载加载
    for (m in FONT_FACE.findAll(clean)) {
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

    // 2) 退到 @import 的远程样式表。
    //    这类表几乎全是 woff2 分片（Android 原生 Typeface 无法加载），所以这里
    //    不改 url —— 只把"主题想要哪个字体"记录下来，交给调用方判断能否取得 ttf/otf。
    FONT_IMPORT.find(clean)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { importUrl ->
        return ThemeFont(family = declaredFamily, url = importUrl, isRemoteCss = true)
    }
    return null
}

data class ThemeFont(
    val family: String?,
    val url: String,
    /**
     * 该 url 是否是「字体样式表」而不是字体文件本身。
     *
     * 官方主题里 73% 用 `@import url(https://fontsapi.zeoseven.com/N/main/result.css)`
     * 引第三方字体。那种 CSS 里定义的是按 unicode-range 切片的 woff2 分片，
     * Android 的 `Typeface.createFromFile` 不支持 woff2、项目也没有 woff2 解码器，
     * 因此这条路径**只在解析出 ttf/otf 时**才可用；否则应当跳过而不是下载一堆分片。
     */
    val isRemoteCss: Boolean = false,
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

    /** 搜索按钮图标（官方 #rm_print_characters_block 等列表搜索入口） */
    val searchImageUrl: String? = null,
    val searchTint: Long? = null,
    val searchScale: Float? = null,

    /** 设置/参数按钮图标（官方 #rightNavDrawerIcon / #leftNavDrawerIcon） */
    val settingsImageUrl: String? = null,
    val settingsTint: Long? = null,
    val settingsScale: Float? = null,

    /** 模型选择图标（官方模型下拉 #model_*_select / .generic_model_select 一带） */
    val modelImageUrl: String? = null,
    val modelTint: Long? = null,
    val modelScale: Float? = null,

    /** 思考等级图标（官方 #reasoning_effort / 推理强度入口） */
    val reasoningImageUrl: String? = null,
    val reasoningTint: Long? = null,
    val reasoningScale: Float? = null,

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
            stopImageUrl == null && searchImageUrl == null && settingsImageUrl == null &&
            modelImageUrl == null && reasoningImageUrl == null && avatarFrameUrl == null &&
            userAvatarFrameUrl == null && botAvatarFrameUrl == null &&
            !hideSend && !hideOptions && !hideExtensions &&
            sendTint == null && optionsTint == null && extensionsTint == null && stopTint == null &&
            searchTint == null && settingsTint == null && modelTint == null &&
            reasoningTint == null

    /** 取指定侧的头像框，带回退 */
    fun avatarFrame(forUser: Boolean): String? =
        (if (forUser) userAvatarFrameUrl else botAvatarFrameUrl) ?: avatarFrameUrl

    /**
     * 主题提供的「任一」图标图，作为缺失槽位的共享素材。
     *
     * 实测主题集里只有极少数会给搜索/思考等级单独换图 —— 作者通常只挑了发送、
     * 菜单等显眼按钮。此时若目标按钮回退到内置矢量图标，整套皮肤会显得割裂：
     * 一半是主题画风、一半是默认风格。因此这里挑一张主题已经用过的图复用，
     * 让风格统一。
     *
     * 优先级按「按钮显眼程度」排：发送 > 菜单 > 扩展 > 停止 > 设置 > 模型 >
     * 搜索 > 思考等级。尺寸与颜色也一并复用，保持同批素材观感一致。
     */
    val anyImageUrl: String?
        get() = sendImageUrl ?: optionsImageUrl ?: extensionsImageUrl ?: stopImageUrl
            ?: settingsImageUrl ?: modelImageUrl ?: searchImageUrl ?: reasoningImageUrl

    /** 与 [anyImageUrl] 配套的颜色，取自同一张图的来源槽位 */
    val anyTint: Long?
        get() = when {
            sendImageUrl != null -> sendTint
            optionsImageUrl != null -> optionsTint
            extensionsImageUrl != null -> extensionsTint
            stopImageUrl != null -> stopTint
            settingsImageUrl != null -> settingsTint
            modelImageUrl != null -> modelTint
            searchImageUrl != null -> searchTint
            reasoningImageUrl != null -> reasoningTint
            else -> null
        }

    /** 与 [anyImageUrl] 配套的缩放，取自同一张图的来源槽位 */
    val anyScale: Float?
        get() = when {
            sendImageUrl != null -> sendScale
            optionsImageUrl != null -> optionsScale
            extensionsImageUrl != null -> extensionsScale
            stopImageUrl != null -> stopScale
            settingsImageUrl != null -> settingsScale
            modelImageUrl != null -> modelScale
            searchImageUrl != null -> searchScale
            reasoningImageUrl != null -> reasoningScale
            else -> null
        }

    /** 搜索按钮最终使用的图（缺失时复用主题其它图标） */
    val effectiveSearchImageUrl: String? get() = searchImageUrl ?: anyImageUrl
    val effectiveSearchTint: Long? get() = if (searchImageUrl != null) searchTint else anyTint
    val effectiveSearchScale: Float? get() = if (searchImageUrl != null) searchScale else anyScale

    /** 设置按钮最终使用的图 */
    val effectiveSettingsImageUrl: String? get() = settingsImageUrl ?: anyImageUrl
    val effectiveSettingsTint: Long? get() = if (settingsImageUrl != null) settingsTint else anyTint
    val effectiveSettingsScale: Float? get() = if (settingsImageUrl != null) settingsScale else anyScale

    /** 模型选择按钮最终使用的图 */
    val effectiveModelImageUrl: String? get() = modelImageUrl ?: anyImageUrl
    val effectiveModelTint: Long? get() = if (modelImageUrl != null) modelTint else anyTint
    val effectiveModelScale: Float? get() = if (modelImageUrl != null) modelScale else anyScale

    /** 思考等级按钮最终使用的图 */
    val effectiveReasoningImageUrl: String? get() = reasoningImageUrl ?: anyImageUrl
    val effectiveReasoningTint: Long? get() = if (reasoningImageUrl != null) reasoningTint else anyTint
    val effectiveReasoningScale: Float? get() = if (reasoningImageUrl != null) reasoningScale else anyScale
}

/** 图标宿主元素 ID → 主题里的选择器（对齐官方 index.html） */
private val ICON_TARGETS = listOf(
    "send" to "#send_but",
    "options" to "#options_button",
    "extensions" to "#extensionsMenuButton",
    "stop" to "#mes_stop",
)

/**
 * 各宿主在主题里的等价选择器集合。
 *
 * 官方界面里同一个功能区往往有多个 id（左右抽屉、字符卡搜索、各类设置面板），
 * 主题作者会挑其中一个来写样式。只认单一 id 会漏掉大部分主题，
 * 因此这里给出候选集合，任一命中即算。
 */
private val ICON_ALIASES: Map<String, List<String>> = mapOf(
    "send" to listOf("#send_but", "#send_form", "#rightSendForm"),
    "options" to listOf("#options_button", "#leftSendForm"),
    "extensions" to listOf("#extensionsMenuButton", "#extensionsMenu"),
    "stop" to listOf("#mes_stop", "#mes_pause", "#stscript_stop"),
    // 搜索：实测命中的选择器（按主题里出现频次排序）
    "search" to listOf(
        "#search_field",
        "#extensionTopBarSearchInput",
        "#settingsSearch",
        "#character_search_bar",
        "#form_character_search_form",
        "#persona_search_bar",
        "#rm_print_characters_block",
        "#search",
    ),
    // 设置：实测换图都写在容器内的 .drawer-icon 上
    "settings" to listOf(
        "#top-settings-holder .drawer-icon",
        "#user-settings-block .drawer-icon",
        "#rightNavDrawerIcon .drawer-icon",
        "#leftNavDrawerIcon .drawer-icon",
        "#WIDrawerIcon .drawer-icon",
        "#table_drawer_icon .drawer-icon",
        "#sys-settings-button .drawer-icon",
        "#extensions-settings-button .drawer-icon",
        "#user-settings-button .drawer-icon",
        "#top-settings-holder",
        "#user-settings-block",
        "#rightNavDrawerIcon",
        "#leftNavDrawerIcon",
        "#settings",
    ),
    // 模型选择：官方聊天顶栏的 API/模型配置入口（#ai-config-button 是实测主力）
    "model" to listOf(
        "#ai-config-button .drawer-icon",
        "#ai-config-button",
        "#API-status-top .drawer-icon",
        "#API-status-top",
        "#custom_model_id",
        "#generic_model_select",
        "#settings_preset_openai",
        "#rm_api_block",
    ),
    // 思考等级：官方消息上的推理按钮
    "reasoning" to listOf(
        "#reasoning_effort",
        "#mes_button_reasoning",
        "#mes_reasoning_header",
        "#mes_reasoning_arrow",
        "#openai_reasoning_effort",
    ),
)

/**
 * 从 custom_css 提取图标主题。
 *
 * 每个按钮独立解析：先按选择器定位规则，再依次读取 background-image（换图）、
 * color（换色）、font-size（换尺寸）、display:none（隐藏）。
 * `background-image: none` 表示作者清掉了默认图标，此时不当作图片，但保留其它属性。
 */
fun extractThemeIconSet(
    css: String?,
    extraVars: CssVariables = emptyMap(),
): ThemeIconSet {
    if (css.isNullOrBlank()) return ThemeIconSet()
    val merged = mergeRulesBySelector(css, extraVars)

    /** 收集所有命中该 id 的规则体（含伪元素写法） */
    /**
     * 规则选择器的「目标元素」（最后一段复合选择器）。
     *
     * 主题写 `#top-settings-holder .drawer-icon` 时，真正被换图的是末尾的
     * `.drawer-icon`；而 `#top-settings-holder` 只是作用域。按整串做子串匹配
     * 会把作用域当作目标，导致抓错规则。这里统一取末段。
     */
    fun targetOf(sel: String): String {
        val head = sel.substringBefore("::")
        val bare = head.replace(Regex("""\[[^\]]*\]"""), "")
        // 先按组合器切掉兄弟/子代后段，再取空格分隔的最后一段
        val last = bare.split(',')
            .flatMap { it.split(Regex("""\s*[>+~]\s*""")) }
            .lastOrNull()
            .orEmpty()
            .trim()
            .split(Regex("""\s+"""))
            .lastOrNull()
            .orEmpty()
        return last
    }

    /** 规则目标元素是否就是该选择器指定的元素（支持 `#id .cls` 形式） */
    fun bodiesFor(id: String): List<String> {
        val want = id.trim().split(Regex("""\s+"""))      // 允许 "#id .cls"
        val wantId = want.firstOrNull().orEmpty()
        val wantCls = want.getOrNull(1)?.trim().orEmpty()
        return merged
            .filter { (sel, _) ->
                val full = sel.replace(Regex("""\[[^\]]*\]"""), "")
                // 目标元素必须精确等于期望的「末段」（或末段以它起头）
                val target = targetOf(sel)
                val targetOk = when {
                    wantCls.isNotEmpty() -> target == wantCls
                    else -> target == wantId
                }
                if (!targetOk) return@filter false
                // 若指定了容器，作用域里必须出现该 id
                if (wantCls.isNotEmpty() && !full.contains(wantId)) return@filter false
                true
            }
            .map { it.second }
    }

    /** 候选选择器任一命中即收集；按候选顺序优先，先命中的排前面 */
    fun bodiesForAny(key: String): List<String> {
        val aliases = ICON_ALIASES[key].orEmpty()
        return aliases.flatMap { bodiesFor(it) }
    }

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
    // 搜索/设置/模型/思考等级：主题里没有统一 id，按候选集合命中
    val searchB = bodiesForAny("search")
    val settingsB = bodiesForAny("settings")
    val modelB = bodiesForAny("model")
    val reasoningB = bodiesForAny("reasoning")

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
        searchImageUrl = imageOf(searchB),
        searchTint = colorOf(searchB),
        searchScale = scaleOf(searchB),
        settingsImageUrl = imageOf(settingsB),
        settingsTint = colorOf(settingsB),
        settingsScale = scaleOf(settingsB),
        modelImageUrl = imageOf(modelB),
        modelTint = colorOf(modelB),
        modelScale = scaleOf(modelB),
        reasoningImageUrl = imageOf(reasoningB),
        reasoningTint = colorOf(reasoningB),
        reasoningScale = scaleOf(reasoningB),
        userAvatarFrameUrl = userFrame,
        botAvatarFrameUrl = botFrame,
        avatarFrameUrl = anyFrame,
        avatarFrameScale = frameScale,
    )
}

// ── 气泡内层元素样式（代码框 / 引用块 / 高亮 / 斜体 / 思维链） ──

/**
 * 从主题 CSS 提取气泡**内层**元素样式。
 *
 * 官方 DOM 里消息正文是 `.mes_text`，其子元素拿到主题样式；主题也常直接写 `.mes blockquote`。
 * 这里对每类元素按「选择器是否命中该类元素」挑出对应声明，并用与浏览器一致的层叠取值。
 *
 * 只取真正会被渲染的几何/颜色属性；`!important` 由 [parseCssRules] 归一化后不影响取值。
 */
fun extractThemeMarkdownStyle(css: String?): ThemeMarkdownStyle {
    if (css.isNullOrBlank()) return ThemeMarkdownStyle()
    val rules = parseCssRules(css)
    if (rules.isEmpty()) return ThemeMarkdownStyle()

    fun declsFor(vararg tags: String): Map<String, String> {
        val wanted = tags.toSet()
        return cascadeDeclarations(rules) { selector ->
            // 去掉伪元素与伪类后，判断最后一段是不是目标标签
            val bare = selector.substringBefore("::").substringBefore(":")
                .replace(Regex("""\[[^\]]*\]"""), "")
                .trim()
            if (bare.isEmpty()) return@cascadeDeclarations false
            bare.split(Regex("""[\s>+~]+"""))
                .lastOrNull()
                ?.trim()
                ?.lowercase() in wanted
        }
    }

    val inlineCode = declsFor("code")
    val codeBlock = declsFor("pre")
    val quote = declsFor("blockquote")
    val mark = declsFor("mark")
    val emphasis = declsFor("em", "i")
    val reasoning = declsFor("reasoning", ".mes_reasoning")

    fun colorOf(d: Map<String, String>): Color? =
        cssColorFrom(d, "color")?.toComposeColor()

    fun bgOf(d: Map<String, String>): Color? {
        val v = d["background-color"] ?: d["background"] ?: return null
        if (isTransparentKeyword(v.trim())) return null
        if (Regex("""url\s*\(""", RegexOption.IGNORE_CASE).containsMatchIn(v)) return null
        val raw = if (Regex("""gradient""", RegexOption.IGNORE_CASE).containsMatchIn(v)) {
            gradientPrimaryColor(v)?.let { argbToCss(it) } ?: return null
        } else v
        return parseCssColor(raw)?.toComposeColor()
    }

    fun radiusOf(d: Map<String, String>): Dp? =
        d["border-radius"]?.let { cssLengthToDp(it.split(Regex("""[\s/]+""")).firstOrNull()) }

    /** 边框：优先读简写，其次读单边（主题大量用 border-inline-start 画引用块竖线） */
    fun borderOf(d: Map<String, String>, side: String = "border"): Pair<Color?, Dp?> {
        val v = d[side] ?: return null to null
        val w = Regex("""(\d+(?:\.\d+)?)px""").find(v)?.groupValues?.get(1)?.toFloatOrNull()?.dp
        val c = Regex("""(#[0-9a-fA-F]{3,8}|rgba?\([^)]*\)|hsla?\([^)]*\))""", RegexOption.IGNORE_CASE)
            .find(v)?.value?.let { parseCssColor(it) }?.toComposeColor()
        return c to w
    }

    val quoteBorder = borderOf(quote)
    val quoteStartBorder = borderOf(quote, "border-inline-start")
    val codeBorder = borderOf(codeBlock)

    return ThemeMarkdownStyle(
        inlineCodeBackgroundArgb = bgOf(inlineCode)?.toArgbOrNull(),
        inlineCodeColorArgb = colorOf(inlineCode)?.toArgbOrNull(),
        inlineCodeCornerRadiusDp = radiusOf(inlineCode)?.value,

        codeBlockBackgroundArgb = bgOf(codeBlock)?.toArgbOrNull(),
        codeBlockColorArgb = colorOf(codeBlock)?.toArgbOrNull(),
        codeBlockCornerRadiusDp = radiusOf(codeBlock)?.value,
        codeBlockBorderColorArgb = codeBorder.first?.toArgbOrNull(),
        codeBlockBorderWidthDp = codeBorder.second?.value,

        quoteBackgroundArgb = bgOf(quote)?.toArgbOrNull(),
        quoteColorArgb = colorOf(quote)?.toArgbOrNull(),
        quoteCornerRadiusDp = radiusOf(quote)?.value,
        quoteBorderColorArgb = (quoteBorder.first ?: quoteStartBorder.first)?.toArgbOrNull(),
        quoteBorderWidthDp = (quoteBorder.second ?: quoteStartBorder.second)?.value,
        quoteAccentWidthDp = (quoteStartBorder.second ?: quoteBorder.second)?.value,

        markBackgroundArgb = bgOf(mark)?.toArgbOrNull(),
        markColorArgb = colorOf(mark)?.toArgbOrNull(),
        markCornerRadiusDp = radiusOf(mark)?.value,

        emphasisBackgroundArgb = bgOf(emphasis)?.toArgbOrNull(),
        emphasisColorArgb = colorOf(emphasis)?.toArgbOrNull(),
        emphasisCornerRadiusDp = radiusOf(emphasis)?.value,

        reasoningBackgroundArgb = bgOf(reasoning)?.toArgbOrNull(),
        reasoningColorArgb = colorOf(reasoning)?.toArgbOrNull(),
        reasoningCornerRadiusDp = radiusOf(reasoning)?.value,
    )
}

/** ARGB Long → CSS 颜色字符串（供复用同一套颜色解析） */
private fun argbToCss(argb: Long): String {
    val a = (argb shr 24) and 0xFF
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "rgba($r, $g, $b, ${"%.3f".format(a / 255.0)})"
}
