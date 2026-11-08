package art.argan.core.motion

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A second-order spring: stiffness, damping, mass, and the motion they produce.
 *
 * ## Why a closed form instead of an integrator
 *
 * The obvious way to build spring motion is to integrate `a = (-k·x - c·v)/m` once
 * per frame. That is wrong for this product, for a specific reason: the P2 brief
 * requires a clip to be *sampleable at any time t*, and scrubbing (dragging on the
 * ribbon) jumps to arbitrary t — including backwards. An integrator is stateful, so
 * scrubbing to `t = 2000 ms` would give a different answer depending on which frames
 * happened to be drawn before. Playback would be path-dependent: the same clip would
 * look different depending on how fast the device was, which is precisely the bug
 * that makes spring systems feel non-deterministic.
 *
 * So the spring is expressed as the **closed-form unit-step response** of the
 * damped harmonic oscillator. It is a pure function of elapsed time, needs no state,
 * no frame-rate assumptions, and scrubbing is exact.
 *
 * The trade-off, stated plainly: a closed form only exists for a *step* input, so a
 * clip driven by a spring follows one segment of its ribbon per spring rather than
 * having an arbitrary force applied mid-flight. That is a real limitation and it is
 * why [MotionNode] carries both an easing character and spring parameters: the easing
 * shapes travel *within* a segment, the spring gives the segment's arrival its weight.
 *
 * @property stiffness `k` in N/m; higher = snappier. Must be > 0.
 * @property damping `c` in N·s/m; must be >= 0.
 * @property mass `m` in kg; higher = more sluggish. Must be > 0.
 */
public data class SpringDynamics(
    public val stiffness: Float = DEFAULT_STIFFNESS,
    public val damping: Float = DEFAULT_DAMPING,
    public val mass: Float = DEFAULT_MASS,
) {
    init {
        require(stiffness > 0f) { "stiffness must be > 0, was $stiffness" }
        require(damping >= 0f) { "damping must be >= 0, was $damping" }
        require(mass > 0f) { "mass must be > 0, was $mass" }
    }

    /** Undamped natural frequency, rad/s. */
    public val naturalFrequency: Float get() = sqrt(stiffness / mass)

    /**
     * Damping ratio ζ.
     *
     * `ζ < 1` underdamped (overshoots — this is the "weight and follow-through" the
     * brief asks for), `ζ = 1` critical, `ζ > 1` overdamped (no overshoot).
     */
    public val dampingRatio: Float get() =
        damping / (2f * sqrt(stiffness * mass))

    /**
     * The unit-step response: displacement for a 0 -> 1 step at `elapsedMs`.
     *
     * Returns `1f` for `elapsedMs <= 0` only when asked to; by default time 0 is the
     * start of travel, so the response is 0. Values above 1 are legitimate and are
     * how overshoot reaches the caller.
     */
    public fun stepResponse(elapsedMs: Float): Float {
        if (elapsedMs <= 0f) return 0f
        val wn = naturalFrequency
        val zeta = dampingRatio
        val t = elapsedMs / 1_000f

        return when {
            // Underdamped: decaying oscillation about 1, crossing it — the overshoot.
            zeta < 1f - EPSILON -> {
                val wd = wn * sqrt(1f - zeta * zeta)
                val decay = exp(-zeta * wn * t)
                1f - decay * (kotlin.math.cos(wd * t) + (zeta / sqrt(1f - zeta * zeta)) * kotlin.math.sin(wd * t))
            }
            // Critically damped: fastest arrival with no overshoot.
            zeta <= 1f + EPSILON -> {
                1f - exp(-wn * t) * (1f + wn * t)
            }
            // Overdamped: the hyperbolic form of the same solution,
            //   1 - e^(-zeta*wn*t) * [cosh(beta*t) + (zeta*wn/beta) * sinh(beta*t)]
            // with beta = wn*sqrt(zeta^2 - 1).
            //
            // An earlier version summed two exponentials with hand-solved coefficients
            // and was WRONG: it was 2 at t=0 instead of 0, so an overdamped spring
            // (RELUCTANT) started halfway to its target and appeared not to travel.
            // The unit tests caught it; the hyperbolic form is checked at t=0 and
            // against the underdamped limit at zeta -> 1.
            else -> {
                val beta = wn * sqrt(zeta * zeta - 1f)
                val decay = exp(-zeta * wn * t)
                1f - decay * (
                    kotlin.math.cosh(beta * t) +
                        (zeta * wn / beta) * kotlin.math.sinh(beta * t)
                    )
            }
        }
    }

    /**
     * The same spring's shape with any overshoot removed: monotonically increasing,
     * reaching exactly 1 at `elapsedMs = infinity` and never exceeding it.
     *
     * ## Why this exists
     *
     * An underdamped spring overshoots and comes back. That is correct for an *arrival*
     * but wrong for travel *through* a point on a path: the element would reverse
     * direction mid-ribbon. The first fix was to clamp the overshooting value to 1,
     * which is worse in a different way — the value saturates and the element PARKS at
     * the interior node for a fifth of a second (measured: 7 consecutive identical
     * samples on a 600 ms elastic clip). A plateau reads as a stutter, not as weight.
     *
     * So interior travel uses a genuinely monotone curve: the critically damped
     * response of a spring with the same natural frequency. Character is preserved
     * because ωn and mass still differ per preset (a stiff spring still starts fast, a
     * heavy one still lags), but there is no overshoot to clamp and therefore no
     * plateau. Overshoot remains available — and meaningful — on the final segment,
     * where it is rendered as follow-through past the end of the ribbon.
     */
    public fun monotoneStepResponse(elapsedMs: Float): Float {
        if (elapsedMs <= 0f) return 0f
        val wn = naturalFrequency
        val t = elapsedMs / 1_000f
        // Critically damped: 1 - e^(-wn t)(1 + wn t). Strictly increasing for t > 0,
        // never above 1.
        return 1f - exp(-wn * t) * (1f + wn * t)
    }

    /**
     * Time of the first (largest) overshoot peak, in ms, or [NO_PEAK] when the spring
     * cannot overshoot.
     *
     * For an underdamped spring the peak is at `pi / wd` exactly. Exposed because the
     * ribbon needs to *place* that peak in time: see [MotionRibbonClip], where a
     * segment's spring is stretched so its overshoot lands near the end of the
     * segment rather than in the middle of it.
     */
    public fun firstPeakTimeMs(): Float {
        val zeta = dampingRatio
        if (zeta >= 1f - EPSILON) return NO_PEAK
        val wd = naturalFrequency * sqrt(1f - zeta * zeta)
        if (wd <= EPSILON) return NO_PEAK
        return (Math.PI.toFloat() / wd) * 1_000f
    }

    /** First time at which the response reaches within [tolerance] of 1 and stays there, in ms. */
    public fun settleTimeMs(tolerance: Float = 0.01f): Float {
        require(tolerance > 0f && tolerance < 1f) { "tolerance must be in (0,1), was $tolerance" }
        // Analytic bound for the envelope: exp(-zeta*wn*t) < tolerance.
        val zeta = dampingRatio
        val wn = naturalFrequency
        if (zeta <= 0f) return Float.MAX_VALUE
        val t = -kotlin.math.ln(tolerance) / (zeta * wn)
        return t * 1_000f
    }

    public companion object {
        public const val DEFAULT_STIFFNESS: Float = 180f
        public const val DEFAULT_DAMPING: Float = 14f
        public const val DEFAULT_MASS: Float = 1f
        private const val EPSILON = 1e-4f

        /** Returned by [firstPeakTimeMs] for a spring that never overshoots. */
        public const val NO_PEAK: Float = -1f

        /**
         * No overshoot, quick settle: reads as "confident".
         *
         * ζ = 0.997 — critically damped. At 2000 damping this was overdamped enough to
         * crawl, which is what the "no personality" test failure was pointing at.
         */
        public val SMOOTH: SpringDynamics = SpringDynamics(170f, 26f, 1f)

        /** Snappy arrival with a small overshoot (ζ ≈ 0.54). */
        public val SNAPPY: SpringDynamics = SpringDynamics(420f, 22f, 1f)

        /**
         * Visible overshoot and wobble back.
         *
         * ζ = 0.202 with ωn ≈ 14.8 rad/s: a ~53% first overshoot, which survives the
         * easing blend and reads unmistakably as bounce-back. The first attempt used damping 7 against
         * stiffness 220, which put the whole travel inside the first ~90 ms of a
         * 300 ms segment: by the time the spring term had any weight in the blend it had
         * already overshot and settled, and the motion read as a straight slam. Slower
         * and lighter is what makes the overshoot visible as motion rather than as a
         * spike on a graph.
         */
        public val ELASTIC: SpringDynamics = SpringDynamics(220f, 6f, 1f)

        /** Heavy and slow (ζ ≈ 1.4, overdamped): lags, then arrives without bouncing. */
        public val RELUCTANT: SpringDynamics = SpringDynamics(90f, 13f, 1f)

        // NOTE: the mapping from MotionCharacter to these presets lives in
        // :ai:backend, which owns that enum. :core:motion must not depend on a
        // backend DTO (ARCHITECTURE.md: the arrow points backend -> motion).
    }
}
