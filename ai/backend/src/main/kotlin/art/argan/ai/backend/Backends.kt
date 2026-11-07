package art.argan.ai.backend

import art.argan.core.motion.MotionClip
import kotlinx.coroutines.flow.Flow

/**
 * The single door between the product and any generative model (rule 3).
 *
 * Nothing outside this package may reference a model, a runtime session, or a
 * file path to weights. `:ai:runtime` implements this interface; every other
 * module consumes it. In P0 the only implementation is a deterministic stub, and
 * no model of any kind exists in the app.
 *
 * Contract for implementers:
 * - Emission order is [GenerationEvent.Started], zero or more
 *   [GenerationEvent.Progress]/[GenerationEvent.Partial], then exactly one
 *   terminal [GenerationEvent.Result] *or* exactly one [GenerationEvent.Error].
 *   Or `GenerationCancelledException`. Nothing at all after a terminal event.
 * - The request's [CancellationToken] must be observed at least once per
 *   emitted event; cancellation must surface within one frame (rule 5, P7).
 * - Never block the collector's thread: all work happens on the flow's context.
 * - Same request + same seed + same model version = same bytes (P7 determinism).
 */
public interface ModelBackend {
    public fun generate(request: GenerationRequest): Flow<GenerationEvent>
}

/**
 * The single door between the product and motion synthesis (rule 3).
 *
 * [synthesize] is synchronous and budgeted: the caller passes the slice of time
 * it can afford in this frame ([budgetMs]), and the implementation must return a
 * playable [MotionClip] within it. Streaming refinement comes in P8 with the
 * neural backend; a budgeted synchronous call is the shape both must satisfy.
 */
public interface MotionBackend {
    public fun synthesize(spec: MotionSpec, budgetMs: Int): MotionClip
}

/** What to generate, expressed spatially. Never textual. */
public data class MotionSpec(
    val path: MotionPath,
    val character: MotionCharacter,
    val durationMs: Int,
) {
    init {
        require(durationMs > 0) { "durationMs must be > 0, was $durationMs" }
    }
}

/**
 * The coarse "feel" the user asked for by stroke shape (P8 maps stroke features
 * to these; P2 lets the user bend them directly).
 */
public enum class MotionCharacter {
    SMOOTH,
    SNAPPY,
    ELASTIC,
    RELUCTANT,
}

/** The pattern the user's strokes describe; `0f..1f` in both axes. */
public data class MotionPath(
    val horizontal: Float,
    val vertical: Float,
) {
    init {
        require(horizontal in 0f..1f) { "horizontal must be in 0f..1f, was $horizontal" }
        require(vertical in 0f..1f) { "vertical must be in 0f..1f, was $vertical" }
    }
}

/** The user's own visual DNA, produced on-device and never sent anywhere. */
public data class StyleFingerprint(
    val reference: StyleFingerprintRef,
    val version: Int,
)

/**
 * Provides the current artist's [StyleFingerprint] (implemented in P6).
 *
 * At P0 the only implementation returns `null`: no fingerprint has been
 * computed, and generation must be able to run without one.
 */
public interface StyleFingerprintProvider {
    public suspend fun current(): StyleFingerprint?
}

/**
 * Measures how much a candidate output looks like generic AI art (implemented in
 * P5). Higher = more generic. On-device only.
 */
public interface GenericnessScorer {
    public fun score(candidate: ByteArray): Float
}
