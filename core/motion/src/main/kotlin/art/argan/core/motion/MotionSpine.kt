package art.argan.core.motion

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A spline path through canvas space: the spine of a ribbon.
 *
 * ## Why this exists in `:core:motion` and not in `:core:ink`
 *
 * A ribbon spine and a drawn stroke are both point sequences, but they are not the
 * same object and must not be conflated. A stroke is ~240 samples per second of
 * hand motion, stored per point and never re-parameterised. A spine is a handful of
 * control points that the user drags, stores with per-segment arc lengths, and is
 * sampled at a *time* rather than along its length. Sharing one type would force the
 * ink hot path to carry arc-length bookkeeping it does not need.
 *
 * The Catmull-Rom maths is deliberately duplicated in spirit but not in code: the ink
 * spline is a cusp-free centripetal interpolation of dense samples, whereas here the
 * control points are sparse and the *arc length* is the interesting quantity.
 *
 * ## Arc length, and why time is position
 *
 * The P2 brief forbids a timeline: "time is expressed as position along the ribbon".
 * That makes arc length the load-bearing quantity. [cumulativeArcLengths] is computed
 * once when the spine is built and stored, so mapping time to a point is a binary
 * search plus one segment evaluation — no integration per frame, and no allocation.
 *
 * @property points control points in canvas units; at least 2.
 */
public class MotionSpine private constructor(
    private val xs: FloatArray,
    private val ys: FloatArray,
    /**
     * `cumulative[i]` is the arc length from the start of the spine to control point
     * `i`; always ascending, always `cumulative[0] == 0`. Empty implies zero length.
     */
    private val cumulative: FloatArray,
) {
    /** Number of control points. */
    public val pointCount: Int get() = xs.size

    /** Number of drawable segments; one fewer than [pointCount]. */
    public val segmentCount: Int get() = (xs.size - 1).coerceAtLeast(0)

    /** Total length in canvas units. A zero-length spine is a dot. */
    public val length: Float get() = if (cumulative.isEmpty()) 0f else cumulative[cumulative.size - 1]

    /** True when every control point coincides, so the spine has no direction. */
    public val isDegenerate: Boolean get() = length <= MIN_LENGTH

    public fun xAt(index: Int): Float = xs[index]
    public fun yAt(index: Int): Float = ys[index]

    /** Arc length from the start to control point [index]. */
    public fun arcLengthAt(index: Int): Float = cumulative[index]

    /**
     * Control-point positions as a plain list, for persistence (P9) and for callers
     * that want to hand the spine to a UI layer without exposing the arrays.
     * Allocates; never call from a draw loop.
     */
    public fun points(): List<SpinePoint> = List(xs.size) { SpinePoint(xs[it], ys[it]) }

    /**
     * Evaluates the position at arc length [distance] along the spine.
     *
     * Complexity is `O(log n)` for the segment search, then a single cubic
     * evaluation. Writes into [out] and allocates nothing.
     *
     * Beyond either end the result clamps to the endpoint, which is what lets a clip
     * overrun its own duration without producing NaNs.
     *
     * @param out caller-owned `FloatArray(2)`, receives x and y.
     */
    public fun pointAtDistance(distance: Float, out: FloatArray) {
        require(out.size >= 2) { "out must have at least 2 elements" }
        val n = xs.size
        if (n == 0) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        if (n == 1 || isDegenerate) {
            out[0] = xs[0]
            out[1] = ys[0]
            return
        }
        val target = distance.coerceIn(0f, length)

        // Binary search for the segment containing `target`.
        var lo = 0
        var hi = n - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] <= target) lo = mid else hi = mid
        }
        val span = cumulative[lo + 1] - cumulative[lo]
        val local = if (span <= MIN_LENGTH) 0f else (target - cumulative[lo]) / span

        // Neighbouring indices clamped at the ends, so the end segments are
        // evaluated against duplicated control points rather than extrapolated.
        val i0 = (lo - 1).coerceAtLeast(0)
        val i3 = (lo + 2).coerceAtMost(n - 1)
        SPLINE.evaluate(
            xs[i0], ys[i0],
            xs[lo], ys[lo],
            xs[lo + 1], ys[lo + 1],
            xs[i3], ys[i3],
            local,
            out,
        )
    }

    /**
     * Unit tangent at arc length [distance], written to [outX]/[outY].
     *
     * Derived by finite difference over a short arc rather than by differentiating the
     * spline analytically: the difference is below visual threshold, and this stays
     * correct for every segment including degenerate ones. Used to orient the attached
     * element along the path (the brief's "twist to rotate along the path").
     */
    public fun tangentAtDistance(distance: Float, outXY: FloatArray) {
        require(outXY.size >= 2) { "outXY must have at least 2 elements" }
        if (isDegenerate) {
            outXY[0] = 1f
            outXY[1] = 0f
            return
        }
        val delta = (length * TANGENT_DELTA_FRACTION).coerceAtLeast(MIN_TANGENT_DELTA)
        // Two distinct buffers: sampling the second point must not overwrite the
        // first. (An earlier draft reused one array here and every tangent came out
        // as (0,0) -> defaulted to +x, which the tangent test caught.)
        pointAtDistance(distance - delta, tangentBefore)
        pointAtDistance(distance + delta, tangentAfter)
        var tx = tangentAfter[0] - tangentBefore[0]
        var ty = tangentAfter[1] - tangentBefore[1]
        val len = sqrt(tx * tx + ty * ty)
        if (len <= MIN_LENGTH) {
            outXY[0] = 1f
            outXY[1] = 0f
            return
        }
        tx /= len
        ty /= len
        outXY[0] = tx
        outXY[1] = ty
    }

    private val tangentBefore = FloatArray(2)
    private val tangentAfter = FloatArray(2)

    public companion object {
        private const val MIN_LENGTH = 1e-4f
        private const val MIN_TANGENT_DELTA = 0.5f
        private const val TANGENT_DELTA_FRACTION = 0.01f

        private val SPLINE = CentripetalSpline()

        /**
         * Builds a spine from control points.
         *
         * Scans the points once to accumulate arc length, sampling each segment at
         * [ARC_SAMPLES_PER_SEGMENT] steps. Dense sampling (rather than chord length) is
         * used because a chord underestimates the length of a curved segment, and an
         * underestimated length makes motion visibly speed up through bends.
         *
         * @throws IllegalArgumentException if fewer than 2 points are supplied.
         */
        public fun of(points: List<SpinePoint>): MotionSpine {
            require(points.size >= 2) { "a spine needs at least 2 points, got ${points.size}" }
            val n = points.size
            val xs = FloatArray(n) { points[it].x }
            val ys = FloatArray(n) { points[it].y }
            val cumulative = FloatArray(n)

            val cursor = FloatArray(2)
            var accumulated = 0f
            val steps = ARC_SAMPLES_PER_SEGMENT
            for (i in 0 until n - 1) {
                val i0 = (i - 1).coerceAtLeast(0)
                val i3 = (i + 2).coerceAtMost(n - 1)
                var lastX = xs[i]
                var lastY = ys[i]
                for (s in 1..steps) {
                    SPLINE.evaluate(
                        xs[i0], ys[i0],
                        xs[i], ys[i],
                        xs[i + 1], ys[i + 1],
                        xs[i3], ys[i3],
                        s.toFloat() / steps,
                        cursor,
                    )
                    val dx = cursor[0] - lastX
                    val dy = cursor[1] - lastY
                    accumulated += sqrt(dx * dx + dy * dy)
                    lastX = cursor[0]
                    lastY = cursor[1]
                }
                cumulative[i + 1] = accumulated
            }
            return MotionSpine(xs, ys, cumulative)
        }

        /** Convenience overload for callers that have plain coordinate pairs. */
        public fun of(vararg coordinates: Float): MotionSpine {
            require(coordinates.size >= 4 && coordinates.size % 2 == 0) {
                "expected at least 2 (x, y) pairs, got ${coordinates.size} values"
            }
            return of(List(coordinates.size / 2) { SpinePoint(coordinates[it * 2], coordinates[it * 2 + 1]) })
        }

        private const val ARC_SAMPLES_PER_SEGMENT = 16
    }
}

/** A control point on a [MotionSpine], in canvas units. */
public data class SpinePoint(val x: Float, val y: Float)

/**
 * Centripetal Catmull-Rom (alpha = 0.5), allocation-free.
 *
 * Centripetal parametrisation is used for the same reason as in the ink engine: with
 * sparse, unevenly spaced control points — exactly what dragging ribbon handles
 * produces — uniform Catmull-Rom forms cusps and loops that the user did not ask for.
 *
 * @see art.argan.core.ink.CatmullRomSpline for the ink-side twin and the fuller
 * rationale; they are intentionally separate classes so neither module depends on
 * the other's model types.
 */
internal class CentripetalSpline {

    fun evaluate(
        p0x: Float, p0y: Float,
        p1x: Float, p1y: Float,
        p2x: Float, p2y: Float,
        p3x: Float, p3y: Float,
        t: Float,
        out: FloatArray,
    ) {
        val t0 = 0.0
        val t1 = t0 + knotDelta(p0x, p0y, p1x, p1y)
        val t2 = t1 + knotDelta(p1x, p1y, p2x, p2y)
        val t3 = t2 + knotDelta(p2x, p2y, p3x, p3y)
        val tEval = t1 + (t2 - t1) * t.coerceIn(0f, 1f).toDouble()

        if (t2 - t1 < EPSILON) {
            out[0] = p1x
            out[1] = p1y
            return
        }

        val a1x = lerp(p0x, p1x, t0, t1, tEval)
        val a1y = lerp(p0y, p1y, t0, t1, tEval)
        val a2x = lerp(p1x, p2x, t1, t2, tEval)
        val a2y = lerp(p1y, p2y, t1, t2, tEval)
        val a3x = lerp(p2x, p3x, t2, t3, tEval)
        val a3y = lerp(p2y, p3y, t2, t3, tEval)

        val b1x = lerp(a1x, a2x, t0, t2, tEval)
        val b1y = lerp(a1y, a2y, t0, t2, tEval)
        val b2x = lerp(a2x, a3x, t1, t3, tEval)
        val b2y = lerp(a2y, a3y, t1, t3, tEval)

        out[0] = lerp(b1x, b2x, t1, t2, tEval)
        out[1] = lerp(b1y, b2y, t1, t2, tEval)
    }

    private companion object {
        const val EPSILON = 1e-6
        const val ALPHA = 0.5

        fun knotDelta(x0: Float, y0: Float, x1: Float, y1: Float): Double {
            val dx = (x1 - x0).toDouble()
            val dy = (y1 - y0).toDouble()
            val dist = sqrt(dx * dx + dy * dy)
            return if (dist < EPSILON) EPSILON else dist.pow(ALPHA)
        }

        fun lerp(a: Float, b: Float, ta: Double, tb: Double, t: Double): Float {
            val span = tb - ta
            if (span < EPSILON) return a
            val u = ((t - ta) / span).coerceIn(0.0, 1.0)
            return a + (b - a) * u.toFloat()
        }
    }
}
