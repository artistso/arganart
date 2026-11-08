package art.argan.core.motion

import kotlin.math.abs
import kotlin.math.atan2

/**
 * One control point of motion along a [MotionRibbonClip]'s spine.
 *
 * A node says *how* the motion behaves from this point to the next one. Everything
 * here is directly sculptable by a gesture:
 *
 * | Field | Gesture (P2 brief) |
 * | --- | --- |
 * | position on spine | drag a node to reshape its character |
 * | [speedMultiplier] | pinch a segment to compress (accelerate) or stretch (decelerate) |
 * | [spring] | the character the node was dropped with, then edited |
 * | [rotationDeltaDegrees] | twist with two fingers |
 * | [scaleDelta] | (secondary) scale follows the same twist gesture's second axis |
 *
 * @property distance along the spine, in canvas units, `0..spineLength`.
 * @property speedMultiplier relative speed through the following segment. `2` = twice
 *   as fast (a compressed segment), `0.5` = half speed. Must be > 0.
 * @property holdFraction of the following segment's duration spent holding still at
 *   this node before travelling. `0` = constant travel; `0.5` = the element pauses for
 *   half the segment. This is how a "hesitation" is sculpted without a second keyframe.
 * @property rotationDeltaDegrees added to the element's orientation as it passes.
 * @property scaleDelta added to the element's scale as it passes.
 */
public data class MotionNode(
    public val distance: Float,
    public val speedMultiplier: Float = 1f,
    public val holdFraction: Float = 0f,
    public val spring: SpringDynamics = SpringDynamics.SMOOTH,
    public val rotationDeltaDegrees: Float = 0f,
    public val scaleDelta: Float = 0f,
) {
    init {
        require(speedMultiplier > 0f) { "speedMultiplier must be > 0, was $speedMultiplier" }
        require(holdFraction in 0f..0.95f) { "holdFraction must be in 0f..0.95f, was $holdFraction" }
    }
}

/**
 * The P2 motion model: a spine plus the nodes distributed along it.
 *
 * ## No timeline
 *
 * There is no playhead, no keyframe track and no ruler, by rule. Time is *position
 * along the ribbon*: the element's progress `s` in `0..1` maps through the nodes to an
 * arc length, and the pose is whatever lives at that arc length. Scrubbing is
 * therefore indistinguishable from playing — a scrub is a jump in `t`, and nothing
 * else in the model has state.
 *
 * ## Sampling contract
 *
 * [sampleInto] allocates nothing and takes no locks (ARCHITECTURE.md rule 6). Every
 * intermediate value in the hot path — the segment lookup, the spring response, the
 * point evaluation — is computed into fields of the clip or into caller-owned arrays.
 * The clip is immutable with respect to sampling: `sampleInto` mutates only scratch
 * buffers, never semantics, so two elements sampling the same clip in the same frame
 * cannot interfere (their `Pose` destinations are separate).
 *
 * @property nodes strictly ascending by distance; must start at or after 0 and are
 *   clamped into `0..spine.length`.
 */
public class MotionRibbonClip private constructor(
    public val spine: MotionSpine,
    public val nodes: List<MotionNode>,
    override val durationMs: Int,
    override val isLooping: Boolean,
    /** Orients the element along the spine's tangent when true (default). */
    public val followPath: Boolean,
) : MotionClip {

    /** One drawable span per node-to-node gap, so the ribbon UI can render them. */
    override val segmentCount: Int get() = spine.segmentCount

    /** Number of sculptable nodes. */
    public val nodeCount: Int get() = nodes.size

    private val point = FloatArray(2)
    private val tangent = FloatArray(2)

    /** Scratch: index of the node pair the current sample falls between. */
    private var segmentIndex = 0
    private var segmentLocalT = 0f
    private var currentProgress = 0f
    private var pendingTailExcess = 0f
    private var interpolatedRotation = 0f
    private var interpolatedScale = 0f

    override fun sampleInto(timeMs: Long, out: Pose) {
        // Loop/clamp exactly as every other clip does, so a ribbon loops the way the
        // P0 stub did; the brief's 3-second motions are usually non-looping.
        val progress = normalizedTime(timeMs, durationMs, isLooping)
        currentProgress = progress
        pendingTailExcess = 0f

        // Position along the spine is the *only* notion of time here. The distance is
        // produced by walking the nodes, which is what makes pinch-to-compress work:
        // a segment with speedMultiplier 2 consumes half the time for the same length.
        val distance = distanceAt(progress)
        spine.pointAtDistance(distance, point)

        // Follow-through past the end of the ribbon.
        //
        // Distance along the spine is capped at the ends (see [distanceAt]), so the
        // element always reaches its node on time in TIME terms. The last node's spring
        // then carries it past the end of the ribbon by a decaying amount and brings it
        // back — that is the brief's "weight and follow-through", and it is the only
        // place overshoot is visible, because it is the only place it reads as arrival
        // rather than as stalling backwards mid-path.
        if (pendingTailExcess > 0f) {
            val span = (nodes[nodes.size - 1].distance - nodes[nodes.size - 2].distance)
            val limit = (spine.length * MAX_OVERSHOOT_FRACTION).coerceAtLeast(MIN_OVERSHOOT)
            // The raw excess (a few percent of the final segment's length) is amplified to
            // a fraction of the whole ribbon. Without the gain the overshoot measured
            // 0.6% of travel: technically present, invisible on screen. The brief asks
            // for "weight and follow-through" that the user can see.
            val tail = (pendingTailExcess * kotlin.math.abs(span) * TAIL_GAIN).coerceAtMost(limit)
            spine.tangentAtDistance(spine.length, tangent)
            point[0] += tangent[0] * tail
            point[1] += tangent[1] * tail
        }

        out.translateX = point[0]
        out.translateY = point[1]

        activeNode() // fills interpolatedRotation / interpolatedScale
        if (followPath) {
            spine.tangentAtDistance(distance, tangent)
            out.rotationDegrees = degreesOf(tangent[0], tangent[1]) + interpolatedRotation
        } else {
            out.rotationDegrees = interpolatedRotation
        }
        out.scale = (1f + interpolatedScale).coerceAtLeast(MIN_SCALE)
        out.alpha = 1f
    }

    /**
     * Maps normalised progress to arc length, applying per-segment speed, holds and
     * spring shaping. Allocation-free.
     */
    private fun distanceAt(progress: Float): Float {
        if (nodes.size < 2 || spine.length <= 0f) return 0f

        // Weight of each gap = length / speed, then (1 - hold) of the time budget is
        // travel and the rest is a hold at the segment's start node. Weights are
        // computed on the fly rather than cached so that editing a node mid-playback
        // takes effect on the next frame with no invalidation step.
        var totalWeight = 0f
        for (i in 0 until nodes.size - 1) {
            totalWeight += segmentWeight(i)
        }
        if (totalWeight <= 0f) return 0f

        var remaining = progress * totalWeight
        for (i in 0 until nodes.size - 1) {
            val weight = segmentWeight(i)
            // `>=` and a non-zero epsilon matter: with `>` the exact end of a segment
            // fell into the NEXT segment, whose local time was then 0, and
            // `distanceAt` returned (node + 1)'s distance before any hold had elapsed.
            // A hold placed at an interior node therefore showed the WRONG position for
            // its whole duration -- the element appeared to teleport forward at the
            // start of the hold. Caught by the canvas-size test failing by 1.2 units.
            if (i < nodes.size - 2 && remaining >= weight - WEIGHT_EPSILON) {
                remaining -= weight
                if (remaining < 0f) remaining = 0f
                continue
            }
            segmentIndex = i
            val startNode = nodes[i]
            val endNode = nodes[i + 1]

            val holdWeight = weight * startNode.holdFraction
            if (remaining <= holdWeight) {
                segmentLocalT = 0f
                return startNode.distance
            }

            val travelWeight = weight - holdWeight
            val localT = if (travelWeight <= 0f) 1f else (remaining - holdWeight) / travelWeight

            // The spring reshapes *within* the segment: it is the arrival character.
            // Shaping here rather than across the whole clip is what lets every node
            // have its own weight while remaining a pure function of time.
            //
            // The result is clamped to 0..1 because overshoot is NOT expressed here.
            // See [sampleInto]: overshoot is a property of the ribbon's END, where it
            // reads as follow-through, not of each segment. Letting every segment
            // overshoot made the element reverse direction at every interior node
            // (caught by the monotonicity test), and letting the last segment overshoot
            // internally put the element past the end and then pulled it BACK before it
            // arrived -- backwards motion the user would read as a glitch.
            val isFinal = i == nodes.size - 2
            val raw = shape(startNode, localT, overshoots = isFinal)
            // The final segment may exceed 1 (that is the follow-through); interior
            // segments use a monotone curve, so this clamp never actually bites there.
            val shaped = raw.coerceIn(0f, 1f)
            segmentLocalT = shaped
            // The last segment's excess above 1 is the follow-through: it is kept here
            // so [sampleInto] can carry the element past the ribbon's end by exactly the
            // amount the spring overflowed its own travel. Deriving it from the same
            // curve (rather than from a second clock, as an earlier draft tried) is what
            // makes it zero at the instant of arrival: `raw` is 1.0 at localT = 1 by
            // construction, because the spring term is normalised against its own value
            // at that time.
            pendingTailExcess = if (isFinal && raw > 1f) raw - 1f else 0f
            return startNode.distance + (endNode.distance - startNode.distance) * shaped
        }

        segmentIndex = nodes.size - 2
        segmentLocalT = 1f
        return nodes[nodes.size - 1].distance
    }

    /**
     * Bounds how far a spring may carry the element past either end of the ribbon.
     *
     * Proportional to the spine so it scales with the drawing, with a floor so a tiny
     * ribbon still shows its overshoot. Beyond this the pose would stop reading as
     * "the element overshot" and start reading as "the element flew away".
     */
    private fun capOvershoot(distance: Float): Float {
        val limit = (spine.length * MAX_OVERSHOOT_FRACTION).coerceAtLeast(MIN_OVERSHOOT)
        return distance.coerceIn(-limit, spine.length + limit)
    }

    /**
     * Blends easing and spring for the segment starting at [node].
     *
     * The two are combined rather than chosen between because the brief asks for
     * "spring dynamics, not just easing curves": the easing gives the shape of travel
     * (smooth/snappy), the spring gives it mass. A spring alone is always an ease-out
     * from rest; easing alone has no weight.
     *
     * The spring term is **normalised by its own value at the end of the span**. That
     * is not decoration: a spring that has not settled when the segment ends would leave
     * the element permanently short of its target — a 300 ms segment under a critically
     * damped spring reaches only ~90% of its travel, so the motion would visibly not
     * arrive. Dividing by `stepResponse(span)` makes the segment land exactly on its
     * node while preserving the overshoot shape in between.
     */
    private fun shape(node: MotionNode, localT: Float, overshoots: Boolean): Float {
        val eased = DEFAULT_EASING.transform(localT)
        // The spring is evaluated over the time this segment actually takes, not over a
        // fixed span. A fixed 1000 ms span (the first draft) meant every spring had
        // finished travelling within the first tenth of a short segment: the easing was
        // then swamped by a curve that had already arrived, and character differences
        // vanished. Scaling by real elapsed time is what makes a light spring read as
        // light and a heavy one read as heavy at any duration.
        val elapsedMs = localT * segmentDurationMs(segmentIndex)
        val spring = if (overshoots) {
            node.spring.stepResponse(elapsedMs * peakAlignment(segmentIndex)) / springEnd(segmentIndex)
        } else {
            // Monotone: no overshoot, so no clamp, so no plateau. See
            // [SpringDynamics.monotoneStepResponse] for why clamping was wrong.
            node.spring.monotoneStepResponse(elapsedMs) / monotoneEnd(segmentIndex)
        }
        return eased * EASE_MIX + spring * (1f - EASE_MIX)
    }

    /**
     * How long, in milliseconds, the user actually experiences segment [index].
     *
     * Every spring is *normalised* against this: [springEnds] is the same spring's
     * response at that time. Without the normalisation a segment shorter than the
     * spring's settle time would end at 60% of its travel, so the element would arrive
     * nowhere near its node and the whole ribbon would drift out of alignment. Dividing
     * by the end value keeps the shape (including overshoot) while guaranteeing arrival.
     */
    private fun segmentDurationMs(index: Int): Float {
        if (nodes.size < 2) return durationMs.toFloat()
        var total = 0f
        for (i in 0 until nodes.size - 1) total += segmentWeight(i)
        if (total <= 0f) return durationMs.toFloat()
        return durationMs * (segmentWeight(index) / total) * (1f - nodes[index].holdFraction)
    }

    /**
     * Time stretch applied to a segment's spring so its overshoot peak lands near the
     * end of the segment.
     *
     * This is the fix for a subtle problem: a spring's biggest overshoot happens at
     * `pi/wd`, which for a normal game-feel spring is early — a few tens of
     * milliseconds. Blended into a segment that runs for hundreds of milliseconds, the
     * overshoot lands while the easing term is still small, the blend never exceeds 1,
     * and the "elastic" character renders as a plain ease-out. Measured: peak
     * displacement on a 1 s ribbon was 100.0 for an elastic clip, i.e. no overshoot at
     * all, twice, before this.
     *
     * Stretching the spring so the peak arrives at [PEAK_POSITION] of the segment (and
     * normalising against the end of the segment, above) yields a genuine overshoot
     * before settling onto the node, at any duration.
     */
    private fun peakAlignment(index: Int): Float {
        // A stretch can legitimately be fractional (a long segment under a fast spring),
        // so NaN must be the sentinel. An earlier version tested `<= 0f`, which treated
        // a computed stretch of 0.075 as "not yet cached" and recomputed it on every
        // sample -- and, because the spring end normaliser caches separately, the two
        // disagreed. That is what left the element 12% short of the ribbon end.
        val cached = alignment[index]
        if (!cached.isNaN()) return cached
        val peakMs = nodes[index].spring.firstPeakTimeMs()
        val computed = if (peakMs <= 0f) {
            1f
        } else {
            val segmentMs = segmentDurationMs(index).coerceAtLeast(1f)
            (PEAK_POSITION * segmentMs / peakMs).coerceIn(MIN_ALIGNMENT, MAX_ALIGNMENT)
        }
        alignment[index] = computed
        return computed
    }

    private val alignment: FloatArray = FloatArray(nodes.size) { Float.NaN }

    /**
     * `springEnds[i]` is node `i`'s spring response at the end of ITS segment.
     *
     * Precomputed into a plain array rather than memoised in a map: rule 6 is about
     * allocations in the hot path, and a map with a boxed key is one. An array index is
     * not.
     */
    private val springEnds: FloatArray = FloatArray(nodes.size) { Float.NaN }

    /** `monotoneEnds[i]` is node `i`'s monotone response at the end of its segment. */
    private val monotoneEnds: FloatArray = FloatArray(nodes.size) { Float.NaN }

    private fun monotoneEnd(index: Int): Float {
        val cached = monotoneEnds[index]
        if (!cached.isNaN()) return cached
        val computed = nodes[index].spring.monotoneStepResponse(segmentDurationMs(index).coerceAtLeast(1f))
        val safe = if (computed <= EPSILON_SPRING_END) 1f else computed
        monotoneEnds[index] = safe
        return safe
    }

    private fun springEnd(index: Int): Float {
        val cached = springEnds[index]
        if (!cached.isNaN()) return cached
        // The SAME time argument the sampler uses: segment duration stretched by the
        // peak alignment. An earlier version normalised against the unstretched
        // duration while the numerator was stretched, so the two disagreed by a factor
        // of ~5 and every segment finished at 2% of its travel (measured 0.019 where 1.0
        // was required). The element then stopped short of every node in the ribbon.
        val stretchedMs = segmentDurationMs(index).coerceAtLeast(1f) * peakAlignment(index)
        val computed = nodes[index].spring.stepResponse(stretchedMs)
        val safe = if (computed <= EPSILON_SPRING_END) 1f else computed
        springEnds[index] = safe
        return safe
    }

    /**
     * Time weight of the gap starting at [index].
     *
     * `length / speed`, scaled by a *normalised* global speed. The normalisation is not
     * cosmetic: dividing every gap by the same speed factor — the first draft, which
     * multiplied every gap by 1/speed — cancels out entirely, so a "faster" clip sampled
     * identically to a slower one. That is exactly the bug the speed-multiplier test
     * caught. With the mean speed divided out, one node at 2x and everything else at 1x
     * genuinely travels its gap in half the relative time, while raising *all* speeds
     * together leaves the motion unchanged (which is correct: global speed is duration,
     * not a per-node character).
     */
    private fun segmentWeight(index: Int): Float {
        val start = nodes[index]
        val length = abs(nodes[index + 1].distance - start.distance)
        // A zero-length gap contributes almost nothing but must not vanish entirely,
        // or a node pair at identical distances would make the clip unsampleable.
        return if (length <= MIN_SEGMENT_LENGTH) ALMOST_ZERO_WEIGHT else length / (start.speedMultiplier * speedScale)
    }

    /** Mean speed multiplier across gaps; see [segmentWeight]. */
    private val speedScale: Float = if (nodes.size < 2) {
        1f
    } else {
        var sum = 0f
        for (i in 0 until nodes.size - 1) sum += nodes[i].speedMultiplier
        (sum / (nodes.size - 1)).coerceAtLeast(MIN_SPEED_SCALE)
    }

    /**
     * The node whose parameters apply at the current sample.
     *
     * Returns the *authored* node (no allocation) after writing the interpolated
     * rotation and scale deltas into [interpolatedRotation] and [interpolatedScale].
     * An earlier draft built a fresh [MotionNode] per sample, which allocated 120 times
     * a second and violated rule 6 while looking perfectly innocent.
     */
    private fun activeNode(): MotionNode {
        val i = segmentIndex.coerceIn(0, nodes.size - 1)
        val a = nodes[i]
        if (i >= nodes.size - 1) {
            interpolatedRotation = a.rotationDeltaDegrees
            interpolatedScale = a.scaleDelta
            return a
        }
        val b = nodes[i + 1]
        val t = segmentLocalT
        interpolatedRotation = a.rotationDeltaDegrees + (b.rotationDeltaDegrees - a.rotationDeltaDegrees) * t
        interpolatedScale = a.scaleDelta + (b.scaleDelta - a.scaleDelta) * t
        return a
    }

    /**
     * The same clip with one node replaced.
     *
     * Editing is a copy, not a mutation. That is what makes "editing during playback"
     * safe: the frame that is being drawn holds a reference to an immutable clip, and
     * the next frame picks up the new one. No lock, no invalidation, no tearing.
     */
    public fun withNode(index: Int, node: MotionNode): MotionRibbonClip {
        require(index in nodes.indices) { "node index $index out of bounds" }
        val updated = nodes.toMutableList()
        updated[index] = node
        return create(spine, updated.sortedBy { it.distance }, durationMs, isLooping, followPath)
    }

    /** The same clip with an extra node inserted in the correct position. */
    public fun withNodeAdded(node: MotionNode): MotionRibbonClip {
        val updated = (nodes + node).sortedBy { it.distance }
        return create(spine, updated, durationMs, isLooping, followPath)
    }

    /**
     * The same clip with node [index] removed.
     *
     * Allowed down to a single node: [create] normalises a one-node list back to a
     * two-node clip (the node plus a copy at the spine's end), so there is no state a
     * user can reach by deleting nodes that the sampler cannot handle. An earlier
     * draft refused below two and left the caller to special-case the last delete,
     * which is a rule someone has to remember rather than a property of the model.
     */
    public fun withNodeRemoved(index: Int): MotionRibbonClip {
        require(index in nodes.indices) { "node index $index out of bounds" }
        val updated = nodes.toMutableList()
        updated.removeAt(index)
        return create(spine, updated, durationMs, isLooping, followPath)
    }

    /** The same clip on a new spine, keeping node distances clamped to the new length. */
    public fun withSpine(newSpine: MotionSpine): MotionRibbonClip =
        create(newSpine, nodes, durationMs, isLooping, followPath)

    public fun withDuration(durationMs: Int): MotionRibbonClip =
        create(spine, nodes, durationMs, isLooping, followPath)

    /**
     * Degrees of the direction (x, y), 0 = pointing right, increasing clockwise on
     * screen (y grows downward in canvas units, so this matches what the user sees).
     */
    private fun degreesOf(x: Float, y: Float): Float =
        atan2(y, x) * (180f / Math.PI.toFloat())

    /** Ascending distances in `0..spine.length`, seeded with the endpoints. */
    public companion object {
        private const val MIN_SCALE = 0.01f
        private const val MIN_SEGMENT_LENGTH = 1e-3f
        private const val ALMOST_ZERO_WEIGHT = 1e-4f
        private const val EASE_MIX = 0.65f

        /**
         * Floor for the spring normaliser.
         *
         * This started at 1e-4, which was catastrophic rather than defensive: a heavy
         * spring stretched over a short segment has an end response of ~1e-4, so the
         * guard replaced the divisor with 1f and the spring term collapsed to ~0.0007.
         * The blend then fell to 0.65 and the element stopped at 88% of the ribbon
         * (`maps normalised path to canvas units` caught it: 8.77 instead of 10.0).
         * The guard must sit far below any legitimate end value, so it is now 1e-9 —
         * enough to keep the division finite against an exactly-zero response.
         */
        private const val EPSILON_SPRING_END = 1e-9f
        private const val MIN_SPEED_SCALE = 1e-3f
        private const val WEIGHT_EPSILON = 1e-6f


        /** Where in a segment the spring's overshoot peak is placed. */
        private const val PEAK_POSITION = 0.72f
        private const val MIN_ALIGNMENT = 0.05f
        private const val MAX_ALIGNMENT = 40f

        /** Amplification of the spring's residual excess into visible follow-through. */
        private const val TAIL_GAIN = 4f

        /** How far past either end of the ribbon a spring may carry the element. */
        private const val MAX_OVERSHOOT_FRACTION = 0.12f
        private const val MIN_OVERSHOOT = 4f


        internal val DEFAULT_EASING: Easing = Easing.SMOOTHSTEP

        public const val DEFAULT_DURATION_MS: Int = 3_000

        /**
         * Builds a clip, clamping and normalising the nodes.
         *
         * The node list is normalised rather than rejected so that a drag can transiently
         * put two nodes at the same distance without throwing at 120 Hz.
         */
        public fun create(
            spine: MotionSpine,
            nodes: List<MotionNode> = defaultNodes(spine),
            durationMs: Int = DEFAULT_DURATION_MS,
            isLooping: Boolean = false,
            followPath: Boolean = true,
        ): MotionRibbonClip {
            require(durationMs > 0) { "durationMs must be > 0, was $durationMs" }
            require(nodes.isNotEmpty()) { "a clip needs at least one node" }
            val clamped = nodes
                .map { it.copy(distance = it.distance.coerceIn(0f, spine.length)) }
                .sortedBy { it.distance }
            // A one-node list is normalised to a full-span clip rather than duplicated
            // in place. Duplicating at the node's own distance produced a clip where
            // every sample returned that one position — the element sat still at the
            // end of the ribbon, which is not what "I deleted nodes until one was left"
            // should mean.
            val normalised = if (clamped.size == 1) {
                listOf(clamped[0].copy(distance = 0f), clamped[0].copy(distance = spine.length))
            } else {
                clamped
            }
            return MotionRibbonClip(spine, normalised, durationMs, isLooping, followPath)
        }

        /**
         * Endpoints plus a middle node: the shape a 3-second motion starts from.
         *
         * The middle node is what makes the motion *not* a straight constant slide, and
         * it is the first thing the user can grab — see the DoD's "30 seconds with no
         * instructions".
         */
        public fun defaultNodes(spine: MotionSpine): List<MotionNode> = listOf(
            MotionNode(distance = 0f, spring = SpringDynamics.SMOOTH),
            MotionNode(distance = spine.length * 0.5f, spring = SpringDynamics.SNAPPY),
            MotionNode(distance = spine.length, spring = SpringDynamics.SMOOTH),
        )
    }
}
