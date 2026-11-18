package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

internal val LorebookRailWidth = 36.dp

/** Measure content first; the rail adopts that height instead of expanding a bounded parent. */
@Composable
internal fun LorebookEntryCardLayout(dragHandle: @Composable () -> Unit, content: @Composable () -> Unit) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Box { content() }
            Box(
                Modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                propagateMinConstraints = true,
            ) { dragHandle() }
        },
    ) { children, constraints ->
        val railWidth = LorebookRailWidth.roundToPx()
        val margin = 4.dp.roundToPx()
        val width = constraints.maxWidth
        val contentWidth = (width - railWidth - margin).coerceAtLeast(0)
        val body = children[0].measure(constraints.copy(minWidth = contentWidth, maxWidth = contentWidth, minHeight = 0))
        val height = constraints.constrainHeight(maxOf(body.height, 48.dp.roundToPx()))
        val rail = children[1].measure(Constraints.fixed(railWidth, (height - margin * 2).coerceAtLeast(0)))
        layout(width, height) {
            body.placeRelative(0, 0)
            rail.placeRelative(width - margin - railWidth, margin)
        }
    }
}
