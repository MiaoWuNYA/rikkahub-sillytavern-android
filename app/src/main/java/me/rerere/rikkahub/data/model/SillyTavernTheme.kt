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
 * custom_css 等无法在 Compose 渲染的字段仅保留原值，不参与映射。
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

    // #hex（# 可省略）
    if (!trimmed.contains('(')) {
        val hex = trimmed.removePrefix("#")
        if (hex.length in 3..8 && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return when (hex.length) {
                3 -> {
                    val r = hex[0].hexToByte(); val g = hex[1].hexToByte(); val b = hex[2].hexToByte()
                    argb(255, r * 0x11, g * 0x11, b * 0x11)
                }

                4 -> {
                    val a = hex[0].hexToByte(); val r = hex[1].hexToByte()
                    val g = hex[2].hexToByte(); val b = hex[3].hexToByte()
                    argb(a * 0x11, r * 0x11, g * 0x11, b * 0x11)
                }

                6 -> argb(255, hex.substring(0, 2).hexToInt(), hex.substring(2, 4).hexToInt(), hex.substring(4, 6).hexToInt())

                8 -> argb(
                    hex.substring(0, 2).hexToInt(),
                    hex.substring(2, 4).hexToInt(),
                    hex.substring(4, 6).hexToInt(),
                    hex.substring(6, 8).hexToInt()
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

/**
 * 把主题映射结果应用到现有 DisplaySetting 上（partial 语义）：
 * 只覆盖解析成功的字段，解析失败/缺失的字段保留 base 原值，其余显示设置不受影响。
 *
 * 映射关系：
 * - main_text_color → globalTextColor
 * - chat_tint_color → chatBackgroundColor
 * - user_mes_blur_tint_color → userBubbleColor
 * - bot_mes_blur_tint_color → assistantBubbleColor
 * - quote_text_color → quoteColor（hex 字符串）
 * - italics_text_color → italicsColor（hex 字符串）
 * - font_scale → fontSizeRatio（clamp 到应用支持的 0.5-2.0）
 */
fun SillyTavernTheme.applyTo(base: DisplaySetting): DisplaySetting {
    return base.copy(
        globalTextColor = parseCssColor(mainTextColor) ?: base.globalTextColor,
        chatBackgroundColor = parseCssColor(chatTintColor) ?: base.chatBackgroundColor,
        userBubbleColor = parseCssColor(userMesBlurTintColor) ?: base.userBubbleColor,
        assistantBubbleColor = parseCssColor(botMesBlurTintColor) ?: base.assistantBubbleColor,
        quoteColor = parseCssColor(quoteTextColor)?.toColorHexString()?.takeIf { base.quoteColor != it }
            ?: base.quoteColor,
        italicsColor = parseCssColor(italicsTextColor)?.toColorHexString()?.takeIf { base.italicsColor != it }
            ?: base.italicsColor,
        fontSizeRatio = fontScale?.takeIf { it > 0.0 }?.toFloat()?.coerceIn(0.5f, 2.0f)
            ?: base.fontSizeRatio,
    )
}
