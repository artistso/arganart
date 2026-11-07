package art.argan.ai.backend

import art.argan.core.motion.MotionClip

/**
 * Everything a [ModelBackend] may emit.
 *
 * DTOs only: callers must be able to unit-test a whole generation conversation
 * without a device, a model, or a GPU.
 */
public sealed interface GenerationEvent {
    /** Work is accepted and progressing; [fraction] is a monotonic 0f..1f hint. */
    public data class Progress(val fraction: Float) : GenerationEvent {
        init {
            require(fraction in 0f..1f) { "progress fraction must be in 0f..1f, was $fraction" }
        }
    }

    /**
     * A deliberately low-resolution frame the canvas can show while refining.
     *
     * @property rgba8 width*height*4 bytes, straight-alpha RGBA.
     * @property isFinalRefinement true when later partials will only improve
     *   quality, never composition — lets the canvas stop re-laying-out.
     */
    public class Partial(
        public val width: Int,
        public val height: Int,
        public val rgba8: ByteArray,
        public val isFinalRefinement: Boolean,
    ) : GenerationEvent {
        init {
            require(width > 0 && height > 0) { "partial size must be positive, was ${width}x$height" }
            require(rgba8.size == width * height * 4) {
                "rgba8 must be ${width * height * 4} bytes, was ${rgba8.size}"
            }
        }
    }

    /** Terminal success: the composited result the canvas can adopt as a layer. */
    public class Result(
        public val width: Int,
        public val height: Int,
        public val rgba8: ByteArray,
        public val modelVersion: String,
        public val seed: Long,
    ) : GenerationEvent {
        init {
            require(width > 0 && height > 0) { "result size must be positive, was ${width}x$height" }
            require(rgba8.size == width * height * 4) {
                "rgba8 must be ${width * height * 4} bytes, was ${rgba8.size}"
            }
            require(modelVersion.isNotBlank()) { "modelVersion must be recorded with every result" }
        }
    }

    /** Terminal failure. [cancelled] distinguishes user intent from a real fault. */
    public data class Error(
        val message: String,
        val cause: Throwable? = null,
        val cancelled: Boolean = false,
    ) : GenerationEvent
}
