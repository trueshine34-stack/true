# Large-scale runs

Run a Playground evaluation over many personas. The result is still **one job**:
one name in Runs, one folder `jobs/<job_name>/`.

First single-persona walkthrough: [quickstart](../quickstart.md).

---

## Launch

Use one of:

- Playground
- `POST /api/harbor/jobs`
- `harbor run -c <job.yaml>`

One launch covers the whole cohort. Do not start a separate job per persona.

---

## Personas

A **cohort** is a directory of persona YAML files.

| Batch size | How to pass them |
|------------|------------------|
| Small (about ≤100) | Optional `personaIds` |
| Large | `personaPool` = that directory, plus `useEntirePool` |

Sources:

1. **Task strategy** — most tasks ship `persona_strategy.json`. Sample in
   Playground, or run
   `generate_application_job.py --task application/tasks/<name>`.
   For a stratified sample, set `sampleSize` to the **total** you want.
   `sampleSizePerValueGroup` is a per-group quota, not a second total.
2. **Public Persona 1M** —
   [`MatrAIx2026/MatrAIx_Persona_1M_Public_Release`](https://huggingface.co/datasets/MatrAIx2026/MatrAIx_Persona_1M_Public_Release).
   Import it locally, then point the job at that path. See
   [Persona setup](../persona/README.md#setup-and-usage).
3. **Dev sample** — `persona/datasets/matraix-persona-dev-sample/` for small
   local batches.

Playground / API fields: `personaPool`, `useEntirePool`, `sampleSize`,
`nConcurrentTrials`. Reference: [playground-api.md](../application/playground-api.md).

Record the persona path (and the Hugging Face revision, if you used one) so the
batch is reproducible.

---

## Outputs

```text
jobs/<job_name>/
├── result.json
├── <task>__<trial>/
│   ├── agent/
│   ├── verifier/
│   └── result.json
└── job.log
```

Keep `jobs/<job_name>/` and any sampled cohort directory if you need to
reproduce the run. Pulled copies sit next to the source dataset, for example
`persona/datasets/matraix-persona-dev-sample/cohorts/`.

---

## Related

- [Runtime](runtime.md)
- [Quickstart](../quickstart.md)
- [Persona 1M](../persona/README.md#public-coreset-matraix-persona-1m)
- [Playground API](../application/playground-api.md)
