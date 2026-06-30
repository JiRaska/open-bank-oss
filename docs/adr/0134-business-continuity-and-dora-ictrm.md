# ADR-0134: Business continuity plan and DORA ICT risk management framework

Date: 2026-06-29
Status: Accepted
Decision-Status: Accepted
Delivery-Status: Complete
Author(s): Jiri Raska

## Context

DORA (Regulation EU 2022/2554), applicable from 17 January 2025 to financial entities, mandates a
documented **ICT Business Continuity Policy** (Art. 11) and a supporting ICT Disaster Recovery Plan
(Art. 11(3)(c)). ČNB implements DORA via its supervisory expectations framework; a regulated entity
must demonstrate:

1. A tested BCP with defined RTO and RPO targets.
2. A documented classification of critical ICT functions.
3. At least one DR test per year.
4. Crisis communication procedures.

OpenBank currently has strong technical foundations — CNPG S3 WAL archiving, ArgoCD GitOps, Karpenter,
Strimzi, per-service runbooks, and a `bcp-health-check.sh` script — but no **policy document** that
maps these technical capabilities to DORA articles, defines ownership, or records test results. The
technical architecture (Milestone M6) is a separate concern from the policy framework (this ADR).

## Decision

We will maintain a **Business Continuity and DORA ICT Risk Management Framework** as a living document
at `docs/bcp/` comprising:

1. **`bcp-policy.md`** — scope, ownership (BCP Owner = CTO / maintainer lead), classification of
   critical ICT functions mapped to OpenBank services, RTO/RPO targets per tier.
2. **`dora-ictrm.md`** — mapping of each DORA Art. 9–14 obligation to the OpenBank control that
   satisfies it (technical + organisational).
3. **`dr-test-log.md`** — append-only record of every DR test: date, scenario, result, lessons learned.
4. **Runbook references** — the existing `openbank-infra/docs/runbooks/` suite is the operational
   layer; BCP references but does not duplicate runbooks.

**Critical ICT function tiers** (initial classification):

| Tier | Services | RTO target | RPO target |
|------|----------|-----------|-----------|
| T0 — Payment execution | payment-service, ledger-service, balance-service, transaction-service | 4 h | 15 min |
| T1 — Identity and auth | party-service, onboarding-service, keycloak | 4 h | 1 h |
| T2 — Compliance | kyc-service, sanctions-service, audit-service | 8 h | 1 h |
| T3 — Supporting | all other services | 24 h | 4 h |

These targets are **aspirational** for the sandbox; they become contractual once a production
deployment is certified. Milestone M6 (multi-region active-passive, ROADMAP) is required to
demonstrate RTO ≤ 30 min for T0.

The DR test cadence is **once per quarter** in sandbox (table-top exercise) and **once per year**
with a live failover test once M6 infrastructure is in place.

## Alternatives considered

- **No formal BCP until licensing** — deferred cost. Rejected: even pre-licensing, OSS adopters who
  operate OpenBank under their own licence will need a reference BCP; providing one is a differentiator
  and reduces their compliance burden.
- **Third-party BCP template** — faster to produce but not specific to OpenBank's architecture or
  DORA mapping. Rejected: the value is the concrete mapping to actual controls.

## Consequences

**Positive**
- Satisfies DORA Art. 11 documentation requirement.
- Provides a clear single source of truth for DR ownership and tested RTO/RPO targets.
- Enables OSS operators to use OpenBank's BCP as a starting point for their own regulated deployment.
- DR test log creates an auditable record of continuity testing over time.

**Negative**
- BCP is a living document — it rots without an owner and a review cadence (annual minimum).
- RTO/RPO targets for T0 cannot actually be met until M6 infrastructure (CNPG `instances: 3`,
  Kafka `replicas: 3`, multi-AZ node pools) is in place.

**Neutral**
- The `bcp-health-check.sh` script is a local Docker Compose health check, not a Kubernetes DR
  validation. A Kubernetes-native BCP smoke test (ArgoCD sync + CNPG promote + Kafka consumer
  reconnect) should replace or complement it for production scenarios.

## Compliance impact

- DORA: Art. 9 (ICT risk management), Art. 11 (ICT business continuity policy), Art. 11(3)(c) (DR
  plan), Art. 17 (ICT-related incident management)
- ČNB: supervisory expectations on BCP testing cadence
- GDPR: Art. 32(1)(c) (ability to restore availability and access to personal data)
- PSD2: Art. 95 (operational and security risk management)
- PCI DSS: Req. 12.10 (incident response plan)

## References

- DORA Regulation (EU) 2022/2554, Articles 9–14
- `openbank-infra/docs/runbooks/` — operational runbooks (BCP operational layer)
- `openbank-infra/bcp-health-check.sh` — local Docker Compose health signal
- `docs/ROADMAP.md` M6 — multi-region active-passive (RTO ≤ 30 min, RPO ≤ 5 min)
- ADR-0027 (cloud-agnostic substrate) — technical DR foundation
- ADR-0101 (Temporal durable execution) — T0 payment saga recovery
