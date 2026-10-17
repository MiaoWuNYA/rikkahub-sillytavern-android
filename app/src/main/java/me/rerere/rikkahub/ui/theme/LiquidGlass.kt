package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.DisplayMaterialMode

val DisplayMaterialMode.hasMaterialBorder: Boolean
    get() = this == DisplayMaterialMode.TRANSLUCENT || this == DisplayMaterialMode.GLASS

val DisplayMaterialMode.isGlass: Boolean
    get() = this == DisplayMaterialMode.GLASS

val DisplayMaterialMode.isTranslucent: Boolean
    get() = this == DisplayMaterialMode.TRANSLUCENT

@Composable
fun materialModeBorderStroke(width: androidx.compose.ui.unit.Dp = 1.dp): BorderStroke? =
    if (LocalMaterialMode.current.hasMaterialBorder) {
        BorderStroke(width, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
    } else {
        null
    }

// 玻璃高光：顶部/底部边缘光带 + 一条横向镜面反光
fun Modifier.liquidGlassHighlight(intensity: Float = 1f): Modifier = drawWithCache {
    val topDepth = 6.dp.toPx()
    val bottomDepth = 4.dp.toPx()
    val specularTop = 1.dp.toPx()
    val specularHeight = 1.dp.toPx()
    val topBrush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.White.copy(alpha = 0.22f * intensity),
            0.5f to Color.White.copy(alpha = 0.08f * intensity),
            1f to Color.Transparent,
        ),
        endY = topDepth,
    )
    val bottomBrush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            0.5f to Color.White.copy(alpha = 0.05f * intensity),
            1f to Color.White.copy(alpha = 0.12f * intensity),
        ),
        startY = (size.height - bottomDepth).coerceAtLeast(0f),
        endY = size.height,
    )
    val specularBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            0.10f to Color.White.copy(alpha = 0.14f * intensity),
            0.28f to Color.White.copy(alpha = 0.42f * intensity),
            0.52f to Color.White.copy(alpha = 0.24f * intensity),
            0.78f to Color.White.copy(alpha = 0.08f * intensity),
            1f to Color.Transparent,
        ),
        start = Offset.Zero,
        end = Offset(size.width, 0f),
    )
    onDrawBehind {
        drawRect(brush = topBrush, size = Size(size.width, minOf(size.height, topDepth)))
        drawRect(
            brush = bottomBrush,
            topLeft = Offset(0f, (size.height - bottomDepth).coerceAtLeast(0f)),
            size = Size(size.width, minOf(size.height, bottomDepth)),
        )
        drawRect(
            brush = specularBrush,
            topLeft = Offset(0f, specularTop),
            size = Size(size.width, minOf(specularHeight, (size.height - specularTop).coerceAtLeast(0f))),
        )
    }
}

// 玻璃底色：以内容色为中心的径向渐变，边缘更透
fun Modifier.liquidGlassFill(tint: Color, intensity: Float = 1f): Modifier = drawWithCache {
    val center = Offset(size.width / 2f, size.height / 2f)
    val fill = Brush.radialGradient(
        colorStops = arrayOf(
            0f to tint.copy(alpha = 0.82f * intensity),
            0.55f to tint.copy(alpha = 0.80f * intensity),
            0.82f to tint.copy(alpha = 0.56f * intensity),
            1f to tint.copy(alpha = 0.40f * intensity),
        ),
        center = center,
        radius = center.getDistance(),
    )
    onDrawBehind { drawRect(fill) }
}
