# Architecture

Argan is an AI-assisted drawing and animation studio whose thesis is **"sculpt, don't
generate."** The user arranges objects on a canvas; those arrangements are the prompt. A
model may refine; it is never the starting point and never load-bearing.

This document is normative. The six rules below are enforced mechanically by
`tools/check-architecture.py`, which runs on every `./gradlew check` and in CI. A rule
that can only be enforced by review is a rule that will be broken.

Phase P0 established the skeleton. Later phases fill modules in without changing the
edges below; changing an edge is an architectural change and requires an update to this
file in the same commit.

---

## 1. The six non-negotiable rules

### Rule 1 — All AI is on-device. No network for generation, ever, in v1.

The app ships **without** `android.permission.INTERNET`. This is deliberate armour: the
platform, not code review, enforces the rule.

Network is permitted only for an optional model download, behind a user-visible toggle
(introduced in P7, and the only phase allowed to touch the permission).

*Enforced by:* `check-architecture.py` R1 — manifest permission check plus a scan for
`java.net.*` socket/HTTP APIs and known HTTP clients in every module.

### Rule 2 — No text prompt field anywhere in the product. Spatial layout is the prompt.

There is no prompt input, no prompt string on any request DTO, no `description` carrying
intent. Intent is expressed by placing objects: a sketch is content, a colour swatch a
style modifier, a motion arrow direction and speed, and so on (P3).

*Enforced by:* R2 — scans request-shaped types (`Generation*`, `*Request`, `*Spec`,
`*Intent`) for `String` fields named like `prompt`/`textPrompt`/`description`/`userText`.
`GenerationRequest` carries only geometry, an assist level, a seed and an **opaque**
fingerprint reference.

### Rule 3 — The UI layer never talks to a model directly.

The only door to a model is `ModelBackend`; the only door to motion synthesis is
`MotionBackend`; both live in `:ai:backend` as interfaces plus DTOs. Feature modules
depend on `:ai:backend` only.

`:app` is the **composition root** and the single exception: it may construct the
concrete registry. It may not call into an implementation — it hands the interfaces out.

*Enforced by:* R3 — no module except `:app` may depend on `:ai:runtime`; `:app` may import
exactly `art.argan.ai.runtime.BackendRegistry`.

### Rule 4 — The ink layer runs at 120 Hz on a mid-range 2023 tablet with the AI layer
completely disabled.

`:core:ink`, `:core:motion` and `:core:spatial` are **pure JVM Kotlin modules**. They have
no Android plugin, no Android dependency, and no Compose import. They are unit-testable on
the host, which is also how their hot paths are proven allocation-free in CI.

AI modules are additive: deleting `:ai:runtime` from the dependency list must leave a
compiling canvas.

*Enforced by:* R4 — pure modules must not apply `com.android.*` and must not import
`android.*`, `androidx.*`, or `com.android.*`.

### Rule 5 — Every long-running operation is cancellable and reports progress.

`GenerationRequest` carries a `CancellationToken` that is serialisable and independent of
any coroutine machinery, so a request survives being written to a project manifest and
resumed. `GenerationEvent` is the progress channel: `Progress`, `Partial`, `Result`,
`Error`. `MotionBackend.synthesize` is budgeted (`budgetMs`) so a caller can bound a
frame. Every backend implementation must observe cancellation at least once per emitted
event; a cancelled result is reported as `Error(cancelled = true)` or
`GenerationCancelledException`, never as a success.

### Rule 6 — 8 ms frame budget for 120 Hz input-to-photon on the ink path.

Hot-path types are designed for it from the start rather than optimised later:

- Sampling takes a caller-owned destination and writes in place: `MotionClip.sampleInto(t, pose)`.
  The allocating `sample(t)` convenience form exists for tests and export only.
- `Pose` is caller-owned, mutable and reused; resampling never allocates.
- Time normalisation is branch-only arithmetic.

*Enforced by:* R6 — any type declaring `sampleX(...)` must also declare an `...Into(...)`
variant. P10 adds the CI allocation scan over `:core:ink`, `:core:motion` and the input
adapter.

---

## 2. Module graph

```
                            ┌──────────────────────────┐
                            │          :app            │  shell, navigation, DI
                            └──────────────────────────┘
                              │      │       │      │
        ┌─────────────────────┘      │       │      └────────────────────┐
        ▼                            ▼       ▼                           ▼
┌───────────────┐            ┌─────────────┐ ┌────────────┐      ┌──────────────┐
│:feature:canvas│            │:feature:    │ │ :data      │      │:ai:runtime   │
│  (canvas UI)  │            │  motion     │ │persistence │      │ impl + stubs │
└───────────────┘            └─────────────┘ └────────────┘      └──────────────┘
        │                            │              │                    │
        ▼                            ▼              ▼                    ▼
┌───────────────┐            ┌─────────────┐  ┌────────────┐     ┌──────────────┐
│   :design     │            │:core:motion │  │ :core:ink  │     │ :ai:backend  │
│ tokens, type  │            │ model, time │  │ transform  │     │ IFACE + DTO  │
└───────────────┘            └─────────────┘  └────────────┘     └──────────────┘
        │                            │                                  │
        ▼                            ▼                                  ▼
┌──────────────────────────────────────────────┐              ┌────────────────┐
│                  :core:ink                   │              │  :core:motion  │
└──────────────────────────────────────────────┘              └────────────────┘

                    :core:spatial      :core:diagnostics
                   (pure; depends on  (frame statistics; fed by
                    nothing)           JankStats in :app)
```

Edges, as permitted by `ALLOWED_EDGES` in `tools/check-architecture.py`:

| Module | May depend on |
| --- | --- |
| `:app` | `:design`, `:feature:canvas`, `:feature:motion`, `:core:ink`, `:core:motion`, `:core:spatial`, `:core:diagnostics`, `:ai:backend`, `:ai:runtime`, `:data` |
| `:feature:canvas` | `:design`, `:core:ink` |
| `:feature:motion` | `:design`, `:core:motion` |
| `:ai:backend` | `:core:motion` (shared motion value types) |
| `:ai:runtime` | `:ai:backend` |
| `:design` | `:core:ink` |
| `:data` | `:core:ink` |
| `:core:*` | nothing in the product |

**Direction rules.** Core never points at AI. AI never points at UI. UI never points at a
concrete runtime except through the app's composition root. `:core:spatial` pointing at
nothing is intentional: it is the pure geometry→intent translator and must remain
importable by any module, including a future test harness or CLI.

### Why `:ai:backend` depends on `:core:motion`

`MotionBackend.synthesize` returns a `MotionClip`, and `GenerationRequest` is shared
vocabulary. Leaving the clip type in `:ai:backend` would force `:core:motion` (the model,
time and procedural engine) to depend on the AI module, inverting the layering. The
dependency is therefore `:ai:backend → :core:motion`, one direction only.

---

## 3. Boundaries that are deliberately empty at P0

`tools/check-architecture.py` does not just whitelist modules; it asserts the *absence* of
later-phase vocabulary, so a scaffold cannot quietly grow into P1–P8:

| Module | Empty at P0 on purpose | Filled by |
| --- | --- | --- |
| `:core:ink` | stroke model, stamps, brushes, smoothing, tiles, history | P1 |
| `:core:motion` | ribbon, spines, motion nodes, springs, pose markers | P2, P8 |
| `:core:spatial` | canvas objects, graph, role resolution, JSON request | P3 |
| `:feature:motion` | the entire ribbon editor | P2 |
| `:data` | project format body, autosave, journaling, encryption | P9 |
| `:ai:runtime` | any model, any download, any ONNX session | P7 |

`core:diagnostics` is the one module not named in the brief's module list. It exists
because the P0 brief requires JankStats and a debug FPS/frame-time overlay as
*instrumentation*, and because P1's Definition of Done is stated as "<1% janky frames" —
a number that must be produced by tested arithmetic rather than by eyeballing a log. The
statistics live in a pure module so they are unit-testable on the host; only the
`JankStats` bridge is Android code, in `:app`. Rationale in DECISIONS.md D12.

What *does* exist in the "AI" module at P0 is the procedural `MotionBackend` and a
`ModelBackend` that reports "not in this build". Motion is procedural because the brief
requires the P0 app to be provably animatable with zero AI present (Rule 4), and because
this implementation is the permanent fallback in P8.

---

## 4. Data contracts that later phases must not break

- `GenerationRequest` is written verbatim into project manifests (P9). Changing the
  meaning of a field is a **format break**; adding an optional field is not.
- `GenerationRequest.cacheKey` excludes `cancellationToken` (transport, not content) and
  includes the layout fingerprint, the style reference hash, assist level and seed.
  Same key + same model version must produce identical bytes (P7 determinism).
- `ProjectFormat.FORMAT_VERSION` may only be raised when older readers still open the file.
- `StyleFingerprintRef` is an opaque handle. Fingerprint *contents* never travel with a
  request, and never into logs or manifests.

---

## 5. Conventions

- Kotlin 2.x, Gradle Kotlin DSL, version catalog, explicit API mode on every pure module.
- `allWarningsAsErrors = true` everywhere. An unused parameter or a deprecated call fails
  the build rather than accumulating.
- No colours outside `:design` and resources; no user-facing strings outside resources.
- Every public interface in a pure module ships with unit tests on the host JVM.
