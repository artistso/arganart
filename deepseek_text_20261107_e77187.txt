Finish the product: persistence, export, accessibility, and the tablet UX that
makes the app feel like a category of one.

PROJECTS
- Project = a directory-based format: a manifest JSON (spatial graph, strokes
  metadata, motion clips, seeds, model versions, fingerprint reference) plus
  binary blobs (tiles, references). Fully portable, versioned, and
  forward-compatible.
- Autosave every 10s and on lifecycle pause; crash-safe via journaling.
- Project browser with thumbnails, search by tag, and duplicate/archive.

EXPORT
- Export stills: PNG (with transparency), and a "print-ready" high-res pass that
  re-renders tiles at export resolution.
- Export animation: MP4 via MediaCodec (H.264, configurable fps and bitrate),
  animated GIF, and a PNG sequence zip.
- Export motion data as a JSON/Lottie-compatible bundle so work is portable.
- Export must run in a foreground service with progress and cancellation, and
  must never block the editor.

ACCESSIBILITY & UX POLISH
- Full TalkBack support for all chrome (canvas itself announces object counts
  and selection state).
- Large touch targets, high-contrast mode, reduced-motion mode that disables
  spring overshoot.
- Full keyboard shortcut support for tablet + keyboard users (undo, tool
  switch, scrub, export).
- Dark and light themes via :design tokens; no hardcoded colors.
- Onboarding refresh covering the full vocabulary: spatial objects, generative
  brushes, motion ribbon, mutation knob. Under 2 minutes, skippable, replayable.

DEFINITION OF DONE
- A project created on one device opens identically on another.
- MP4 export of a 5-second animation completes in under 30 seconds and plays
  correctly in a third-party player.
- TalkBack can complete a full draw-and-export flow without sighted assistance.
- Zero hardcoded strings or colors outside :design and resources.