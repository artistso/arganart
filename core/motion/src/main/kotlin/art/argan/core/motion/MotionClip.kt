package art.argan.core.motion

/**
 * A complete, immutable motion: a path through space plus the timing character
 * of that path.
 *
 * A [MotionClip] is self-contained and sampleable: given any time `t` it can
 * report a pose without consulting a timeline, a keyframe store, or an Android
 * object. It is the value [art.argan.ai.backend.MotionBackend] returns and the
 * value P9 writes into a project manifest.
 *
 * SAMPLING CONTRACT (rule 6, hard requirement):
 * - [sampleInto] performs no allocation and takes no locks. The destination
 *   [Pose] is caller-owned and reused. This is why [MotionClip] is an interface
 *   with a concrete `sampleInto` rather than a data class returning values:
 *   returning a new [Pose] would allocate once per frame per element.
 * - [sample] is the convenience form for non-hot-path callers (tests, tooling,
 *   export). It allocates and must never be called from a draw loop.
 *
 * @property durationMs total playable length; `t` is clamped to `0..durationMs`.
 * @property isLooping whether the path wraps when t exceeds [durationMs].
 * @property segmentCount number of spline segments in the spine.
 */
public interface MotionClip {
    public val durationMs: Int
    public val isLooping: Boolean
    public val segmentCount: Int

    /**
     * Evaluates the clip at [timeMs] into [out]. Allocation-free.
     *
     * @param timeMs clamped to `0..durationMs`.
     * @param out caller-owned destination; overwritten in place.
     */
    public fun sampleInto(timeMs: Long, out: Pose)

    /** Convenience wrapper around [sampleInto]. Allocates: not for hot paths. */
    public fun sample(timeMs: Long): Pose {
        val pose = Pose()
        sampleInto(timeMs, pose)
        return pose
    }
}

/**
 * A single instantaneous state of an animated element.
 *
 * Mutable and caller-owned so that sampling can be allocation-free. Every field
 * is pre-initialised to the identity pose, so a freshly constructed [Pose] is
 * valid and reusing one never leaks a previous frame's state.
 */
public class Pose {
    /** Translation in canvas units, relative to the element's authored position. */
    public var translateX: Float = 0f
    public var translateY: Float = 0f

    /** Rotation in degrees, clockwise, about the element's anchor. */
    public var rotationDegrees: Float = 0f

    /** Uniform scale multiplier; 1f is authored size. */
    public var scale: Float = 1f

    /** Opacity multiplier `0f..1f`; 1f is authored opacity. */
    public var alpha: Float = 1f

    /** Copies [other] into this pose. */
    public fun set(other: Pose) {
        translateX = other.translateX
        translateY = other.translateY
        rotationDegrees = other.rotationDegrees
        scale = other.scale
        alpha = other.alpha
    }

    public fun reset() {
        translateX = 0f
        translateY = 0f
        rotationDegrees = 0f
        scale = 1f
        alpha = 1f
    }

    override fun toString(): String =
        "Pose(t=($translateX, $translateY), r=$rotationDegrees, s=$scale, a=$alpha)"
}

/**
 * A straight-line eased translation: the only motion that exists in P0.
 *
 * Implements the P0 deliverable "a stub procedural MotionBackend that returns a
 * simple eased translation". It is a real, tested [MotionClip] rather than a
 * placeholder so that the empty canvas can be proven to animate without any AI
 * present (rule 4: AI is additive, never load-bearing).
 *
 * @param fromX/fromY/toX/toY endpoints in canvas units.
 */
public class EasedTranslationClip(
    private val fromX: Float,
    private val fromY: Float,
    private val toX: Float,
    private val toY: Float,
    override val durationMs: Int = DEFAULT_DURATION_MS,
    private val easing: Easing = Easing.SMOOTHSTEP,
    override val isLooping: Boolean = false,
) : MotionClip {

    init {
        require(durationMs > 0) { "durationMs must be > 0, was $durationMs" }
    }

    /** One spline segment: the P0 stub has no ribbon yet (that is P2). */
    override val segmentCount: Int get() = 1

    override fun sampleInto(timeMs: Long, out: Pose) {
        val t = normalizedTime(timeMs, durationMs, isLooping)
        val eased = easing.transform(t)
        out.translateX = fromX + (toX - fromX) * eased
        out.translateY = fromY + (toY - fromY) * eased
        out.rotationDegrees = 0f
        out.scale = 1f
        out.alpha = 1f
    }

    public companion object {
        public const val DEFAULT_DURATION_MS: Int = 1_000
    }
}

/**
 * Maps linear time `0f..1f` to eased progress `0f..1f`.
 *
 * Hand-rolled rather than delegated to an animation library so that `:core:motion`
 * stays a pure-JVM, allocation-free module and so every curve is documented and
 * testable.
 */
public enum class Easing {
    LINEAR {
        override fun transform(t: Float): Float = t
    },

    /** 3t^2 - 2t^3: zero velocity at both ends, the "simple eased" default. */
    SMOOTHSTEP {
        override fun transform(t: Float): Float = t * t * (3f - 2f * t)
    },

    /** Fast departure, long settle. */
    EASE_OUT_CUBIC {
        override fun transform(t: Float): Float {
            val u = 1f - t
            return 1f - u * u * u
        }
    };

    /** @param t linear progress, clamped to `0f..1f` by the caller. */
    public abstract fun transform(t: Float): Float
}

/**
 * Normalises [timeMs] into `0f..1f` progress for a clip of [durationMs].
 *
 * Shared by every clip so looping and clamping behave identically everywhere.
 * Branch-only and allocation-free; safe in a hot loop.
 */
internal fun normalizedTime(timeMs: Long, durationMs: Int, looping: Boolean): Float {
    if (durationMs <= 0) return 0f
    if (looping) {
        var m = timeMs % durationMs
        if (m < 0) m += durationMs
        return m.toFloat() / durationMs
    }
    return when {
        timeMs <= 0L -> 0f
        timeMs >= durationMs -> 1f
        else -> timeMs.toFloat() / durationMs
    }
}
