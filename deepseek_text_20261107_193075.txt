Implement the stylus ink engine in :core:ink and wire it into :feature:canvas.
This is the foundation of the entire product — it must feel better than any
consumer drawing app on Android. Do not add any AI to this phase.

INPUT
- Consume raw MotionEvent from a SurfaceView-hosted input listener (not Compose
  pointer input) to minimize latency.
- Handle TOOL_TYPE_STYLUS and TOOL_TYPE_ERASER; ignore palm and finger for drawing
  (finger = pan/zoom).
- Use getHistoricalX/Y/Pressure/Tilt/Orientation for all batched points.
- Use getPredictedEvents() to render up to 2 predicted frames ahead; render
  predictions in a separate, visually identical layer that is replaced (not
  blended) when real points arrive, so prediction never contaminates the
  committed stroke.
- Request unbuffered input where available (View.requestUnbufferedDispatch).
- Support pressure (0..1), tiltX/tiltY, orientation, and velocity.

STROKE MODEL (pure Kotlin, no Android types in the core model)
- Stroke = list of InputPoint(timestamp, x, y, pressure, tilt, orientation) +
  BrushParams.
- Smoothing: one-euro filter on position, Catmull-Rom or centripetal Catmull-Rom
  for curve fitting, with a tunable smoothing strength.
- Stroke stabilizer: optional lag-based stabilizer with a visible "leash" ring.
- Brush params: size, opacity, spacing, hardness, flow, and a Stamp interface
  so brushes are pluggable (round stamp for now).

RENDERING
- Backend: OpenGL ES 3.0 via GLSurfaceView (or Skia-backed SurfaceView if you can
  demonstrate equal or better input-to-photon). Justify the choice in DECISIONS.md.
- Independent layers: committed artwork layer, in-progress stroke layer, predicted
  stroke layer, selection/overlay layer.
- Infinite canvas: tile-based committed layer (e.g. 512px tiles, LRU-cached,
  disk-backed above a threshold), with pinch-zoom and two-finger pan.
- Pressure must drive width and opacity with a configurable response curve,
  previewed live in a settings sheet.

HISTORY
- Command-pattern undo/redo, tile-diff based for memory efficiency, at least 100
  steps, with a visible history scrubber.
- Redo stack invalidated correctly on new strokes.

MEASUREMENT
- Ship a debug overlay showing: input-to-photon latency (estimate via
  event timestamp vs. frame present time), dropped events, and frame time.
- Target: median input-to-photon under 16ms on a 120Hz tablet.

DEFINITION OF DONE
- Drawing with a stylus on a physical tablet feels indistinguishable from
  leading note-taking apps at 120Hz.
- Zero palm rejection failures in a 5-minute handwriting test.
- 100-stroke undo/redo round-trips with no visual artifacts.
- JankStats shows <1% janky frames during continuous drawing.
- No AI, no motion, no spatial prompting exists yet.