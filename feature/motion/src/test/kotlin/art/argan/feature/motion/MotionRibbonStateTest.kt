package art.argan.feature.motion

import art.argan.core.motion.MotionNode
import art.argan.core.motion.MotionRibbonClip
import art.argan.core.motion.MotionSpine
import art.argan.core.motion.SpringDynamics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The ribbon's editing state: the clock, scrubbing, node selection, and reload.
 *
 * The clock is injected as a lambda so ticks are deterministic — a test that had to
 * sleep to advance time would be both slow and flaky, and this is the one place in the
 * ribbon where "when" matters at all.
 */
class MotionRibbonStateTest {

    private var now = 1_000L

    private fun state(clip: MotionRibbonClip = MotionRibbonState.defaultClip()) =
        MotionRibbonState(clip) { now }

    private fun straightSpine() = MotionSpine.of(0f, 0f, 100f, 0f, 200f, 0f)

    @Test
    fun `starts at the beginning and playing`() {
        val s = state()
        assertEquals(0L, s.timeMs)
        assertTrue(s.isPlaying)
        assertEquals(3_000, s.clip.durationMs)
    }

    @Test
    fun `tick advances time by the elapsed clock`() {
        val s = state()
        s.tick() // establishes the baseline
        now += 500
        s.tick()
        assertEquals(500L, s.timeMs)
    }

    @Test
    fun `a long stall catches up rather than jumping or rewinding`() {
        // The animation is a function of time, so after a 10-second stall in the
        // background the clip must be 10 seconds further on -- which, for a 3 s clip,
        // means finished. The first version capped the elapsed delta instead of the
        // per-step size, so a long stall advanced the clip by 64 ms and the motion
        // visibly REWOUND when the app came back to the foreground.
        val s = state()
        s.tick()
        now += 10_000
        s.tick()
        assertEquals(s.clip.durationMs.toLong(), s.timeMs)

        // A shorter stall must land proportionally, not be clamped to one step.
        val landed = state()
        landed.tick()
        now += 500
        landed.tick()
        assertEquals(500L, landed.timeMs)
    }

    @Test
    fun `paused time does not advance`() {
        val s = state()
        s.tick()
        s.togglePlayback()
        assertFalse(s.isPlaying)
        now += 1_000
        s.tick()
        assertEquals(0L, s.timeMs)
    }

    @Test
    fun `the clip stops at its end unless looping`() {
        val s = state()
        s.tick()
        now += 5_000
        s.tick()
        assertEquals(s.clip.durationMs.toLong(), s.timeMs)
    }

    @Test
    fun `scrubbing sets time and pauses playback`() {
        val s = state()
        s.tick()
        s.scrubTo(0.5f)
        assertEquals(1_500L, s.timeMs)
        assertFalse(s.isPlaying, "scrubbing must pause, or the next tick would fight the drag")
    }

    @Test
    fun `scrubbing is clamped to the clip`() {
        val s = state()
        s.scrubTo(-3f)
        assertEquals(0L, s.timeMs)
        s.scrubTo(9f)
        assertEquals(s.clip.durationMs.toLong(), s.timeMs)
    }

    @Test
    fun `scrubbing by distance maps to position along the spine`() {
        val spine = straightSpine()
        val clip = MotionRibbonClip.create(spine, durationMs = 2_000)
        val s = state(clip)
        s.scrubToDistance(spine.length / 2f)
        // Halfway along the spine is halfway through the clip, within rounding: the
        // tolerance is in milliseconds, and Long milliseconds have no fractional part.
        assertTrue(
            kotlin.math.abs(s.timeMs - 1_000L) <= 5L,
            "expected ~1000ms, was ${s.timeMs}ms",
        )
    }

    @Test
    fun `pose follows time, so scrubbing shows the frame it scrubbed to`() {
        val spine = straightSpine()
        val clip = MotionRibbonClip.create(spine, durationMs = 1_000, followPath = false)
        val s = state(clip)

        s.scrubTo(1f)
        s.updatePose()
        val atEnd = s.pose.translateX

        s.scrubTo(0f)
        s.updatePose()
        val atStart = s.pose.translateX

        assertTrue(atEnd > atStart + 100f, "scrub to the end should be far along the spine")
    }

    @Test
    fun `selecting a node then editing replaces the clip and leaves the old one intact`() {
        val s = state()
        val before = s.clip
        s.selectNode(1)
        s.updateSelectedNode { it.copy(spring = SpringDynamics.ELASTIC) }

        assertNotEquals(before, s.clip)
        assertEquals(SpringDynamics.ELASTIC, s.clip.nodes[1].spring)
        // The pre-edit clip is untouched: edits are copies, which is what makes editing
        // during playback safe.
        assertEquals(SpringDynamics.SNAPPY, before.nodes[1].spring)
    }

    @Test
    fun `selecting out of range deselects rather than throwing`() {
        val s = state()
        s.selectNode(99)
        assertEquals(-1, s.selectedNode)
        // Editing with nothing selected must be a no-op, not a crash.
        s.updateSelectedNode { it.copy(speedMultiplier = 2f) }
        assertEquals(1f, s.clip.nodes[0].speedMultiplier)
    }

    @Test
    fun `adding a node grows the clip and keeps order`() {
        val spine = straightSpine()
        val clip = MotionRibbonClip.create(spine)
        val s = state(clip)
        s.addNodeAt(120f)
        assertEquals(4, s.clip.nodeCount)
        assertEquals(s.clip.nodes.map { it.distance }.sorted(), s.clip.nodes.map { it.distance })
    }

    @Test
    fun `moving a spine point reshapes the path`() {
        val spine = straightSpine()
        val s = state(MotionRibbonClip.create(spine))
        val before = s.clip.spine.length
        s.moveSpinePoint(1, 100f, 80f)
        assertNotEquals(before, s.clip.spine.length)
        assertTrue(s.clip.spine.length > before, "bending the control point should lengthen the path")
    }

    @Test
    fun `moving a spine point out of range is ignored`() {
        val s = state()
        val before = s.clip
        s.moveSpinePoint(99, 0f, 0f)
        assertEquals(before, s.clip)
    }

    @Test
    fun `a snapshot round trip restores an identical clip`() {
        val s = state()
        s.selectNode(1)
        s.updateSelectedNode {
            it.copy(speedMultiplier = 1.8f, holdFraction = 0.2f, rotationDeltaDegrees = 25f)
        }
        val snapshot = s.snapshot()

        val restored = state(MotionRibbonClip.create(MotionSpine.of(0f, 0f, 1f, 1f)))
        restored.restore(snapshot)

        assertEquals(s.clip.nodes, restored.clip.nodes)
        assertEquals(s.clip.durationMs, restored.clip.durationMs)
        s.scrubTo(0.4f)
        restored.scrubTo(0.4f)
        s.updatePose()
        restored.updatePose()
        assertEquals(s.pose.translateX, restored.pose.translateX, 1e-3f)
        assertEquals(s.pose.translateY, restored.pose.translateY, 1e-3f)
    }

    @Test
    fun `replacing a clip clamps the current time into it`() {
        val s = state()
        s.scrubTo(1f)
        val shorter = MotionRibbonClip.create(straightSpine(), durationMs = 400)
        s.replaceClip(shorter)
        assertTrue(s.timeMs <= 400L, "time ${s.timeMs} must be clamped to the new clip")
    }

    @Test
    fun `the default clip is a real, sampleable three second motion`() {
        val clip = MotionRibbonState.defaultClip()
        assertEquals(3_000, clip.durationMs)
        assertTrue(clip.nodes.size >= 3)
        assertFalse(clip.spine.isDegenerate)
        // It must actually move: a default clip whose element sits still is a bug the
        // user would see on first launch.
        val start = clip.sample(0)
        val end = clip.sample(clip.durationMs.toLong())
        assertTrue(
            kotlin.math.abs(end.translateX - start.translateX) > 100f,
            "default clip barely moves: ${start.translateX} -> ${end.translateX}",
        )
    }

    @Test
    fun `nodes are individually editable without disturbing their neighbours`() {
        val s = state()
        val original = s.clip.nodes.toList()
        s.selectNode(1)
        s.updateSelectedNode { it.copy(holdFraction = 0.3f) }
        assertEquals(original[0], s.clip.nodes[0])
        assertEquals(0.3f, s.clip.nodes[1].holdFraction)
        assertEquals(original[2], s.clip.nodes[2])
    }

    @Test
    fun `every preset in the inspector is usable`() {
        // The inspector offers exactly these four; each must be a node the sampler accepts.
        val presets = listOf(
            SpringDynamics.SMOOTH,
            SpringDynamics.SNAPPY,
            SpringDynamics.ELASTIC,
            SpringDynamics.RELUCTANT,
        )
        val s = state()
        for (preset in presets) {
            s.selectNode(1)
            s.updateSelectedNode { it.copy(spring = preset) }
            s.scrubTo(0.37f)
            s.updatePose()
            assertFalse(s.pose.translateX.isNaN(), "preset $preset produced NaN")
        }
    }

    @Test
    fun `a node copied from one clip keeps its character`() {
        val node = MotionNode(distance = 30f, speedMultiplier = 1.4f, spring = SpringDynamics.ELASTIC)
        val spine = straightSpine()
        val clip = MotionRibbonClip.create(spine, nodes = listOf(
            MotionNode(0f), node, MotionNode(spine.length),
        ))
        assertEquals(SpringDynamics.ELASTIC, clip.nodes[1].spring)
        assertEquals(1.4f, clip.nodes[1].speedMultiplier)
    }
}
