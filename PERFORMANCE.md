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

- **JankStats collection and a debug frame overlay**, as the brief requires. The statistics
  (`:core:diagnostics`) are pure Kotlin with 18 host-runnable tests; the Android bridge and
  the overlay (three taps, bottom-left corner) are in `:app` and are debug-only.
- **A verdict, not a vibe.** `FrameReport.verdict` is `PASS`/`FAIL`/`INSUFFICIENT_DATA`,
  and reports `INSUFFICIENT_DATA` below 60 frames rather than optimistically passing.
- **Design constraints, not measurements.** The two things worth asserting before any
  pixel is drawn:
  1. `MotionClip.sampleInto` writes into a caller-owned `Pose`; resampling allocates
     nothing. Pinned by a test (`sampleInto writes the caller-owned pose and never
     reallocates it`).
  2. `:core:*` have no Android dependency, so no hidden `Context`, no `Bundle`, no
     per-frame object graph from the framework.
- **Architecture check in `./gradlew check`.** R6 refuses a sampling API without an
  allocation-free variant, so a future hot path cannot be added without one.

### Known limitation, stated

The overlay cannot show input-to-photon yet: JankStats does not expose it, and the stylus
event path it would be derived from does not exist until P1. The overlay prints
`i2p: n/a (P1)` rather than a plausible-looking number.

## What does *not* exist yet, deliberately

Macrobenchmark tests, the allocation scan, LOD and thermal policy. These are P10, and
each needs a draw path to measure.

An earlier draft of this file deferred JankStats and the overlay to P1 on the grounds that
there was nothing yet worth measuring. That was wrong: `docs/phases/P01-ink-engine.md` has
"JankStats shows <1% janky frames" in *its* Definition of Done, so deferring the
instrumentation would have blocked the next phase rather than postponing work. Both now
ship in P0.

## CI status

`assembleDebug`, `lint` and `test` pass in CI (run 42374315085). The 57 host tests run on
every push. No numbers in this file are measurements yet, and none will be added from an
emulator.

## Measurement rules (for every later phase)

1. Numbers come from a physical device, not an emulator. Emulator frame times are
   fiction.
2. Report median **and** p95. A median that hides a janky tail is a lie.
3. A regression beyond 10% fails the build once Macrobenchmark lands in CI (P10).
4. Any budget that is missed is written down here with the date, the device and the
   reason. Budgets are not edited to match results.
