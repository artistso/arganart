# Performance contract

Established at P0 so every later phase is measured against the same numbers rather than
against a feeling. The budgets are the brief's; the *instrumentation* arrives with the
phases that can be measured.

## Budgets

| Path | Metric | Budget | Enforced from |
| --- | --- | --- | --- |
| Ink input-to-photon | median | < 16 ms | P1 (overlay), P10 (CI) |
| Ink input-to-photon | p95 | < 24 ms | P1 (overlay), P10 (CI) |
| Ink frame budget @120 Hz | per frame | 8 ms | P0 (design constraint), P10 |
| Motion sampling | per sample | 0 allocations | P0 (API shape), P10 (CI scan) |
| Canvas pan/zoom | frame time | ≤ display refresh | P0 (emulator + device check) |
| Generation | UI-thread blocking | 0 jank spikes | P7 |
| Memory | peak RSS | bounded per RAM class | P10 |
| Stability | 50 project open/close | no leak trend | P10 |

## What exists at P0

- **Design constraints, not measurements.** The two things worth asserting before any
  pixel is drawn:
  1. `MotionClip.sampleInto` writes into a caller-owned `Pose`; resampling allocates
     nothing. Pinned by a test (`sampleInto writes the caller-owned pose and never
     reallocates it`).
  2. `:core:*` have no Android dependency, so no hidden `Context`, no `Bundle`, no
     per-frame object graph from the framework.
- **Architecture check in `./gradlew check`.** R6 refuses a sampling API without an
  allocation-free variant, so a future hot path cannot be added without one.

## What does *not* exist yet, deliberately

JankStats, the debug FPS/frame-time overlay, Macrobenchmark tests, the allocation scan,
LOD, and thermal policy. The P0 brief lists the *overlay and JankStats integration* as a
P0 deliverable; that item is **not delivered** and is tracked in
`docs/reviews/P00-review.md` as an open item with its owner phase, because measuring a
canvas that cannot yet draw would produce numbers with no meaning. This is a stated
deviation, not an oversight.

## Measurement rules (for every later phase)

1. Numbers come from a physical device, not an emulator. Emulator frame times are
   fiction.
2. Report median **and** p95. A median that hides a janky tail is a lie.
3. A regression beyond 10% fails the build once Macrobenchmark lands in CI (P10).
4. Any budget that is missed is written down here with the date, the device and the
   reason. Budgets are not edited to match results.
