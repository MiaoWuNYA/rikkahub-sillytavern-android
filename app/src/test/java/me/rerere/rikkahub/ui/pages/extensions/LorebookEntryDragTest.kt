package me.rerere.rikkahub.ui.pages.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryDragTest {
    @Test fun `midpoint hysteresis rejects jitter but large reverse movements switch immediately`() {
        fun at(y: Float, previous: LorebookEntryPlacement?) = evaluateLorebookEntryDrag(
            LorebookDragPoint(100f, y), null, viewport, listOf(first), 64f, 720f, false, false,
            previous = previous, switchThreshold = 8f).placement
        val before = LorebookEntryPlacement(first.id, false, "A")
        assertEquals(before, at(263f, before))
        val after = checkNotNull(at(270f, before))
        assertTrue(after.after)
        assertEquals(after, at(257f, after))
        assertEquals(before, at(250f, after))
    }

    @Test fun `own placeholder is neutral while group header always appends`() {
        val own = evaluateLorebookEntryDrag(LorebookDragPoint(100f, 250f), null, viewport,
            listOf(first, otherGroup), 64f, 720f, false, false, draggedEntryId = first.id)
        assertNull(own.placement)
        val header = evaluateLorebookEntryDrag(LorebookDragPoint(100f, 250f), null, viewport,
            listOf(LorebookDropTarget(null, "B", 220f, 280f)), 64f, 720f, false, false)
        assertEquals(LorebookEntryPlacement.groupEdge("B", true), header.placement)
    }

    @Test fun `large jumps select the current group and safety clears even a remembered target`() {
        val previous = LorebookEntryPlacement(first.id, true, "A")
        val jump = evaluateLorebookEntryDrag(LorebookDragPoint(100f, 380f), safety, viewport,
            listOf(first, otherGroup), 64f, 720f, true, true, previous, 8f)
        assertEquals("B", jump.placement?.group)
        assertPaused(evaluateLorebookEntryDrag(LorebookDragPoint(100f, 100f), safety, viewport,
            listOf(first), 64f, 720f, true, true, previous, 8f), LorebookDragPhase.SAFE)
    }
    private val safety = LorebookDragBounds(0f, 0f, 300f, 200f)
    private val viewport = LorebookDragBounds(0f, 200f, 300f, 800f)
    private val first = LorebookDropTarget(Uuid.random(), "A", 220f, 300f)
    private val otherGroup = LorebookDropTarget(Uuid.random(), "B", 330f, 400f)
    private val last = LorebookDropTarget(Uuid.random(), "A", 450f, 550f)

    private fun feedback(
        point: LorebookDragPoint?,
        targets: List<LorebookDropTarget> = listOf(first, otherGroup, last),
        bounds: LorebookDragBounds? = viewport,
        backward: Boolean = true,
        forward: Boolean = true,
    ) = evaluateLorebookEntryDrag(
        pointer = point,
        safety = safety,
        viewport = bounds,
        targets = targets,
        edgeSize = 64f,
        maxScrollVelocity = 720f,
        canScrollBackward = backward,
        canScrollForward = forward,
    )

    private fun assertPaused(result: LorebookDragFeedback, phase: LorebookDragPhase) {
        assertEquals(phase, result.phase)
        assertNull(result.placement)
        assertEquals(0f, result.scrollVelocity, 0f)
    }

    @Test
    fun `starting or moving anywhere in safety never selects a target or scrolls`() {
        listOf(1f, 100f, 199.99f).forEach { y ->
            assertPaused(feedback(LorebookDragPoint(280f, y)), LorebookDragPhase.SAFE)
        }
    }

    @Test
    fun `leaving safety selects before or after the entry using pointer position`() {
        val before = feedback(LorebookDragPoint(280f, 230f))
        val after = feedback(LorebookDragPoint(280f, 290f))
        assertEquals(LorebookDragPhase.INSERTION, before.phase)
        assertEquals(LorebookEntryPlacement(first.id, false, "A"), before.placement)
        assertEquals(LorebookEntryPlacement(first.id, true, "A"), after.placement)
    }

    @Test
    fun `returning to safety clears target and scrolling then leaving resumes`() {
        val selecting = feedback(LorebookDragPoint(280f, 799f))
        assertTrue(selecting.scrollVelocity > 0f)
        assertEquals(last.id, selecting.placement?.targetId)
        assertPaused(feedback(LorebookDragPoint(280f, 150f)), LorebookDragPhase.SAFE)
        val resumed = feedback(LorebookDragPoint(280f, 500f))
        assertEquals(last.id, resumed.placement?.targetId)
        assertEquals(0f, resumed.scrollVelocity, 0f)
    }

    @Test
    fun `safe area wins while viewport bounds overlap during layout updates`() {
        assertPaused(
            feedback(LorebookDragPoint(280f, 190f), bounds = LorebookDragBounds(0f, 180f, 300f, 800f)),
            LorebookDragPhase.SAFE,
        )
    }

    @Test
    fun `horizontal or vertical exit disables placement and auto scroll`() {
        listOf(
            LorebookDragPoint(-1f, 210f),
            LorebookDragPoint(300f, 790f),
            LorebookDragPoint(280f, 800f),
            LorebookDragPoint(280f, -1f),
        ).forEach { assertPaused(feedback(it), LorebookDragPhase.OUTSIDE) }
    }

    @Test
    fun `cancelled gesture cannot retain its previous drop target`() {
        assertEquals(first.id, feedback(LorebookDragPoint(280f, 240f)).placement?.targetId)
        assertPaused(feedback(null), LorebookDragPhase.IDLE)
    }

    @Test
    fun `auto scroll ramps only near viewport edges and respects scroll limits`() {
        val top = feedback(LorebookDragPoint(280f, 201f)).scrollVelocity
        val nearTop = feedback(LorebookDragPoint(280f, 240f)).scrollVelocity
        val bottom = feedback(LorebookDragPoint(280f, 799f)).scrollVelocity
        assertTrue(top < nearTop && nearTop < 0f)
        assertTrue(top >= -720f && bottom <= 720f && bottom > 0f)
        assertEquals(0f, feedback(LorebookDragPoint(280f, 400f)).scrollVelocity, 0f)
        assertEquals(0f, feedback(LorebookDragPoint(280f, 201f), backward = false).scrollVelocity, 0f)
        assertEquals(0f, feedback(LorebookDragPoint(280f, 799f), forward = false).scrollVelocity, 0f)
    }

    @Test
    fun `a short viewport still has a non scrolling middle`() {
        val result = feedback(LorebookDragPoint(280f, 210f), bounds = LorebookDragBounds(0f, 200f, 300f, 220f))
        assertEquals(0f, result.scrollVelocity, 0f)
    }

    @Test
    fun `foreign group becomes an explicit drop destination`() {
        assertEquals("B", feedback(LorebookDragPoint(280f, 360f)).placement?.group)
    }

    @Test
    fun `pointer inside a tall card takes precedence over a nearby short card center`() {
        val tall = last.copy(top = 300f, bottom = 780f)
        val short = otherGroup.copy(top = 220f, bottom = 300f)
        val point = LorebookDragPoint(280f, 320f)
        assertEquals(tall.id, feedback(point, targets = listOf(short, tall)).placement?.targetId)
        assertEquals("B", feedback(point, targets = listOf(first, tall.copy(group = "B"))).placement?.group)
    }

    @Test
    fun `scrolling updates target from current item geometry without moving the pointer`() {
        val point = LorebookDragPoint(280f, 250f)
        assertEquals(first.id, feedback(point).placement?.targetId)
        val scrolled = listOf(last.copy(top = 220f, bottom = 300f))
        assertEquals(last.id, feedback(point, targets = scrolled).placement?.targetId)
        assertNull(feedback(point, targets = emptyList()).placement)
    }

    @Test
    fun `unmeasured viewport and fully clipped entries cannot be targeted`() {
        assertPaused(feedback(LorebookDragPoint(280f, 250f), bounds = null), LorebookDragPhase.OUTSIDE)
        val clipped = listOf(first.copy(top = 100f, bottom = 190f), last.copy(top = 810f, bottom = 900f))
        assertNull(feedback(LorebookDragPoint(280f, 400f), targets = clipped).placement)
    }
}
