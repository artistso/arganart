package art.argan.core.ink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The infinite canvas's pan/zoom maths is the only behaviour P0 ships to users,
 * so it is tested rather than eyeballed. Zoom must be anchored to the gesture
 * focus point: that is what makes the canvas feel infinite.
 */
class CanvasTransformTest {

    @Test
    fun `screen to canvas round-trips exactly`() {
        val t = CanvasTransform(scale = 2.5f, translateX = 40f, translateY = -13f)
        val canvasX = 123.5f
        val canvasY = -77.25f
        assertEquals(canvasX, t.screenToCanvasX(t.canvasToScreenX(canvasX)), 1e-4f)
        assertEquals(canvasY, t.screenToCanvasY(t.canvasToScreenY(canvasY)), 1e-4f)
    }

    @Test
    fun `pan moves the origin by exactly the screen delta`() {
        val t = CanvasTransform()
        val panned = t.panBy(30f, -12f)
        assertEquals(30f, panned.translateX)
        assertEquals(-12f, panned.translateY)
        assertEquals(t.scale, panned.scale)
    }

    @Test
    fun `zoom keeps the canvas point under the focus stationary`() {
        val t = CanvasTransform(scale = 1f, translateX = 10f, translateY = 20f)
        val focusX = 300f
        val focusY = 450f
        val canvasXBefore = t.screenToCanvasX(focusX)
        val canvasYBefore = t.screenToCanvasY(focusY)

        val zoomed = t.zoomAbout(focusX, focusY, factor = 2f)

        assertEquals(2f, zoomed.scale, 1e-5f)
        assertEquals(canvasXBefore, zoomed.screenToCanvasX(focusX), 1e-3f)
        assertEquals(canvasYBefore, zoomed.screenToCanvasY(focusY), 1e-3f)
    }

    @Test
    fun `zoom is clamped and cannot invert or explode`() {
        val t = CanvasTransform()
        val min = t.zoomAbout(0f, 0f, factor = 1e-9f)
        assertEquals(CanvasTransform.MIN_SCALE, min.scale, 1e-6f)
        val max = t.zoomAbout(0f, 0f, factor = 1e9f)
        assertEquals(CanvasTransform.MAX_SCALE, max.scale, 1e-4f)
    }

    @Test
    fun `scale outside the allowed range is rejected at construction`() {
        assertTrue(runCatching { CanvasTransform(scale = 0f) }.isFailure)
        assertTrue(runCatching { CanvasTransform(scale = 1_000f) }.isFailure)
    }
}
