package me.rerere.ai.ui

import kotlinx.serialization.Serializable

@Serializable
data class ImageGenerationItem(
    val data: String,
    val mimeType: String,
    val partial: Boolean = false,
    val partialImageIndex: Int? = null,
)

@Serializable
enum class ImageGenSize(val value: String) {
    AUTO("auto"),
    SQUARE_1024("1024x1024"),
    LANDSCAPE_1536("1536x1024"),
    PORTRAIT_1536("1024x1536"),
    SQUARE_256("256x256"),
    SQUARE_512("512x512"),
    LANDSCAPE_1792("1792x1024"),
    PORTRAIT_1792("1024x1792"),
    // 4K 档。取值用 4096x4096 而不是字面量 "4k"——认尺寸参数的站点认的是 WxH 格式。
    SQUARE_4K("4096x4096"),
}
