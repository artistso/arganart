# P0.5 — On-device model feasibility: what fits, and where the rules bite

Companion to `docs/phases/P07-on-device-model.md`. Written before any code, because the
answer to "can we embed a free LLM (DeepSeek, Qwen, Muse, Gemma) into the app?" is
architectural, not tactical, and getting it wrong means reversing it out of a shipped APK.

**Short version:** we cannot download a model from GitHub from this environment (network
policy), we should not vendor an LLM into this APK (it would not fit the product's rules or
its size budget), and the smallest useful thing to build *now* is the runtime seam — which
also happens to be the P7 boundary that is allowed to exist.

---

## 1. What was actually reachable

| Host | Result |
| --- | --- |
| `huggingface.co`, `cdn-lfs.huggingface.co` | unreachable (curl 35) |
| `objects.githubusercontent.com`, `release-assets.githubusercontent.com` | unreachable |
| `raw.githubusercontent.com` | unreachable |
| `github.com`, `api.github.com` | reachable |
| `productionresultssa8.blob.core.windows.net` (CI artifacts) | unreachable |

Consequences, stated plainly rather than worked around:

- **A model cannot be pulled from GitHub or HuggingFace in this environment.** No
  `.onnx`, `.gguf`, `.tflite` or `.task` weights can be fetched here.
- **The APK cannot be downloaded to this sandbox either** (CI artifacts redirect to a
  blocked Azure blob host). It is built by CI and downloadable from the Actions UI by you;
  it is not downloadable by me.

What *is* possible, and is done: publish the APK to a GitHub Release so there is a stable
URL you can open in a browser.

---

## 2. Rule 2 closes the door on a general-purpose LLM

`ARCHITECTURE.md` rule 2: *no text prompt field anywhere in the product; spatial layout is
the prompt.* The P0 brief is even more explicit: "There is still no text prompt field
anywhere in the product and there never will be."

An LLM as **the user-facing surface** is a text-in interface. That is the one thing the
product has committed to never having. So "put DeepSeek/Qwen/Gemma in the app" as an
assistant is not a scoping question — it is the thesis inverted.

## 3. An LLM as an *internal translator* is legitimate, and genuinely interesting

`core:spatial` turns canvas geometry into a structured `GenerationRequest`. Inside that
seam, a small language model could plausibly help with interpreting an *arrangement*
("four swatches clustered at the frame edge, a sketch crossing the border") into roles and
weights. That is the model reading spatial relationships, not reading text. No prompt field
appears. Rule 2 is satisfied.

This is a real design opportunity and is worth exploring **in P3**, against the resolver
that exists, rather than guessed at now.

---

## 4. Sizes: what a 400 MB model does to a 1024 MB app

Google Play's per-bundle limit is 1 GB (APK 100 MB, the rest via Play Asset/AAB delivery).
Gemma 3 1B in int4 is ≈ 0.5–0.7 GB. Add a runtime, a tokenizer and the actual product, and
a "free LLM inside the APK" ships as a ~700 MB–1 GB download for a *drawing app*.

That is not a build problem, it is a product problem: it is a 10–20× download-size increase
for a capability the product has said it does not want as a user surface.

## 5. The right model for this product is not an LLM at all

The bottleneck in this app is **image-to-image**, not text-to-image — the sketch is the
prompt. A generic chat LLM cannot draw. The models that can are small image models; a
distilled few-step diffusion model is what P7 already calls for. For the spatial resolver,
a tiny embedding model or a small classifier is enough and is ~10–100 MB.

If we do embed language capability, the honest pick is **Gemma 3 1B int4 via MediaPipe
LLM Inference** (~0.5 GB, Apache-2.0, Google-maintained Android bindings) behind the
optional user-toggled download that rule 1 already permits — *not* bundled in the APK.

Licences, for the record: Gemma terms (not OSI); Qwen Apache-2.0 (most versions);
DeepSeek MIT (code) with model-specific licences; **Muse is ambiguous** — there are
multiple unrelated "Muse" models, so it is not a name we can act on without a link.

---

## 6. What this commit actually builds

Because we cannot fetch weights and should not vendor an LLM, the useful increment is the
**runtime seam**, which is exactly what P7 authorises (`docs/phases/P07-on-device-model.md`):

- A new **`:ai:onnx`** module (Android library, because the ONNX AAR is an AAR and
  `:ai:runtime` is deliberately pure JVM) holds `OnnxModelSession` around the **verified**
  `ai.onnxruntime` API (§7). It loads a model from a path, reports its inputs/outputs, runs
  a float tensor through it, and closes cleanly.
- **Nothing depends on `:ai:onnx` yet**, so its native `.so` files are not packaged into the
  APK and the APK is the same size as P0. Native code starts shipping on the day a model is
  actually wired up (P7), not before.
- It is **not wired into any UI**, is **not** a `ModelBackend`, and has **no model file**
  attached. The app behaves exactly as before: no generation.
- The CI build downloads no weights.

Why this and not more: a backend without a model is untestable; a model without a backend
is unusable; and wiring either into the UI would violate P0's "no AI feature code" and
P1's "no AI" boundaries. The session wrapper is verifiable by compilation, unit-testable
at its seams, and is the piece both this and P7 need regardless of which model we choose.

## 7. Verified ONNX Runtime API (not recalled)

Source of truth: `microsoft/onnxruntime` (the repo moved from `microsoft/onnxruntime`;
Java sources now live under `java/src/main/java/ai/onnxruntime/`). Artifact:
`com.microsoft.onnxruntime:onnxruntime-android`, latest **1.31.0** (Maven Central).

| API used | Verified signature |
| --- | --- |
| `OrtEnvironment.getEnvironment()` | `public static synchronized OrtEnvironment getEnvironment()` |
| `env.createSession(path, opts)` | `public OrtSession createSession(String modelPath, SessionOptions options)` |
| `OrtSession.run(inputs)` | `public Result run(Map<String, ? extends OnnxTensorLike> inputs)` |
| `Result.get(index)` | `public OnnxValue get(int index)` |
| `Result.get(key)` | `public Optional<OnnxValue> get(String key)` |
| `Result.size()` / `close()` | `public int size()` / `public void close()` |
| `OnnxValue.getValue()` | `public Object getValue() throws OrtException` |
| `OnnxTensor.getFloatBuffer()` | `public FloatBuffer getFloatBuffer()` |
| `OnnxTensor.createTensor` | `public static OnnxTensor createTensor(OrtEnvironment env, FloatBuffer data, long[] shape)` |
| `SessionOptions` | `setIntraOpNumThreads(int)`, `setOptimizationLevel(OptLevel)`, `setExecutionMode(ExecutionMode)` |
| `OrtSession.getInputNames()` | `public Set<String> getInputNames()` |

Two things this changes from memory-based code, both of which would have failed CI:

1. `OrtSession.Result` is **nested** (`OrtSession.Result`), not a top-level `OrtSessionResult`.
2. `createTensor` for floats takes a `FloatBuffer` + `long[]` shape, not a `float[]` + shape.
