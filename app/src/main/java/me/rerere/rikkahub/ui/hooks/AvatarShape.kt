package me.rerere.rikkahub.ui.hooks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Shape
import kotlin.math.roundToInt

@Composable
fun rememberAvatarShape(loading: Boolean): Shape {
    val infiniteTransition = rememberInfiniteTransition()
    val spin by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 3000,
                easing = LinearEasing
            ),
        )
    )
    // 形状保持花朵、只让旋转平滑收敛：此前加载结束会直接切成圆形，看起来是突变
    val rotation by animateFloatAsState(
        targetValue = if (loading) spin else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "avatarShapeRotation",
    )
    return MaterialShapes.Cookie6Sided.toShape(rotation.roundToInt())
}
