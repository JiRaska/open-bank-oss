---
date: 2026-08-09
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [observability, testing, governance, architecture]
summary: "Every control that claims to do work must expose a countable artifact proving it did — a row, a metric, a status — and a reader that fails when the count is zero; a wired, healthy, silent component is this estate's dominant defect."
---

# ADR-0253 — Evidence of effect: a control must be able to show it carried work

## Context

The dominant defect in this estate is not a component that breaks. It is a component that
is present, wired, healthy, reporting success — and carrying nothing.

Five instances were found in a single day (2026-08-08), none of which code review or CI could
see, because every one was green:

| Defect | What reported success | What was actually true |
|---|---|---|
| #4178 | Pod healthy after deploy; env vars set on the workload | The env names bound no config key, so the service read a `localhost` default and crashlooped; a canary abort then left the stable image unbootable |
| #4182 | Temporal workflow `COMPLETED` | It completed on `SENT_TO_CLEARING`, a non-terminal business status, with no timer and no sweeper — the payment was stranded and nothing could resume it |
| #4238 | HTTP 201, money moved, ledger balanced | The transaction row stayed `PENDING` forever; the terminal write lived outside the durable workflow and the idempotent-replay path returned a `PENDING` row as `201` |
| #4221 | Fraud adapter healthy, shadow verdicts logged | `fraud_scores` held **0 rows**; no workload set `FRAUD_SERVICE_URL`, and the enforcement flag had no reader in any Kotlin source |
| #4353 | Notification rows written, pushes "attempted" | 81% of pushes FAILED; 13 of 15 onboarded customers had no registered device, and every failure path on both sides was silent |

They share one shape. The apparatus exists and is observable; what is missing is any signal
that it *carried* anything. A `GROUP BY` answers in seconds what a week of reading source
does not, because the source is correct — it describes work that the deployed system is not
doing.

Existing decisions do not cover this. ADR-0008 and ADR-0077 establish *how* to emit telemetry;
ADR-0088 adds SLOs and on-call. All of them assume the thing being measured is running. None
requires that a control be able to demonstrate it ever acted, and none makes "this counter has
been zero since it shipped" a failure rather than a silence. `rules.yaml` already knows that a
gate which has only ever passed is unfalsified; this is the same argument one level out, applied
to runtime controls rather than to CI checks.

## Decision

**We will require every control that claims to do work to expose a countable artifact proving it
did, and to be covered by a check that fails when that count is zero.**

Concretely, a change that introduces or modifies a control must satisfy three obligations.

1. **Name the artifact.** State, in the PR, what a *working* instance of this control leaves
   behind: a table and the rows it accumulates, a metric and the series it produces, a status
   transition, a signed attestation. "It logs on failure" is not an artifact — silence is its
   healthy state and its broken state alike.

2. **Emit an outcome, not a state.** Any terminal path — success, refusal, degradation — must
   increment a counter tagged with the outcome and, where a control can decline for more than one
   reason, the reason. A single `FAILED` that conflates "there was nothing to send to" with "the
   provider rejected us" describes two defects with different owners as one, and picks neither.

3. **Make zero fail.** The artifact needs a reader: an alert, a gate, or a scheduled check that
   goes red when the count is zero (or has not moved) over a window in which the control was
   expected to act. Absent that reader, the artifact is a fact nobody consults, and the control
   returns to the silence this ADR exists to remove.

Money-path services (`rules.yaml: money_path_services`) additionally may not close a durable unit
of work with a non-durable step. If a workflow, saga or transaction performs the effect, the
record that the effect happened belongs inside the same durable unit — #4238 is precisely the cost
of putting it in the caller.

**We will also treat live-data inspection as a first-class verification step, not an incident
tool.** A quarterly sweep of outbox tables, delivery-outcome counters and per-status row counts
is cheap (three passes, ~20 minutes each) and has repeatedly out-produced code-level review. The
sweep's findings belong in issues; its recipes belong in the runbook.

## Alternatives considered

- **Rely on the existing SLO and alerting layers (ADR-0077, ADR-0088).** Rejected because they
  measure whether a service *answers*, not whether accepted work *progresses*. Every tier-1 rule
  was correctly green throughout all five defects above: the pods were healthy, latency was
  normal, error rates were zero — a fail-closed hold and a dropped notification both return 2xx.
  This is not a gap in the rules but in what they are able to observe.

- **Require integration tests to cover each path instead.** Rejected as insufficient rather than
  wrong. Four of the five defects are invisible to any in-process test: a test supplies the Vert.x
  context the scheduler does not, the Pact mock answers whatever path it is asked for, a stubbed
  client cannot show that no workload sets the env var, and a unit test cannot tell a served route
  from an unserved one. Tests remain necessary; they cannot be the only evidence.

- **Add a blanket CI gate that greps for the pattern.** Rejected because the pattern is semantic.
  The five defects share a shape, not a syntax — there is no text that distinguishes a counter
  nobody reads from one that is read. A checkable subset (an outbox table implies at least one
  `OutboxMessage(` construction, #4007) is worth encoding, and is being encoded; the general case
  is a review obligation, and this ADR is what a reviewer cites.

- **Do nothing and rely on the war-story notes in `CLAUDE.md`.** Rejected: those are already
  written and were already read. The five defects landed anyway, because a footgun list tells you
  what went wrong before, not what a new PR must show. The obligation has to attach to the change.

## Consequences

**Positive**
- A control that has never acted becomes discoverable at the moment it ships, instead of during
  the incident that its silence caused.
- The two failure classes that most often masquerade as each other — "nothing to do" and "could
  not do it" — become distinguishable in the data, so the owner is identifiable without a
  debugging session.
- Live-data sweeps get a mandate and a place to put their findings.

**Negative**
- More work per change: a counter, an alert, and a sentence of justification. On small changes
  that will feel disproportionate.
- A risk of alert inflation. Mitigated by requiring the reader to be *actionable* — an alert
  whose triage note says "known, benign" is the failure mode `rules.yaml` already warns about,
  and a rule that overstates its own severity is one people learn to discount.
- Some controls genuinely have no natural artifact (a guard that should never fire). Those need
  an explicit "expected zero" declaration rather than a manufactured metric — and that
  declaration is itself reviewable.

**Neutral**
- No runtime behaviour changes on adoption; this is an obligation on new and modified controls.
- Existing controls are not swept retroactively. The quarterly live-data pass will find them at
  the rate it finds them, which is how all five of the founding examples surfaced.

## Compliance impact

Supports DORA Art. 9-10 (detection: an ICT control that cannot demonstrate it operated is not
evidence of detection) and BCBS 239 §3 (accuracy and completeness of risk data — a fraud scorer
over an empty table satisfies neither). PSD2 Art. 97 SCA is directly implicated by #4353: the
approval prompt is a regulatory control whose delivery was failing for 87% of onboarded
customers with no signal. Nothing here weakens an existing control; it constrains what may be
claimed about one.
