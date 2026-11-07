package art.argan.ai.onnx

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * The one piece of this module that can be tested with no model, no device and no
 * native library: turning whatever rank ONNX Runtime hands back into a flat
 * FloatArray.
 *
 * This matters more than it looks. `OnnxValue.getValue()` is `Object`, and its
 * runtime type varies with tensor rank: a 1-D output is `float[]`, a 2-D one is
 * `Array<float[]>`, a 3-D one `Array<Array<float[]>>`. Code that assumes a rank
 * works on the author's test model and silently corrupts the next one. Walking the
 * structure instead is the difference between "works for the model I tried" and
 * "works for the model you ship".
 */
class OnnxOutputFlatteningTest {

    @Test
    fun `rank 1 output passes through`() {
        assertContentEquals(
            floatArrayOf(1f, 2f, 3f),
            OnnxModelSession.flattenFloatOutput(floatArrayOf(1f, 2f, 3f)),
        )
    }

    @Test
    fun `rank 2 output flattens row-major`() {
        val nested = arrayOf(
            floatArrayOf(1f, 2f),
            floatArrayOf(3f, 4f),
        )
        assertContentEquals(
            floatArrayOf(1f, 2f, 3f, 4f),
            OnnxModelSession.flattenFloatOutput(nested),
        )
    }

    @Test
    fun `rank 3 output flattens in order`() {
        val nested = arrayOf(
            arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)),
            arrayOf(floatArrayOf(5f, 6f), floatArrayOf(7f, 8f)),
        )
        assertContentEquals(
            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f),
            OnnxModelSession.flattenFloatOutput(nested),
        )
    }

    @Test
    fun `ragged nesting still produces a complete array`() {
        // Not something a real tensor produces, but it must not drop values if it
        // ever does: silently losing half an image is worse than being slow.
        val ragged = arrayOf(
            floatArrayOf(1f),
            floatArrayOf(2f, 3f),
            floatArrayOf(4f, 5f, 6f),
        )
        assertContentEquals(
            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f),
            OnnxModelSession.flattenFloatOutput(ragged),
        )
    }

    @Test
    fun `empty output flattens to empty`() {
        assertContentEquals(floatArrayOf(), OnnxModelSession.flattenFloatOutput(arrayOf<FloatArray>()))
        assertContentEquals(floatArrayOf(), OnnxModelSession.flattenFloatOutput(floatArrayOf()))
    }

    @Test
    fun `non-float output is rejected loudly rather than cast`() {
        // An int64 logits tensor or a string label must not be silently mangled
        // into floats. P7 will need a typed path for these; guessing here would
        // produce plausible-looking garbage.
        assertFailsWith<ModelExecutionException> {
            OnnxModelSession.flattenFloatOutput(arrayOf("not", "a", "tensor"))
        }
        assertFailsWith<ModelExecutionException> {
            OnnxModelSession.flattenFloatOutput(null)
        }
    }

    @Test
    fun `missing model file is a supported state, not a crash`() {
        // Regression guard for a real CI failure: the ONNX environment used to be
        // a field initialiser, so constructing this class loaded the native
        // library before the file check ran. The test then died with
        // UnsatisfiedLinkError instead of exercising the fallback -- which would
        // also have happened on a tablet with no model installed.
        // P7 requires the app to be fully usable with the model deleted. The
        // constructor must therefore raise the specific exception a caller can
        // catch to fall back, not a generic failure.
        assertFailsWith<ModelNotAvailableException> {
            OnnxModelSession("/nonexistent/path/to/model.onnx")
        }
        assertFailsWith<ModelNotAvailableException> {
            OnnxModelSession("   ")
        }
    }
}
