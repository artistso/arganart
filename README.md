# Argan

An AI-assisted drawing and animation studio for Android tablets with an active stylus.
The thesis is **sculpt, don't generate**: you arrange things on a canvas, and those
arrangements *are* the prompt. There is no text prompt field in the product, and there
never will be.

> **Current state: P0 — scaffolding only.**
> The app launches to an empty infinite canvas that pans and zooms. There is no drawing,
> no motion, no AI. That is the phase contract, not a bug.

## Docs

| File | What it is |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | The six non-negotiable rules, the module graph, the data contracts. Normative. |
| [DECISIONS.md](DECISIONS.md) | Every stack choice, what was rejected, and when to revisit. |
| [PERFORMANCE.md](PERFORMANCE.md) | The budgets every phase is measured against. |
| [docs/phases/](docs/phases/) | The phase briefs, P00–P10. |
| [docs/guardrails/](docs/guardrails/) | The review, stop-scope and blocked protocols, verbatim. |
| [docs/reviews/P00-review.md](docs/reviews/P00-review.md) | P0's self-review against the guardrail protocol: what is done, what is not, with evidence. |

## Modules

```
:app            application shell, navigation, DI wiring (composition root)
:core:ink       stylus input, stroke model, raster/vector engine   (pure JVM)
:core:motion    motion model, timing, procedural engine           (pure JVM)
:core:spatial   canvas geometry -> intent                          (pure JVM)
:ai:backend     ModelBackend / MotionBackend interfaces + DTOs     (pure JVM)
:ai:runtime     backend implementations (stub procedural motion only)
:feature:canvas the infinite canvas surface
:feature:motion the motion ribbon editor (empty until P2)
:data           project persistence, settings, encrypted storage
:design         design tokens, typography, components
```

## Build

Requires JDK 17+ and the Android SDK (API 36 platform, build-tools).

```bash
./gradlew assembleDebug     # app
./gradlew test              # all unit tests
./gradlew lint
./gradlew check             # runs the architecture rules too
```

`python3 tools/check-architecture.py` on its own runs only the architectural rules and
needs no Android SDK at all — it is the fastest useful check on a fresh machine.

### Gradle wrapper

`gradle/wrapper/gradle-wrapper.jar` is a binary and was **not** generated in the
environment that wrote P0. Regenerate it once on a machine with Gradle installed:

```bash
gradle wrapper --gradle-version 8.11.1
```

Commit the resulting `gradlew`, `gradlew.bat` and `gradle/wrapper/*`. CI then works
unchanged.

## Verifying the pure core without Android tooling

`:core:*` and `:ai:*` are plain JVM Kotlin, so they can be compiled and their tests run
anywhere a Kotlin compiler and a JDK exist:

```bash
KOTLINC=$(command -v kotlinc) tools/kotlin-verify/verify.sh
```

It compiles the five pure modules, compiles their unit tests unmodified, runs them, and
prints a pass/fail summary. See `tools/kotlin-verify/` for exactly what it shims and why.
