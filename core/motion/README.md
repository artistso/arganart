# :core:motion

Pure Kotlin. **No UI, no Android types**. Nothing may import `android.*`, `androidx.*`,
or Compose.

## What exists in P0

- `MotionClip`, `Pose`, `Easing`, `EasedTranslationClip` — the contract that
  `MotionBackend` returns, and the "simple eased translation" the P0 brief requires.

The P0 brief lists `MotionBackend` as an interface only, implemented by a stub in
`:ai:runtime`. A *return type* is not an implementation, so the clip type lives here:
it is the value type the interface trades in, and the fallback backend in P8 depends on
it. `Pose.sampleInto` is allocation-free by design, and that is tested now rather than
retrofitted in P10 (see `EasedTranslationClipTest`).

## What is deliberately absent

The ribbon, spines, motion nodes, springs, pose markers, transition painting. That is
**P2** (`docs/phases/P02-motion-ribbon.md`) and **P8**. There is no timeline type here
and there will not be one: time is position along the ribbon (P2's no-timeline rule).
