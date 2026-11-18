package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Shared lift from the mode/lorebook lists; default Compose spring also settles the release. */
@Composable
internal fun Modifier.promptReorderLift(dragging: Boolean): Modifier {
    val scale by animateFloatAsState(if (dragging) PromptDraggingScale else 1f, label = "prompt_reorder_lift")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

internal const val PromptDraggingScale = 1.05f

@Composable
internal fun Modifier.promptDragPlaceholder(lifted: Boolean): Modifier {
    val alpha by animateFloatAsState(if (lifted) 0.25f else 1f, label = "prompt_drag_placeholder")
    return graphicsLayer { this.alpha = alpha }
}

/** Same default spring and scale family as the existing reorder lift, without changing measurement. */
@Composable
internal fun PromptPendingEntrance(key: Any, content: @Composable () -> Unit) {
    val state = androidx.compose.runtime.remember(key) {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = state,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 1f / PromptDraggingScale),
        exit = androidx.compose.animation.fadeOut(),
    ) { content() }
}
