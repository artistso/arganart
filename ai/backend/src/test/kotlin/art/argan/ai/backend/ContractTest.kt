package art.argan.ai.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The backend contract must be testable without a device, a model or a GPU
 * (guardrail: "are the new public interfaces testable and tested"). These tests
 * exercise every DTO's invariants and the cache-key semantics that P5/P7 rely on.
 */
class ContractTest {

    private fun node(id: String, left: Float = 0f) = SpatialNode(
        id = id,
        kind = SpatialNodeKind.SKETCH,
        bounds = FrameBounds(left, 0f, left + 10f, 10f),
    )

    private fun layout(
        nodes: List<SpatialNode> = listOf(node("a")),
        edges: List<SpatialEdge> = emptyList(),
    ) = SpatialLayoutGraph(
        nodes = nodes,
        edges = edges,
        frame = FrameBounds(0f, 0f, 100f, 100f),
        focusPoint = Point2(50f, 50f),
    )

    private fun request(
        assistLevel: Float = 0.5f,
        seed: Long = 42L,
        ref: StyleFingerprintRef? = null,
        token: CancellationToken = CancellationToken(),
        layout: SpatialLayoutGraph = layout(),
    ) = GenerationRequest(
        layout = layout,
        styleFingerprintRef = ref,
        assistLevel = assistLevel,
        mutationSeed = seed,
        cancellationToken = token,
    )

    @Test
    fun `assist level is validated, not silently clamped`() {
        assertFailsWith<IllegalArgumentException> { request(assistLevel = 1.1f) }
        assertFailsWith<IllegalArgumentException> { request(assistLevel = -0.1f) }
        assertFailsWith<IllegalArgumentException> { request(assistLevel = Float.NaN) }
        // Boundaries are inclusive.
        request(assistLevel = 0f)
        request(assistLevel = 1f)
    }

    @Test
    fun `layout rejects duplicate node ids`() {
        assertFailsWith<IllegalArgumentException> {
            layout(nodes = listOf(node("a"), node("b", left = 20f), node("a", left = 40f)))
        }
    }

    @Test
    fun `layout rejects edges that reference unknown nodes and bad weights`() {
        assertFailsWith<IllegalArgumentException> {
            layout(edges = listOf(SpatialEdge("a", "ghost", 0.5f)))
        }
        assertFailsWith<IllegalArgumentException> {
            layout(nodes = listOf(node("a"), node("b", 20f)), edges = listOf(SpatialEdge("a", "b", 2f)))
        }
    }

    @Test
    fun `frame geometry is derived`() {
        val frame = FrameBounds(0f, 0f, 1920f, 1080f)
        assertEquals(1920f, frame.width)
        assertEquals(16f / 9f, frame.aspectRatio, 1e-4f)
        assertEquals(Point2(960f, 540f), frame.center)
        assertTrue(frame.contains(FrameBounds(10f, 10f, 20f, 20f)))
        assertTrue(!frame.contains(FrameBounds(-1f, 0f, 20f, 20f)))
    }

    @Test
    fun `cache key ignores the cancellation token`() {
        assertEquals(
            request(token = CancellationToken()).cacheKey,
            request(token = CancellationToken()).cacheKey,
            "two requests describing the same art must share a cache key",
        )
    }

    @Test
    fun `cache key changes when content changes`() {
        val base = request().cacheKey
        assertNotEquals(base, request(assistLevel = 0.6f).cacheKey)
        assertNotEquals(base, request(seed = 43L).cacheKey)
        assertNotEquals(base, request(ref = StyleFingerprintRef("fp-1")).cacheKey)
        assertNotEquals(base, request(layout = layout(nodes = listOf(node("z")))).cacheKey)
    }

    @Test
    fun `layout fingerprint is order sensitive and stable`() {
        val one = layout(nodes = listOf(node("a"), node("b", 20f)))
        val reordered = layout(nodes = listOf(node("b", 20f), node("a")))
        assertEquals(one.stableFingerprint(), one.stableFingerprint())
        assertNotEquals(one.stableFingerprint(), reordered.stableFingerprint())
    }

    @Test
    fun `style fingerprint ref rejects blanks`() {
        assertFailsWith<IllegalArgumentException> { StyleFingerprintRef("") }
        assertFailsWith<IllegalArgumentException> { StyleFingerprintRef("   ") }
    }

    @Test
    fun `cancellation token is cooperative and idempotent`() {
        val token = CancellationToken()
        assertTrue(!token.isCancelled)
        token.throwIfCancelled() // no-op while live
        token.cancel()
        token.cancel() // idempotent
        assertTrue(token.isCancelled)
        assertFailsWith<GenerationCancelledException> { token.throwIfCancelled() }
    }

    @Test
    fun `progress fraction is validated`() {
        assertFailsWith<IllegalArgumentException> { GenerationEvent.Progress(1.5f) }
        assertEquals(0.25f, GenerationEvent.Progress(0.25f).fraction)
    }

    @Test
    fun `partial and result validate their buffers`() {
        assertFailsWith<IllegalArgumentException> { GenerationEvent.Partial(2, 2, ByteArray(3), true) }
        assertFailsWith<IllegalArgumentException> {
            GenerationEvent.Result(2, 2, ByteArray(16), modelVersion = " ", seed = 1L)
        }
        // A correctly-sized buffer is accepted.
        GenerationEvent.Result(2, 2, ByteArray(16), modelVersion = "stub-0", seed = 1L)
    }

    @Test
    fun `motion spec validates its inputs`() {
        assertFailsWith<IllegalArgumentException> {
            MotionPath(horizontal = 1.2f, vertical = 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            MotionSpec(MotionPath(0.1f, 0.1f), MotionCharacter.SMOOTH, durationMs = 0)
        }
    }
}
