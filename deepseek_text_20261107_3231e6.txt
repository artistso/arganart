Implement :core:spatial — the system that turns canvas geometry into a structured
generation request. There is still no text prompt field anywhere in the product
and there never will be. This phase produces a resolved GenerationRequest from
spatial arrangement, and a debug overlay to inspect it. No model is called yet.

CANVAS OBJECTS
Introduce first-class "prompt objects" that live on the canvas alongside strokes:
- Sketch (any existing stroke group)
- ColorSwatch (draggable color chip)
- TextureCard (draggable image/texture reference)
- MotionArrow (a draggable, rotatable arrow indicating intended motion direction
  and speed via length)
- Frame (a rectangle defining output bounds and aspect ratio)
- ReferenceCard (a pinned image, used for style, not content)
Each is selectable, movable, resizable, and deletable with a consistent gesture
vocabulary. They render in a subtle "object" visual language distinct from ink.

RESOLVER
Given a target Frame (or the current viewport), compute a GenerationRequest:
- Build a spatial graph: nodes = objects, edges weighted by distance and by
  angular relationship to a "focus point" (the centroid of the user's most
  recent strokes, or the Frame center).
- Assign roles by containment and proximity: objects inside the Frame are
  content candidates; objects adjacent to it are modifiers; objects nearest a
  MotionArrow inherit directionality.
- Encode the result as an explicit, inspectable JSON structure:
  { frame, contents[], style_modifiers[], texture_modifiers[],
    motion: {direction, speed, character}, assistLevel, seed }
- Resolver must be pure and unit-tested. Given identical geometry it must
  produce identical output.

DEBUG / TEACHING OVERLAY
- A developer overlay that draws the resolved graph (edges, weights, assigned
  roles) over the canvas, plus a scrollable JSON pane.
- A first-run onboarding that teaches the spatial vocabulary in under 60 seconds
  using three animated example arrangements. No walls of text.

DEFINITION OF DONE
- Moving a ColorSwatch changes the resolved style_modifiers deterministically.
- Moving a MotionArrow past a threshold flips the resolved motion.direction.
- Resolver unit tests cover 15+ arrangements including ambiguous ones.
- Onboarding completes without any user typing anything.