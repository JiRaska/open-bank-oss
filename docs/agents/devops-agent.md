---
id: devops-agent
plane: control
adr: ADR-0119
---

# devops-agent

## Mission

The delivery-pipeline twin of `finops-agent`. Runs daily (04:00 UTC) plus reactively on a GoAlert
CI/deploy/runner anomaly, and watches build/lint health, the four DORA metrics (deployment
frequency, lead time, change failure rate, time-to-restore), ARC runner-pool capacity, deploy/rollout
health, SSDLC hygiene and recurring incidents. It diagnoses a root cause and proposes a *durable* fix
— a code/IaC PR, a runbook update, a tracking ticket — through the same HITL queue as every other
control-plane agent. It never merges, applies, or executes anything itself.

## Why this agent exists

DORA metrics and CI health are exactly the kind of signal that degrades slowly and gets normalized —
a flaky test that "always fails on Mondays", a runner pool that's quietly under capacity, a change
failure rate creeping up over weeks. Nobody notices the trend because nobody's dashboard-watching job
is "compare this week to four weeks ago". This agent's job is specifically to catch the slow drift,
not just the acute failure.

## Human oversight

- `any_pipeline_or_infra_change` and `every: proposal` — a human merges every proposal; there is no
  path from a finding to an applied change without that step.
- `tokens_per_run: 50000`, `runs_per_day: 6` — same discipline as `finops-agent`: the agent's own
  operating cost is capped.

## Known gaps (these are real, not aspirational)

- **The GitHub Actions metrics exporter doesn't exist yet.** The `github-actions-readonly` data
  source and the CI-health detector are wired in code but have no upstream signal to read — this
  detector is currently inert, not producing false negatives so much as producing nothing.
- **The SSDLC-drift feed is the same story** — coverage-floor breaches, `openapi.yaml` drift, a
  service missing `version.txt` — the detector exists, the governance-drift feed it depends on
  doesn't yet.
- **The LLM diagnosis step and the PR-creation step are both stubs** (`LlmDiagnosisAdapter`,
  `RemediationProposalAdapter`) — the detection side of this agent is further along than the
  proposal-generation side. Don't expect a diagnosed root cause to already produce a PR; that wiring
  is still pending.
