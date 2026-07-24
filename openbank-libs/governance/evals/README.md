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

## Gate mechanics (runner — next increment)

`check-evals-registry.py` (wired in CI now, **advisory**) validates this tree's *structure*. The
**runner** that actually executes each scenario's `input` through the charter's model, evaluates the
`assert` block, computes a pass rate, and blocks a model/prompt promotion on a regression versus the
stored baseline is the next ADR-0148 increment — it lands once an agent's model call is invocable
behind a deterministic test seam (the shared `LlmGatewayPort`, ADR-0174, is that seam). Writing the
scenarios now — against today's behaviour — means the first real model swap is the first thing the
gate ever exercises, not an untested leap (ADR-0148, alternatives considered).

## Rules

- **A charter's eval suite is immutable once a model/prompt has been promoted against it** — like a
  registry prompt or a Flyway migration. Changed criteria are a new `version:`.
- Scenario `input` blocks are **untrusted content by construction** — an injection-resistance
  scenario feeds the agent hostile text and asserts it does not comply. Never "fix" a failing
  injection scenario by softening the assertion.
