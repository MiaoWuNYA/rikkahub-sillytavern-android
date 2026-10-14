package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf

// 存活中的 WebView 计数：模糊/玻璃采样到 WebView 硬件层会崩
object WebViewPresence {
    private val counter = mutableIntStateOf(0)

    val active: Boolean get() = counter.intValue > 0

    fun enter() {
        counter.intValue++
    }

    fun exit() {
        counter.intValue = (counter.intValue - 1).coerceAtLeast(0)
    }
}

@Composable
fun TrackWebViewPresence() {
    DisposableEffect(Unit) {
        WebViewPresence.enter()
        onDispose { WebViewPresence.exit() }
    }
}
