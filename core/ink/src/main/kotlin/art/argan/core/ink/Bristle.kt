package art.argan.core.ink

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns raw stylus samples into the points a stroke is actually drawn from.
 *
 * This is the piece that decides whether the product feels like a pen or like a
 * mouse, and it is deliberately pure Kotlin: filtering order, stabiliser behaviour
 * and the one-euro filter's response are all unit-testable on the host, with no
 * `MotionEvent` and no GPU.
 *
 * PIPELINE, in order (the order matters and is tested):
 *
 *   raw point
 *     -> one-euro filter (position smoothing, strength from BrushParams)
 *     -> leash stabiliser (optional lag with a visible radius)
 *     -> emitted point
 *
 * Predictions never enter this pipeline. They are rendered from the last emitted
 * point toward the predicted position on a separate layer, so a wrong prediction
 * cannot bend the committed curve.
 *
 * ALLOCATION: every mutable field is caller-owned. [push] fills a caller-provided
 * output array; the class allocates only when a `list` accumulator is used, and
 * that variant is documented as the non-hot path.
 */
public class StrokeBristle(
    private val brush: BrushParams,
    private val oneEuro: OneEuroFilter = OneEuroFilter(),
    private val stabilizer: LeashStabilizer = LeashStabilizer(),
) {
    /** Caller-owned pipeline state for one stroke. */
    public class State {
        public val filterX: OneEuroFilter.State = OneEuroFilter.State()
        public val filterY: OneEuroFilter.State = OneEuroFilter.State()
        public val leash: LeashStabilizer.LeashState = LeashStabilizer.LeashState()
        internal val scratch = FloatArray(2)
        public var lastEmittedX: Float = 0f
        public var lastEmittedY: Float = 0f
        public var hasEmitted: Boolean = false

        public fun reset() {
            filterX.reset()
            filterY.reset()
            leash.reset()
            lastEmittedX = 0f
            lastEmittedY = 0f
            hasEmitted = false
        }
    }

    /**
     * Processes one raw sample.
     *
     * @param samplesPerSecond used to convert the point's millisecond timestamp
     *   into the seconds the filter needs; 0 means "derive from timestamps".
     * @param out caller-owned `FloatArray(2)`; receives the point to draw.
     * @return true when [out] should be appended to the stroke.
     */
    public fun process(
        point: InputPoint,
        state: State,
        samplesPerSecond: Int,
        out: FloatArray,
    ): Boolean {
        require(out.size >= 2) { "out must have at least 2 elements" }
        require(!point.isPredicted) { "predicted points must not enter the stroke pipeline" }

        val time = seconds(point.timestampMillis, samplesPerSecond)
        val smoothedX = oneEuro
            .filter(point.x.toDouble(), time, state.filterX, brush.smoothingStrength)
            .toFloat()
        val smoothedY = oneEuro
            .filter(point.y.toDouble(), time, state.filterY, brush.smoothingStrength)
            .toFloat()

        stabilizer.update(
            smoothedX,
            smoothedY,
            brush.stabilizerStrength,
            state.leash,
            state.scratch,
        )

        out[0] = state.scratch[0]
        out[1] = state.scratch[1]
        state.lastEmittedX = out[0]
        state.lastEmittedY = out[1]
        state.hasEmitted = true
        return true
    }

    /**
     * Flushes the pipeline at the end of a stroke, writing the pen-lift point.
     *
     * WHY THIS IS NECESSARY. Smoothing and stabilising are lag filters: at the
     * moment the pen leaves the glass, the filtered position is still behind the
     * real one. Without a flush, **every stroke ends short of where the user lifted
     * the pen** — most visibly on quick flicks, where the missing tail is exactly
     * the expressive part. Drawing apps call this the pen-up flush, and it is the
     * difference between a stroke that ends where you meant and one that stops
     * early.
     *
     * The final point is emitted at the raw position with the same timestamp, so
     * the committed stroke's last point is exactly the user's last input.
     */
    public fun finish(point: InputPoint, state: State, out: FloatArray): Boolean {
        require(out.size >= 2) { "out must have at least 2 elements" }
        require(!point.isPredicted) { "predicted points must not finish a stroke" }
        out[0] = point.x
        out[1] = point.y
        state.lastEmittedX = point.x
        state.lastEmittedY = point.y
        state.hasEmitted = true
        // Reset the filter's notion of the current value so a following stroke does
        // not inherit this one's lag.
        state.filterX.filteredValue = point.x.toDouble()
        state.filterY.filteredValue = point.y.toDouble()
        state.filterX.previousRawValue = point.x.toDouble()
        state.filterY.previousRawValue = point.y.toDouble()
        state.leash.x = point.x
        state.leash.y = point.y
        return true
    }

    /**
     * Processes a whole stroke into a list, finishing with the pen-lift point.
     *
     * Convenience for tests, export and replay. NOT the hot path: the live path
     * pushes samples one at a time into a reusable buffer and calls [finish] on
     * ACTION_UP.
     */
    public fun processAll(points: List<InputPoint>, samplesPerSecond: Int = 0): List<InputPoint> {
        val state = State()
        val out = FloatArray(2)
        val result = ArrayList<InputPoint>(points.size)
        for (point in points) {
            if (!process(point, state, samplesPerSecond, out)) continue
            result += point.copy(x = out[0], y = out[1])
        }
        // Snap the endpoint: see finish() for why a stroke must not end short.
        points.lastOrNull()?.let { last ->
            if (finish(last, state, out)) {
                if (result.isNotEmpty()) {
                    result[result.lastIndex] = last.copy(x = out[0], y = out[1])
                } else {
                    result += last.copy(x = out[0], y = out[1])
                }
            }
        }
        return result
    }

    /** Current leash distance, for drawing the stabiliser ring. */
    public fun leashDistance(point: InputPoint, state: State): Float =
        stabilizer.leashDistance(point.x, point.y, state.leash)

    private fun seconds(timestampMillis: Long, samplesPerSecond: Int): Double =
        if (samplesPerSecond > 0) {
            timestampMillis.toDouble() * samplesPerSecond / 1000.0
        } else {
            timestampMillis / 1000.0
        }

    /**
     * Predicted tail geometry: where to draw a provisional segment for a predicted
     * point, starting from the last committed point.
     *
     * Predictions are drawn as a straight shot from the last real point, never fed
     * through the spline, because a predicted point that disagree with the spline's
     * curvature would visibly kink the line — and kinks are exactly what the
     * replace-don't-blend rule exists to avoid.
     */
    public fun predictedTail(
        lastRealX: Float,
        lastRealY: Float,
        predictedX: Float,
        predictedY: Float,
        out: FloatArray,
    ) {
        require(out.size >= 4) { "out must have at least 4 elements (x1, y1, x2, y2)" }
        out[0] = lastRealX
        out[1] = lastRealY
        out[2] = predictedX
        out[3] = predictedY
    }

    public companion object {
        /** Distance between two points, for spacing checks in the renderer. */
        public fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float {
            val dx = x1 - x0
            val dy = y1 - y0
            return sqrt(dx * dx + dy * dy)
        }

        /**
         * True when a new point is far enough from the last stamp to place another.
         * Spacing is a fraction of the stamp diameter, so dense brushes stay dense
         * regardless of zoom (the renderer converts to screen units).
         */
        public fun shouldStamp(
            lastX: Float,
            lastY: Float,
            x: Float,
            y: Float,
            diameter: Float,
            spacing: Float,
        ): Boolean {
            val minDistance = (diameter * spacing).coerceAtLeast(0.5f)
            return distance(lastX, lastY, x, y) >= minDistance
        }

        /** Straightness of a polyline, `0f` (a spike) to `1f` (a perfectly straight line). */
        public fun straightness(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val pathLength = distance(x0, y0, x1, y1) + distance(x1, y1, x2, y2)
            if (pathLength <= 0f) return 1f
            val chord = distance(x0, y0, x2, y2)
            return (chord / pathLength).coerceIn(0f, 1f)
        }

        /** Absolute angular deviation at the middle point, in radians `0..PI`. */
        public fun angularDeviation(
            x0: Float, y0: Float,
            x1: Float, y1: Float,
            x2: Float, y2: Float,
        ): Float {
            val ax = x1 - x0
            val ay = y1 - y0
            val bx = x2 - x1
            val by = y2 - y1
            val magA = sqrt(ax * ax + ay * ay)
            val magB = sqrt(bx * bx + by * by)
            if (magA <= 0f || magB <= 0f) return 0f
            val cos = ((ax * bx + ay * by) / (magA * magB)).coerceIn(-1f, 1f)
            return kotlin.math.acos(cos)
        }

        /** True when a point is a plausible continuation rather than an outlier. */
        public fun isPlausibleJump(
            lastX: Float,
            lastY: Float,
            x: Float,
            y: Float,
            maxJump: Float,
        ): Boolean = abs(distance(lastX, lastY, x, y)) <= maxJump
    }
}
