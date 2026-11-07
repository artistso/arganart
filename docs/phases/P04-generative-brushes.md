Introduce the concept of a "generative brush" as a protocol and a UI affordance,
implemented with a deterministic stub — no AI model in this phase. The goal is to
prove the interaction model: pressure and tilt become bidirectional controls
between human intent and machine suggestion.

ASSIST MODEL
- Every stroke carries an assistLevel (0f..1f), derived at stroke time from
  initial pressure and a user-tunable curve. Light touch = 0 (pure human line).
  Heavy press = 1 (maximum machine suggestion).
- Tilt modulates assistType: near-flat tilt = color/texture suggestion;
  steep tilt = structural continuation (completing the implied form).
- The user can invert or disable this mapping entirely in settings.

STUB IMPLEMENTATION (deterministic, offline, no ML)
- Implement at least three stub generative brushes:
  1. Symmetry brush — mirrors the stroke across the canvas or a user-placed axis,
     with assistLevel controlling how much the mirror deviates (jitter, scale,
     offset) via a seeded PRNG.
  2. Continuation brush — fits a spline to the user's stroke and extends it by
     N control points, with assistLevel controlling extension length and
     curvature variance.
  3. Texture brush — stamps a user-picked procedural texture along the stroke,
     assistLevel controlling density and scale drift.
- All three go through the same GenerativeBrush interface that a real
  ModelBackend will later implement. Define that interface now.

UI / FEEDBACK
- While a generative stroke is in progress, render the machine-suggested portion
  as a semi-transparent "ghost" in a distinct accent color until the stroke
  commits, then fade to the real color over ~150ms.
- A small, always-visible assist indicator at the canvas edge shows the current
  assistLevel as a radial gauge that responds live to pressure.
- No text, no prompt, no dialogs during drawing.

SAFETY
- The committed stroke must always be reproducible from (input points,
  brush params, seed). Store the seed with the stroke.

DEFINITION OF DONE
- All three stub brushes work with stylus pressure and tilt on a physical device.
- assistLevel = 0 produces a bit-identical result to the plain ink brush.
- Ghost rendering never leaves residue after commit or cancel.
- A recorded stroke replays identically from its seed.