Add the two features that make animation feel like drawing rather than
programming: transition painting, and real synthesized motion.

TRANSITION PAINTING
- Introduce Pose Markers: the user stamps a pose at a point on the Motion Ribbon
  (either a transformed copy of the current drawing, or a separately drawn pose).
- A Motion Brush lets the user drag from one Pose Marker to the next, painting
  the transition. The stroke's shape, speed, and pressure profile determine the
  transition character:
  - Slow, even stroke -> smooth/eased
  - Fast, short flick -> snappy
  - Overshooting arc -> elastic
  - Hesitant, jittery stroke -> reluctant/stuttering
- Map stroke features (velocity, curvature, pressure variance) to the
  interpolation parameters in :core:motion. Document the mapping; make it
  tunable and previewable.

NEURAL MOTION BACKEND
- Implement a second MotionBackend that synthesizes motion from a MotionSpec
  using an on-device model (e.g. a small motion-matching or diffusion-based
  motion generator producing skeletal or transform trajectories).
- Hard constraint: synthesize within a 2ms-per-chunk budget, streaming results
  as chunks so playback starts immediately and refines as it goes.
- Procedural MotionBackend remains the fallback and must produce acceptable
  results when the neural backend is unavailable or throttled.
- Latent correction: the user must be able to correct synthesized motion by
  manipulating the ribbon directly (not by re-prompting). Editing the ribbon
  after synthesis should blend, not snap.

DEFINITION OF DONE
- Painting the same pair of poses with two different stroke styles produces
  visibly different motion characters.
- Neural synthesis starts playback within 50ms of request on a real tablet.
- Switching to procedural fallback is seamless and preserves the user's edits.
- Ribbon edits after neural synthesis blend smoothly with no pop.