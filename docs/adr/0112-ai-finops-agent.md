---
date: 2026-06-25
decision-status: accepted
delivery-status: partial
authors: [OpenBank Platform Team]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [finops, ai-agents, observability]
summary: "A control-plane finops-agent detects cost precursors (NAT egress, cross-AZ traffic, node churn, EBS attach health, CI runner pressure) and proposes IaC fixes for human approval, plus AI token-cost dashboards."
---

# ADR-0112 — AI-FinOps Agent: proaktivní nákladová observabilita a optimalizace

## Context

Tři třídy cost-incidentů se opakují protože reakce je vždy post-hoc:

| Incident | Root cause | Jak odhalen |
|---|---|---|
| 250 GB/noc NAT drain | Karpenter overnight churn → cross-AZ traffic | Faktura |
| EBS Multi-Attach crash | Stuck finalizer po node recycle | Pod crashloop |
| CI aio-max-nr exhaustion | Souběžné jobs vyčerpají kernel pool | CI červená |

Žádný z nich není nepředvídatelný — všechny mají měřitelné předzvěsti ≥ 1 hodinu předem.
Zároveň AI agenti (copilot, simulation, holmes-rca) nemají unified cost visibility
v operátorské konzoli — token budgety jsou deklarované v `agents.yaml`, ale operátor
nevidí kolik stojí, kdo přetahuje budget, ani trend.

## Decision

Zavádíme **finops-agent** jako control-plane AI agenta (ADR-0031) s těmito pilíři:

### D1 — NAT egress spike detector
- Metrika: `container_network_transmit_bytes_total` + CloudWatch `NatGatewayBytesOut`
- Threshold: >50 GB/den nebo >200 % 7d rolling avg
- Akce: identifikuje zdrojový pod/namespace → WRITE_PROPOSAL (Karpenter consolidation fix, VPC endpoint)

### D2 — Cross-AZ traffic anomálie
- Metrika: CloudWatch VPC FlowLogs cross-AZ bytes (přes CW Metric Stream → Prometheus)
- Detekuje: Karpenter rozhodí repliky do různých AZ
- Akce: navrhuje `topologySpreadConstraints` patch jako IaC PR

### D3 — Node churn rate
- Metrika: `karpenter_nodes_total` lifecycle events/hodina
- Threshold: >3 node cycles/h mimo maintenance window (20:00–07:00 UTC)
- Akce: navrhuje úpravu `disruption budget` v Karpenter NodePool YAML → gitops PR

### D4 — EBS attachment health
- Metrika: k8s Events `FailedAttachVolume` + PVC `phase != Bound` duration
- Detekuje: stuck volumes před tím než způsobí crashloop
- Akce: navrhuje force-detach runbook + preventivní `multi-attach=false` annotation

### D5 — CI runner pool pressure
- Metrika: ARC runner queue depth + aio-max-nr headroom (node exporter)
- Threshold: headroom < 20 % nebo queue depth > 5 min avg
- Akce: navrhuje scale-up runner pool před exhaustion

### AI cost observability
- Langfuse → Prometheus bridge: token usage, cost per model/agent
- Admin-UI FinOps: karta "AI & Agent Costs" s budget vs. actual, per-agent burn rate
- Admin-UI IAOps: agent roster rozšíření o cost/budget progress bar + anomaly badge
- Kill-switch toggle v IAOps (→ ADR-0067)

## Architecture

```
AWS Cost Explorer API + CloudWatch    Prometheus + k8s Events API
              │                                │
              └──────────────┬────────────────┘
                             ▼
                    finops-agent (Temporal workflow, ADR-0101)
                    ┌─────────────────────────────────────┐
                    │  schedule: daily 03:00 UTC          │
                    │  reactive: GoAlert webhook trigger  │
                    │  manual: operator-triggered via UI  │
                    │                                     │
                    │  1. collect()  — metriky            │
                    │  2. detect()   — 5 detektorů        │
                    │  3. diagnose() — LLM RCA            │
                    │  4. propose()  — IaC diff gen       │
                    │  5. queue()    — HITL               │
                    └──────────────┬──────────────────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼               ▼
               IAOps page    GoAlert/Slack    GitHub PR
               (admin-UI)     (alert)          (IaC diff)
```

Agent je **control-plane agent** (ADR-0031):
- `policy_decision: deny` — deny-by-default, whitelist READ akce
- `write_proposal` tier — otevírá GitHub PRy, **nikdy nepíše přímo do AWS**
- HITL mandatory pro vše co mění infrastrukturu
- Temporal orchestration — každý run durable, replayable, AI-attributed audit

## Security & Audit

1. **IAM role read-only**: `ce:GetCostAndUsage`, `ce:GetCostForecast`, `cloudwatch:GetMetricData`. Žádné write.
2. **GitHub App PR scope only** — ne PAT. Short-TTL SVID (SPIFFE/SPIRE, ADR-0017).
3. **HITL mandatory** pro každou infrastrukturní změnu — EU AI Act Art. 9 (risk management system).
4. **Audit trail**: každý run → `AuditEvent(actor_type=AI_AGENT, agent_id=finops-agent, action=PROPOSE_OPTIMIZATION, cost_impact_usd=..., human_approved_by=...)`
5. **Kill switch**: `enabled: false` v agents.yaml zastaví agenta bez redeploymentu.
6. **Cost self-cap**: agent má vlastní token budget — nesmí stát víc než ušetří.

## Phasing

| Fáze | Co | PRs |
|---|---|---|
| **P1 — Observe** | ADR + agents.yaml charter + Langfuse→Prometheus bridge | feat/adr-0112 |
| **P2 — Detect** | Prometheus alerting rules D1–D5 + admin-UI AI costs karta | feat/finops-alerts |
| **P3 — Diagnose** | finops-agent Temporal service (collect+detect+diagnose) | feat/finops-agent-svc |
| **P4 — Propose** | HITL queue + GitHub PR generování + IAOps approval UI | feat/finops-hitl-ui |

## Consequences

**Pozitivní:**
- NAT/EBS/CI problémy detekované ≥1h před dopadem
- AI costs viditelné v operátorské konzoli poprvé
- EU AI Act Art. 9 splněn pro AI workloady s finančním dopadem
- Každý finops-agent run je auditovaný, replayable

**Negativní:**
- Nový agent = nový Temporal workflow = další dependency na Temporal cluster
- AWS Cost Explorer API má 1h granularitu — real-time NAT spike vyžaduje CloudWatch (nižší latence)
- Langfuse→Prometheus bridge = nový exporter k maintainovat

## References

- [ADR-0031](0031-ai-agent-governance.md) — AI agent governance framework
- [ADR-0054](0054-finops-managed-version-lifecycle-and-cost-audit.md) — FinOps lifecycle
- [ADR-0062](0062-finops-cost-allocation-showback.md) — Cost allocation
- [ADR-0058](0058-fck-nat-egress-cost-sandbox.md) — NAT egress optimization
- [ADR-0101](0101-temporal-durable-execution.md) — Temporal orchestration
- [Issue #2137](https://github.com/JiRaska/open-bank-oss/issues/2137) — tracking issue
