---
id: control-liveness-sentinel
plane: control
adr: ADR-0163
---

# control-liveness-sentinel

## Mission

Fleet-wide correlator for the four ADR-0160 liveness/drift-detection mechanisms: stale
`WorkflowLivenessWatchdog` heartbeats, event-consumer-liveness regressions, lineage-vs-code drift,
and sustained reconciliation drift. Runs daily (03:00 UTC) plus reactively when a watchdog gauge
itself pages, and turns "N controls have a stale or missing heartbeat right now" into one triaged
finding instead of N independent pages. Proposes a durable fix (a code/IaC PR, or a tracking
ticket for an unowned control) through the HITL queue — it never touches a control directly.

## Why this agent exists

ADR-0160 built the detection primitives after two incidents investigated back-to-back: a
standing-order scheduler that published an event nothing consumed for weeks (#889), and a
reconciliation control that reported a ~220,000 CZK drift that turned out to be a transient
backfill artifact, not a defect (#860). Both root causes were the same shape — a claim about
runtime behaviour that was never re-verified — and ADR-0160's own "Negative" consequences section
is explicit that adoption of its mechanisms (especially the shared watchdog primitive) is a
fleet-wide sweep, not a single change. This agent is the thing that watches the sweep's progress
and the mechanisms themselves, the same role finops-agent plays for cost and devops-agent plays
for CI/DORA — so a control that goes silent doesn't wait to be found by accident the way balance
reconciliation did for 41 days (issue #855).

## Human oversight

- `any_control_or_pipeline_change` — every proposal needs human approval before anything applies.
- `every: proposal` — the agent never merges or applies its own finding; segregation of duties
  matches finops-agent/devops-agent.
- `tokens_per_run: 50000` — capped so the agent's own running cost stays a rounding error next to
  what a caught-early silent control failure is worth.

## Known gaps

- Detection is only as good as ADR-0160 mechanism 3's adoption rate: a `@Scheduled` job that has
  not yet been wired into `WorkflowLivenessWatchdog` emits no gauge, so this agent cannot see it
  going silent — the exact #855 failure mode, until that job's own adoption PR lands.
- The LLM diagnosis and durable-fix-diff generation are stubs pending the shared LiteLLM gateway
  wiring (same bootstrap state finops-agent/devops-agent shipped with); a finding today produces a
  tracking ticket rather than a ready-to-review code diff.
- The Prometheus gauge names this agent queries
  (`openbank_workflow_liveness_last_success_age_seconds`,
  `openbank_event_consumer_liveness_producer_only`, `openbank_lineage_audit_unverified_edge`,
  `openbank_reconciliation_consecutive_drift_runs`) are the contract this agent expects from
  ADR-0160's mechanisms 1–4; wiring each mechanism's CI script / watchdog primitive to actually
  emit them is tracked as part of ADR-0160's own rollout, not duplicated here.
