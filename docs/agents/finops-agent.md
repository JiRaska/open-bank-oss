---
id: finops-agent
plane: control
adr: ADR-0112
---

# finops-agent

## Mission

Proactive AWS cost monitoring. Runs daily (03:00 UTC) plus reactively on a GoAlert cost/infra
anomaly, and watches five things: NAT egress spikes, cross-AZ traffic, Karpenter node churn, EBS
attachment failures, and CI runner pool pressure. When it finds something, it proposes a fix as a
GitHub PR — an IaC diff — through the HITL queue. It never writes to AWS directly; `tools.deny`
blocks every write and execute tier explicitly, not just by omission.

## Why this agent exists

Cloud cost anomalies (a misconfigured NAT route, a runaway node churn loop, a stale ARC runner pool)
are cheap to fix once noticed and expensive to leave running — but nobody's job is to watch five
different telemetry sources every day for them. See the FinOps sweep memory for the kind of drift
this catches in practice: config recorder mode silently flipping to CONTINUOUS, a stopped recorder
after a provider bump, an idle runner pool nobody remembered to shrink. This agent is the "somebody
is always watching" answer to that class of problem.

## Human oversight

- `any_infrastructure_change` — every proposal needs human approval before anything applies,
  full stop.
- `cost_impact_gt_100_usd` — proposals estimating more than $100/month of impact get called out
  explicitly for approval, not just logged as one line among many.
- `tokens_per_run: 50000` — deliberately capped so the agent's own running cost can't outgrow what
  it's meant to save.

## Known gaps

- The Langfuse → Prometheus cost bridge that would show this agent's own token spend on `/iaops` is
  still a skeleton (`/api/finops/ai-costs`) — the cost anomalies it finds are visible, but its own
  running cost isn't fully wired up yet.
- Every proposal is a PR, never an applied change — so a genuinely time-critical cost anomaly (a
  runaway NAT bill accumulating hour by hour) still waits on a human merge. That's the deliberate
  trade-off (ADR-0112: propose, never write), not a bug, but it's worth knowing the agent buys
  detection speed, not remediation speed.
- **`GitHubProposalPort` is unwired and REFUSES — no anomaly of this agent reaches GitHub today**
  (#5897). `openProposalPr` returns `null`, and `DiagnoseAndProposeActivityImpl` then leaves the
  anomaly `DIAGNOSED` with no `proposalPrUrl`: it is never counted in a run's `anomaliesProposed`
  and never presented as awaiting a human. It previously returned a fabricated
  `https://github.com/openbank/openbank/pulls/pending-finops-<id>` URL and moved the anomaly to
  `PROPOSED` — a no-op sharing its shape with a real result, on a host that is not even this
  repository. This follows `openbank-mcp-service`'s `UnwiredProposalPort` (#3900).
  `flaky-test-hunter`'s adapter is the template if and when this gets wired.
