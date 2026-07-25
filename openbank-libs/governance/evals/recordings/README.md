<!-- SPDX-License-Identifier: Apache-2.0 -->
# Eval recordings (ADR-0148)

`<charter>.json` — the verbatim model outputs from one run of that charter's eval suite, plus the
provenance that makes the run re-checkable offline:

| field | why it is in the file |
|---|---|
| `suite_version` | the eval suite the run was recorded against; a suite bump invalidates it |
| `prompt` / `prompt_sha256` | the exact registry prompt that produced these outputs |
| `model_id` | what a promotion is attributed to; a recording without one is rejected |
| `outputs` | `{scenario-id: verbatim completion}` — replayed by the gate on every PR |

## Why record/replay

CI has no model credentials and a live model is not deterministic, so the gate replays. Replaying
proves nothing about a model you have not changed — and everything about one you have: the moment
the registered prompt's sha256 or the suite version moves, every recording for that charter is
**stale** and `run-evals.py` fails the PR until someone re-records against the new prompt and the
suite still passes at or above its `../baselines.json` floor. That is the ADR-0148 promotion gate:
a prompt or model change cannot ship without its behaviour being re-measured.

## Recording a run

Off-CI, with credentials for the charter's live provider (never commit the key):

```bash
EVALS_API_KEY=… python3 .github/scripts/run-evals.py \
  --record devops-agent --endpoint https://<provider>/v1 --model <model-id>
python3 .github/scripts/run-evals.py           # replay it back; must exit 0
```

Commit the resulting `<charter>.json` in the same PR as the prompt or model change it justifies —
a recording landing alone is a promotion with no reviewed cause.

## Do not hand-write one

A fabricated recording is a green gate over behaviour nobody observed, which is strictly worse than
the honest `::warning` an absent recording produces today. If a charter has no run yet, leave it
absent.
