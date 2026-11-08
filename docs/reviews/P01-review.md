# P1 review — stylus ink engine

Following `docs/guardrails/phase-review.md`.

**Verdict: code-complete and CI-verified; not device-verified.** Nothing in P1 has been
run on hardware. The 90 host tests and a green `assembleDebug`/`lint`/`test` are real
evidence for the pure core and for compilation; they say nothing about how the ink feels.

---

## 1. Definition of Done

| # | Item | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Stylus drawing on a physical tablet feels like leading note apps at 120 Hz | **UNVERIFIED** | No device in this environment. The user's device is available but has not run this build. |
| 2 | Zero palm rejection failures in a 5-minute handwriting test | **UNVERIFIED** | Palm logic implemented (`StylusEventSource.isPalm`, stylus-present heuristic, abandon-on-second-pointer). No 5-minute test run. |
| 3 | 100-stroke undo/redo round-trips with no visual artifacts | **PARTIAL** | `History` proven for 100 round trips in `TileAndHistoryTest` (pixels identical). The *rendered* result is not verified: the View-backed surface keeps a stroke list, so a redo re-appends rather than restoring tile pixels. |
| 4 | JankStats shows <1% janky frames during continuous drawing | **UNVERIFIED** | Overlay wired end-to-end including an input-to-photon estimate. No reading taken. |
| 5 | No AI, no motion, no spatial prompting | **YES** | `[SCOPE] OK: P1: no code from a later phase is present (14 vocabularies guarded)`. |

**DoD 3 deserves scrutiny rather than a green tick.** The history layer is correct and
tested. The *renderer* is not yet tile-backed, so the tile-diff machinery is exercised in
tests but not by the running app. That is the honest state: the data structure is proven,
its integration is not.

---

## 2. Rule compliance

| Rule | Result |
| --- | --- |
| 1 · on-device only | PASS — no `INTERNET` permission; motion prediction is on-device Jetpack code |
| 2 · no text prompt | PASS |
| 3 · UI never talks to a model | PASS — no AI dependency added |
| 4 · ink runs at 120 Hz with AI disabled | PASS by construction — `:core:ink` still pure JVM, no Android import |
| 5 · cancellable + progress | n/a this phase |
| 6 · 8 ms frame budget | **PARTIAL** — hot paths are allocation-free by design and tested; no frame time measured |

---

## 3. Scope: anything built for a later phase?

Nothing. The scope guard was made phase-aware rather than deleted (see §5).

Three P1 brief items were deliberately **not** implemented, and this is where the phase
falls short of the letter of the brief:

1. **GL/Skia backend.** The brief says OpenGL ES 3.0 or "Skia-backed SurfaceView if you can
   demonstrate equal or better input-to-photon", and to justify the choice in DECISIONS.md.
   Neither can be *demonstrated* without hardware, so a justification would be invented
   evidence. Shipped: a backend-agnostic `View` that implements the input, layer-separation
   and prediction contract so a real baseline can be measured. Recorded as D14.
2. **Tile-backed committed layer + disk spill.** The tile model, LRU eviction and eviction
   reporting are implemented and tested in `:core:ink`, but the renderer does not yet
   rasterise into tiles. Consequence: the committed layer is a stroke list, which is correct
   but will not hold a very large drawing.
3. **Infinite canvas pan/zoom via two-finger gestures on the ink surface.** Finger input is
   routed to a `onFingerGesture` hook that is currently unused; pan/zoom still only exists on
   the P0 `InfiniteCanvas` composable. Fingers on the ink surface do not yet pan.

---

## 4. Hot-path allocations

| Path | Status |
| --- | --- |
| Input | `StylusEventSource.appendRealPoints` appends to a caller-supplied list; no per-sample allocation beyond the `InputPoint` data class the model requires. `Pair<Float,Float>` from the transform lambda **does** allocate per sample — a known cost, flagged for P10. |
| Stroke sampling | Filters and stabiliser use caller-owned state; no allocation per sample (tested). |
| Motion sampling | Unchanged from P0; allocation-free (tested). |
| `onDraw` | Reuses one `Path` and two `Paint`s. Rebuilds the whole in-progress path each frame rather than appending — O(stroke length) per frame, flagged for P10. |

---

## 5. What CI caught

| # | Failure | Lesson |
| --- | --- | --- |
| 1 | `Unresolved reference predictedEvents/getX/getY/eventTime/getPressure` | The P1 brief asks for `MotionEvent.getPredictedEvents()`. **That method does not exist on Android** — it is a W3C Pointer Events API. Verified against AOSP `MotionEvent.java` (zero occurrences of "predict" in the file, main and android14-release) and replaced with `androidx.input:input-motionprediction` (`MotionEventPredictor.newInstance/record/predict`), verified against AndroidX source. |
| 2 | `Material3 FloatingActionButton` has no `enabled` parameter | Caught by reading the AndroidX source before pushing, not by CI. Replaced with a dimmed-but-present action. |

The transferable lesson from P0 held: for any third-party API, **read the source**. This
phase it caught one fabricated API before it cost a CI cycle, and one real defect in the
phase brief itself.

**A note on the brief being wrong.** `getPredictedEvents()` is not the only place the P1
brief assumes an API that Android does not have. Where a brief and the platform disagree,
the intent is followed and the mechanism substituted, with the discrepancy written down —
pretending the brief was implementable as written would have produced either a stub that
does nothing or a lie in DECISIONS.md.

---

## 6. Prioritised fixes

1. **Measure on device.** Install `v0.2.0-p1`, draw for five minutes, read the overlay
   (three taps, bottom-left). Until that happens, DoD 1, 2 and 4 are all assumptions.
2. **Decide the renderer with data.** After (1), choose GL/Skia vs staying on `View` using a
   measured input-to-photon number, not a preference. This is D14's open question.
3. **Tile-backed committed layer.** Needed before a drawing of any real size is tested; the
   data structures already exist and are tested.
4. **Wire two-finger pan/zoom on the ink surface.** Currently absent, and the brief requires
   it.
5. **Remove the `Pair` allocation in the input transform** (P10, but trivial now).
6. **Append to the in-progress path** instead of rebuilding it each frame (P10).
