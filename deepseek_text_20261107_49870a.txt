Review the diff for the last phase only. Check: (1) does it meet every item in
the phase's Definition of Done, verified with evidence, not assertion; (2) did it
violate any of the six architectural rules in ARCHITECTURE.md; (3) did it build
anything scoped to a later phase — if so, list it for removal; (4) are there
allocations or blocking calls in the input, stroke-sampling, or motion-sampling
hot paths; (5) are the new public interfaces testable and tested. Output a
prioritized list of fixes, then apply them.