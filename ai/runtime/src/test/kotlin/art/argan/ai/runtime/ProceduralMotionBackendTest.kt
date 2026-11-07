package art.argan.ai.runtime

import art.argan.ai.backend.MotionCharacter
import art.argan.ai.backend.MotionPath
import art.argan.ai.backend.MotionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The procedural backend is the permanent fallback (P8), so its P0 behaviour is
 * pinned now: responsive within any positive budget, eased rather than linear,
 * bounded by the spec, and deterministic.
 */
class ProceduralMotionBackendTest {

    private val backend = ProceduralMotionBackend(canvasWidth = 100f, canvasHeight = 200f)

    private fun spec(
        h: Float = 0.5f,
        v: Float = 0.25f,
        character: MotionCharacter = MotionCharacter.SMOOTH,
        durationMs: Int = 600,
    ) = MotionSpec(
        path = MotionPath(horizontal = h, vertical = v),
        character = character,
        durationMs = durationMs,
    )

    @Test
    fun `returns a clip inside the requested budget`() {
        // The stub does O(1) work, so this is really asserting it does not block:
        // the elapsed time must be far inside even a tight per-frame budget.
        val startNanos = System.nanoTime()
        val clip = backend.synthesize(spec(), budgetMs = 2)
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0

        assertEquals(600, clip.durationMs)
        assertTrue(elapsedMs < 2.0, "synthesize took ${elapsedMs}ms, budget was 2ms")
    }

    @Test
    fun `maps normalised path to canvas units`() {
        val clip = backend.synthesize(spec(h = 0.5f, v = 0.25f), budgetMs = 8)
        val end = clip.sample(clip.durationMs.toLong())
        assertEquals(50f, end.translateX, 1e-3f)
        assertEquals(50f, end.translateY, 1e-3f)
    }

    @Test
    fun `is eased, not linear`() {
        val clip = backend.synthesize(spec(h = 1f, v = 0f), budgetMs = 8)
        val quarter = clip.sample(150).translateX // t = 0.25 of 600ms
        assertTrue(quarter < 25f, "expected smoothstep to lag linear at t=0.25, was $quarter")
    }

    @Test
    fun `character selects a distinguishable curve`() {
        val snappy = backend.synthesize(spec(character = MotionCharacter.SNAPPY), budgetMs = 8)
        val reluctant = backend.synthesize(spec(character = MotionCharacter.RELUCTANT), budgetMs = 8)
        // At 25% of the timeline, ease-out has moved further than linear.
        assertTrue(snappy.sample(150).translateX > reluctant.sample(150).translateX)
    }

    @Test
    fun `is deterministic for identical specs`() {
        val a = backend.synthesize(spec(), budgetMs = 8)
        val b = backend.synthesize(spec(), budgetMs = 8)
        for (t in listOf(0L, 100L, 333L, 600L)) {
            assertEquals(a.sample(t).translateX, b.sample(t).translateX)
            assertEquals(a.sample(t).translateY, b.sample(t).translateY)
        }
    }

    @Test
    fun `rejects a negative budget and bad canvas size`() {
        assertTrue(runCatching { backend.synthesize(spec(), budgetMs = -1) }.isFailure)
        assertTrue(runCatching { ProceduralMotionBackend(canvasWidth = 0f) }.isFailure)
    }
}
