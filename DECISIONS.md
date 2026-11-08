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

## D12 — Frame statistics live in a pure module; only the JankStats bridge is Android *(P0)*

**Decision.** A new `:core:diagnostics` module (pure JVM) owns the arithmetic:
`FrameStat`, `FrameWindow`, `FrameReport`, `FrameBudget`. `:app` owns a thin
`FrameDiagnostics` that forwards `JankStats` callbacks into it, and a debug-only overlay
toggled by three taps in the bottom-left corner.

**Why.** Three reasons, in order of weight:

1. **The numbers get quoted.** P1's DoD is "<1% janky frames"; `PERFORMANCE.md` states
   median < 16 ms and p95 < 24 ms. Those claims must come from tested code. Splitting the
   arithmetic out means 18 host tests pin the percentile maths, the ring's bounded memory
   and the verdict logic — including the important case that a good median cannot hide a
   bad p95.
2. **Honesty about missing data.** `FrameReport` returns `null` (not `0`) for unobtainable
   statistics, and `BudgetVerdict.INSUFFICIENT_DATA` (not `PASS`) below 60 frames. An
   instrumentation layer that reports "all good" when it has no data is worse than none.
3. **Cost.** Percentiles are recomputed at most once a second, not per frame, and the
   window is a fixed 240-frame ring. An overlay that grows memory changes the behaviour
   it exists to measure.

**Rejected.** Putting it all in `:app` (untestable without a device, which is exactly the
problem P0's instrumentation needs to avoid); using JankStats' own aggregate percentages
(they answer the platform's question, not the product's 8 ms ink budget, and cannot be
asserted in a unit test).

**Deviation.** This module is not in the brief's module list. It is added under
"PERFORMANCE INSTRUMENTATION", which the P0 brief does list as a deliverable. If you would
rather keep the module list literally at ten, the same code can move into `:core:motion`'s
package — but it has nothing to do with motion, so the coercion would be worse than the
extra module.

**Note on input-to-photon.** JankStats cannot supply it. `FrameStat.inputToPhotonNanos` is
therefore `null` at P0 and the overlay prints `i2p: n/a (P1)`. P1 wires an explicit
estimate from the stylus event timestamp; until then no latency figure is fabricated.

---

## D13 — The `suspend` question is settled: `generate` stays non-suspending *(P0, closed)*

Reconfirmed rather than left open. See D4. Rationale unchanged: a cold `Flow` already
suspends inside its own context, so the keyword adds no capability while forcing every
fake and caller to own a scope. No further action.

---

## D16 — Springs are closed-form, not integrated *(P2)*

**Decision.** `SpringDynamics` evaluates the analytic step response of a damped harmonic
oscillator: underdamped (decaying oscillation), critically damped, and overdamped
(hyperbolic form) branches. There is no per-frame integrator anywhere in `:core:motion`.

**Why.** The P2 brief requires a clip to be *sampleable at any t*, and scrubbing is a
jump to an arbitrary t — including backwards. An integrator is stateful, so scrubbing to
2000 ms would give a different answer depending on which frames happened to be drawn
first. Playback would be path-dependent: the same clip would look different on a fast
device than a slow one. That is precisely the property that makes spring systems feel
non-deterministic, and it is invisible until someone scrubs.

**Cost.** A closed form exists only for a *step* input, so a clip follows one segment of
its ribbon per spring rather than accepting an arbitrary mid-flight force. This is why
`MotionNode` carries both an easing character and spring parameters: easing shapes travel
within a segment, the spring gives the segment's arrival its weight.

**Also fixed by taking the closed form seriously.** The overdamped branch was written by
hand-solving two exponentials and was wrong — it returned 2.0 at t = 0 instead of 0, so
RELUCTANT motion began halfway along its path and never travelled. The test suite caught
it because it asserted the endpoint, not the shape.

---

## D17 — Overshoot is expressed at the ribbon's END, not per segment *(P2)*

**Decision.** Interior segments use a monotone (critically damped, same ωn) curve so
travel is always forward. The final segment's excess above 1 becomes visible
follow-through past the end of the ribbon, capped at 12% of its length.

**Why.** Three attempts, each failing differently and each failure is why the rule is
written down:

1. **Clamp overshoot to 0..1 everywhere.** Elastic parked at each interior node for ~140 ms
   (7 identical consecutive samples on a 600 ms clip). A plateau reads as a stutter, not
   as weight.
2. **Let every segment overshoot.** The element reversed direction at each interior node —
   a 0.03-unit regression mid-ribbon, caught by the monotonicity test. Backwards motion
   mid-path reads as a glitch.
3. **Let only the last segment overshoot internally.** The element passed the ribbon end
   and then came BACK before arriving. Also backwards motion, just at the end.

The fix separates the two concerns: travel through a point is monotone, arrival at the end
carries weight. Both are visible and neither reverses.

---

## D18 — The scope guard is phase-aware and permanent *(P1, rebuilt in P2)*

**Decision.** `tools/check-architecture.py` reads `docs/phases/CURRENT_PHASE` and guards
the vocabulary of every *later* phase. Bumping the phase number is what opens a
vocabulary.

**Why not delete it when P0 closed.** The guard's purpose — "nothing from a later phase
exists yet" — is a standing property, not a P0 chore. Re-aiming it costs one line per
phase and catches the same class of mistake every time: P2 leaking a spatial resolver,
P4 leaking a genericness meter. Entries for completed phases are kept deliberately; they
cost nothing and document when each concept entered the product.

---

## D19 — Paper is the default theme *(P2)*

**Decision.** `ArganTheme` defaults to `darkTheme = false` rather than
`isSystemInDarkTheme()`. The dark palette still exists and P9 exposes the toggle.

**Why.** This came from user feedback, and it is a correctness point rather than taste: a
drawing app that opens to a near-black rectangle with no visible structure reads as an
unfinished tool. Most devices default to dark, so "follow the system" meant most users
met the worst version of the product first. Two related defects surfaced from the same
report: the ink surface hardcoded a near-black ink colour, so drawing on the dark canvas
produced *invisible ink*; and strokes rendered as constant-width polylines, discarding the
4096 pressure levels the pipeline had carefully carried from `MotionEvent`.

**Rule taken from this.** The default configuration must be the one that demonstrates the
product. A non-default option can be worse; the default cannot.

---

## Dependency pinning policy

Pinned, no dynamic versions, no `+`. The catalog is the single place to bump anything.

**Resolved at P0.** The pins in `gradle/libs.versions.toml` were a considered baseline
that could not be resolved in the authoring sandbox. CI has now resolved every one of them
**without a single bump**: AGP 8.10.1, Kotlin 2.1.21, compileSdk/targetSdk 36, Compose BOM
2025.06.01, `metrics-performance` 1.0.0. `assembleDebug`, `lint` and `test` all pass
(run 42374315085).

Two smaller manifest decisions were forced by CI rather than chosen: `<uses-feature>` had
to move out of `<application>` (an AAPT error, not a style preference), and the JankStats
frame type was `FrameData(frameStartNanos, frameDurationUiNanos, isJank, states)` — see
`docs/reviews/P00-review.md` §7.
