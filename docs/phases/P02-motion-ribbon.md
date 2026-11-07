Implement :core:motion and :feature:motion: motion as a first-class, directly
sculptable material. No timeline in the traditional sense. No keyframe grid.
Use the procedural MotionBackend stub from P0 — neural motion comes in P8.

MOTION MODEL (pure Kotlin)
- MotionClip = a spine (a spline path) + a set of MotionNodes distributed along
  it. Each node carries: easing character, speed multiplier, and an optional
  rotation/scale delta.
- Interpolation supports spring dynamics, not just easing curves: stiffness,
  damping, and mass per node, so motion has weight and follow-through.
- MotionClip must be sampleable at any time t with no allocation in the hot path.

THE RIBBON
- The Motion Ribbon is a visible, editable band that runs through the scene,
  drawn in a distinct visual language (not ink, not UI chrome). It is the
  primary object the user manipulates.
- Gestures on the ribbon:
  - Drag a segment perpendicular to bend the path.
  - Pinch a segment to compress (accelerate) or stretch (decelerate).
  - Long-press to drop a node; drag a node to reshape its character.
  - Twist with two fingers to rotate the attached element along the path.
- Scrubbing: drag anywhere on the ribbon to scrub time. The scene updates live.

LIVE PREVIEW
- The canvas shows the animated element moving along the ribbon in real time as
  the user edits. Editing while playing must be possible (motion is always live).
- Motion is rendered on its own layer so the ink layer's frame budget is
  unaffected.

NO-TIMELINE RULE
- Do not build a ruler, a playhead, or a keyframe track. Time is expressed as
  position along the ribbon. If you believe a timeline is unavoidable for a
  specific sub-case, stop and ask rather than building it.

DEFINITION OF DONE
- A user can go from a static drawing to a 3-second, eased, spring-weighted
  motion in under 30 seconds with no instructions.
- Editing the ribbon during playback does not drop ink frames.
- Every motion state is serializable and reloads identically.