package art.argan.core.ink

/**
 * One sampled point of a stylus stroke.
 *
 * Pure Kotlin on purpose: no `android.view.MotionEvent` anywhere in `:core:ink`
 * (rule 4). The Android adapter in `:feature:canvas` converts batched and
 * predicted motion events into these.
 *
 * @property timestampMillis event time, from `MotionEvent.getEventTime()`.
 * @property x/y canvas coordinates in device-independent units.
 * @property pressure `0f..1f`; devices without pressure report 1f.
 * @property tiltX/tiltY tilt in degrees from the surface normal, `-90f..90f`.
 * @property orientation stylus rotation about its axis in degrees, `0f..360f`.
 * @property isPredicted true for points from `MotionEvent.getPredictedEvents()`.
 *   Predicted points render on their own layer and are replaced, never blended,
 *   when real points arrive.
 */
public data class InputPoint(
    val timestampMillis: Long,
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val orientation: Float = 0f,
    val isPredicted: Boolean = false,
) {
    init {
        require(pressure in 0f..1f) { "pressure must be in 0f..1f, was $pressure" }
    }

    /** Squared distance to [other]. Avoids a sqrt in hit-testing and resampling. */
    public fun distanceSquaredTo(other: InputPoint): Float {
        val dx = x - other.x
        val dy = y - other.y
        return dx * dx + dy * dy
    }
}

/** Maps raw pressure `0f..1f` onto `0f..1f` effective width/opacity. */
public enum class PressureResponse {
    LINEAR {
        override fun map(pressure: Float): Float = pressure.coerceIn(0f, 1f)
    },

    /** Smoothstep: gentle at both ends, pleasant for handwriting. */
    SOFT {
        override fun map(pressure: Float): Float {
            val p = pressure.coerceIn(0f, 1f)
            return p * p * (3f - 2f * p)
        }
    },

    /** Square root: reaches full width early, for broad marks. */
    FIRM {
        override fun map(pressure: Float): Float {
            val p = pressure.coerceIn(0f, 1f)
            return kotlin.math.sqrt(p)
        }
    };

    public abstract fun map(pressure: Float): Float
}

/**
 * Shape of a single brush imprint.
 *
 * The renderer implements this; `:core:ink` only defines what a brush must be able
 * to say about itself. Keeping the interface here (not in the UI module) is what
 * lets brush configuration be serialised and unit-tested without a device.
 */
public interface Stamp {
    /** Radius in canvas units at the given pressure, after the response curve. */
    public fun radiusAt(pressure: Float): Float

    /** Alpha `0f..1f` for one imprint at the given pressure. */
    public fun alphaAt(pressure: Float): Float

    /** True when the software fallback path can rasterise this stamp. */
    public val supportsSoftwareFallback: Boolean
}

/** Round stamp: the only brush shipped in P1. */
public class RoundStamp(private val params: BrushParams) : Stamp {
    override val supportsSoftwareFallback: Boolean get() = true

    override fun radiusAt(pressure: Float): Float {
        val effective = params.pressureResponse.map(pressure)
        // A floor of 25% of size keeps light strokes visible rather than dotted.
        val scaled = 0.25f + 0.75f * effective
        return params.size * 0.5f * scaled
    }

    override fun alphaAt(pressure: Float): Float {
        val effective = params.pressureResponse.map(pressure)
        val floor = 0.4f
        return params.opacity * (floor + (1f - floor) * effective) * params.flow
    }
}

/**
 * The brush configuration a stroke was drawn with.
 *
 * Fixed parameter set so that serialisation (P9) and the live pressure-response
 * preview have a stable contract.
 *
 * @property size diameter in canvas units at full pressure.
 * @property spacing stamp spacing as a fraction of size; smaller = denser.
 * @property hardness `0f..1f`: 1f hard edge, 0f fully feathered.
 * @property flow per-stamp alpha as a fraction of [opacity].
 * @property smoothingStrength `0f..1f` one-euro filter strength.
 * @property stabilizerStrength `0f..1f` lag-based stabiliser; 0 = off.
 */
public data class BrushParams(
    val size: Float,
    val opacity: Float = 1f,
    val spacing: Float = 0.08f,
    val hardness: Float = 0.8f,
    val flow: Float = 1f,
    val smoothingStrength: Float = 0.5f,
    val stabilizerStrength: Float = 0f,
    val pressureResponse: PressureResponse = PressureResponse.SOFT,
) {
    init {
        require(size > 0f) { "size must be > 0, was $size" }
        require(opacity in 0f..1f) { "opacity must be in 0f..1f, was $opacity" }
        require(spacing > 0f) { "spacing must be > 0, was $spacing" }
        require(hardness in 0f..1f) { "hardness must be in 0f..1f, was $hardness" }
        require(flow in 0f..1f) { "flow must be in 0f..1f, was $flow" }
        require(smoothingStrength in 0f..1f) {
            "smoothingStrength must be in 0f..1f, was $smoothingStrength"
        }
        require(stabilizerStrength in 0f..1f) {
            "stabilizerStrength must be in 0f..1f, was $stabilizerStrength"
        }
    }

    public fun stamp(): Stamp = RoundStamp(this)

    public companion object {
        /** A sensible default so a first stroke needs no settings sheet. */
        public val DEFAULT: BrushParams = BrushParams(size = 6f)
    }
}

/**
 * A committed stroke: ordered input samples plus the brush they were drawn with.
 *
 * Predicted points are rejected at construction. That is the enforcement of the P1
 * rule that prediction never contaminates committed artwork: it is not a
 * convention anyone has to remember, it is a constructor invariant.
 *
 * @property seed present from P4 onward, for reproducible generative strokes.
 */
public data class Stroke(
    val points: List<InputPoint>,
    val brush: BrushParams,
    val seed: Long = 0L,
) {
    init {
        require(points.isNotEmpty()) { "a stroke must have at least one point" }
        require(points.none { it.isPredicted }) {
            "a committed stroke must not contain predicted points; predictions are " +
                "rendered on a replaceable layer and never committed"
        }
    }

    /** Bounding box in canvas units, or null for a single point's degenerate box. */
    public fun bounds(): StrokeBounds {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val pad = brush.size * 0.5f
        return StrokeBounds(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }

    public companion object {
        /** Undo steps the history must support. */
        public const val MIN_UNDO_STEPS: Int = 100
    }
}

/** Axis-aligned bounds in canvas units. */
public data class StrokeBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    public val width: Float get() = right - left
    public val height: Float get() = bottom - top

    public fun intersects(other: StrokeBounds): Boolean =
        !(other.left > right || other.right < left || other.top > bottom || other.bottom < top)

    public fun union(other: StrokeBounds): StrokeBounds = StrokeBounds(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )
}
