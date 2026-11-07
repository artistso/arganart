package art.argan.ai.runtime

import art.argan.ai.backend.GenerationEvent
import art.argan.ai.backend.GenerationRequest
import art.argan.ai.backend.ModelBackend
import art.argan.ai.backend.MotionBackend
import art.argan.ai.backend.MotionCharacter
import art.argan.ai.backend.MotionSpec
import art.argan.core.motion.EasedTranslationClip
import art.argan.core.motion.Easing
import art.argan.core.motion.MotionClip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The P0 motion implementation: fully procedural, no model, no network.
 *
 * This is a *real working* backend, not a stub that throws. Two reasons:
 *
 * 1. Rule 4 says the ink layer must reach 120Hz with the AI layer completely
 *    disabled. Proving motion works with zero AI present is part of that rule.
 * 2. It is the permanent fallback. P8 adds a neural backend that may be missing,
 *    throttled or too slow; this class must keep producing acceptable motion
 *    then, so its behaviour is pinned by tests now rather than rewritten later.
 *
 * For P0 it renders [MotionCharacter] as an easing curve only. Spring dynamics,
 * ribbon bending and node-level character are deliberately P2.
 *
 * It always returns within the caller's budget because it computes nothing
 * beyond the clip's endpoints; the budget parameter exists so callers can be
 * written against the final contract today.
 *
 * @param canvasWidth used to convert normalised [MotionSpec.path] to canvas units.
 * @param canvasHeight used to convert normalised [MotionSpec.path] to canvas units.
 */
public class ProceduralMotionBackend(
    private val canvasWidth: Float = DEFAULT_CANVAS_WIDTH,
    private val canvasHeight: Float = DEFAULT_CANVAS_HEIGHT,
) : MotionBackend {

    init {
        require(canvasWidth > 0f) { "canvasWidth must be > 0, was $canvasWidth" }
        require(canvasHeight > 0f) { "canvasHeight must be > 0, was $canvasHeight" }
    }

    public override fun synthesize(spec: MotionSpec, budgetMs: Int): MotionClip {
        require(budgetMs >= 0) { "budgetMs must be >= 0, was $budgetMs" }

        return EasedTranslationClip(
            fromX = 0f,
            fromY = 0f,
            toX = spec.path.horizontal * canvasWidth,
            toY = spec.path.vertical * canvasHeight,
            durationMs = spec.durationMs,
            easing = spec.character.toEasing(),
        )
    }

    public companion object {
        public const val DEFAULT_CANVAS_WIDTH: Float = 1080f
        public const val DEFAULT_CANVAS_HEIGHT: Float = 720f

        /**
         * P0 mapping from coarse character to easing.
         *
         * P2 replaces this with per-node spring parameters. The mapping lives
         * here, not in `:core:motion`, because it is a policy of this backend
         * rather than a property of the motion model.
         */
        public fun MotionCharacter.toEasing(): Easing = when (this) {
            MotionCharacter.SMOOTH -> Easing.SMOOTHSTEP
            MotionCharacter.SNAPPY -> Easing.EASE_OUT_CUBIC
            // P0 cannot express overshoot, so it returns the closest
            // non-overshooting curve instead of faking overshoot. P2 owns springs.
            MotionCharacter.ELASTIC -> Easing.EASE_OUT_CUBIC
            MotionCharacter.RELUCTANT -> Easing.LINEAR
        }
    }
}

/**
 * The only [ModelBackend] that exists at P0: it reports that generation is not
 * part of this build.
 *
 * It exists so the DI graph is complete and the app launches with a backend
 * wired in, while guaranteeing nothing generative is load-bearing. It
 * deliberately does not fabricate images: fabricating a "result" now would let
 * later code depend on behaviour that changes entirely in P7.
 */
public class UnavailableModelBackend : ModelBackend {
    public override fun generate(request: GenerationRequest): Flow<GenerationEvent> =
        flowOf(
            GenerationEvent.Error(
                message = "Generation is not part of this build (phase P0).",
                cancelled = false,
            ),
        )
}
