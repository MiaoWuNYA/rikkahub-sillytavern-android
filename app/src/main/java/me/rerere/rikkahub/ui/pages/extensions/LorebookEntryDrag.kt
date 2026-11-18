package me.rerere.rikkahub.ui.pages.extensions

import kotlin.math.abs
import kotlin.uuid.Uuid

/** All geometry uses root coordinates, including the actual pointer, not the dragged card. */
internal data class LorebookDragPoint(val x: Float, val y: Float)

internal data class LorebookDragBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(point: LorebookDragPoint): Boolean =
        right > left && bottom > top && point.x >= left && point.x < right &&
            point.y >= top && point.y < bottom
}

internal data class LorebookDropTarget(val id: Uuid?, val group: String, val top: Float, val bottom: Float)

internal enum class LorebookDragPhase { IDLE, SAFE, INSERTION, OUTSIDE }

internal data class LorebookDragFeedback(
    val phase: LorebookDragPhase,
    val placement: LorebookEntryPlacement? = null,
    val scrollVelocity: Float = 0f,
)

/** One decision controls the preview and scrolling; returning to safety cannot retain a stale target. */
internal fun evaluateLorebookEntryDrag(
    pointer: LorebookDragPoint?,
    safety: LorebookDragBounds?,
    viewport: LorebookDragBounds?,
    targets: List<LorebookDropTarget>,
    edgeSize: Float,
    maxScrollVelocity: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    previous: LorebookEntryPlacement? = null,
    switchThreshold: Float = 0f,
    draggedEntryId: Uuid? = null,
    draggedGroup: String? = null,
): LorebookDragFeedback {
    if (pointer == null) return LorebookDragFeedback(LorebookDragPhase.IDLE)
    if (safety?.contains(pointer) == true) return LorebookDragFeedback(LorebookDragPhase.SAFE)
    if (viewport == null || !viewport.contains(pointer)) return LorebookDragFeedback(LorebookDragPhase.OUTSIDE)

    val effectiveTargets = if (draggedGroup == null) targets else targets.groupBy { if (it.group.isBlank()) "entry:${it.id}" else "group:${it.group}" }.values.map { rows ->
            val first = rows.first()
            LorebookDropTarget(if (first.group.isBlank()) first.id else null, first.group, rows.minOf { it.top }, rows.maxOf { it.bottom })
        }
    val visible = effectiveTargets.filter { it.bottom > viewport.top && it.top < viewport.bottom }
    val hit = visible.firstOrNull { pointer.y >= it.top && pointer.y < it.bottom }
    val target = hit ?: visible.filterNot { draggedEntryId != null && it.id == draggedEntryId }
        .minByOrNull { minOf(abs(pointer.y - it.top), abs(pointer.y - it.bottom)) }
    val placement = target?.takeUnless { (draggedEntryId != null && it.id == draggedEntryId) || it.group == draggedGroup }?.let {
        if (it.id == null) {
            if (draggedGroup == null && it.group.isNotBlank() && pointer.y < it.top + (it.bottom - it.top) * 0.15f)
                LorebookEntryPlacement(null, false, "", rootGroup = it.group)
            else LorebookEntryPlacement.groupEdge(it.group, if (draggedGroup == null) true else pointer.y > (it.top + it.bottom) / 2f)
        }
        else {
            val midpoint = (it.top + it.bottom) / 2f
            val margin = switchThreshold.coerceIn(0f, (it.bottom - it.top) * 0.15f)
            val same = previous != null && previous.targetId == it.id && previous.group == it.group
            val after = when {
                same && previous?.after == true -> pointer.y >= midpoint - margin
                same -> pointer.y > midpoint + margin
                else -> pointer.y > midpoint
            }
            LorebookEntryPlacement(it.id, after, it.group)
        }
    }
    // Prevent overlapping edge bands in a short viewport. Speed is distance-based, in px/second.
    val edge = edgeSize.coerceIn(0f, (viewport.bottom - viewport.top) / 3f)
    val velocity = if (edge > 0f) {
        val fromTop = pointer.y - viewport.top
        val fromBottom = viewport.bottom - pointer.y
        when {
            fromTop < edge && canScrollBackward -> -maxScrollVelocity * (1f - fromTop / edge)
            fromBottom < edge && canScrollForward -> maxScrollVelocity * (1f - fromBottom / edge)
            else -> 0f
        }
    } else 0f
    return LorebookDragFeedback(LorebookDragPhase.INSERTION, placement, velocity)
}
