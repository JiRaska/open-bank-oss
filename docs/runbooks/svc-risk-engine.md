<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-risk-engine

> Operational runbook for the `risk-engine` service. Data domain **core**,
> classification **confidential**, datastore **PostgreSQL**.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-risk-engine` |
| HTTP port | `8159` |
| Data domain | core |
| Datastore | PostgreSQL (database `openbank_risk`) |
| Classification | confidential |
| Retention | 10 years |
| Lineage role | consumer |

## Dependencies

- **Upstream (this service consumes):** `fx-service`
- **Downstream (depends on this service):** `ledger-service`

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `risk-engine`.

## Health & probes

- Readiness: `GET :8085/q/health/ready` · Liveness: `GET :8085/q/health/live`
- Metrics: scraped by the fleet PodMonitor (namespace `risk`); dashboards in Grafana.
- Logs: `kubectl logs -n risk deploy/risk-engine -f`, or Loki
  `{namespace="risk"}`.

## Routine operations

- **Restart:** `kubectl rollout restart deploy/risk-engine -n risk` (rolling, zero-downtime at >1 replica).
- **Scale:** `kubectl scale deploy/risk-engine -n risk --replicas=<n>` (or edit the GitOps manifest — GitOps is source of truth, a later ArgoCD sync reconciles manual changes).
- **Config/secret change:** edit the GitOps manifest; ArgoCD syncs. Never `kubectl edit` in place.

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
> (`openbank-libs/governance/attestations.yaml: risk-engine.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `risk-engine.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
