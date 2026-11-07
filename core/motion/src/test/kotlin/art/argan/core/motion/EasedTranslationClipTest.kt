package art.argan.core.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the P0 motion contract: the stub backend's clip must be eased (not
 * linear), bounded, reusable and allocation-free when sampled into a caller-owned
 * pose.
 */
class EasedTranslationClipTest {

    private fun clip(
        durationMs: Int = 1_000,
        easing: Easing = Easing.SMOOTHSTEP,
        looping: Boolean = false,
    ) = EasedTranslationClip(
        fromX = 0f,
        fromY = 0f,
        toX = 100f,
        toY = -50f,
        durationMs = durationMs,
        easing = easing,
        isLooping = looping,
    )

    @Test
    fun `rejects non-positive duration`() {
        val failure = runCatching { clip(durationMs = 0) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected IAE, was $failure")
    }

    @Test
    fun `starts at origin and ends at the target`() {
        val c = clip()
        val start = c.sample(0)
        assertEquals(0f, start.translateX)
        assertEquals(0f, start.translateY)

        val end = c.sample(1_000)
        assertEquals(100f, end.translateX, 1e-4f)
        assertEquals(-50f, end.translateY, 1e-4f)
    }

    @Test
    fun `clamps beyond both ends`() {
        val c = clip()
        assertEquals(0f, c.sample(-5_000).translateX)
        assertEquals(100f, c.sample(99_999).translateX, 1e-4f)
    }

    @Test
    fun `smoothstep is strictly eased - halfway in time is exactly halfway in space but slower at the ends`() {
        val c = clip()
        // smoothstep(0.5) == 0.5 exactly, so the midpoint must match.
        assertEquals(50f, c.sample(500).translateX, 1e-4f)

        // ...but a quarter of the way through time covers less than a quarter of
        // the distance. This is the evidence that easing exists at all.
        val quarter = c.sample(250).translateX
        assertTrue(quarter < 25f, "expected eased start behind linear, was $quarter")
        val threeQuarters = c.sample(750).translateX
        assertTrue(threeQuarters > 75f, "expected eased end ahead of linear, was $threeQuarters")
    }

    @Test
    fun `linear easing differs from smoothstep - proves the curve is wired through`() {
        val linear = clip(easing = Easing.LINEAR).sample(250).translateX
        val smooth = clip(easing = Easing.SMOOTHSTEP).sample(250).translateX
        assertEquals(25f, linear, 1e-4f)
        assertTrue(linear > smooth, "linear=$linear should exceed smoothstep=$smooth at t=0.25")
    }

    @Test
    fun `looping wraps instead of clamping`() {
        val c = clip(durationMs = 1_000, looping = true)
        assertEquals(0f, c.sample(1_000).translateX, 1e-4f)
        assertEquals(50f, c.sample(1_500).translateX, 1e-4f)
        assertEquals(50f, c.sample(-500).translateX, 1e-4f)
    }

    @Test
    fun `sampleInto writes the caller-owned pose and never reallocates it`() {
        val c = clip()
        val pose = Pose()
        val identity = pose
        // Dirty the pose first: sampling must overwrite every field, not merge.
        pose.translateX = 9_999f
        pose.translateY = 9_999f
        pose.rotationDegrees = 45f
        pose.scale = 4f
        pose.alpha = 0f

        c.sampleInto(1_000, pose)

        assertSame(identity, pose, "sampleInto must not replace the pose instance")
        assertEquals(100f, pose.translateX, 1e-4f)
        assertEquals(-50f, pose.translateY, 1e-4f)
        assertEquals(0f, pose.rotationDegrees, "rotation must be reset, not left from before")
        assertEquals(1f, pose.scale, "scale must be reset, not left from before")
        assertEquals(1f, pose.alpha, "alpha must be reset, not left from before")
    }

    @Test
    fun `sampling is deterministic`() {
        val c = clip()
        repeat(50) { step ->
            val t = step * 20L
            assertEquals(c.sample(t).translateX, c.sample(t).translateX)
        }
    }

    @Test
    fun `pose is identity by construction and reset restores it`() {
        val pose = Pose()
        assertEquals(1f, pose.scale)
        assertEquals(1f, pose.alpha)
        pose.translateX = 5f
        pose.reset()
        assertEquals(0f, pose.translateX)
        assertEquals(1f, pose.scale)
    }

    @Test
    fun `pose set copies every field`() {
        val a = Pose()
        a.translateX = 1f
        a.translateY = 2f
        a.rotationDegrees = 3f
        a.scale = 4f
        a.alpha = 0.5f
        val b = Pose()
        b.set(a)
        assertEquals(1f, b.translateX)
        assertEquals(2f, b.translateY)
        assertEquals(3f, b.rotationDegrees)
        assertEquals(4f, b.scale)
        assertEquals(0.5f, b.alpha)
    }
}
