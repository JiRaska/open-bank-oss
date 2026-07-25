# OpenBank Business Continuity Policy

Date: 2026-06-30
Version: 1.0
Owner: CTO / Maintainer Lead
Review cadence: Annual (before 31 January each year)
DORA reference: Regulation EU 2022/2554, Art. 11

---

## 1. Scope

This policy covers all ICT systems comprising the OpenBank platform that support payment execution,
customer identity, compliance, and supporting services. It applies to all deployments operating under
a financial services licence or regulatory sandbox acknowledgement.

Out of scope: development workstations, CI/CD build infrastructure (Hetzner/Mac runners), and
third-party SaaS not directly integrated into the payment path.

---

## 2. Critical ICT function classification

| Tier | Services | RTO target | RPO target |
|------|----------|-----------|-----------|
| T0 — Payment execution | payment-service, ledger-service, balance-service, transaction-service, clearing-service | 4 h | 15 min |
| T1 — Identity and auth | party-service, onboarding-service, keycloak, consent-service | 4 h | 1 h |
| T2 — Compliance | kyc-service, sanctions-service, audit-service, aml-service | 8 h | 1 h |
| T3 — Supporting | all other services | 24 h | 4 h |

**Aspirational targets** until Milestone M6 (multi-region active-passive) is in place.
Once M6 infrastructure is certified, T0 targets become contractual (RTO ≤ 30 min, RPO ≤ 5 min).

---

## 3. Technical recovery capabilities

| Capability | Implementation | Tier coverage |
|------------|----------------|---------------|
| Database backup (WAL archiving) | CNPG → S3 continuous archiving, 14-day retention | T0–T3 |
| Point-in-time recovery | CNPG `recovery.targetTime` | T0–T3 |
| Configuration recovery | ArgoCD + GitOps (infra state in git) | T0–T3 |
| Kafka message retention | 7-day topic retention | T0–T1 |
| Secret recovery | OpenBao break-glass keys in AWS Secrets Manager | T0–T3 |
| Node auto-replacement | Karpenter disruption budget + node pool | T0–T3 |
| Service auto-restart | Kubernetes liveness/readiness probes | T0–T3 |
| Rollback | ArgoCD app-level rollback; per-service image history | T0–T3 |

---

## 4. Recovery procedures

All operational recovery procedures are in `openbank-infra/docs/runbooks/`:

| Scenario | Runbook |
|----------|---------|
| Database failover / PITR restore | `runbook-0003-pg-pitr.md` |
| Kafka topic loss | `runbook-0004-kafka-recovery.md` |
| Cluster re-init after node failure | `runbook-0001-cluster-ops.md` |
| OpenBao break-glass re-init | `runbook-0005-openbao-breakglass.md` |
| Full DR restore from backup | `runbook-0002-disaster-recovery.md` |
| Money-path pod kill / CNPG live failover mid-posting | `money-path-chaos-drill-procedure.md` (procedure only — not yet executed, issue #669) |

---

## 5. Crisis communication

| Role | Responsibility | Contact |
|------|---------------|---------|
| BCP Owner (CTO) | Declare incident, initiate recovery, customer notification | Primary maintainer |
| On-call engineer | Execute runbooks, status updates | GoAlert on-call schedule |
| External communication | Regulator notification within 4 h of T0 disruption (DORA Art. 19) | BCP Owner |

Incident status updates: internal Slack #incidents, external status page (if live).

---

## 6. DR test cadence

| Frequency | Scope | Format |
|-----------|-------|--------|
| Quarterly | Sandbox table-top exercise | Review runbooks, verify backup restore |
| Annual (M6+) | Production live failover | Actual failover + measure RTO/RPO |

All tests are recorded in `docs/bcp/dr-test-log.md`.

---

## 7. BCP review and approval

This policy is reviewed annually or after any significant architectural change.
Changes require approval from the BCP Owner and must be committed to `origin/main` via standard PR.
