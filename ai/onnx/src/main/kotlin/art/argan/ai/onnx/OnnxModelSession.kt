package art.argan.ai.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * A thin, honest wrapper around one ONNX Runtime session.
 *
 * WHY THIS EXISTS AT P0.5
 * -----------------------
 * The eventual on-device model (P7) needs a session that loads a model from disk,
 * reports what it wants, runs a float tensor through it, and closes without
 * leaking native memory. Every one of those four things is independent of *which*
 * model we choose, so it can be built and pinned now while the model question is
 * still open.
 *
 * WHAT THIS DELIBERATELY IS NOT
 * ----------------------------
 * - It is **not** a [art.argan.ai.backend.ModelBackend]. There is no model, and a
 *   backend that pretends to generate would be a lie the product acts on.
 * - It is **not** wired into any UI. Nothing in `:feature:*` or `:app` references
 *   this class, so the running app is byte-for-byte the P0 app.
 * - It ships **no weights**. The constructor takes a path; if no file exists, it
 *   throws [ModelNotAvailableException], which is a supported state (P7: "the app
 *   must remain fully usable with the model file deleted").
 *
 * THREADING. ONNX Runtime sessions are thread-safe for `run`, but a session created
 * on one thread and used to translate inputs on another is a frequent source of
 * subtle bugs. Callers own the threading decision; this class does not spawn any.
 *
 * @param modelPath absolute path to an `.onnx` file on local storage.
 * @param threads intra-op thread count; clamped to at least 1.
 */
public class OnnxModelSession(
    modelPath: String,
    threads: Int = DEFAULT_THREADS,
) : Closeable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        require(modelPath.isNotBlank()) { "modelPath must not be blank" }
        val file = java.io.File(modelPath)
        if (!file.isFile) {
            // Not a crash-worthy condition: P7 requires the app to work without a
            // model. Callers catch this and fall back.
            throw ModelNotAvailableException("No model file at $modelPath")
        }
        try {
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(threads.coerceAtLeast(1))
            // CPU is always present; delegates (NNAPI/GPU) are a P7 decision and
            // adding one here would silently change numerics.
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            session = environment.createSession(modelPath, options)
        } catch (e: OrtException) {
            throw ModelNotAvailableException("ONNX Runtime could not load $modelPath", e)
        }
    }

    /** Names of the tensors this model expects, in a stable order. */
    public val inputNames: List<String>
        get() = session.inputNames.sorted()

    /** Names of the tensors this model produces, in a stable order. */
    public val outputNames: List<String>
        get() = session.outputNames.sorted()

    /**
     * Runs one float tensor as the model's single input and returns the first
     * output's float data.
     *
     * Scope limit, stated rather than hidden: this supports the single-input,
     * float-only shape that a distilled diffusion or embedding model actually
     * uses. Multi-input, int64 and dynamic-shape models will need a richer entry
     * point, and adding one is cheap — guessing its shape now is not.
     *
     * @param inputName must equal `inputNames.single()` for this call to make sense.
     * @param shape row-major dimensions of [data].
     * @return the first output flattened into a new array. Allocates: not a hot path.
     */
    public fun runSingleFloatInput(
        inputName: String,
        data: FloatArray,
        shape: LongArray,
    ): FloatArray {
        require(shape.isNotEmpty()) { "shape must not be empty" }
        require(shape.all { it > 0 }) { "every dimension must be positive: ${shape.toList()}" }
        val expected = shape.fold(1L) { acc, dim -> acc * dim }
        require(expected == data.size.toLong()) {
            "shape ${shape.toList()} needs $expected values, got ${data.size}"
        }

        try {
            // Verified signature: createTensor(env, FloatBuffer, long[] shape).
            // A FloatBuffer is required; there is no float[] overload.
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    require(result.size() > 0) { "model produced no outputs" }
                    val output = result.get(0)
                    // getValue() returns a nested float[][][]... for tensors; the
                    // flat form is what callers of this seam want.
                    return flattenFloatOutput(output.value)
                }
            }
        } catch (e: OrtException) {
            throw ModelExecutionException("ONNX Runtime failed to run the model", e)
        }
    }

    /** Releases the native session. Safe to call more than once. */
    override fun close() {
        try {
            session.close()
        } catch (_: OrtException) {
            // Closing a session that is already gone is not actionable; a debug
            // log would be noise. Swallowing here keeps `use {}` blocks safe.
        }
    }

    public companion object {
        /**
         * Deliberately conservative: a 2023 mid-range tablet shares cores with the
         * UI thread, and saturating them during generation is exactly the jank
         * P7's Definition of Done forbids.
         */
        public const val DEFAULT_THREADS: Int = 2

        /**
         * Flattens whatever nesting ONNX Runtime hands back into a FloatArray.
         *
         * `OnnxValue.getValue()` is `Object`, and its runtime type depends on the
         * tensor rank: float[], float[][], float[][][] and so on. Rather than
         * assume a rank, walk it.
         */
        public fun flattenFloatOutput(value: Any?): FloatArray = when (value) {
            is FloatArray -> value
            is Array<*> -> {
                val parts = value.map { flattenFloatOutput(it) }
                val total = parts.sumOf { it.size }
                val out = FloatArray(total)
                var offset = 0
                for (part in parts) {
                    part.copyInto(out, offset)
                    offset += part.size
                }
                out
            }
            else -> throw ModelExecutionException(
                "expected a float tensor output, got ${value?.javaClass?.name ?: "null"}",
            )
        }
    }
}

/** The model file is absent. A supported state, not a crash. */
public class ModelNotAvailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The model file is present but the runtime could not execute it. */
public class ModelExecutionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
