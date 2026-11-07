# :ai:backend

**Interfaces and DTOs only.** No implementation, no model loading, no file IO, no network.
If this module ever needs to import something from `:ai:runtime`, the layering has
inverted and the build should fail — it does (`tools/check-architecture.py`).

Pure JVM (not an Android library) so the whole generation contract is unit-testable on
the host: see `ContractTest`.

## Contents

| Type | P0 role |
| --- | --- |
| `ModelBackend.generate(GenerationRequest): Flow<GenerationEvent>` | the only door to a model (rule 3) |
| `MotionBackend.synthesize(MotionSpec, budgetMs): MotionClip` | the only door to motion synthesis |
| `StyleFingerprintProvider.current()` | P6 seam; P0 returns `null` |
| `GenericnessScorer.score(ByteArray)` | P5 seam; P0 throws rather than lie |
| `GenerationRequest`, `SpatialLayoutGraph`, `FrameBounds`, … | the spatial prompt as data (rule 2) |
| `GenerationEvent` | `Progress`/`Partial`/`Result`/`Error` streaming contract |

## Deliberate deviation from the brief's literal signature

The brief writes `suspend fun generate(...)`. It is declared as a **non-suspend** function
returning `Flow<GenerationEvent>`. Rationale, and why it is not a functional change:

- A `Flow` is cold: suspending happens inside the flow's own context, so cancellation
  (rule 5) and streaming both work identically.
- Declaring `suspend` on top adds no capability, but it forces every caller and every
  fake in a test to own a coroutine scope just to *obtain* the flow.
- The P0 brief also requires that interfaces be "testable and tested" without a device.
  `ContractTest` exercises the DTOs on a plain JVM; keeping the entry point
  non-suspending keeps the seam fake-able with a plain object.

Recorded in `DECISIONS.md`. If you prefer the literal `suspend` signature, say so and it
will be restored — it is a one-line change plus `ContractTest` updates.
