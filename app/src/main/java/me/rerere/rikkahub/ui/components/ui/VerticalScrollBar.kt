package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 列表右侧的拖动滚动条：长列表（角色卡、消息）靠它快速跳转。
 * 只监听拖动位移，不做惯性；拖动时按比例定位到对应条目。
 */
@Composable
fun VerticalScrollBar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(14.dp),
    ) {
        val trackHeightPx = constraints.maxHeight.toFloat()
        val info = state.layoutInfo
        val totalItems = info.totalItemsCount
        val visibleItems = info.visibleItemsInfo.size
        if (totalItems <= 0 || visibleItems <= 0 || trackHeightPx <= 0f) return@BoxWithConstraints

        val thumbHeightPx = (trackHeightPx * visibleItems / totalItems)
            .coerceIn(with(density) { 36.dp.toPx() }.coerceAtMost(trackHeightPx), trackHeightPx)
        val maxScrollIndex = (totalItems - visibleItems).coerceAtLeast(1)
        val progress = (state.firstVisibleItemIndex / maxScrollIndex.toFloat()).coerceIn(0f, 1f)
        val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)

        // 拖动起点用按下瞬间的位置做基准，避免每帧累积误差把滑块甩飞
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 2.dp)
                .offset(y = with(density) { (travelPx * progress).toDp() })
                .height(with(density) { thumbHeightPx.toDp() })
                .width(6.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    shape = CircleShape,
                )
                .pointerInput(totalItems, visibleItems, trackHeightPx) {
                    var baseProgress = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            val current = state.layoutInfo
                            val maxIndex = (current.totalItemsCount - current.visibleItemsInfo.size)
                                .coerceAtLeast(1)
                            baseProgress = (state.firstVisibleItemIndex / maxIndex.toFloat())
                                .coerceIn(0f, 1f)
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            baseProgress = (baseProgress + dragAmount / travelPx.coerceAtLeast(1f))
                                .coerceIn(0f, 1f)
                            val current = state.layoutInfo
                            val maxIndex = (current.totalItemsCount - current.visibleItemsInfo.size)
                                .coerceAtLeast(1)
                            scope.launch {
                                state.scrollToItem((baseProgress * maxIndex).toInt())
                            }
                        },
                    )
                },
        )
    }
}
