# Decisions

Each entry: the decision, why, what was rejected, and what would make us revisit it.
Dated by phase, not by calendar.

---

## D1 — Kotlin 2.x, Compose for chrome, Gradle Kotlin DSL + version catalog *(P0)*

**Decision.** Kotlin for everything; Jetpack Compose for UI chrome only; Gradle Kotlin DSL
with a single `gradle/libs.versions.toml`; explicit API mode and warnings-as-errors.

**Why.** Compose gives a fast, consistent iteration loop for chrome on large screens, and
its `Canvas`/`drawWithCache` story is adequate for the P0 pan/zoom surface. A version
catalog with pinned versions makes a dependency change a reviewable one-line diff.

**Rejected.** XML views (slower iteration, no state model we want to keep); Bazel
(excellent for large monorepos, but a single-team tablet app gets more from Gradle's
Android integration); using Compose for the ink surface itself — see D3.

---

## D2 — Module graph fixed at ten modules, edges mechanically enforced *(P0)*

**Decision.** Exactly the modules in the brief, with an `ALLOWED_EDGES` table in
`tools/check-architecture.py` that fails the build on an undeclared dependency.

**Why.** The brief's hard boundaries ("AI is additive, never load-bearing") are worthless
if enforced by memory. Encoding the graph as data means a reviewer reads a table instead
of a 40-file diff, and CI refuses a violation even when nobody is looking.

**Rejected.** A single `:app` module with package discipline (rules become conventions);
a convention plugin that infers layers from package names (clever, unreadable, and it
hides the graph exactly where a reader needs it).

---

## D3 — `:core:*` are pure JVM modules, not Android libraries *(P0)*

**Decision.** `:core:ink`, `:core:motion`, `:core:spatial` and `:ai:backend` use the Kotlin
JVM plugin. No `android {}` block, no Android types in the model.

**Why.** Three concrete benefits, all of which were exercised in this phase:

1. **Testability.** Their tests run on the host JVM in milliseconds, so the hot-path
   contracts (`sampleInto` allocation-free, cache-key semantics, pan/zoom anchoring) are
   pinned by fast tests rather than instrumented ones.
2. **Rule 4 becomes compile-time.** A core module *cannot* accidentally take a dependency
   on `MotionEvent` or a `Context`, so "the ink layer runs with AI disabled" is a property
   of the build, not a promise.
3. **Portability.** The same models can back an export CLI or a benchmark harness without
   an emulator.

**Rejected.** One Android library per core module for convenience (invites Android types
into the model and slows the test loop by ~100×).

**Note.** The Android adapters (raw `MotionEvent` handling, `GLSurfaceView`/`SurfaceView`
hosting) will live in `:feature:canvas` at P1, not in `:core:ink`.

---

## D4 — `ModelBackend.generate` is non-suspending; it returns a cold `Flow` *(P0)*

**Decision.** `fun generate(request: GenerationRequest): Flow<GenerationEvent>`, not the
brief's literal `suspend fun`.

**Why.** A `Flow` is already cold and already suspends inside its own context, so upstream
work, cancellation (Rule 5) and streaming are unchanged. Marking the *entry point* suspend
adds nothing to the contract but forces every test double and every caller to own a
coroutine scope merely to obtain the flow — which conflicts with the P0 requirement that
the new public interfaces be testable without a device.

**Rejected.** The literal `suspend` signature (functionally equivalent, more ceremony to
test).

**Revisit if** the team prefers literal fidelity to the brief. It is a one-line change plus
updating `ContractTest`'s fakes. **This is the single deliberate deviation from the P0
brief's literal text and is flagged in `docs/reviews/P00-review.md`.**

---

## D5 — `MotionBackend.synthesize` is synchronous and budgeted *(P0)*

**Decision.** `fun synthesize(spec: MotionSpec, budgetMs: Int): MotionClip`.

**Why.** The caller is a render loop; it knows how many milliseconds it can spare *this
frame*. Making the budget an explicit parameter forces every implementation (procedural
now, neural in P8) to be honest about cost, and gives the P8 streaming backend a natural
place to return an initial playable chunk and refine later.

**Rejected.** `suspend fun synthesize(...)` (the caller cannot express a per-frame budget);
an unparameterised synchronous call (hides the cost model until it hurts).

---

## D6 — Cancellation is a first-class DTO, not a coroutine `Job` *(P0)*

**Decision.** `GenerationRequest.cancellationToken: CancellationToken` with `cancel()`,
`isCancelled`, `throwIfCancelled()`.

**Why.** Rule 5 needs cancellation to survive *serialisation*: a request is stored in a
project manifest, and a cancelled generation must be recorded as cancelled. A `Job` is
neither serialisable nor part of the request's identity. It also keeps `:ai:backend`'s
contract execution-model-agnostic.

**Rejected.** Relying on structured concurrency only (loses the "was this cancelled?"
fact once the scope dies); `Flow` collector cancellation alone (same problem).

---

## D7 — No DI framework at P0; a hand-written `BackendRegistry` *(P0)*

**Decision.** `ArganApplication.backends` is a lazy `BackendRegistry` built by
`BackendRegistry.createDefault()`.

**Why.** There is exactly one graph and it has four members. Hilt would add annotation
processing to every module, a generated class per injection point and a build-time cost —
for a graph a reader can take in at a glance. It also keeps `:app` the only module that
names concrete backends, which is what makes Rule 3 checkable.

**Rejected.** Hilt/Koin (cost without benefit at this size); manual construction inline in
the Activity (loses the single composition root, and makes UI hold concrete types).

**Revisit at** P7, when backends gain runtime sessions, delegates and lifecycle. If the
graph exceeds ~15 bindings, adopt Hilt then, in one contained PR.

---

## D8 — No navigation library at P0 *(P0)*

**Decision.** One activity, one destination (`CanvasScreen`), no nav dependency.

**Why.** A nav graph with a single node is pure ceremony. The seam is the composable
boundary; adding Navigation Compose in P1 costs one dependency line.

**Rejected.** Pre-building empty destinations for every future screen (that is scope
creep: it designs P2–P9's information architecture before their requirements exist).

---

## D9 — Design tokens from P0, not "we'll theme it later" *(P0)*

**Decision.** `:design` owns every colour and dimension in an `ArganColors` /
`ArganDimensions` pair exposed through `ArganTheme`.

**Why.** P9 requires zero hardcoded colours outside `:design` and resources. Retrofitting
that after eight phases of UI work is a large, risky refactor; starting with it costs a
file. The four canvas-layer colours (`ink`, `inkInProgress`, `inkPredicted`,
`ghostSuggestion`) are named now because the P1 contract treats layers as distinct by
design, not by taste.

**Rejected.** Raw Material 3 dynamic colour (it would make the canvas background depend on
the user's wallpaper — unacceptable for an art app, where the paper must be neutral and
consistent).

---

## D10 — Theming: dark and light from the system, explicit toggle in P9 *(P0)*

**Decision.** `ArganTheme(darkTheme = isSystemInDarkTheme())` with two hand-built palettes.

**Why.** Dynamic colour is wrong for an art app (see D9). The canvas paper colour must be
predictable, and the ink must always read against it.

---

## D11 — Phase discipline as tooling, not etiquette *(P0)*

**Decision.** `tools/check-architecture.py` asserts the *absence* of P1–P8 vocabulary in
P0, and `docs/guardrails/` holds the review, stop-scope and blocked protocols verbatim.

**Why.** The uploaded phase briefs contain three explicit guardrails (a review protocol, a
stop-and-revert protocol, and a "state the failing behaviour before changing code"
protocol). Making the scope boundary executable is the only way those survive contact with
a coding agent in a hurry.

---

## Dependency pinning policy

Pinned, no dynamic versions, no `+`. The catalog is the single place to bump anything.

**P0 caveat, stated plainly:** the Android/Gradle pins in `gradle/libs.versions.toml`
could not be resolved in the environment P0 was built in (no Android SDK, no JDK, and
Google Maven/Maven Central unreachable). They are a considered baseline, not a verified
one. The first CI run is the authority; if a pin fails to resolve, bump it in that same PR
and add a line here. The pure-Kotlin modules *were* compiled and their 39 unit tests
*were* executed against a real Kotlin 2.4.x compiler — see `docs/reviews/P00-review.md`.
