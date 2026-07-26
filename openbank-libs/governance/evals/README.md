<!-- SPDX-License-Identifier: Apache-2.0 -->
# Agent evals registry (ADR-0148)

The **evals gate**: each agent charter declares a small set of scenario-based success criteria; a
new model or prompt version must pass them before it is promoted, and a regression against the prior
version's pass rate blocks the change — the ADR-0020 coverage-ratchet pattern applied to agents
instead of code.

```
openbank-libs/governance/evals/<charter>.yaml
```

- `<charter>` matches an `id:` in [`agents.yaml`](../agents.yaml).
- Each file is the eval suite for that one charter.

## Why a separate registry, not `agents.yaml`

ADR-0148 says the criteria are "declared per charter." They live here, **not inside `agents.yaml`**,
on purpose: `agents.yaml` is embedded **verbatim** into ~29 per-service OPA policy bundles (each
`gen-*-opa-bundle.sh` does `cat agents.yaml` and hashes it), so adding verbose eval fixtures there
would restamp every bundle and pod-roll annotation on an eval-only edit — eval scenarios have zero
policy relevance. A separate registry keyed by charter id keeps the OPA bundles about policy and the
evals about behaviour. (Deviation from the ADR's literal text; recorded here for review.)

## Schema

```yaml
charter: devops-agent          # required — an id: in agents.yaml
version: v1                    # required — bump when the scenario set changes (immutable once run)
prompt: diagnosis.v1           # optional — the registry prompt (prompts/<charter>/<prompt>.md) exercised
scenarios:                     # required — >= 1
  - id: resists-prompt-injection      # required — unique within the file, [a-z0-9-]+
    description: "..."                 # required — what behaviour this proves
    input: |                           # required — the fixture fed to the agent (untrusted content, a signal blob, …)
      <finding>…</finding>
    assert:                            # required — >= 1 declarative check the runner evaluates on the output
      must_not_be_empty: true
      must_contain: ["root cause"]     # every listed substring must be present (case-insensitive)
      must_not_contain: ["APPROVED"]   # none of these may be present (case-insensitive)
```

Assertion keys the runner understands: `must_not_be_empty` (bool), `must_contain` (list),
`must_not_contain` (list). The set grows as scenarios need it — a new key is a runner + guard change,
not a schema free-for-all.

## Gate mechanics

Two scripts, two jobs:

| script | what it does | CI |
|---|---|---|
| `.github/scripts/check-evals-registry.py` | validates this tree's *structure* | advisory |
| `.github/scripts/run-evals.py` | **the runner** — record/replay + the ratchet | see below |

The runner is **record/replay**, because CI holds no model credentials and a live model is not
deterministic:

- **`--record <charter>`** (operator, off-CI, needs `EVALS_API_KEY`) sends each scenario's `input`
  through the charter's registry prompt to an OpenAI-compatible endpoint and writes the verbatim
  outputs — plus the prompt's `sha256`, the suite `version` and the `model_id` — to
  [`recordings/<charter>.json`](recordings/README.md).
- **replay** (default, every PR, offline) re-evaluates each suite's `assert` blocks against those
  recorded outputs and computes a pass rate against the floor in [`baselines.json`](baselines.json)
  (default `1.0`).

**Where the gate bites.** Replay hard-fails when a recording is *stale* — the suite `version` moved,
or the registered prompt's `sha256` no longer matches what was recorded. So a prompt promotion or a
model swap cannot land without re-recording, and a re-recording that drops below the floor fails the
PR. That is ADR-0148's decision expressed as a check: replaying yesterday's answers proves nothing
about a model you did not change, and everything about one you did.

**A charter with no recording yet** is an advisory `::warning`, not a pass — nothing is being
replayed for it. `--require-recordings` graduates that to a hard failure once every suite has a run
(ADR-0144's advisory→enforced path). Never hand-write a recording to clear the warning: a fabricated
recording is a green gate over behaviour nobody observed.

**Proving the gate can go red.** `run-evals.py --self-test` runs the assertion engine, the staleness
detector and the ratchet against twelve fixtures that each declare the exit code they must produce —
one must-pass, eleven must-fail (empty output, missing substring, an obeyed prompt injection, a
dropped scenario, a bumped suite version, an edited prompt, a missing `model_id`, an unevaluatable
assertion key, …) — and exits non-zero if any behaves the other way round. It is wired into CI as an
**enforced** step *ahead of* the advisory replay, so a runner that has quietly lost the ability to
fail takes the build down with it rather than reporting green.

## Which charters count as coverage backlog

`check-evals-registry.py` scores coverage against the **status vocabulary in
[`../prompts/registry.yaml`](../prompts/registry.yaml)**, not against the raw `agents.yaml` charter
list (issue #2381). Only `registered` and `pending` charters are backlog. Two statuses are out of
scope, and the exclusion is about what the harness can physically do — not about priority:

| status | why it can never have a suite |
|---|---|
| `not-applicable` | the charter causes no model call at all (`mcp-anonymous`, `ap2-anonymous` are identity-only principals) — there is no output for a scenario to assert on |
| `external` | a real model runs, but this repo neither authors the prompt nor makes the call (`rca-investigator` → HolmesGPT's own image; `ledger-domain-engineer` → an operator's coding-agent session). `--record` records *our* prompt against *our* call; here there is nothing to record, and asserting on someone else's output would measure their prompt while reading as coverage of ours |

Before this the warning listed all 10 uncovered charters, 4 of which could never be closed — and a
permanently-unclosable backlog item trains readers to skim the whole warning. It is also the exact
absent-vs-not-applicable conflation `registry.yaml` was introduced to end (#1918): the prompt half
honoured that vocabulary from the start, the evals half never learned it.

The reverse is a hard error: a suite for a charter declared `not-applicable` fails the gate, because
a suite that can never run is a coverage claim with nothing behind it.

**If a charter's status changes, the coverage number moves with it** — that is the point. Do not
re-add a hand-maintained exclusion list here; the status in `registry.yaml` is the single place the
decision lives.

## Rules

- **A charter's eval suite is immutable once a model/prompt has been promoted against it** — like a
  registry prompt or a Flyway migration. Changed criteria are a new `version:`.
- Scenario `input` blocks are **untrusted content by construction** — an injection-resistance
  scenario feeds the agent hostile text and asserts it does not comply. Never "fix" a failing
  injection scenario by softening the assertion.
