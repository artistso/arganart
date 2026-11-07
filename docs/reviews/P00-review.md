# P0 review

Following `docs/guardrails/phase-review.md`: Definition of Done with evidence, rule
compliance, scope check, hot-path check, testability. Then a prioritised fix list.

**Verdict: P0 is NOT fully done.** Two deliverables are undelivered, one deviation is
deliberate, and the Gradle build is unverified. Everything that could be verified in this
environment was verified by executing it, not by assertion. Details below.

---

## 1. Definition of Done, item by item

The P0 brief's DoD is four lines plus a deliverables list. Evidence column says what was
actually run.

| # | Item | Status | Evidence |
| --- | --- | --- | --- |
| 1 | `./gradlew assembleDebug` succeeds | **UNVERIFIED** | Cannot run: no JDK, no Android SDK, and `dl.google.com` / `repo1.maven.org` are unreachable from the authoring sandbox (curl exit 35). See "Honest gaps". |
| 2 | App launches on an emulator and on one physical tablet if available | **UNVERIFIED** | Same tooling gap. No emulator, no device. |
| 3 | Empty canvas pans and zooms smoothly at the display refresh rate | **PARTIAL** | The maths is unit-tested: 5 tests in `CanvasTransformTest`, including "zoom keeps the canvas point under the focus stationary" and exact screen↔canvas round-trip. Smoothness itself is unmeasured (no device). |
| 4 | No feature code for drawing, AI, or animation exists yet | **YES** | `python3 tools/check-architecture.py` → `[SCOPE] OK: no code from any later phase is present`. Negative control run: injecting a `BrushParams` class made the check fail. |
| D1 | Compiling app that launches to an empty infinite canvas (pan/zoom only) | **UNVERIFIED** (build) / content reviewed | `CanvasScreen` → `InfiniteCanvas` → `CanvasViewport`; the only gesture handler is `detectTransformGestures`. |
| D2 | ARCHITECTURE.md with the six rules plus a module dependency diagram | **YES** | `ARCHITECTURE.md` §1 (rules, each with its enforcement), §2 (diagram + edge table). |
| D3 | DECISIONS.md with stack choices and rejected alternatives | **YES** | `DECISIONS.md` D1–D11, each with rejected alternatives and revisit triggers. |
| D4 | A stub procedural MotionBackend returning a simple eased translation | **YES, beyond the letter** | `ProceduralMotionBackend` + `EasedTranslationClip`; 6 tests in `ProceduralMotionBackendTest`, 10 in `EasedTranslationClipTest`, all executed and passing. |
| D5 | CI: assemble + lint + unit test on push | **WRITTEN, UNVERIFIED** | `.github/workflows/ci.yml` has three jobs (architecture, build+lint+test, report upload). Never executed: the wrapper jar is absent (see gap G2). |

### Evidence that the pure core really compiles and passes

Command, and its output, from the authoring environment:

```
$ JAVA_HOME=<temurin 21.0.8> kotlinc = kotlin 2.4.21
$ tools/kotlin-verify/verify.sh
== compile main sources ==          (5 modules, zero errors, warnings suppressed only for /^warning:/)
== compile tests ==
tests passed: 39, failed: 0
```

39 tests across 5 classes:

| Test class | Tests | Covers |
| --- | --- | --- |
| `ContractTest` | 12 | DTO invariants, cache-key semantics, cancellation, event buffer validation |
| `EasedTranslationClipTest` | 10 | easing actually eased, clamping, looping, `sampleInto` reuse + full field reset |
| `ProceduralMotionBackendTest` | 6 | budget compliance, canvas-unit mapping, per-character curves, determinism |
| `CanvasTransformTest` | 5 | pan, anchored zoom, clamping, round-trip |
| `RegistryAndStubsTest` | 6 | registry wiring, honest absence (throws rather than fabricates), budget compliance |

**Negative control.** To prove the harness reports real failures, one expectation was
deliberately corrupted (`assertEquals(30f, r.width)` → `31f`); the run reported
`FAIL ... expected <31.0> but was <30.0>`, `tests passed: 38, failed: 1`, exit 1. Restored,
re-ran, 39/0. So the green result is not a harness that always says yes.

---

## 2. Architectural rule violations

`python3 tools/check-architecture.py` → **all rules satisfied**, with one rule relaxed
after the check itself exposed a real ambiguity (recorded here rather than quietly):

| Rule | Result | Notes |
| --- | --- | --- |
| 1 · on-device only | PASS | No `INTERNET` permission; no socket/HTTP API in any module. The permission's absence is asserted, not remembered. |
| 2 · no text prompt | PASS | `GenerationRequest` has no string describing intent; `StyleFingerprintRef` is an opaque handle. |
| 3 · UI never talks to a model | PASS **after a fix** | The first run failed: `:app` legitimately depends on `:ai:runtime` to build the registry. Fixed by making the rule precise — `:app` is the composition root, may import exactly `BackendRegistry`, and `:feature:*` still may not see runtime at all. The rule is stricter and clearer than before, not looser. |
| 4 · ink runs with AI disabled | PASS | `:core:*` have no Android plugin and no Android import. Deleting `:ai:runtime` from `:app` would still compile the canvas. |
| 5 · cancellable + progress | PASS (contract) | `CancellationToken` + `GenerationEvent`; behaviour is implementable only in P7, so P0 pins the seam with tests. |
| 6 · 8 ms frame budget | PASS (shape) | `sampleInto` destination-taking API; R6 refuses a sampling API without one. |

**Bugs the checks caught during P0** (i.e. the checks earn their keep):

1. A missing `MotionClip` import in `:ai:backend` — rejected by the Kotlin compiler.
2. The R3 ambiguity above — surfaced by the architecture check, fixed by making the rule precise.
3. **Explicit-API violation** in `StyleFingerprintRef`'s primary-constructor property
   (`public val` was missing), which `explicitApi()` + `allWarningsAsErrors` would have
   failed in CI. Found by re-running the compiler with the build's real flags
   (`-Xexplicit-api=strict -Werror`), not by reading the code.
4. Two Compose errors found by review before any build existed: a `@Composable` token read
   inside `DrawScope` (a non-composable lambda), and `Size` used where `IntSize` was
   declared. Both fixed in `InfiniteCanvas.kt`.

**Strict-flag verification.** The five pure modules compile cleanly under
`-Xexplicit-api=strict -Werror`, i.e. the same settings `allWarningsAsErrors` +
`explicitApi()` apply in Gradle. That narrows — it does not close — the gap left by the
unrunnable Android build: it proves the *Kotlin source* would pass, not that the Android
Gradle configuration resolves.

---

## 3. Scope: anything built for a later phase?

Three things were drafted and then **deleted** precisely because they were P1/P3 scope:

- `InputPoint` / `Stroke` / `BrushParams` / `Stamp` / `PressureResponse` in `:core:ink` —
  P1.
- `CanvasObject` and its six variants plus `CanvasRect` in `:core:spatial` — P3.

Neither survived into the build. Current scope check is clean.

Three items are **kept, deliberately, with reasons** — flagging them so they can be
removed if you disagree:

1. `EasedTranslationClip` / `MotionClip` / `Pose` in `:core:motion`. The brief says
   `MotionBackend` is "interfaces only + a procedural fallback". A *return type* is not an
   implementation, and a fallback needs a concrete type to return. Without it the P0
   deliverable D4 is unimplementable.
2. `SpatialLayoutGraph` and friends in `:ai:backend`. The brief requires
   `GenerationRequest` to carry the spatial layout graph, so its *transport shape* must
   exist. The P3 work — turning canvas geometry into it — does not.
3. `BackendRegistry` / DI wiring in `:app`. Required by "DI wiring" in the module list.

Nothing else from P1–P8 is present: no stylus, no brush, no ribbon, no resolver, no
generator, no meter, no fingerprint, no model runtime.

---

## 4. Hot-path allocations and blocking calls

| Path | Status |
| --- | --- |
| Input | Does not exist yet (P1). Nothing to audit. |
| Stroke sampling | Does not exist yet (P1). |
| Motion sampling | **Clean by construction, and tested.** `MotionClip.sampleInto(timeMs, out)` mutates a caller-owned `Pose`; `normalizedTime` is branch-only arithmetic. The convenience `sample()` allocates a `Pose` and is documented as non-hot-path. The test dirties all five pose fields before sampling and asserts the instance is unchanged and every field overwritten, so "no allocation" cannot silently regress into "allocates and copies". |
| Blocking calls | None in any pure module: no IO, no locks, no `Thread.sleep`, no `runBlocking`. `ProceduralMotionBackend.synthesize` is O(1) and a test asserts it completes inside a 2 ms budget. |

---

## 5. Are the new public interfaces testable and tested?

| Interface | Testable without a device? | Tested? |
| --- | --- | --- |
| `ModelBackend` | Yes — pure JVM, `Flow`-returning | Contract shape tested via DTO/event tests; a fake backend is exercised |
| `MotionBackend` | Yes | **Yes**, 6 tests, real implementation |
| `StyleFingerprintProvider` | Yes | P0 impl returns `null`; asserted |
| `GenericnessScorer` | Yes | P0 impl throws rather than fabricate; asserted |
| `MotionClip` / `Pose` | Yes | **Yes**, 10 tests |
| `CanvasTransform` | Yes | **Yes**, 5 tests |

---

## 6. Prioritised fix list

Applied in this phase unless marked otherwise.

### P1 — must fix before P0 can be called done

1. **Resolve the Gradle pins and run `assembleDebug`, `lint`, `test`.** *(not applied —
   impossible here)* The catalog versions are a considered baseline, not a verified set.
   Expect at least one bump (AGP/compileSdk pairing and Compose BOM are the likely ones).
   Record any bump in `DECISIONS.md`.
2. **Commit the Gradle wrapper.** *(not applied — needs a machine with Gradle:
   `gradle wrapper --gradle-version 8.11.1`)* Without `gradle/wrapper/gradle-wrapper.jar`
   the CI workflow cannot run and the DoD line "assembleDebug succeeds" is unverifiable
   for everyone, not just this sandbox.
3. **Launch on an emulator and one physical tablet.** *(not applied — no device)* Confirm
   the empty canvas pans and zooms at refresh rate, and that the theme applies without a
   transparent-window flicker on API 29.

### P2 — should fix, small

4. **Reconsider the `suspend` signature** (DECISIONS D4). One-line change if you want
   literal fidelity to the brief. Currently a stated deviation.
5. **Add JankStats + the hidden-gesture FPS overlay.** *(P1's job in practice)* The brief
   lists it under P0's instrumentation. Deferred because there is nothing meaningful to
   measure on a canvas with no draw path; flagged rather than silently dropped.

### P3 — nice to have

6. **Kotlin version alignment.** The authoring sandbox could only obtain Kotlin 2.4.21 to
   *verify* code; the catalog pins 2.1.21 for the real build. The pure modules were
   compiled on 2.4.21, which is stricter in a few places (explicit API, unused-parameter
   warnings), so 2.1.21 should also accept them — but this is an inference, not a
   measurement. After fix 1, either pin 2.1.21 confidently or bump both together.
7. **`tools/check-architecture.py` R2 heuristic.** It only inspects file names matching
   `Generation|Request|Spec|Intent`. A prompt field on a differently-named type would slip
   through. Cheap improvement: also scan any class annotated `@Serializable`.
8. **`ALLOWED_EDGES` pruned.** `:data → :core:ink` was unused and has been removed;
   `:design → :core:ink` remains because P1's tool chrome will need the canvas transform.

---

## 7. Honest gaps (environment, stated once)

The authoring environment had: no JDK, no Android SDK, and no route to Google Maven,
Maven Central or GitHub release assets. It *did* have PyPI, npm and the GitHub API, which
is how a JDK 21 and a Kotlin 2.4.21 compiler were obtained to verify the code that could
be verified. Consequences, all reflected above:

- The Android/Gradle build is unverified. This is the single largest gap in P0.
- No device, no emulator, no latency numbers. Any performance claim in P0 would be
  fabricated, so none is made.
- The wrapper jar is absent rather than fake-generated.

Nothing in the repo claims otherwise, and no test was weakened to make a check pass.
