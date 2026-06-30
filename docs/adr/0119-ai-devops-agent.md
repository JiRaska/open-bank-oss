# ADR-0119 — AI DevOps Agent: proactive SSDLC / DORA observability and durable-fix proposals

| Field       | Value |
|-------------|-------|
| Status      | Accepted |
| Delivery-Status | Partial |
| Date        | 2026-06-27 |
| Authors     | OpenBank Platform Team |
| Relates to  | ADR-0031, ADR-0061, ADR-0053, ADR-0082, ADR-0101, ADR-0112, ADR-0002, ADR-0029 |
| Issue       | #2284 |

## Context

OpenBank already proactively watches one operational axis and does nothing on the other:

| Axis | What watches it | Posture |
|---|---|---|
| **Cost** (cloud spend) | finops-agent (ADR-0112) | **proactive** — detect → diagnose → propose IaC PR via HITL |
| **Delivery** (SSDLC / CI-CD / DORA) | the `/devops` page (ADR-0061) | **read-only** — four DORA cards, no agent |

The delivery axis has measurement but no agency. The DORA metrics (Deployment Frequency,
Lead Time, Change Failure Rate, MTTR) are derived in CI and served as a read-only snapshot
(ADR-0061) — a dashboard a human must remember to look at. Nothing proactively watches
SSDLC/CI-CD health, diagnoses a regression, or proposes a **durable** fix for the class of
self-inflicted pipeline problem that recurs because the fix was applied live and never made
permanent.

The motivating incident is exactly that class. On **2026-06-27** the `openbank-batch` runner
label was added **live via the GitHub API** so the ARC batch lane (Trivy in `security.yml`,
FinOps in `finops-lifecycle.yml`, both `runs-on: openbank-batch`) would be served — but the
label was **not durable** in `openbank-infra/scripts/reregister-runner.sh`
(`RUNNER_LABELS` did not carry `openbank-batch`). A re-register would have silently dropped
the label and **stranded every batch job in an indefinite queue** (jobs assigned, zero online
runners for the pool). It was caught by a human and fixed manually in **PR #2284**. A proactive
agent watching runner capacity would have caught the stranded pool and proposed the durable
script fix as a PR — turning a manual catch into a governed proposal.

The pattern is identical to finops-agent's: measurable precursors exist, the reaction is
post-hoc, and the durable fix is an IaC/code PR a human should approve — not a direct write.
The DevOps agent is the finops-agent's SSDLC/DORA twin.

## Decision

We introduce **`openbank-devops-agent`** as a control-plane AI agent (ADR-0031), modeled
**1:1 on the finops-agent**: a Quarkus/Kotlin service (port **8142**), hexagonal (ADR-0002),
Temporal-orchestrated (ADR-0101). A durable analysis sweep runs the same five-stage pipeline:

```
collect()  → Prometheus SSDLC/DORA signals
detect()   → 6 threshold detectors → findings
diagnose() → LiteLLM gateway RCA (ADR-0031 D6)
propose()  → durable remediation: code/IaC PR | runbook update | tracking ticket
queue()    → HITL: a human approves/dismisses; the agent never merges
```

The agent **proposes a durable fix, a human disposes** (segregation of duties). Six detectors:

### D1 — CI pipeline health
- Signals: repeated build/lint failures, rising build duration, flaky-test rate.
- Inert until a **github-actions metrics exporter** lands (the signal source). Detector wired.

### D2 — DORA regression (CFR proxy)
- Signal: a DORA metric (ADR-0061) degrades vs. its rolling baseline — Change Failure Rate
  proxy as the headline trend signal.
- Action: diagnose the regressing window → propose a tracking ticket or guard.

### D3 — Runner capacity (the stranded-pool detector)
- Signal: jobs **assigned but 0 online runners** for a pool → **CRITICAL**.
- This is the detector that catches the 2026-06-27 `openbank-batch` incident: a starved pool
  with queue pressure and no online runners. Action: propose the durable
  `reregister-runner.sh` `RUNNER_LABELS` fix as a PR.

### D4 — Deploy health
- Signal: Argo Rollouts canary alerts, rollout aborted, `can-i-deploy` blocked, stale image.
- Action: propose a rollback runbook reference or a tracking ticket.

### D5 — SSDLC hygiene (follow-up)
- Signal: coverage below floor, `openapi.yaml` drift, missing `version.txt` bump, missing
  threat model (ADR-0029 rules).
- Inert until an **SSDLC-drift feed** lands. Detector wired.

### D6 — Incident recurrence (learning loop)
- Signal: the **same alert/incident recurs** → the live fix never became durable.
- Action: propose a **permanent guard** (the durable-fix loop the `openbank-batch` incident
  exemplifies). This is the detector that closes the "fixed live, not in git" gap.

### Admin-UI surface
- The `/devops` page (`openbank-admin-ui/src/app/devops/page.tsx`) gains a **"DevOps Insights
  (AI)"** panel with HITL **Approve / Dismiss** buttons, mirroring the IAOps cost-anomaly card
  from ADR-0112. Served read-only (no runtime token in the pod, ADR-0061).

## Architecture

```
Prometheus (SSDLC/DORA/runner/rollout metrics)    GitHub Actions exporter (D1, deferred)
                 │                                          │
                 └────────────────────┬─────────────────────┘
                                      ▼
              devops-agent (Temporal workflow, ADR-0101, port 8142)
              ┌──────────────────────────────────────────────────┐
              │  schedule: durable analysis sweep                 │
              │  reactive: alert webhook trigger                  │
              │  manual:   operator-triggered via /devops         │
              │                                                   │
              │  1. collect()  — 5 collect activities             │
              │  2. detect()   — 6 threshold detectors (D1–D6)    │
              │  3. diagnose() — LiteLLM gateway RCA (STUB)        │
              │  4. propose()  — PR | runbook | ticket (STUB)     │
              │  5. queue()    — HITL finding repository          │
              └──────────────────┬───────────────────────────────┘
                                 │
                  ┌──────────────┼──────────────┐
                  ▼              ▼               ▼
            /devops page     alert/Slack    GitHub PR
            (admin-UI)        (notify)       (durable fix)
```

Agent is a **control-plane agent** (ADR-0031): `deny`-by-default, whitelist READ actions,
`write_proposal` tier (opens GitHub PRs only — **never** writes to CI, k8s, or the cluster),
HITL mandatory for every proposal, Temporal orchestration (each run durable, replayable,
AI-attributed audit).

## Governance (ADR-0031)

The agent has a charter in `openbank-libs/governance/agents.yaml`:

- **id** `devops-agent`, **plane** `control`, **charter** `ADR-0119`, deny-by-default.
- **Tools — read tier**: `prometheus`, `github-actions`, `kubernetes-events`,
  `governance/runbooks` (read SSDLC/DORA signals + runbooks). **write_proposal tier**:
  `github-pr` only (opens a durable-fix PR).
- **Denied**: all `write` resources, all `execute` resources, `gh.pr.merge`,
  `secrets.read.raw`. The agent proposes; it never merges or applies.
- **`requires_human` on every proposal** — a human disposes and merges (segregation of
  duties: an author identity ≠ an approver identity, ADR-0031 D3).
- **pii: none** — pipeline/SSDLC metadata only; no customer data in scope.
- **Kill switch** (`enabled: false` halts the agent without redeploy, ADR-0031 D7).
- **EU AI Act**: proposal-only ⇒ **not Annex III high-risk** → limited/minimal risk;
  Art. 9 (risk management) and Art. 13 (transparency — all proposals logged) via the
  AI-attributed audit (ADR-0031 D5).

## Scope of this first vertical / what's deferred

**Delivered in this change:**
- The Quarkus/Kotlin **service skeleton** — hexagonal (ADR-0002), under `openbank-devops-agent`
  (`version.txt` = `0.1.0`).
- The **Temporal workflow** (`DevOpsAnalysisWorkflow` + `…Impl`) + **5 collect activities**
  (`CollectSignalsActivityImpl`: CI, DORA, runner-capacity, deploy-health, incident-recurrence)
  + **6 threshold detectors** (`DetectorId.D1…D6` in `domain/model/DevOpsModels.kt`,
  `DetectFindingsActivityImpl`). **D1 and D5 are inert** until a github-actions exporter and a
  governance-drift feed land.
- The **LiteLLM** (`LlmDiagnosisAdapter`) and **GitHub-PR** (`RemediationProposalAdapter`)
  adapters as **structured STUBs** — same posture as finops-agent's P3: the actual
  `/chat/completions` wiring and PR creation are documented follow-ups.
- An **in-memory finding repository** (`InMemoryFindingRepository`) — Postgres persistence
  deferred; **Temporal history is the durable record**.
- The admin-UI **"DevOps Insights (AI)"** panel with placeholder HITL buttons.
- The **charter** in `agents.yaml` and the **gitops manifests**
  (`openbank-infra/gitops/components/devops-agent/`: `devops-agent.yaml`, `namespace.yaml`,
  `network-policies.yaml`).

**Deferred to follow-ups (honestly):** the live LLM call, real PR creation, Postgres
persistence, the HITL approve/reject backend, the github-actions metrics exporter (D1), and
the SSDLC-drift feed (D5).

## Alternatives considered

- **Fold into finops-agent.** Pros: one agent, one workflow. Cons: different domain (delivery
  vs. cost), different charter (`github-actions`/runner reads vs. AWS Cost Explorer), different
  cadence and signal sources. Rejected — one agent per bounded concern keeps charters
  least-privilege and auditable.
- **Extend HolmesGPT for this.** Cons: Holmes is **reactive per-alert RCA** (it explains a
  firing alert). This is **proactive trend/SSDLC monitoring** that proposes a **durable** fix
  for a problem that may not yet be alerting. Rejected — different posture.
- **Just add more dashboards to `/devops`.** Cons: the point is **proposals**, not more
  read-only charts. ADR-0061 already proved the dashboard exists and is passively ignored.
  Rejected — measurement without agency is what we are fixing.

## Consequences

**Positive**
- The delivery axis gets the same proactive detect → diagnose → propose posture cost already
  has; the `openbank-batch` class of "fixed live, not in git" incident is caught by D3/D6 and
  turned into a durable PR proposal.
- Reuses the finops-agent blueprint 1:1 (Temporal, hexagonal, control-plane charter, HITL) —
  little net-new pattern, mostly the delivery-domain detectors and adapters.
- Every run is AI-attributed, durable, replayable (Temporal history is the audit substrate).

**Negative**
- Another Temporal workflow = another dependency on the Temporal cluster.
- D1 and D5 are **inert** until their signal sources (github-actions exporter, SSDLC-drift
  feed) land — the panel will under-report until then; stated plainly rather than faked.
- The LLM/PR adapters are STUBs — the agent **detects** but does not yet **diagnose or open
  PRs** until the follow-ups wire `/chat/completions` and PR creation.

**Neutral**
- The agent is one more `actor` (`actorType = AI_AGENT`) in the same governance machinery as
  the finops-agent; no parallel rulebook.
- EU AI Act scope is per-agent (ADR-0031): proposal-only ⇒ limited/minimal risk, not Annex III.

## Compliance impact

- PCI DSS: Req. 7 (least privilege) + Req. 10 (audit trail) — read-tier gating + AI-attributed audit.
- DORA:    Art. 8–10 (ICT change management — the agent watches and proposes durable fixes for
  the delivery pipeline as an ICT process), Art. 17 (run reconstruction via Temporal history).
- GDPR:    Art. 30 (agent actions in the audit log); pii **none** — no customer data in scope.
- PSD2:    not applicable (no money-path; pipeline metadata only).
- CNB:     supports operational-resilience expectations for the delivery pipeline.
- EU AI Act: **limited/minimal risk** — proposal-only, not Annex III high-risk; Art. 9
  (risk management) + Art. 13 (transparency) via HITL + AI-attributed audit (ADR-0031 D4/D5).

## References

- [ADR-0031](0031-ai-agent-governance-and-operations.md) — AI agent governance the devops-agent obeys.
- [ADR-0112](0112-ai-finops-agent.md) — the cost-axis twin this agent mirrors 1:1.
- [ADR-0061](0061-dora-metrics-from-in-house-sources.md) — DORA snapshot + the `/devops` page + phasing.
- [ADR-0101](0101-temporal-durable-execution.md) — Temporal durable orchestration.
- [ADR-0002](0002-hexagonal-architecture-per-service.md) — hexagonal architecture (ports/adapters).
- [ADR-0053](0053-ephemeral-scale-to-zero-arc-runners.md) / [ADR-0082](0082-ci-runner-governance.md) — the deploy pipeline + ARC runner pools.
- `openbank-devops-agent/` — the service (workflow, 5 collect activities, 6 detectors, STUB adapters, in-memory repo).
- `openbank-libs/governance/agents.yaml` — the `devops-agent` charter.
- `openbank-infra/scripts/reregister-runner.sh` — the durable `RUNNER_LABELS` the D3 incident exposed (PR #2284).
- `openbank-infra/gitops/components/devops-agent/` — gitops manifests.
- `openbank-admin-ui/src/app/devops/page.tsx` — the "DevOps Insights (AI)" panel.
- [Issue #2284](https://github.com/JiRaska/open-bank-oss/issues/2284) — the motivating `openbank-batch` fix.
