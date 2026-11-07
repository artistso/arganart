You are the lead engineer on a new Android app: an AI-assisted drawing and animation studio
whose central thesis is "sculpt, don't generate." Before writing feature code, establish the
project skeleton and hard architectural boundaries. Do NOT build any drawing or AI features
in this phase. This prompt is scaffolding only.

TARGET PLATFORM
- Android, minSdk 29, targetSdk and compileSdk = latest stable.
- Primary form factor: tablets with active stylus (Samsung S Pen, USI 2.0, Wacom AES).
  Phone support is secondary. Design for landscape-first, large screens.
- Kotlin 2.x, Jetpack Compose for UI chrome, Gradle Kotlin DSL, version catalog.

MODULE STRUCTURE (create exactly this, empty-but-compiling)
- :app                      — application shell, navigation, DI wiring
- :core:ink                 — stylus input, stroke model, raster/vector engine (no UI)
- :core:motion              — motion model, timing, procedural animation engine (no UI)
- :core:spatial             — spatial prompt resolver (canvas geometry -> intent)
- :ai:backend               — ModelBackend and MotionBackend INTERFACES ONLY + request/response DTOs
- :ai:runtime               — implementations of the backends (stubs for now)
- :feature:canvas           — the infinite canvas surface
- :feature:motion           — the motion ribbon editor
- :data                     — project persistence, settings, encrypted storage
- :design                   — design tokens, typography, custom components

DEFINE THESE INTERFACES NOW (signatures only, no implementations)
- ModelBackend: suspend fun generate(request: GenerationRequest): Flow<GenerationEvent>
  with GenerationRequest carrying: spatial layout graph, style fingerprint reference,
  assist level (0f..1f), mutation seed, cancellation token.
  GenerationEvent = Progress | Partial | Result | Error.
- MotionBackend: fun synthesize(spec: MotionSpec, budgetMs: Int): MotionClip
  with a procedural fallback implementation that must exist and work in P0.
- StyleFingerprintProvider: suspend fun current(): StyleFingerprint
- GenericnessScorer: fun score(candidate: ByteArray): Float

NON-NEGOTIABLE ARCHITECTURAL RULES (write these into ARCHITECTURE.md)
1. All AI work is on-device. No network calls for generation, ever, in v1.
   Network is permitted only for optional model download, behind a user-visible toggle.
2. No text prompt field anywhere in the product. Spatial layout is the prompt.
3. The UI layer never talks to a model directly — only through ModelBackend/MotionBackend.
4. The ink layer must be able to run at 120Hz on a mid-range 2023 tablet with the
   AI layer completely disabled. AI is additive, never load-bearing.
5. Every long-running operation is cancellable and reports progress.
6. Frame budget: 8ms for 120Hz input-to-photon on the ink path.

PERFORMANCE INSTRUMENTATION
- Integrate JankStats and a debug-only FPS/frame-time overlay toggleable via a
  hidden gesture. Every subsequent phase must be measured against it.

DELIVERABLES
- Compiling app that launches to an empty infinite canvas (pan/zoom only, no drawing).
- ARCHITECTURE.md with the six rules above plus a module dependency diagram.
- DECISIONS.md with the stack choices and rejected alternatives.
- A stub procedural MotionBackend that returns a simple eased translation.
- CI: assemble + lint + unit test on push.

DEFINITION OF DONE
- `./gradlew assembleDebug` succeeds.
- App launches on an emulator and on one physical tablet if available.
- Empty canvas pans and zooms smoothly at the display refresh rate.
- No feature code for drawing, AI, or animation exists yet.