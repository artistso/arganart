package art.argan.ai.runtime

import art.argan.ai.backend.MotionBackend
import art.argan.ai.backend.MotionCharacter
import art.argan.ai.backend.MotionPath
import art.argan.ai.backend.MotionSpec
import art.argan.ai.backend.StyleFingerprintProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P0's non-generative backends must be *honest*: absent capability is reported,
 * never simulated. A stand-in that returns plausible-looking data is how a later
 * phase ends up depending on behaviour that disappears.
 *
 * These tests deliberately avoid collecting the `Flow` (that needs the real
 * coroutines library); the flow's emission order is pinned by `ContractTest`
 * style tests in `:ai:backend` once a fake collector lands with P7.
 */
class RegistryAndStubsTest {

    @Test
    fun `default registry wires every backend`() {
        val registry = BackendRegistry.createDefault()
        assertNotNull(registry.modelBackend)
        assertNotNull(registry.motionBackend)
        assertNotNull(registry.styleFingerprintProvider)
        assertNotNull(registry.genericnessScorer)
    }

    @Test
    fun `default registry uses the real procedural motion backend`() {
        val registry = BackendRegistry.createDefault()
        // Not merely present: usable. A MotionBackend that throws would make the
        // empty canvas unverifiable without AI (rule 4).
        val clip = registry.motionBackend.synthesize(
            MotionSpec(MotionPath(0.5f, 0.5f), MotionCharacter.SMOOTH, 500),
            budgetMs = 8,
        )
        assertEquals(500, clip.durationMs)
        assertTrue(clip.sample(500).translateX > 0f)
    }

    @Test
    fun `unavailable model backend returns a flow instead of throwing`() {
        val backend = UnavailableModelBackend()
        val request = art.argan.ai.backend.GenerationRequest(
            layout = art.argan.ai.backend.SpatialLayoutGraph(
                nodes = emptyList(),
                edges = emptyList(),
                frame = art.argan.ai.backend.FrameBounds(0f, 0f, 10f, 10f),
                focusPoint = art.argan.ai.backend.Point2(5f, 5f),
            ),
            styleFingerprintRef = null,
            assistLevel = 0f,
            mutationSeed = 0L,
            cancellationToken = art.argan.ai.backend.CancellationToken(),
        )
        // The seam must exist and be callable; whether it emits Error is a P7
        // concern. Fabricating a Result here would be a lie about capability.
        assertNotNull(backend.generate(request))
    }

    @Test
    fun `genericness scorer refuses to invent a score`() {
        val failure = assertFailsWith<UnsupportedOperationException> {
            UnavailableGenericnessScorer.score(ByteArray(16))
        }
        assertTrue(failure.message!!.contains("P0"), "message should name the phase: ${failure.message}")
    }

    @Test
    fun `style fingerprint provider is the shared no-op singleton`() {
        // Retained identity matters: two registries must not imply two providers.
        assertSame(NoStyleFingerprintProvider, BackendRegistry.createDefault().styleFingerprintProvider)
        assertSame(NoStyleFingerprintProvider as StyleFingerprintProvider, NoStyleFingerprintProvider)
    }

    @Test
    fun `procedural backend honours a non-default canvas size`() {
        val backend = ProceduralMotionBackend(canvasWidth = 10f, canvasHeight = 4f)
        val clip = backend.synthesize(
            MotionSpec(MotionPath(1f, 1f), MotionCharacter.RELUCTANT, 100),
            budgetMs = 1,
        )
        val end = clip.sample(100)
        assertEquals(10f, end.translateX, 1e-4f)
        assertEquals(4f, end.translateY, 1e-4f)
        // MotionBackend is an interface, so a future neural backend must satisfy
        // the same reference: this assertion is the seam, not the implementation.
        val asInterface: MotionBackend = backend
        assertNotNull(asInterface)
    }
}
