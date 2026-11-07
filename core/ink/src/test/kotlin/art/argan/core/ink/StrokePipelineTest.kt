package art.argan.core.ink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The stroke pipeline is the difference between "feels like a pen" and "feels like a
 * mouse". Its behaviour is pinned here rather than tuned by eye on a device, because
 * a regression in smoothing is invisible until someone draws a circle.
 */
class StrokePipelineTest {

    private fun point(
        t: Long,
        x: Float,
        y: Float,
        pressure: Float = 1f,
        predicted: Boolean = false,
    ) = InputPoint(
        timestampMillis = t,
        x = x,
        y = y,
        pressure = pressure,
        isPredicted = predicted,
    )

    @Test
    fun `committed stroke rejects predicted points`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            Stroke(
                points = listOf(point(0, 0f, 0f), point(8, 10f, 10f, predicted = true)),
                brush = BrushParams.DEFAULT,
            )
        }
        assertTrue(failure.message!!.contains("predicted"), failure.message!!)
    }

    @Test
    fun `stroke bounds include the brush radius`() {
        val stroke = Stroke(
            points = listOf(point(0, 0f, 0f), point(8, 100f, 50f)),
            brush = BrushParams(size = 20f),
        )
        val bounds = stroke.bounds()
        // Half of size 20 = 10 units of padding on every side.
        assertEquals(-10f, bounds.left)
        assertEquals(-10f, bounds.top)
        assertEquals(110f, bounds.right)
        assertEquals(60f, bounds.bottom)
    }

    @Test
    fun `bounds intersection and union behave`() {
        val a = StrokeBounds(0f, 0f, 10f, 10f)
        val b = StrokeBounds(5f, 5f, 20f, 20f)
        val c = StrokeBounds(100f, 100f, 110f, 110f)
        assertTrue(a.intersects(b))
        assertTrue(!a.intersects(c))
        assertEquals(StrokeBounds(0f, 0f, 20f, 20f), a.union(b))
    }

    @Test
    fun `pressure response curves are ordered and clamped`() {
        // Every curve must map 0 to 0 and 1 to 1, or strokes start thick or never
        // reach full width.
        for (response in PressureResponse.entries) {
            assertEquals(0f, response.map(0f), 1e-6f, "$response at 0")
            assertEquals(1f, response.map(1f), 1e-6f, "$response at 1")
            assertEquals(0f, response.map(-5f), 1e-6f, "$response clamps low")
            assertEquals(1f, response.map(5f), 1e-6f, "$response clamps high")
        }
        // The curves must be genuinely distinguishable, or offering the choice is
        // cosmetic. Compare off-centre: smoothstep is symmetric, so SOFT and LINEAR
        // agree at exactly 0.5 and differ everywhere else.
        val low = 0.25f
        assertTrue(
            PressureResponse.SOFT.map(low) < PressureResponse.LINEAR.map(low),
            "SOFT must be gentler at low pressure",
        )
        assertTrue(
            PressureResponse.FIRM.map(low) > PressureResponse.LINEAR.map(low),
            "FIRM must reach width sooner at low pressure",
        )
    }

    @Test
    fun `smoothing strength zero is exactly passthrough`() {
        val bristle = StrokeBristle(BrushParams(size = 4f, smoothingStrength = 0f))
        val raw = listOf(point(0, 0f, 0f), point(10, 100f, 100f), point(20, 200f, 0f))
        val processed = bristle.processAll(raw)
        assertEquals(raw.map { it.x }, processed.map { it.x })
        assertEquals(raw.map { it.y }, processed.map { it.y })
    }

    @Test
    fun `smoothing removes jitter but keeps the endpoints near the input`() {
        val brush = BrushParams(size = 4f, smoothingStrength = 1f)
        val bristle = StrokeBristle(brush)
        // A straight horizontal line with +/-2 units of vertical jitter.
        val raw = (0..40).map { i ->
            val jitter = if (i % 2 == 0) 2f else -2f
            point(i * 8L, i.toFloat(), jitter)
        }
        val processed = bristle.processAll(raw)
        // Endpoints stay put: smoothing must not move the user's first/last point.
        assertEquals(raw.first().x, processed.first().x, 1e-3f)
        assertEquals(raw.last().x, processed.last().x, 1e-3f)

        // Interior jitter is flattened: the peak-to-peak of the interior is smaller
        // than the raw 4-unit swing.
        val interior = processed.drop(3).dropLast(3).map { it.y }
        val swing = (interior.max()) - (interior.min())
        assertTrue(swing < 3.9f, "expected jitter below raw swing, was $swing")
    }

    @Test
    fun `stabilizer introduces lag that is bounded by its leash radius`() {
        val bristle = StrokeBristle(
            BrushParams(size = 4f, smoothingStrength = 0f, stabilizerStrength = 1f),
        )
        // A single huge jump: without a clamp the leash would trail by 500 units.
        val state = StrokeBristle.State()
        val out = FloatArray(2)
        bristle.process(point(0, 0f, 0f), state, 0, out)
        bristle.process(point(8, 500f, 0f), state, 0, out)

        val lag = bristle.leashDistance(point(8, 500f, 0f), state)
        assertTrue(lag <= 24f + 1e-3f, "leash must clamp at 24 units, was $lag")
        assertTrue(lag > 0f, "a stabilised stroke must visibly lag")
    }

    @Test
    fun `one euro filter rejects duplicate timestamps without dividing by zero`() {
        val filter = OneEuroFilter()
        val state = OneEuroFilter.State()
        val first = filter.filter(10.0, 0.0, state, strength = 1f)
        val second = filter.filter(99.0, 0.0, state, strength = 1f)
        assertEquals(10.0, first, 1e-9)
        assertEquals(10.0, second, 1e-9, "a duplicate timestamp must hold, not jump or NaN")
        assertTrue(!second.isNaN())
    }

    @Test
    fun `one euro filter tracks a moving target without permanent offset`() {
        val filter = OneEuroFilter()
        val state = OneEuroFilter.State()
        var value = 0.0
        var last = 0.0
        // Move steadily rightwards for one second at 120 Hz.
        for (i in 0..120) {
            value = i.toDouble()
            last = filter.filter(value, i / 120.0, state, strength = 1f)
        }
        // It must not be stuck; a broken filter would return ~0 forever.
        assertTrue(last > 100.0, "filter did not track, ended at $last")
    }

    @Test
    fun `centripetal spline passes through its control points`() {
        val spline = CatmullRomSpline()
        val out = FloatArray(2)
        // Endpoints of the segment must be reproduced exactly.
        spline.evaluate(0f, 0f, 10f, 10f, 20f, 0f, 30f, 10f, t = 0f, out = out)
        assertEquals(10f, out[0], 1e-3f)
        assertEquals(10f, out[1], 1e-3f)

        spline.evaluate(0f, 0f, 10f, 10f, 20f, 0f, 30f, 10f, t = 1f, out = out)
        assertEquals(20f, out[0], 1e-3f)
        assertEquals(0f, out[1], 1e-3f)
    }

    @Test
    fun `centripetal spline does not overshoot wildly with unevenly spaced points`() {
        val spline = CatmullRomSpline()
        val out = FloatArray(2)
        // Two points extremely close, then a far one: uniform Catmull-Rom cusps here.
        var maxOvershoot = 0f
        for (step in 0..10) {
            spline.evaluate(
                0f, 0f,
                0.01f, 0f,
                100f, 0f,
                101f, 0f,
                t = step / 10f,
                out = out,
            )
            maxOvershoot = maxOf(maxOvershoot, kotlin.math.abs(out[1]))
            assertTrue(!out[0].isNaN() && !out[1].isNaN())
        }
        // A cusping spline would swing far off the y=0 line.
        assertTrue(maxOvershoot < 5f, "spline overshot by $maxOvershoot on a straight line")
    }

    @Test
    fun `coincident points do not produce NaN`() {
        val spline = CatmullRomSpline()
        val out = FloatArray(2)
        spline.evaluate(5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f, t = 0.5f, out = out)
        assertTrue(!out[0].isNaN() && !out[1].isNaN(), "degenerate span produced NaN")
        assertEquals(5f, out[0], 1e-3f)
    }

    @Test
    fun `stamp spacing decides placement from diameter and spacing`() {
        // diameter 10, spacing 0.1 -> minimum distance 1.0
        assertTrue(!BristleShouldStamp(0f, 0f, 0.5f, 0f, 10f, 0.1f))
        assertTrue(BristleShouldStamp(0f, 0f, 1.5f, 0f, 10f, 0.1f))
    }

    @Suppress("FunctionName")
    private fun BristleShouldStamp(
        x0: Float, y0: Float, x1: Float, y1: Float, d: Float, s: Float,
    ) = StrokeBristle.shouldStamp(x0, y0, x1, y1, d, s)

    @Test
    fun `straightness and angular deviation measure what they claim`() {
        // Perfectly straight through the middle point.
        assertEquals(1f, StrokeBristle.straightness(0f, 0f, 5f, 0f, 10f, 0f), 1e-4f)
        // A spike doubles back: chord is zero, so straightness is zero.
        assertEquals(0f, StrokeBristle.straightness(0f, 0f, 10f, 0f, 0f, 0f), 1e-4f)

        assertEquals(0f, StrokeBristle.angularDeviation(0f, 0f, 5f, 0f, 10f, 0f), 1e-3f)
        val rightAngle = StrokeBristle.angularDeviation(0f, 0f, 5f, 0f, 5f, 5f)
        assertEquals((Math.PI / 2).toFloat(), rightAngle, 1e-3f)
    }

    @Test
    fun `round stamp grows with pressure and never reaches zero radius`() {
        val stamp = BrushParams(size = 20f, pressureResponse = PressureResponse.LINEAR).stamp()
        assertTrue(stamp.radiusAt(1f) > stamp.radiusAt(0.2f))
        assertTrue(stamp.radiusAt(0f) > 0f, "a light stroke must still mark the canvas")
        assertEquals(10f, stamp.radiusAt(1f), 1e-3f)
        assertTrue(stamp.supportsSoftwareFallback)
    }

    @Test
    fun `brush params reject nonsensical values`() {
        assertFailsWith<IllegalArgumentException> { BrushParams(size = 0f) }
        assertFailsWith<IllegalArgumentException> { BrushParams(size = 1f, opacity = 2f) }
        assertFailsWith<IllegalArgumentException> { BrushParams(size = 1f, spacing = 0f) }
        assertFailsWith<IllegalArgumentException> { BrushParams(size = 1f, flow = -1f) }
    }
}
