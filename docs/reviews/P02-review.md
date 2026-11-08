# P2 review — motion ribbon

Following `docs/guardrails/phase-review.md`.

**Verdict: the motion model is real and tested; the ribbon's gestures are partial and
nothing has been touched on hardware.** The phase brief has four gesture families. Two
of them exist as controls, two are modelled but unreachable.

---

## 1. Definition of Done

| # | Item | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Static drawing → 3-second eased, spring-weighted motion in under 30 s with no instructions | **PARTIAL** | The app opens with a live 3-second spring-weighted ribbon already animating, so *seeing* motion takes zero seconds and zero instructions. *Authoring* one from a drawing is not yet possible: the ribbon is a fixed default, not something drawn. |
| 2 | Editing the ribbon during playback does not drop ink frames | **PARTIAL** | Structurally guaranteed and worth separating from measured: the ribbon is a separate Compose `Canvas` above the ink `View`, the clip is immutable so a frame in flight cannot tear, and no edit invalidates the ink surface. But no frame times were measured, so "does not drop" is an argument, not an observation. |
| 3 | Every motion state is serializable and reloads identically | **YES** | `MotionClipSnapshot` round-trips asserted **pose by pose** across the whole clip, plus 20 ribbon-state tests including a full state round trip through `MotionRibbonState`. |

**DoD 1 deserves direct criticism.** The brief says "from a static drawing to a
3-second motion in under 30 seconds". What shipped is a ribbon that is already there.
That is a different thing from authoring motion *from* a drawing — the drawing does not
yet influence the motion at all. The honest framing: P2 made motion visible and
sculptable, and did not make it *derived*. Deriving it from stroke shape is what P8's
neural motion and P3's spatial roles are for.

---

## 2. Rule compliance

| Rule | Result |
| --- | --- |
| 1 · on-device only | PASS — no `INTERNET` permission; nothing in P2 goes near a network |
| 2 · no text prompt | PASS |
| 3 · UI never talks to a model | PASS — `:feature:motion` depends on `:core:motion` only; the procedural backend is reached through `:ai:backend`'s interface from `:app` |
| 4 · ink runs at 120 Hz with AI disabled | PASS by construction — the ribbon never runs on the ink frame; `:core:motion` is pure JVM |
| 5 · cancellable + progress | n/a — synthesis is a synchronous, budgeted call (measured median well under 1 ms) |
| 6 · 8 ms frame budget | **PARTIAL** — hot paths are allocation-free by construction and asserted by test; no frame time measured |

---

## 3. The no-timeline rule

No ruler, no playhead, no keyframe track. `MotionRibbonState` has `timeMs` because
*something* must resolve a pose, but a user never sets it directly: scrubbing sets it from
a *position on the ribbon*, and `scrubToDistance` is the entry point the gesture uses.
There is no duration field, no loop toggle and no transport bar — `MotionControls` is
two buttons, because a transport bar is how a timeline gets reintroduced one control at a
time.

I did not find a sub-case where a timeline was unavoidable, so the brief's "stop and ask"
clause was not triggered.

---

## 4. What the brief asked for vs what is reachable

| Gesture | Model support | Reachable in the UI |
| --- | --- | --- |
| Drag a segment perpendicular to bend the path | `moveSpinePoint` — tested | **No** (long-press drops a node instead; bend has no gesture) |
| Pinch a segment to compress / stretch | `speedMultiplier` — tested | Via the Speed slider, not a pinch |
| Long-press to drop a node | `addNodeAt` — tested and wired | **Yes** |
| Drag a node to reshape character | `updateSelectedNode` — tested | **Yes**, via tap + inspector |
| Twist with two fingers to rotate | `rotationDeltaDegrees` — modelled, interpolated, persisted | **No** |
| Drag anywhere to scrub | `scrubTo` by distance — tested | **Yes** |

The two missing gestures (pinch, twist) need multi-pointer arbitration with the ink
surface, which currently consumes stylus and routes fingers to an unused hook. Bolting
them on now would either fight the stylus or silently do nothing on the first two-finger
attempt. P9 owns that arbitration; this is recorded rather than faked.

---

## 5. Bugs the tests caught

All seven were real; each is now a named invariant or a code comment at the site.

| # | Bug | Why it mattered |
| --- | --- | --- |
| 1 | Overdamped spring returned 2.0 at t=0 | RELUCTANT started halfway along its path and never travelled |
| 2 | Clamping overshoot created plateaus | Elastic "parked" 140 ms at each node — a visible stutter |
| 3 | Uniform speed multipliers cancelled out | A "faster" clip sampled identically to a slower one |
| 4 | Normaliser used a different time argument than the numerator | Every segment finished at 2% of travel; the element stopped short of every node |
| 5 | `>` instead of `>=` at a segment boundary | A hold at an interior node showed the wrong position for its entire duration |
| 6 | `<= 0f` as a "not cached" sentinel | A legitimate fractional value was recomputed every sample and disagreed with its cache |
| 7 | `nodes.size - 1` used as a segment index | `ArrayIndexOutOfBoundsException` at exactly t = durationMs |

The pattern worth naming: **five of the seven were index or sentinel mistakes, not
conceptual ones.** The design was mostly right and the arithmetic kept being wrong. The
tests that found them were the ones asserting *shape* (monotone, reaches the end,
overshoots by a bounded amount), not the ones asserting a specific number.

---

## 6. The user-reported defect

The P2 work was interrupted by feedback: "black canvas, minimal features, looks like AI
made it." That was accurate and it was not about missing features.

Three causes, all fixed:

1. **The default theme was the dark one.** Following the system meant most devices opened
   to a near-black rectangle.
2. **The ink colour was hardcoded near-black.** Drawing on the dark canvas produced
   *invisible ink* — the worst failure mode a drawing app has.
3. **Strokes rendered at constant width.** 4096 pressure levels were carried from
   `MotionEvent` through the whole pipeline and discarded at render time, so every stroke
   looked like a cable.

Plus: the tile lattice is now drawn (the document's real 512-unit structure, not
decoration), a welcome card shows a pressure-varying stroke drawing itself and leaves for
good on the first stroke, and the chrome is one labelled pill instead of three floating
buttons containing raw text.

This is recorded in `DECISIONS.md` as D19 with a rule: **the default configuration must be
the one that demonstrates the product.**

---

## 7. What CI caught

Four compile errors, all one class of mistake — assuming an API shape instead of reading
it:

1. `libs.androidx.input.motionprediction` had a dependency line but no catalog **alias**.
   Kotlin reported it as three unrelated type-inference failures at the use site.
2. An `Int` colour was passed where `BrushParams` was expected, in a property setter.
3. `kotlin.math.sin` returns `Double`; the result was assigned to a `Float`.
4. `ArganTheme.colors` (a `@Composable` property) was read inside a `Canvas { }` draw
   scope.

`FilterChipDefaults.filterChipColors`' parameter names were verified against AndroidX
source rather than guessed, which is the only reason the inspector was not a fifth.

---

## 8. Prioritised fixes

1. **Touch it on hardware.** Nothing in this phase has run on a device. The ribbon's
   gestures, the welcome card's timing and the tile lattice's visual weight are all
   written, not tested.
2. **Bend gesture.** `moveSpinePoint` is tested and unreachable. It is the brief's first
   listed gesture.
3. **Pinch and twist**, once the ink surface's multi-pointer arbitration exists (P9).
4. **Measure the "no dropped ink frames" claim** with the existing overlay rather than
   arguing it.
5. **Derive the ribbon from the drawing.** DoD 1 is only half met until a drawing can
   produce motion without the user placing a ribbon.
