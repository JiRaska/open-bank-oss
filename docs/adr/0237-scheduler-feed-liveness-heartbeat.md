---
date: 2026-08-03
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [observability, ci, governance]
summary: "Every domain @Scheduled job and external feed registers the shared workflow-liveness gauge; staleness alerts at 2x interval via PrometheusRule, sentinel correlates, adoption gated in CI. Feeds record success only on real data."
---

# ADR-0237 — Scheduler and external-feed liveness heartbeat

## Context

The operational-maturity assessment (#3343) scored the platform at level 1 because
controls fail silently: five `@Scheduled` jobs never ran (HR000068, three of them
money-path), and the ČNB feed was a 404 for 46 days while the downstream FX
revaluation kept logging "no movement" — a job that *ran successfully* while doing
nothing. A green readiness probe proves the process is up; it says nothing about
whether the scheduled work inside it ever executes.

ADR-0160 mechanism 3 built the primitive for exactly this:
`DomainMetrics.registerWorkflowLiveness(workflow, expectedInterval)` — two scrape-time
gauges (`openbank_workflow_last_success_age_seconds`,
`openbank_workflow_expected_interval_seconds`, tagged by `workflow`) plus a
`WorkflowLivenessRecorder.recordSuccess()` the job calls after each completed run.
ADR-0163's control-liveness-sentinel already consumes those gauges and correlates
"N controls went silent" into one triaged finding. But adoption is the weak point the
sentinel's own doc names: today only ~5 of ~30 domain schedulers register
(standing-order-execution, fx-cnb-ingestion, ledger-tie-out, ledger-fx-revaluation,
billing-cycle), so most of the fleet is still invisible to it.

Two distinct signals get conflated today, and the ČNB incident is the proof they must
not be: **workflow heartbeat** (the job executed) and **feed freshness** (the job
actually obtained data). `FxRevaluationScheduler` records success even when every leg
is skipped for lack of a rate — correct as a heartbeat, useless as feed evidence.

## Decision

1. **Coverage: every domain `@Scheduled` job registers the shared primitive.** Each
   job registers `registerWorkflowLiveness` once at startup with its expected
   interval and calls `recordSuccess()` after every completed run — including
   legitimate zero-work runs (a day with no due standing orders is a run, not a
   miss). Exempt: the sub-minute outbox dispatchers/backlog gauges, which already
   have their own freshness signal (`openbank.outbox.backlog`). Bespoke one-off
   gauges measuring the same thing (e.g. statement's `CloseLastRunGauge`) migrate to
   the shared primitive and are deleted.
2. **External feeds use the same primitive with a `feed-` prefix and a stricter
   success condition.** A `feed-<name>` liveness entry records success only when the
   feed was fetched *and* its payload parsed — never on a 404, a circuit-open, or a
   parse failure. The workflow heartbeat and the feed freshness are separate
   `workflow` tag values, so "the scheduler ran" and "the feed delivered" alert
   independently. The CI probe (`external-feed-watch`) stays as-is: it falsifies the
   URL from outside; the in-cluster gauge measures freshness from inside.
3. **Staleness alerts fire at 2× the expected interval, severity warning.** A
   `PrometheusRule` (the audit-chain three-rule pattern: never-ran via `absent()`,
   stale via `age_seconds > 2 * expected_interval_seconds` with a `> 0` guard)
   routes to Slack. **Not critical, even for money-path jobs**: staleness with a
   2× grace is a liveness gap, not an active outage — the outage signals downstream
   (outbox stuck, SLO burn) already carry critical rules, and #3346 makes "zero
   standing criticals" a goal, so a daily job missing one run must not page.
   The sentinel (ADR-0163) continues to correlate staleness across controls into one
   finding; the rule is the raw signal, the sentinel is the triage.
   **The age gauge is seeded at registration time, and that is a precondition of this
   point rather than an implementation detail** (amended 2026-08-08). ADR-0160's
   primitive seeded it from `Instant.EPOCH`, so a workflow that had not yet recorded a
   success read ~1.8e9 seconds — decades — on every fresh pod. That is survivable for a
   sentinel that runs daily and files a finding, and fatal for an alert: the rule above
   would fire 15 minutes after every deploy or restart, for every daily workflow, and
   keep firing until that workflow's next success (up to 24h for a daily job), across
   all ~28 registration sites. No `for:` duration helps, because the condition genuinely
   persists. The rule and `DomainMetrics`' own KDoc were shipped describing opposite
   behaviours; seeding the gauge at registration is what reconciles them. Two
   consequences that follow and are accepted here: a job whose pod restarts *more often*
   than 2× its interval can never accumulate enough age to alert — covered statically by
   point 4's gate instead — and "has this job ever succeeded" no longer follows from the
   age, so the primitive publishes it separately as
   `openbank_workflow_success_recorded` (0 until the first `recordSuccess`, 1 after).
   That gauge changes no verdict; the sentinel reads it so a finding can say "no success
   since this pod registered it" rather than assert a last success that never happened.
4. **Adoption is gated, or it rots.** A new CI check (advisory-first per ADR-0144,
   with a `target_enforce_date` in rules.yaml) fails when a domain `@Scheduled`
   method has no liveness registration — otherwise the fleet slides back to
   invisible-by-default the day this sweep ends.
5. **Rollout: money-path first, one PR per service** (the sweep convention —
   the scope selects the released component, so a cross-service change cannot land
   as one commit): ledger, billing, interest, balance, sdd, lending, then the rest.

## Alternatives considered

- **Extend the control-liveness-sentinel to alert, skip PrometheusRule.** Rejected:
  the sentinel runs daily and correlates; it is triage, not the raw signal. A
  PrometheusRule is declarative, follows the audit-chain precedent, needs no code
  deploy to tune a threshold, and fires within a scrape interval rather than at the
  sentinel's next run.
- **Critical severity for money-path schedulers.** Rejected: a daily money-path job
  that missed one run has a full second interval before harm (interest accrues the
  next day); paging is for the downstream outage signals that already exist. Page on
  symptoms, warn on controls — that is what keeps critical meaningful (#3346).
- **A bespoke metric per scheduler.** Rejected by ADR-0160 already: one tagged gauge
  pair for all workflows avoids cardinality sprawl and the name-drift defect class
  that bit the sentinel in #2187.
- **Feed success = scheduler success (no `feed-` distinction).** Rejected: that is
  precisely the ČNB failure — 46 days of "successful" runs over a dead feed.

## Consequences

**Positive**
- The HR000068 class (job never ran) and the ČNB class (feed dead, job "green") both
  become same-day alerts instead of archaeological findings — the two exact incident
  shapes that motivated level 1.
- The sentinel's known gap (adoption rate) closes; its correlation starts covering
  the fleet rather than five jobs.
- One primitive, one alert pattern, one naming convention — no per-service
  observability dialects.

**Negative**
- ~25 service PRs of sweep work (one per service, by convention), plus a new
  advisory gate to shepherd to enforcement.
- Weekend/holiday feeds need the 2× grace to not false-fire (a daily feed silent on
  a 3-day weekend still fits under 2×; anything longer is a real miss).

**Neutral**
- The 2× multiplier moves from an implicit convention to the alert expression; a job
  with a special cadence can set its own `expectedInterval` accordingly.
- The boot-seed (point 3's amendment) makes "age" mean *time since the last success or
  this pod's registration, whichever is later*. Every consumer already treats it that
  way, since both readings cross the same threshold; only the wording of a sentinel
  finding depended on the difference, and that now comes from
  `openbank_workflow_success_recorded` instead of from the age's magnitude.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data surface.
- DORA: operational-resilience engagement in plain words — liveness evidence for
  scheduled controls and external dependencies; no specific clause cited in this ADR.
- GDPR: not applicable — the retention/cleaner jobs get watched, the data itself is
  untouched.
- PSD2: not applicable — no customer-facing API change.
- CNB: not applicable as a reporting change — but the ČNB fixing feed is the
  canonical case this ADR instruments.

## References

- Issue #3343 (operational maturity tracker), #3345 (this item)
- ADR-0160 (liveness standard, mechanism 3 primitive), ADR-0163 (sentinel),
  ADR-0236 (deployed == main drift watch)
- `.github/scripts/check-scheduler-exercised.py` / `check-no-runblocking-in-scheduled.py`
  (HR000068 enforcement), `.github/workflows/external-feed-watch.yml` (URL probe)
- `prometheus-rules-audit-chain.yaml` (the three-rule staleness pattern)
