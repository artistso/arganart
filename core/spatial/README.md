# :core:spatial

Pure Kotlin. **No UI, no Android types**.

## What exists in P0

Nothing but this README. The module compiles so the graph specified by the P0 brief is
complete and so the architecture check can prove no resolver has been written early.

## What is deliberately absent

The canvas object vocabulary (sketch, colour swatch, texture card, motion arrow, frame,
reference card), the spatial graph, role assignment by containment and proximity, the
resolved JSON `GenerationRequest`, and the debug/teaching overlay. All of that is **P3**
(`docs/phases/P03-spatial-resolver.md`).

The *transport* shape of a resolved request (`SpatialLayoutGraph` in `:ai:backend`) does
exist, because the P0 brief requires `GenerationRequest` to carry the spatial layout
graph. Building it from canvas geometry is P3 and is not started.
