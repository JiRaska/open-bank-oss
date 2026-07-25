<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-balance-service

> Operational runbook for the `balance` service. Data domain **core**,
> classification **internal**, datastore **Redis**.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-balance-service` |
| HTTP port | `8103` |
| Data domain | core |
| Datastore | Redis (schema `balances_schema`) |
| Classification | internal |
| Retention | 3 years |
| Lineage role | consumer |

## Dependencies

- **Upstream (this service consumes):** `transaction-service`, `account-service`, `agent-service`
- **Downstream (depends on this service):** _none declared_

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `balance`.

## Health & probes

- Readiness: `GET :8103/q/health/ready` · Liveness: `GET :8103/q/health/live`
- Metrics: scraped by the fleet PodMonitor (namespace `balances`); dashboards in Grafana.
- Logs: `kubectl logs -n balances deploy/balance-service -f`, or Loki
  `{namespace="balances"}`.

## Routine operations

- **Restart:** `kubectl rollout restart deploy/balance-service -n balances` (rolling, zero-downtime at >1 replica).
- **Scale:** `kubectl scale deploy/balance-service -n balances --replicas=<n>` (or edit the GitOps Deployment — GitOps is source of truth, a manual scale is reverted by ArgoCD).
- **Config/secret change:** edit the GitOps manifest; ArgoCD syncs. Never `kubectl edit` in place.

## Common failure modes

- **Pod CrashLoopBackOff at boot:** usually a missing/invalid config or secret
  (`ExternalSecret` not synced) or a Flyway checksum mismatch. Check
  `kubectl describe pod` events and the first 50 log lines.
- **Readiness flapping:** datastore (Redis) unreachable or saturated — check the
  datastore pod/cluster health and connection-pool metrics.
- **Downstream errors:** verify the upstream dependencies above are healthy before
  assuming the fault is local.

## Disaster recovery

- **RPO target:** ≤ 5 min (continuous archiving). **RTO target:** ≤ 30 min (restore + warm-up).
- **Mechanism:** restore from the datastore's managed backup to a fresh instance (confirm a backup is actually configured for this datastore first).
- **Restore:** provision a new datastore from the latest backup, replay any incremental logs, re-point the service.
- **Verify:** health endpoint green + a domain spot check against last known-good.

> RPO/RTO above are documented targets. They become **Bank-grade** (prod-readiness
> C6=3) only once a restore/failover drill has actually been rehearsed and attested
> (`openbank-libs/governance/attestations.yaml: balance.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `balance.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
