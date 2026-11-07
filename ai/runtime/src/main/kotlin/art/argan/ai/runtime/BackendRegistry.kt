package art.argan.ai.runtime

import art.argan.ai.backend.GenericnessScorer
import art.argan.ai.backend.ModelBackend
import art.argan.ai.backend.MotionBackend
import art.argan.ai.backend.StyleFingerprintProvider

/**
 * The app's dependency-injection surface: one object that hands out every
 * backend, built once per process.
 *
 * This is the only place in the product where a concrete backend is chosen. The
 * UI layer depends on the interfaces from `:ai:backend` and receives them from
 * here (rule 3). In P0 every member is a non-generative implementation.
 */
public class BackendRegistry(
    public val modelBackend: ModelBackend,
    public val motionBackend: MotionBackend,
    public val styleFingerprintProvider: StyleFingerprintProvider,
    public val genericnessScorer: GenericnessScorer,
) {
    public companion object {
        /**
         * The P0 registry: no model, no fingerprint, no scorer.
         *
         * Motion is real (procedural) because the empty canvas must be provably
         * animatable without any AI present.
         */
        public fun createDefault(): BackendRegistry = BackendRegistry(
            modelBackend = UnavailableModelBackend(),
            motionBackend = ProceduralMotionBackend(),
            styleFingerprintProvider = NoStyleFingerprintProvider,
            genericnessScorer = UnavailableGenericnessScorer,
        )
    }
}

/**
 * P0 stand-in for P6's provider: no fingerprint has ever been computed, and
 * generation must run without one.
 */
public object NoStyleFingerprintProvider : StyleFingerprintProvider {
    public override suspend fun current(): Nothing? = null
}

/**
 * P0 stand-in for P5's scorer.
 *
 * Throws rather than returning a fake number: a fabricated genericness value is
 * worse than an honest absence, because the meter would lie to the user.
 */
public object UnavailableGenericnessScorer : GenericnessScorer {
    public override fun score(candidate: ByteArray): Float =
        throw UnsupportedOperationException(
            "Genericness scoring is not part of this build (phase P0).",
        )
}
