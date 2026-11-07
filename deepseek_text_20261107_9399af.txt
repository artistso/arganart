Harden the app to hit its performance contract under real conditions. No new
features in this phase — only measurement, optimization, and degradation
strategy.

BUDGETS (write them into PERFORMANCE.md and enforce with tests)
- Ink input-to-photon: median <16ms, p95 <24ms on a 2023 mid-range tablet.
- Motion playback: no dropped frames during simultaneous ink + motion +
  generation.
- Generation: must never block the UI thread; measured by zero jank spikes
  during generation.
- Memory: peak RSS bounded per RAM class; no leaks across 50 project
  open/close cycles.

WORK
- Profile with Perfetto and Macrobenchmark; add Macrobenchmark tests for
  app startup, first draw, and 60-second continuous drawing.
- Implement a Level-of-Detail system: degraded preview quality during
  interaction, full quality on idle.
- Implement thermal and battery policy: automatic step-count reduction,
  resolution scaling, and honest user-facing status.
- Background/foreground handling: cancel or pause generation on background,
  resume cleanly, never lose committed strokes.
- Fix every allocation in the hot path (input, stroke sampling, motion
  sampling). Add a CI check that flags allocations in these files.

DEFINITION OF DONE
- All budgets met on at least two physical devices (one mid-range, one flagship).
- Macrobenchmark suite runs in CI and fails the build on regression beyond 10%.
- A 30-minute stress test (draw + animate + generate continuously) shows no
  memory growth trend and no ANRs.