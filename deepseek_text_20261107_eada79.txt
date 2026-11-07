Build the system that actively pushes output away from generic AI aesthetics.
This is the product's headline differentiator and must be visible, opinionated,
and controllable.

GENERICNESS SCORER
- Compute a running "genericness" score for any candidate output, on-device,
  without a network call.
- Approach: maintain an on-device embedding model (small, quantized). Curate a
  reference set of ~200 "visual elevator music" images — the stock, samey
  aesthetics that AI art converges to — and compute their centroid and spread.
  Score = normalized distance from that centroid, combined with a
  palette-uniformity term and a symmetry-excess term.
- The reference set ships with the app or is built once at first launch from
  bundled assets. Document the curation criteria.

GENERICNESS METER
- A persistent, minimal meter on the canvas (not a modal, not a nag) that shows
  the current candidate's genericness live as generation streams in.
- Three states: Fresh / Drifting / Converging. Color-coded, glanceable.
- When it enters "Converging," offer a single-tap corrective action
  (see Mutation below), never a lecture.

MUTATION KNOB
- A physical-feeling control (radial dial at canvas edge) that injects seeded,
  controlled perturbation: asymmetry, unexpected hue shift, compositional
  offset, texture noise, line-weight disruption.
- Mutation is seeded and stored with the result so it is reproducible and
  reversible.
- A/B preview: the canvas can split-screen original vs. mutated with a
  draggable divider so the user judges by eye, not by description.

NUDGE SYSTEM
- Contextual, terse, non-patronizing suggestions at most once per minute:
  "Break the symmetry?" "Push the texture?" Each is a single tap that applies
  a mutation and is undoable. No suggestion is ever modal.

DEFINITION OF DONE
- 20 consecutive generations at mutation = 0 measurably converge in score;
  the meter correctly reports this.
- At mutation = 0.7, the same 20 generations stay in Fresh/Drifting.
- Every mutation is reversible via undo with no residue.
- The meter is glanceable at 10 inches away without reading text.