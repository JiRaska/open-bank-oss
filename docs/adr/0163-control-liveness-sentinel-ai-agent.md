---
date: 2026-07-13
decision-status: accepted
delivery-status: partial
authors: [jiri.raska (paired with Claude Fable 5)]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, observability, governance]
summary: "A read-only control-liveness-sentinel agent correlates ADR-0160 liveness mechanisms fleet-wide and proposes tickets or PRs, because each mechanism only pages for itself and an uninstalled watchdog cannot page at all."
---

# ADR-0163 — Control-liveness-sentinel AI agent

## Context

ADR-0160 (2026-07-13, same day) diagnosed a recurring failure class: a claim about runtime
behaviour — an event is consumed, a scheduled job still runs, a reconciliation control is live —
is encoded only in prose and never re-verified against the fleet. It shipped four mechanisms to
convert that class from "an agent has to remember to check this" into a checked, alertable fact:
a CI event-consumer-liveness gate, a lineage-vs-code CI gate, a shared
`WorkflowLivenessWatchdog` primitive (a Prometheus gauge, one per `@Scheduled` job, paging when
age-of-last-success exceeds 2× the expected interval), and a reconciliation drift-SLA (alert only
on sustained drift across `consecutive_drift_threshold` runs).

Those four mechanisms are real, but each one only pages for *itself*, one gauge or one CI run at a
time. Nothing looks at the mechanisms as a set, fleet-wide, before they page. Two incidents this
ADR's predecessor investigated illustrate the gap directly:

- Balance reconciliation (issue #855) was silently dead for 41 days after a migration was
  hand-edited post-apply. A `WorkflowLivenessWatchdog` gauge (mechanism 3) would have caught this
  once wired up — but wiring it up is a per-service adoption sweep (ADR-0160's own "Negative"
  section says so explicitly), and nothing tracks *which* scheduled jobs have opted in yet versus
  which are still unmonitored. A watchdog that is never installed cannot page.
- `openbank.outbox.dispatch-enabled` defaulting to `false` and the AWS Config recorder silently
  sitting at `recording: false` for 15 hours (this file's sibling incidents, both in this repo's
  own operational history) are the same shape again: a control that stopped, with no single
  metric watching "did this specific control's heartbeat continue."

The common gap is not detection primitives — ADR-0160 built those — it is **someone watching the
watchdogs as a set**, correlating "how many controls have a stale or missing heartbeat right now,
fleet-wide" into one triaged view, the same role finops-agent (ADR-0112) plays for cost signals
and devops-agent (ADR-0119) plays for CI/DORA signals. Both of those exist because a dashboard
that must be remembered is exactly the failure mode being fixed.

## Decision

We add **control-liveness-sentinel** as a new control-plane AI agent (ADR-0031), the ADR-0160
mechanisms' fleet-wide correlator and proposer, following the same shape as finops-agent and
devops-agent:

- **Reads only.** `query.observability.readonly` (the `WorkflowLivenessWatchdog` Prometheus
  gauges from ADR-0160 mechanism 3, one per opted-in `@Scheduled` job) and `read.governance`
  (`rules.yaml`'s advisory-gate `blocked_on` list for mechanisms 1/2, so it can distinguish a
  known, tracked exception from a new regression).
- **Detects four ways**, one per ADR-0160 mechanism:
  - **D1 — stale watchdog heartbeat.** Any `openbank_workflow_last_success_age_seconds`
    gauge past 2× its declared expected interval — the same threshold ADR-0160 mechanism 3
    already defines as the paging line, so this agent's finding and Alertmanager's page always
    agree.
    (Correction, #2187 follow-up: this line originally named the gauge
    `openbank_workflow_liveness_last_success_age_seconds`, and the implementation queried that,
    but ADR-0160's `DomainMetrics.registerWorkflowLiveness` emits it without the `liveness`
    infix — so D1 collected an empty vector and could only report "no stale heartbeats". The
    query and this line were aligned to the emitted name, which is the one actually in
    Prometheus; both sides now read it from `WorkflowLivenessMetrics`. The "Alertmanager's page"
    it claims to agree with does not exist yet either — no PrometheusRule implements mechanism
    3's generic expression.)
  - **D2 — event-consumer regression.** A newly producer-only topic in
    `check-event-consumer-liveness.sh`'s report that is not in the `rules.yaml` allowlist.
  - **D3 — lineage-vs-code drift.** A `governance.yaml` lineage edge the code audit
    (ADR-0160 mechanism 2) can no longer verify.
  - **D4 — sustained reconciliation drift.** A control-account tie-out past
    `consecutive_drift_threshold` consecutive runs (ADR-0160 mechanism 4).
- **Proposes only.** Each finding becomes a `draft.ticket` (unowned control, needs triage) or,
  when the fix is mechanical (e.g. wiring an unmonitored `@Scheduled` job into the shared
  watchdog), a `gh.pr.open` IaC/code diff through the HITL queue — never a direct write. `tools.deny`
  blocks every write/execute tier explicitly, matching finops-agent/devops-agent.
- **Runs daily (03:00 UTC, offset from finops-agent/devops-agent) plus reactively** on a GoAlert
  webhook when any `WorkflowLivenessWatchdog` gauge itself pages — so a real-time page still gets
  an agent-drafted root-cause note attached, not just a bare alert.

## Alternatives considered

- **Fold this into devops-agent instead of a new agent.** Rejected: devops-agent's charter is
  CI/CD and DORA delivery health; liveness/drift is a distinct data-plane concern (reconciliation
  controls, event consumers, lineage) with its own read scope (`query.observability.readonly` +
  `read.governance` only, no `github-actions-readonly`). Keeping them separate keeps each
  charter's `tool_tiers.allow` list a tight, auditable least-privilege set (ADR-0031 D2) instead
  of a growing union.
- **A dashboard aggregating the four ADR-0160 mechanisms, no agent.** Rejected for the same reason
  ADR-0160 itself rejected a dashboard-only mechanism 3/4: discoverable only by someone who
  remembers to look. This ADR's whole premise is that "remembered to check" is the failure mode.
- **Enforce all four ADR-0160 mechanisms immediately instead of adding a correlator.** Not
  mutually exclusive — this agent complements, not replaces, ADR-0160's CI-graduation path
  (ADR-0144). The two-year lag until every mechanism is fully adopted fleet-wide is exactly the
  window in which a fleet-wide correlator has the most value, since coverage is partial and
  uneven.

## Consequences

**Positive**
- Closes the "watches the watchdogs" gap ADR-0160 left open: a stale-but-not-yet-paging control
  (heartbeat at 1.5× interval, not yet 2×) surfaces as a WARNING finding before it becomes a
  CRITICAL page.
- Reuses ADR-0160's own thresholds (2× interval, `consecutive_drift_threshold`) rather than
  inventing new ones — the agent's findings and the underlying Alertmanager rules can never
  silently disagree.
- Same governance shape as finops-agent/devops-agent (control plane, proposal-only, HITL,
  kill-switch, token budget) — no new review pattern for operators to learn.

**Negative**
- Only as good as ADR-0160 mechanism 3's adoption rate — a scheduled job that has not yet been
  wired into `WorkflowLivenessWatchdog` has no gauge for this agent to read, so it cannot detect a
  silently-dead job outside that primitive (the exact #855 failure mode, until the adoption sweep
  reaches that job).
- A fifth Temporal-orchestrated control-plane agent adds one more workload watching the same
  Prometheus/governance surface finops-agent and devops-agent already read — acceptable
  duplication (each is least-privilege scoped to its own charter) but worth tracking if the
  control-plane agent count keeps growing.

**Neutral**
- No new infrastructure: reuses Temporal (ADR-0101), Prometheus (already the ADR-0160 mechanism 3
  sink), and the existing GitHub-proposal / HITL-queue pattern.

## Compliance impact

- PCI DSS: not applicable directly; strengthens evidence that change-control/monitoring controls
  (12.x) remain live rather than silently lapsed.
- DORA: supports Art. 9 (ICT risk detection) and Art. 24 (testing of ICT systems) — this agent is
  the fleet-wide correlator for exactly the detection controls DORA testing requirements target.
- GDPR: not applicable.
- PSD2: not applicable directly; a stale reconciliation or event-consumer heartbeat on a payment
  rail is exactly the silent-failure class this agent surfaces before it becomes a customer-facing
  incident.
- CNB: supports vyhláška ČNB 501/2002 Sb. control-account tie-out expectations by keeping the
  ADR-0039/ADR-0160 reconciliation control's own liveness auditable, not just its output.

## References

- [ADR-0031](0031-ai-agent-governance.md) — AI agent governance framework (charter shape, HITL,
  kill switch)
- [ADR-0160](0160-end-to-end-integration-liveness-and-drift-detection-standard.md) — the four
  mechanisms this agent correlates and proposes fixes for
- [ADR-0112](0112-ai-finops-agent.md) — sibling control-plane agent (cost axis), the template this
  agent's shape follows
- [ADR-0119](0119-ai-devops-agent.md) — sibling control-plane agent (delivery axis)
- [ADR-0101](0101-temporal-durable-execution.md) — Temporal orchestration
- issue #855 — balance reconciliation silently dead for 41 days (motivating incident)
