# :core:ink

Pure Kotlin. **No UI, no Android types** (ARCHITECTURE.md rules 3 and 4). Nothing in this
module may import `android.*`, `androidx.*`, or Compose.

## What exists in P0

- `CanvasTransform` — the infinite canvas's pan/zoom maths, shared by the viewport
  and (later) the tile cache. Tested in `CanvasTransformTest`.

## What is deliberately absent

Stylus input, stroke model, stamps, brushes, smoothing, tiling, history. All of that is
**P1** (`docs/phases/P01-ink-engine.md`) and adding any of it to P0 is a scope violation.
`tools/check-architecture.py` enforces the absence of the P1 vocabulary in this module.
