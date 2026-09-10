/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SweepGradient
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * 流动光球 (ChatGPT 独立语音模式风格)
 *
 * - 纯色深色背景上, 一个发光的、流动感的圆形光斑
 * - 用 sweepGradient (天蓝色 ↔ 白色) 持续旋转/偏移, 制造"流动"观感
 * - 可选 blur 效果 (Android 12+), 低版本降级为不加 blur
 * - 说话/聆听时球体有呼吸缩放, Processing 时流动速度变快
 * - 不再用正弦波浪线叠加
 */
@Composable
fun VoiceOrb(
    modifier: Modifier = Modifier,
    amplitudes: List<Float> = emptyList(),
    status: VoiceCallStatus = VoiceCallStatus.Idle,
    baseColor: Color = Color(0xFF4FC3F7), // 天蓝色
    accentColor: Color = Color.White,
    size: Dp = 240.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voice_orb")

    // 流动速度: Processing 时更快, 营造"在思考"的感觉
    val rotationDurationMs = when (status) {
        VoiceCallStatus.Processing -> 4000
        VoiceCallStatus.Speaking -> 6000
        VoiceCallStatus.Listening -> 8000
        else -> 12000
    }
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(rotationDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // 呼吸缩放
    val breatheDurationMs = when (status) {
        VoiceCallStatus.Processing -> 1200
        VoiceCallStatus.Speaking -> 1800
        VoiceCallStatus.Listening -> 2400
        else -> 4000
    }
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(breatheDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // 第二层偏移动画, 让渐变停止点位置持续缓慢变化, 制造"流动"而非简单旋转
    val shift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(rotationDurationMs * 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shift"
    )

    // 振幅 -> 强度
    val currentAmplitude = if (amplitudes.isNotEmpty()) {
        amplitudes.takeLast(4).average().toFloat()
    } else {
        0f
    }
    val intensity = when (status) {
        VoiceCallStatus.Listening -> (currentAmplitude * 0.8f + 0.15f).coerceIn(0.15f, 0.8f)
        VoiceCallStatus.Speaking -> 0.5f + (currentAmplitude * 0.3f)
        VoiceCallStatus.Processing -> 0.25f
        VoiceCallStatus.Error -> 0.1f
        VoiceCallStatus.Idle -> 0.08f
    }

    val scale = breathe + intensity * 0.3f

    // 不再用 Modifier.blur 把整个 Canvas 糊掉 —— 那是球发虚难看的元凶.
    // 发光感由外层多层半透明圆自然实现, 球体本体保持清晰锐利.
    Canvas(
        modifier = modifier.size(size)
    ) {
        val canvasSize = this.size.minDimension
        val center = Offset(canvasSize / 2, canvasSize / 2)
        val baseRadius = canvasSize / 2 * 0.65f

        // 外层光晕 (多层半透明圆, 制造发光感, 替代原来的整体 blur)
        for (i in 5 downTo 1) {
            val layerRadius = baseRadius * scale * (1f + i * 0.22f)
            val alpha = (0.05f / i) * (1f + intensity)
            drawCircle(
                color = baseColor.copy(alpha = alpha.coerceAtMost(0.25f)),
                radius = layerRadius,
                center = center
            )
        }

        // 主球体: 旋转的 sweepGradient
        val mainRadius = baseRadius * scale
        rotate(degrees = rotation, pivot = center) {
            // sweepGradient 的颜色数组, shift 参数让停止点位置缓慢变化, 制造流动感
            val colorStops = arrayOf(
                0.0f to accentColor.copy(alpha = 0.9f),
                (0.2f + shift * 0.3f) % 1f to baseColor.copy(alpha = 0.85f),
                (0.5f + shift * 0.2f) % 1f to accentColor.copy(alpha = 0.7f),
                (0.8f + shift * 0.25f) % 1f to baseColor.copy(alpha = 0.8f),
                1.0f to accentColor.copy(alpha = 0.9f)
            ).sortedBy { it.first }

            drawCircle(
                brush = Brush.sweepGradient(
                    colors = colorStops.map { it.second },
                    center = center
                ),
                radius = mainRadius,
                center = center
            )
        }

        // 中心高亮 (径向渐变, 白色 -> 透明, 制造"光斑"核心)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.6f * (0.5f + intensity)),
                    accentColor.copy(alpha = 0.2f),
                    Color.Transparent
                ),
                center = center,
                radius = mainRadius * 0.6f
            ),
            radius = mainRadius * 0.6f,
            center = center
        )

        // 外圈细环 (有强度时显示, 增强动感)
        if (intensity > 0.15f) {
            val ringCount = 2
            for (i in 0 until ringCount) {
                val phase = ((rotation / 360f + i.toFloat() / ringCount) % 1f)
                val ringRadius = baseRadius * (1.15f + phase * 0.5f)
                val ringAlpha = (1f - phase) * 0.35f * intensity
                drawCircle(
                    color = baseColor.copy(alpha = ringAlpha),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = 2f)
                )
            }
        }
    }
}