<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-referral-service

> Operational runbook for the `referral` service. Data domain **open-banking**,
> classification **confidential**, datastore **PostgreSQL**.

## Deployment status — WORKLOAD STAGED, ACTIVATION PENDING

**GitOps deliberately declares zero replicas for this workload.** This is not a live
service and does not authorize a replica increase, restart, log inspection, traffic claim,
or metrics/health assertion. Activation remains the separately reviewed step after the
pinned image, GitOps sync, and live cluster-health evidence are available. After that
step, health must be checked on the management endpoint `:8086`;
the public HTTP port is not a health-evidence substitute.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-referral-service` |
| HTTP port | `8155` |
| Data domain | open-banking |
| Datastore | PostgreSQL (database `openbank_referral`) |
| Classification | confidential |
| Retention | 13 months |
| Lineage role | both |

## Dependencies

- **Upstream (this service consumes):** _none declared_
- **Downstream (depends on this service):** _none declared_

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `referral`.

## Runtime operations — DEFERRED

Do not increase replicas, restart, or use log/metrics commands to activate this staged
workload. The reviewed activation procedure must first establish the signed image,
GitOps sync, and actual cluster health. It will then use management health endpoints
`GET :8086/q/health/ready` and
`GET :8086/q/health/live`.

## Common failure modes

- **Pod CrashLoopBackOff at boot:** usually a missing/invalid config or secret
  (`ExternalSecret` not synced) or a Flyway checksum mismatch. Check
  `kubectl describe pod` events and the first 50 log lines.
- **Readiness flapping:** datastore (PostgreSQL) unreachable or saturated — check the
  datastore pod/cluster health and connection-pool metrics.
- **Downstream errors:** verify the upstream dependencies above are healthy before
  assuming the fault is local.

## Disaster recovery

- **RPO target:** ≤ 5 min (continuous archiving). **RTO target:** ≤ 30 min (restore + warm-up).
- **Mechanism:** CloudNativePG continuous WAL archiving + base backups to S3 (`barmanObjectStore`). Point-in-time recovery (PITR).
- **Restore:** create a `Cluster` with `bootstrap.recovery` pointing at the backup object store; CNPG replays WAL to the target time. See runbook 0003 (PG major upgrade) for the cluster-recreate mechanics.
- **Verify:** `kubectl cnpg status <db>-rw -n <ns>` shows the recovered cluster Healthy and the `*-app` secret regenerated.

> RPO/RTO above are documented targets. They become **Bank-grade** (prod-readiness
> C6=3) only once a restore/failover drill has actually been rehearsed and attested
> (`openbank-libs/governance/attestations.yaml: referral.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `referral.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
