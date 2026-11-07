package art.argan.core.ink

import kotlin.math.abs
import kotlin.math.pow

/**
 * One-euro filter — the standard answer to stylus jitter that does not feel laggy.
 *
 * The idea: low-pass the signal, but make the cutoff frequency rise with the
 * signal's own speed. Slow movements are smoothed hard (jitter disappears); fast
 * movements pass through almost untouched (no rubber-banding). One parameter
 * controls the trade-off, which is why it is used here instead of a plain
 * exponential average.
 *
 * Reference: Casiez, Roussel, Vogel, "1€ Filter", CHI 2012.
 *
 * ALLOCATION-FREE: [filter] writes into a caller-owned [OneEuroFilter.State]. The
 * filter keeps no state of its own, so one instance serves every stroke and the
 * hot path never allocates (rule 6).
 *
 * @param minCutoff cutoff at zero speed, in Hz. Lower = smoother and laggier.
 * @param beta how aggressively the cutoff rises with speed. Higher = crisper.
 * @param derivativeCutoff cutoff applied to the speed estimate itself.
 */
public class OneEuroFilter(
    private val minCutoff: Double = 1.0,
    private val beta: Double = 0.007,
    private val derivativeCutoff: Double = 1.0,
) {
    /** Per-axis filter state. Caller-owned so filtering allocates nothing. */
    public class State {
        public var initialised: Boolean = false
        public var filteredValue: Double = 0.0
        public var filteredDerivative: Double = 0.0
        public var previousRawValue: Double = 0.0
        public var previousTimeSeconds: Double = 0.0

        public fun reset() {
            initialised = false
            filteredValue = 0.0
            filteredDerivative = 0.0
            previousRawValue = 0.0
            previousTimeSeconds = 0.0
        }
    }

    /**
     * Filters one sample.
     *
     * @param value raw input.
     * @param timeSeconds monotonically increasing timestamp.
     * @param state per-axis state, reused across samples.
     * @param strength `0f..1f` from [BrushParams.smoothingStrength]; 0 returns the
     *   raw value untouched, so "smoothing off" is exactly off rather than nearly.
     */
    public fun filter(
        value: Double,
        timeSeconds: Double,
        state: State,
        strength: Float = 1f,
    ): Double {
        if (strength <= 0f) return value
        if (!state.initialised) {
            state.initialised = true
            state.filteredValue = value
            state.previousRawValue = value
            state.previousTimeSeconds = timeSeconds
            state.filteredDerivative = 0.0
            return value
        }

        val dt = timeSeconds - state.previousTimeSeconds
        if (dt <= 0.0) return state.filteredValue // duplicate timestamp: hold previous

        // Speed estimate, itself low-passed.
        val rawDerivative = (value - state.previousRawValue) / dt
        val dAlpha = alpha(dt, derivativeCutoff)
        state.filteredDerivative += dAlpha * (rawDerivative - state.filteredDerivative)

        // Strength interpolates between a light filter (strength 0) and the base
        // minCutoff (strength 1). It must not push *below* minCutoff, or "full
        // smoothing" lags so far behind that strokes visibly fall short.
        val cutoff = minCutoff * (1.0 + (1.0 - strength.toDouble()) * 4.0) +
            beta * abs(state.filteredDerivative)
        val valueAlpha = alpha(dt, cutoff)
        state.filteredValue += valueAlpha * (value - state.filteredValue)

        state.previousRawValue = value
        state.previousTimeSeconds = timeSeconds
        return state.filteredValue
    }
}

/** Smoothing factor for a first-order low pass at [cutoffHz] over [dtSeconds]. */
private fun alpha(dtSeconds: Double, cutoffHz: Double): Double {
    val tau = 1.0 / (2.0 * Math.PI * cutoffHz)
    return 1.0 / (1.0 + tau / dtSeconds)
}

/**
 * Lag-based stroke stabiliser with a visible "leash".
 *
 * The cursor chases the pen with a spring-like lag, so the drawn line trails behind
 * the stylus by a small, visible distance. The user sees the leash ring at the pen
 * tip while the line catches up. This is how "stabilised" modes in note apps feel
 * controlled rather than mushy.
 *
 * Deliberately *lag* rather than *exponential smoothing*: an exponential average
 * shortens the stroke at corners, while a pull-with-a-max-radius model keeps the
 * stroke's shape and only delays it.
 *
 * ALLOCATION-FREE: the caller owns the [LeashState].
 *
 * @param maxLagRadius maximum distance in canvas units the tip may trail.
 */
public class LeashStabilizer(private val maxLagRadius: Float = 24f) {

    /** Per-stroke stabiliser state. Caller-owned. */
    public class LeashState {
        public var x: Float = 0f
        public var y: Float = 0f
        public var initialised: Boolean = false

        public fun reset() {
            initialised = false
            x = 0f
            y = 0f
        }
    }

    /**
     * Advances the leash toward ([inputX], [inputY]) and returns the point the
     * *stroke* should be drawn at, writing it into [out].
     *
     * @param strength `0f..1f`; 0 passes input through exactly.
     * @param out caller-owned output; must have at least 2 elements (x, y).
     * @return true when the stabiliser is active and [out] was written.
     */
    public fun update(
        inputX: Float,
        inputY: Float,
        strength: Float,
        state: LeashState,
        out: FloatArray,
    ): Boolean {
        require(out.size >= 2) { "out must have at least 2 elements" }
        if (strength <= 0f) {
            state.x = inputX
            state.y = inputY
            state.initialised = true
            out[0] = inputX
            out[1] = inputY
            return false
        }
        if (!state.initialised) {
            state.initialised = true
            state.x = inputX
            state.y = inputY
            out[0] = inputX
            out[1] = inputY
            return true
        }

        // Pull the leash toward the pen by a fraction that grows with strength.
        val pull = 0.15f + 0.55f * strength
        state.x += (inputX - state.x) * pull
        state.y += (inputY - state.y) * pull

        // Clamp the lag so a fast flick cannot leave the line metres behind.
        val dx = inputX - state.x
        val dy = inputY - state.y
        val distSq = dx * dx + dy * dy
        val maxRadius = maxLagRadius * strength
        if (distSq > maxRadius * maxRadius && distSq > 0f) {
            val scale = maxRadius / kotlin.math.sqrt(distSq)
            state.x = inputX - dx * scale
            state.y = inputY - dy * scale
        }

        out[0] = state.x
        out[1] = state.y
        return true
    }

    /** Distance the pen tip is ahead of the drawn point, for the leash ring. */
    public fun leashDistance(inputX: Float, inputY: Float, state: LeashState): Float {
        if (!state.initialised) return 0f
        val dx = inputX - state.x
        val dy = inputY - state.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

/**
 * Centripetal Catmull-Rom spline through the stroke's points.
 *
 * Centripetal parametrisation (alpha = 0.5) is chosen over uniform Catmull-Rom
 * because uniform splines form cusps and self-intersections when consecutive
 * points are unevenly spaced — which is exactly what happens at the start of a
 * stroke and during fast flicks. Centripetal never does that. It is the difference
 * between a curve that looks drawn and one that looks like a bug.
 *
 * Points are evaluated into a caller-owned buffer: no allocation per segment.
 */
public class CatmullRomSpline {

    /**
     * Evaluates the curve segment between [p1] and [p2] at [t] in `0f..1f`.
     *
     * @param p0..p3 consecutive control points; p0/p3 may equal p1/p2 at the ends.
     * @param out caller-owned `FloatArray(2)`, receives x and y.
     */
    public fun evaluate(
        p0x: Float, p0y: Float,
        p1x: Float, p1y: Float,
        p2x: Float, p2y: Float,
        p3x: Float, p3y: Float,
        t: Float,
        out: FloatArray,
    ) {
        require(out.size >= 2) { "out must have at least 2 elements" }

        // Centripetal knot spacing: t_{i+1} = t_i + |p_{i+1} - p_i|^0.5
        val t0 = 0.0
        val t1 = t0 + knotDelta(p0x, p0y, p1x, p1y)
        val t2 = t1 + knotDelta(p1x, p1y, p2x, p2y)
        val t3 = t2 + knotDelta(p2x, p2y, p3x, p3y)
        val tEval = t1 + (t2 - t1) * t.toDouble()

        // Barry-Goldman pyramidal formulation. Degenerate spans collapse to p1.
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

    public companion object {
        private const val EPSILON = 1e-6
        private const val ALPHA = 0.5

        /** Knot spacing `|d|^alpha`, guarded against coincident points. */
        private fun knotDelta(x0: Float, y0: Float, x1: Float, y1: Float): Double {
            val dx = (x1 - x0).toDouble()
            val dy = (y1 - y0).toDouble()
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            return if (dist < EPSILON) EPSILON else dist.pow(ALPHA)
        }

        /** Interpolates between a and b over [ta]..[tb] at [t]. */
        private fun lerp(a: Float, b: Float, ta: Double, tb: Double, t: Double): Float {
            val span = tb - ta
            if (span < EPSILON) return a
            val u = ((t - ta) / span).coerceIn(0.0, 1.0)
            return (a + (b - a) * u.toFloat())
        }
    }
}
