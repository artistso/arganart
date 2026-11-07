# Phase briefs

The briefs, in order. Each is a separate, closed phase: a phase is not "done" until its
own Definition of Done is met *and nothing from a later phase exists*.

| Phase | Brief | Module(s) |
| --- | --- | --- |
| P00 | Scaffolding, module graph, architectural boundaries | all |
| P01 | Stylus ink engine | `:core:ink`, `:feature:canvas` |
| P02 | Motion as sculpture — the ribbon | `:core:motion`, `:feature:motion` |
| P03 | Spatial resolver — arrangement as prompt | `:core:spatial` |
| P04 | Generative brushes (deterministic stubs) | `:feature:canvas` |
| P05 | Genericness scorer, meter, mutation knob | `:ai:runtime` |
| P06 | Style fingerprint provider | `:data`, `:ai:runtime` |
| P07 | Real on-device model behind `ModelBackend` | `:ai:runtime` |
| P08 | Transition painting + neural motion | `:core:motion`, `:feature:motion` |
| P09 | Persistence, export, accessibility, tablet UX | `:data`, `:app` |
| P10 | Performance hardening | all |

Guardrails that apply to **every** phase are in `../guardrails/`:

- `phase-review.md` — the review protocol (DoD with evidence, rules, scope, hot paths, tests).
- `stop-scope-violation.md` — revert anything not in the current phase, then restate the DoD.
- `blocked-protocol.md` — when something fails: state the behaviour, the minimal repro, the
  three most likely causes ranked, and the cheapest distinguishing experiment. Then run
  only that experiment.
