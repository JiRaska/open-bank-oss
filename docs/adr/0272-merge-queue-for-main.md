---
date: 2026-08-22
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, governance, release-versioning]
summary: "Adopt a GitHub merge queue for main: 19 of 20 main CI failures come from PRs that were themselves green. Enabling it before every required context handles merge_group would stall the queue permanently."
---

# ADR-0272 — Merge queue for main

## Context

`main` breaks about twenty times a day, and almost never because a red PR was merged.

Measured over 24.3 h (2026-08-21T13:50Z .. 2026-08-22T14:10Z), with the queries pinned so a
later reading is comparable:

```
git log origin/main --format=%cI -200
  -> 200 commits / 1.01 days                       = ~197 merges/day

gh run list --branch main --workflow=ci.yml --limit 200
  -> 197 completed runs: 177 success, 20 failure   = 10.2% of merges leave main red
```

For each of those 20 failures, the PR behind the merge commit was checked. **Nineteen of the
twenty had zero failing checks of their own**; the twentieth (#4308) had two. The failing jobs are
`gates (data)`, `gates (supplychain)` and `Validate manifests` — registry lists, ratchet baselines
and derived files. Those are the artefacts git merges *cleanly and wrongly*: two branches add
neighbouring entries to the same list, the textual merge succeeds, and the resulting tree is
invalid in a way neither branch's CI could have observed.

This is not a new observation in this repo — CLAUDE.md documents the class at length, including a
merge that silently took `.release-please-manifest.json` from 56 entries to 55. What is new is the
rate. At ~197 merges/day the window between "CI ran" and "merge landed" is wide enough that the
race is not an edge case; it is a tenth of all merges.

ADR-0048's `openapi.yaml info.version` race is the same shape, and was deliberately resolved in
favour of **detection over prevention** ("the repo has deliberately chosen detection over
prevention"). That choice was reasonable when the alternative was described as hypothetical. The
measurement above is the argument for revisiting it: detection is working — the gates catch these
— but it catches them on `main`, after the fact, roughly twenty times a day.

## Decision

We will adopt a GitHub **merge queue** on `main`, so every merge is tested against the tree it will
actually produce rather than against the base it was branched from.

**We will not enable it until every required status check reports on the `merge_group` event.**
Today none of them does:

| Required context | Workflow | Triggers | `merge_group` |
| --- | --- | --- | --- |
| `all-green` | `services-ci.yml` | push, pull_request, schedule, workflow_dispatch | no |
| `Validate manifests` | `ci.yml` | push, pull_request | no |
| `Gitleaks` | `secret-scan.yml` | push, pull_request, schedule | no |
| `issue-hygiene` | `issue-hygiene.yml` | pull_request | no |
| `OPA policy gate` | `opa-policy.yml` | push, pull_request | no |

Enabling the queue in that state would wedge it permanently: every queued entry would wait forever
for five contexts that never report. That is precisely the failure `check-ruleset-context-parity.py`
already exists to prevent one event earlier — "a required context with no workflow left to satisfy
it strands every PR forever … and there is no error anywhere pointing at why" — and it would be
self-inflicted, at fleet scale, on the default branch.

### The contexts are not all the same kind of check

Two categories, and conflating them is how the queue would go permanently red rather than
permanently stalled:

**State-scoped** — they validate the resulting *tree*. These are the checks that catch the defect
this ADR is about, and they must run on `merge_group`: the gate shards in `ci.yml`
(`Validate manifests`), `OPA policy gate`, `Gitleaks`, and `all-green`.

**PR-scoped** — they validate *the change as authored*: `issue-hygiene` (does the PR link an
issue), and inside `ci.yml` the gates that classify from a diff base — `api-contract-gate`,
`db-migration-gate`, `schema-compat-gate`, `release-scope-mismatch-gate`,
`threat-model-updated-on-trust-boundary-change`. Verified locally: these five fail on a pristine
`origin/main` worktree precisely because no PR diff context exists there.

A PR-scoped context must still **report** on `merge_group` — a required context that does not
report blocks with zero failures — but it must report on something meaningful, not fail for want
of a payload it cannot have.

For the diff-classifying gates, `merge_group` supplies what is needed:
`github.event.merge_group.base_sha` is the branch point of the queued batch, so `ci.yml`'s
`resolve PR diff base` step can produce a real base and the gates classify the whole batch against
`main`. That is better semantics than skipping them, and it closes the residual race ADR-0048
names — a PR whose last CI run predates a competing spec merge — which no base resolution on the
`pull_request` event can close.

`issue-hygiene` has no such payload: there is no PR in a merge group. It reports success as an
explicit no-op job, because the property it asserts was already established on the PR.

### Rollout

1. Extend `check-ruleset-context-parity.py` to also assert `merge_group` coverage for every
   required context, **advisory** while the queue is off. The precondition becomes machine-checked
   instead of a paragraph in this ADR.
2. Add `merge_group:` to the five workflows, with the diff-base and no-op handling above.
3. **Validate against a real `merge_group` event on a canary branch, not on `main`.** A merge queue
   can be enabled on a non-default branch, which is the only way to get a genuine `merge_group`
   payload — none of step 2 is testable before that event exists, and shipping five untested
   workflow branches onto the default branch's merge path would be exactly the "verify by effect,
   not by appearance" failure this repo keeps paying for.
4. Enable on `main` with batching, and flip the parity check from advisory to enforced.

**Batch size.** ~197 merges/day is one every ~7 minutes; a service build job averages 612 s
(measured, n=14). A strictly serial queue cannot keep up. Batches of 5 give roughly 40 merges per
CI cycle, comfortably ahead of arrival rate, at the cost that one bad entry invalidates its batch
and forces a bisect-and-requeue.

**Cost.** Zero. The PR lane runs on `ubuntu-latest`, which is free and unlimited for a public
repository. The queue re-runs CI on the merge path, so CI *volume* roughly doubles; the invoice
does not change.

## Alternatives considered

- **Keep detection-only (status quo).** Pros: no change, no queue latency, no batching failure
  modes. Cons: this is what produces 20 red-main events per day. Detection is working; it just
  runs after the merge. Rejected on the measurement, not on principle — and note the two costs
  that are easy to miss: `main red watch` fires on each event, and a red `main` is the state in
  which the next merge's CI is *also* red, so the signal degrades exactly when it matters.
- **Require branches to be up to date before merging** (the ruleset's strict mode). Pros: much
  simpler than a queue; closes the same race. Cons: at ~197 merges/day every PR would need a
  rebase within minutes of merging, so with N open PRs the rebase load grows with the merge rate
  and the queue is just moved onto humans and agents. With 47 PRs open today it is unworkable.
  Rejected as the wrong place to put the serialization.
- **A pre-merge "does the merge result still pass" bot.** Pros: no GitHub feature dependency.
  Cons: it is a merge queue, hand-rolled, minus the atomicity — between the bot's verdict and the
  merge, another merge can land. Reinventing a primitive GitHub already provides, with a race left
  in it, is strictly worse. Rejected.
- **Narrow the required contexts so the queue is cheaper.** Considered and rejected as a *separate*
  question: which checks block a merge is a decision about risk, not about queue throughput, and
  deciding it under throughput pressure is how a gate gets dropped for the wrong reason.

## Consequences

**Positive**
- The defect class this ADR measures — a green PR that breaks `main` — becomes structurally
  impossible for the checks that run in the queue.
- The diff-classifying gates gain a base they cannot have today, closing the residual ADR-0048
  race that was explicitly left open as undetectable.
- `main red watch` noise drops with the underlying rate; a control that fires 20 times a day is a
  control nobody reads.

**Negative**
- Merge latency rises: a merge now waits for a CI cycle it previously skipped.
- A failing batch entry invalidates its batch. With batches of 5 that is four innocent PRs
  requeued, and the failure reads as "your PR failed" to someone whose PR did not.
- CI volume roughly doubles on the merge path. Free here, but it is real runner time and it
  competes with the PR lane for the same hosted capacity.
- The five workflow changes are untestable until a `merge_group` event exists — hence the canary
  branch in step 3, which is itself work.

**Neutral**
- This reverses ADR-0048's detection-over-prevention stance for this class. That ADR is not
  superseded: its gate remains correct and necessary, and the queue removes the race the gate was
  built to detect after the fact.

## Compliance impact

- PCI DSS: not applicable — no change to cardholder data flows, scope, or segmentation.
- DORA:    not applicable as a control claim. The change reduces unplanned `main` breakage, which
           is a change-management quality improvement rather than an ICT risk control this
           platform asserts to a supervisor.
- GDPR:    not applicable — no personal data is processed by CI queueing.
- PSD2:    not applicable — no change to any regulated interface or its availability.
- CNB:     not applicable — internal engineering change with no reporting surface.

## References

- ADR-0048 — API contract versioning; the detection-over-prevention choice this revisits.
- ADR-0254 — required status-check context parity (`check-ruleset-context-parity.py`).
- `CLAUDE.md` — "A merge git calls CLEAN can still DELETE content"; the `.release-please-manifest.json`
  56→55 incident and the shared-namespace class it generalises to.
- `main-protection` ruleset (id 18325357) — the five required contexts tabulated above.
