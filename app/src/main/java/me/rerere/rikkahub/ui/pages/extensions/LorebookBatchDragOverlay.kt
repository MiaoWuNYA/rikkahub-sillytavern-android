package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/** Visual-only overlay. The original handles keep pointer capture and all drop decisions. */
@Composable
internal fun LorebookBatchDragOverlay(state: LorebookInsertionState, batch: LorebookBatchPreview, rootOrigin: Offset) {
    val drag = state.drag ?: return
    val entries = remember(drag.token) { batch.entries.filter { drag.entryId == null || it.id == drag.entryId } }
    LorebookInsertionOverlay(state, rootOrigin) {
        val width = with(LocalDensity.current) { drag.origin.width.toDp() }
        LazyRow(Modifier.width(width).offset(y = (-4).dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            state = rememberLazyListState(if (drag.entryId == null) drag.window.firstIndex else 0,
                if (drag.entryId == null) drag.window.offset else 0),
            contentPadding = PaddingValues(vertical = 4.dp), userScrollEnabled = false) {
            items(entries, key = { it.id }) { entry ->
                LorebookThumbnailFace(entry, Modifier.shadow(4.dp, MaterialTheme.shapes.medium))
            }
        }
        if (drag.entryId == null) Text("${entries.size} 条", Modifier.align(Alignment.BottomEnd),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun LorebookInsertionOverlay(state: LorebookInsertionState, rootOrigin: Offset, content: @Composable BoxScope.() -> Unit) {
    val drag = state.drag ?: return
    val settle = remember(drag.token) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val opacity = remember(drag.token) { Animatable(1f) }
    var settling by remember(drag.token) { mutableStateOf(false) }
    LaunchedEffect(drag.token, drag.released) {
        if (drag.released) {
            settle.snapTo(drag.last - drag.start)
            settling = true
            // Same default spring as the existing reorder lift: cancelled drags return to their slot.
            if (drag.submitted) opacity.animateTo(0f) else settle.animateTo(Offset.Zero)
            if (state.drag?.token == drag.token) state.drag = null
        }
    }
    val delta = if (drag.released && settling) settle.value else drag.last - drag.start
    val position = drag.origin.topLeft - rootOrigin + delta
    Box(Modifier.zIndex(1f).offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
        .promptReorderLift(!drag.released).graphicsLayer { alpha = opacity.value }
        .testTag("lorebook-lifted-batch")) {
        content()
    }
}
