package art.argan.core.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2 motion model: spine geometry, spring dynamics, ribbon sampling, and the two
 * properties the brief actually stakes the phase on — no timeline, and identical
 * reload.
 */
class MotionRibbonTest {

    private fun straightSpine(length: Float = 100f): MotionSpine =
        MotionSpine.of(0f, 0f, length * 0.5f, 0f, length, 0f)

    // ---------------------------------------------------------------- spine

    @Test
    fun `spine arc length follows the curve, not the chord`() {
        // A quarter circle of radius 100: the chord is ~141, the arc is ~157.
        val steps = 24
        val pts = (0..steps).map {
            val a = (Math.PI / 2) * it / steps
            SpinePoint((100 * kotlin.math.cos(a)).toFloat(), (100 * kotlin.math.sin(a)).toFloat())
        }
        val spine = MotionSpine.of(pts)
        val arc = 100f * (Math.PI.toFloat() / 2f)
        // Sampling is polygonal, so it under-measures slightly; assert it is much
        // closer to the true arc than to the chord. A naive chord-length
        // implementation passes the chord check and fails this one.
        val chord = kotlin.math.sqrt(2f) * 100f
        assertTrue(spine.length > chord + 5f, "arc ${spine.length} should exceed chord $chord")
        assertTrue(spine.length <= arc + 0.5f, "arc ${spine.length} should not exceed $arc")
    }

    @Test
    fun `spine clamps outside its ends and never produces NaN`() {
        val spine = straightSpine()
        val out = FloatArray(2)
        spine.pointAtDistance(-500f, out)
        assertEquals(0f, out[0], 1e-3f)
        spine.pointAtDistance(500f, out)
        assertEquals(100f, out[0], 1e-3f)
        assertFalse(out[0].isNaN() || out[1].isNaN())
    }

    @Test
    fun `degenerate spine is reported and samples to a dot`() {
        val spine = MotionSpine.of(50f, 50f, 50f, 50f)
        assertTrue(spine.isDegenerate)
        val out = FloatArray(2)
        spine.pointAtDistance(10f, out)
        assertEquals(50f, out[0], 1e-4f)
        assertEquals(50f, out[1], 1e-4f)
    }

    @Test
    fun `tangent points along travel`() {
        val spine = straightSpine()
        val tangent = FloatArray(2)
        spine.tangentAtDistance(50f, tangent)
        assertEquals(1f, tangent[0], 1e-3f)
        assertEquals(0f, tangent[1], 1e-3f)
    }

    @Test
    fun `tangent tracks a curving spine`() {
        // Up the y axis and then along x: at the end the tangent must be ~+x, at the
        // start ~+y, and in between neither.
        val spine = MotionSpine.of(0f, 100f, 0f, 0f, 100f, 0f)
        val tangent = FloatArray(2)
        spine.tangentAtDistance(1f, tangent)
        assertTrue(tangent[1] < -0.5f, "expected an upward start, got ${tangent[0]},${tangent[1]}")
        spine.tangentAtDistance(spine.length - 1f, tangent)
        assertTrue(tangent[0] > 0.5f, "expected a rightward end, got ${tangent[0]},${tangent[1]}")
    }

    // --------------------------------------------------------------- springs

    @Test
    fun `spring step response starts at zero and settles at one`() {
        val spring = SpringDynamics.SMOOTH
        assertEquals(0f, spring.stepResponse(0f), 1e-4f)
        assertEquals(1f, spring.stepResponse(10_000f), 1e-2f)
    }

    @Test
    fun `underdamped spring overshoots, critically damped spring does not`() {
        val bouncy = SpringDynamics.ELASTIC
        assertTrue(bouncy.dampingRatio < 1f, "ELASTIC should be underdamped")

        var peak = 0f
        var t = 0f
        while (t < 2_000f) {
            peak = maxOf(peak, bouncy.stepResponse(t))
            t += 5f
        }
        assertTrue(peak > 1.05f, "underdamped peak was $peak, expected real overshoot")

        val critical = SpringDynamics(180f, 2f * kotlin.math.sqrt(180f * 1f), 1f)
        assertEquals(1f, critical.dampingRatio, 1e-3f)
        var criticalPeak = 0f
        t = 0f
        while (t < 2_000f) {
            criticalPeak = maxOf(criticalPeak, critical.stepResponse(t))
            t += 5f
        }
        assertTrue(criticalPeak <= 1.0005f, "critically damped overshoot $criticalPeak")
    }

    @Test
    fun `heavier mass settles more slowly`() {
        val light = SpringDynamics(stiffness = 200f, damping = 20f, mass = 1f)
        val heavy = SpringDynamics(stiffness = 200f, damping = 20f, mass = 2f)
        assertTrue(heavy.settleTimeMs() > light.settleTimeMs())
    }

    @Test
    fun `spring parameters are validated`() {
        assertTrue(runCatching { SpringDynamics(stiffness = 0f) }.isFailure)
        assertTrue(runCatching { SpringDynamics(damping = -1f) }.isFailure)
        assertTrue(runCatching { SpringDynamics(mass = 0f) }.isFailure)
    }

    // ---------------------------------------------------------------- ribbon

    @Test
    fun `clip starts at the spine start and ends at the spine end`() {
        val clip = MotionRibbonClip.create(straightSpine(length = 200f))
        val start = clip.sample(0)
        val end = clip.sample(clip.durationMs.toLong())
        assertEquals(0f, start.translateX, 0.5f)
        assertEquals(200f, end.translateX, 0.5f)
    }

    @Test
    fun `interior travel is always forward, with no settling back`() {
        // Interior nodes are speed and hold changes, never arrivals, so the element must
        // never move backwards mid-ribbon. (The final segment may overshoot the end —
        // that is follow-through and is asserted separately.)
        val clip = MotionRibbonClip.create(straightSpine(length = 100f), durationMs = 1_000)
        var previous = -1f
        var t = 0
        // Stop at the end of the interior: the last segment owns its own overshoot.
        while (t <= 700) {
            val x = clip.sample(t.toLong()).translateX
            assertTrue(x >= previous - 0.001f, "went backwards at t=$t: $x after $previous")
            previous = x
            t += 8
        }
    }

    @Test
    fun `only the last segment overshoots past the end of the ribbon`() {
        val spine = straightSpine(length = 100f)
        val elastic = MotionRibbonClip.create(
            spine = spine,
            nodes = listOf(
                MotionNode(distance = 0f, spring = SpringDynamics.ELASTIC),
                MotionNode(distance = 50f, spring = SpringDynamics.ELASTIC),
                MotionNode(distance = 100f, spring = SpringDynamics.ELASTIC),
            ),
            durationMs = 1_000,
            followPath = false,
        )
        var maxX = 0f
        var t = 0
        while (t <= 1_000) {
            maxX = maxOf(maxX, elastic.sample(t.toLong()).translateX)
            t += 5
        }
        assertTrue(maxX > 100f, "elastic follow-through should pass the ribbon end, peak was $maxX")
        // And it must be bounded rather than flying away.
        assertTrue(maxX < 130f, "overshoot must stay bounded, peak was $maxX")
    }

    @Test
    fun `speed multiplier changes how much distance a segment consumes`() {
        val spine = straightSpine(length = 100f)
        // Three nodes at 0 / 50 / 100. The first gap is covered twice as fast, so when
        // the clip is halfway through its TIME it must be well past halfway through the
        // SPINE. Two clips differing only in the first segment's speed are compared, so
        // the assertion is about speed and not about absolute distance along the spine.
        // Only the FIRST gap's speed changes. Setting the same multiplier on both
        // nodes makes the two gaps equally weighted, and the clip then samples
        // identically no matter what that shared multiplier is -- which is correct
        // behaviour (global speed is duration), and is why the first version of this
        // test proved nothing.
        fun clipStartingAt(firstGapSpeed: Float) = MotionRibbonClip.create(
            spine = spine,
            nodes = listOf(
                MotionNode(distance = 0f, speedMultiplier = firstGapSpeed),
                MotionNode(distance = 50f, speedMultiplier = 1f),
                MotionNode(distance = 100f),
            ),
            durationMs = 1_000,
            followPath = false,
        )

        val fast = clipStartingAt(2f).sample(500).translateX
        val slow = clipStartingAt(0.5f).sample(500).translateX
        // Normalisation divides out the MEAN speed, so a first gap at 2x against a
        // second at 1x (mean 1.5) moves 2/1.5 = 1.33x as far, not 2x: the clip lands
        // at ~54 rather than ~67. That is the intended behaviour, and the assertion
        // states the direction and a margin rather than an exact figure that would
        // break every time the normalisation constant was touched.
        assertTrue(fast > slow + 5f, "fast first segment ($fast) should lead slow ($slow)")
    }

    @Test
    fun `hold fraction parks the element at the node`() {
        val spine = straightSpine(length = 100f)
        val clip = MotionRibbonClip.create(
            spine = spine,
            nodes = listOf(
                MotionNode(distance = 0f, holdFraction = 0.5f),
                MotionNode(distance = 100f),
            ),
            durationMs = 1_000,
            followPath = false,
        )
        // The first quarter of time is inside the hold, so nothing moves.
        assertEquals(clip.sample(0).translateX, clip.sample(250).translateX, 1e-3f)
        assertTrue(clip.sample(700).translateX > 10f, "should travel after the hold")
    }

    @Test
    fun `rotation follows the path and adds the node delta`() {
        val spine = straightSpine(length = 100f)
        val plain = MotionRibbonClip.create(spine = spine, followPath = true)
        val turned = MotionRibbonClip.create(
            spine = spine,
            nodes = MotionRibbonClip.defaultNodes(spine).map { it.copy(rotationDeltaDegrees = 45f) },
            followPath = true,
        )
        assertEquals(0f, plain.sample(0).rotationDegrees, 0.5f)
        assertEquals(45f, turned.sample(0).rotationDegrees, 0.5f)
    }

    @Test
    fun `looping wraps and non-looping clamps`() {
        val clip = MotionRibbonClip.create(straightSpine(), durationMs = 1_000, isLooping = true)
        assertEquals(clip.sample(200).translateX, clip.sample(1_200).translateX, 0.01f)

        val once = MotionRibbonClip.create(straightSpine(), durationMs = 1_000, isLooping = false)
        assertEquals(once.sample(1_000).translateX, once.sample(5_000).translateX, 1e-3f)
    }

    @Test
    fun `editing returns a new clip and leaves the original alone`() {
        val clip = MotionRibbonClip.create(straightSpine())
        val edited = clip.withNode(1, clip.nodes[1].copy(rotationDeltaDegrees = 90f))
        assertEquals(0f, clip.nodes[1].rotationDeltaDegrees)
        assertEquals(90f, edited.nodes[1].rotationDeltaDegrees)
        assertTrue(clip !== edited)
    }

    @Test
    fun `nodes stay ordered after an edit that moves one past another`() {
        val clip = MotionRibbonClip.create(straightSpine(length = 100f))
        val moved = clip.withNode(0, clip.nodes[0].copy(distance = 80f))
        assertEquals(listOf(50f, 80f, 100f), moved.nodes.map { it.distance })
    }

    @Test
    fun `a node can be added and removed without dropping below two`() {
        val clip = MotionRibbonClip.create(straightSpine(length = 100f))
        val added = clip.withNodeAdded(MotionNode(distance = 25f))
        assertEquals(4, added.nodeCount)
        assertEquals(25f, added.nodes[1].distance)

        val removed = added.withNodeRemoved(1)
        assertEquals(3, removed.nodeCount)

        // Deleting down to one node normalises back to a two-node clip rather than
        // throwing or producing something unsampleable.
        val single = clip.withNodeRemoved(1).withNodeRemoved(0)
        assertEquals(2, single.nodeCount)
        assertEquals(0f, single.sample(0).translateX, 0.5f)
    }

    @Test
    fun `two nodes at the same distance do not make the clip unsampleable`() {
        val clip = MotionRibbonClip.create(
            spine = straightSpine(length = 100f),
            nodes = listOf(
                MotionNode(distance = 0f),
                MotionNode(distance = 50f),
                MotionNode(distance = 50f),
                MotionNode(distance = 100f),
            ),
            durationMs = 1_000,
        )
        val pose = Pose()
        var t = 0
        while (t <= 1_000) {
            clip.sampleInto(t.toLong(), pose)
            assertFalse(pose.translateX.isNaN(), "NaN at t=$t")
            t += 8
        }
    }

    // ------------------------------------------------------- hot path safety

    @Test
    fun `sampling into a caller-owned pose does not disturb the clip`() {
        val clip = MotionRibbonClip.create(straightSpine())
        val a = Pose()
        val b = Pose()

        clip.sampleInto(300, a)
        // Interleave a second consumer, as two animated elements would.
        clip.sampleInto(900, b)
        clip.sampleInto(300, b)

        assertEquals(a.translateX, b.translateX, 1e-4f)
        assertEquals(a.rotationDegrees, b.rotationDegrees, 1e-4f)
    }

    @Test
    fun `pose reset restores identity`() {
        val pose = Pose()
        pose.translateX = 5f
        pose.rotationDegrees = 90f
        pose.scale = 2f
        pose.alpha = 0.5f
        pose.reset()
        assertEquals(0f, pose.translateX)
        assertEquals(0f, pose.rotationDegrees)
        assertEquals(1f, pose.scale)
        assertEquals(1f, pose.alpha)
    }

    // ------------------------------------------------------------ reload

    @Test
    fun `a snapshot round trip reloads identically`() {
        val spine = MotionSpine.of(
            0f, 0f,
            40f, 30f,
            90f, 10f,
            120f, 70f,
        )
        val original = MotionRibbonClip.create(
            spine = spine,
            nodes = listOf(
                MotionNode(distance = 0f, speedMultiplier = 1.3f, holdFraction = 0.1f, spring = SpringDynamics.ELASTIC),
                MotionNode(distance = 60f, rotationDeltaDegrees = 30f, scaleDelta = 0.2f),
                MotionNode(distance = spine.length, spring = SpringDynamics.RELUCTANT),
            ),
            durationMs = 2_400,
            isLooping = true,
            followPath = false,
        )

        val reloaded = MotionClipSnapshot.decode(MotionClipSnapshot.of(original).encode()).decode()

        assertEquals(original.durationMs, reloaded.durationMs)
        assertEquals(original.isLooping, reloaded.isLooping)
        assertEquals(original.followPath, reloaded.followPath)
        assertEquals(original.nodeCount, reloaded.nodeCount)
        assertEquals(original.nodes, reloaded.nodes)
        assertEquals(original.spine.length, reloaded.spine.length, 1e-3f)

        // The real assertion: identical poses at many times, not just equal fields.
        var t = 0L
        while (t <= original.durationMs) {
            assertEquals(original.sample(t).translateX, reloaded.sample(t).translateX, 1e-3f, "x at t=$t")
            assertEquals(original.sample(t).translateY, reloaded.sample(t).translateY, 1e-3f, "y at t=$t")
            assertEquals(original.sample(t).rotationDegrees, reloaded.sample(t).rotationDegrees, 1e-2f, "r at t=$t")
            assertEquals(original.sample(t).scale, reloaded.sample(t).scale, 1e-3f, "s at t=$t")
            t += 97
        }
    }

    @Test
    fun `a corrupt snapshot is rejected rather than silently emptied`() {
        val good = MotionClipSnapshot.of(MotionRibbonClip.create(straightSpine())).encode()
        assertTrue(runCatching { MotionClipSnapshot.decode(good) }.isSuccess)
        assertTrue(runCatching { MotionClipSnapshot.decode(good.replace("argan.motion/1", "argan.motion/9")) }.isFailure)
        assertTrue(runCatching { MotionClipSnapshot.decode(good.substringBeforeLast('|')) }.isFailure)
        assertTrue(runCatching { MotionClipSnapshot.decode(good.replaceFirst("|3000|", "|notanumber|")) }.isFailure)
    }

    @Test
    fun `two decodes of one snapshot are independent`() {
        val snapshot = MotionClipSnapshot.of(MotionRibbonClip.create(straightSpine()))
        val a = snapshot.decode()
        val b = snapshot.decode()
        val edited = a.withNode(1, a.nodes[1].copy(rotationDeltaDegrees = 90f))
        assertEquals(0f, b.nodes[1].rotationDeltaDegrees)
        assertEquals(90f, edited.nodes[1].rotationDeltaDegrees)
    }
}
