<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-communication-service

> Operational runbook for the `communication` service. Data domain **engagement**,
> classification **internal**, datastore **PostgreSQL**.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-communication-service` |
| HTTP port | `8158` |
| Data domain | engagement |
| Datastore | PostgreSQL (database `openbank_communication`) |
| Classification | internal |
| Retention | 3 years |
| Lineage role | internal |

## Dependencies

- **Upstream (this service consumes):** _none declared_
- **Downstream (depends on this service):** _none declared_

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `communication`.

## Activation and verification

This change proposes one replica; it is not evidence of a running workload. Keep the
activation PR draft with auto-merge disabled until the final reviewed head has explicit
operator approval and the standard deployment-window prerequisites pass.

Before approval, retain evidence for the exact pinned image: vulnerability verdict,
signature, SBOM and provenance, source freshness, real customer-edge consumer publication,
provider verification and the strict deployment contract verdict. Manifest checks alone
cannot qualify the workload. Preserve independent deployment holds.

After the approved GitOps rollout, verify the actual running image digest and readiness
on the management endpoint `:8086/q/health/ready`, then customer-edge app-copy and the
authenticated admin editor flow. Do not publish customer content as a health probe.
A Healthy Application with zero replicas is not workload health evidence.

Rollback through a reviewed GitOps change restoring replicas to zero, retaining the
database and published copy. Do not use manual scaling or restarts to bypass this process.

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
> (`openbank-libs/governance/attestations.yaml: communication.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `communication.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
