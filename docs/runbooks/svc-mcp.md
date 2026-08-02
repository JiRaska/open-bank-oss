<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-mcp-service

> Operational runbook for the `mcp` service. Data domain **payments**,
> classification **confidential**, datastore **PostgreSQL**.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-mcp-service` |
| HTTP port | `8150` |
| Data domain | payments |
| Datastore | PostgreSQL (database `openbank_mcp`) |
| Classification | confidential |
| Retention | 13-months |
| Lineage role | consumer |

## Dependencies

- **Upstream (this service consumes):** _none declared_
- **Downstream (depends on this service):** _none declared_

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `mcp`.

## Health & probes

- Readiness: `GET :8150/q/health/ready` · Liveness: `GET :8150/q/health/live`
- Metrics: scraped by the fleet PodMonitor (namespace `platform`); dashboards in Grafana.
- Logs: `kubectl logs -n platform deploy/mcp-service -f`, or Loki
  `{namespace="platform"}`.

## Routine operations

- **Restart:** `kubectl rollout restart deploy/mcp-service -n platform` (rolling, zero-downtime at >1 replica).
- **Scale:** `kubectl scale deploy/mcp-service -n platform --replicas=<n>` (or edit the GitOps manifest — GitOps is source of truth, a manual scale is reverted by ArgoCD).
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

- **RPO/RTO: undefined** — no backup is configured yet (see the prerequisite below), so no recovery-point/time guarantee can be made today.
- **⚠ Prerequisite NOT met:** this PostgreSQL cluster has **no backup configured** (`barmanObjectStore` absent — prod-readiness C5=1). **DR is not achievable today** and the RPO/RTO targets above do NOT yet apply. Enabling the CNPG backup is the blocking prerequisite (see the backup sweep). Once enabled, the procedure is:
- **Mechanism (after enablement):** CNPG continuous WAL + base backups → PITR.
- **Restore:** create a `Cluster` with `bootstrap.recovery` pointing at the backup object store; CNPG replays WAL to the target time. See runbook 0003 (PG major upgrade) for the cluster-recreate mechanics.
- **Verify:** `kubectl cnpg status <db>-rw -n <ns>` shows the recovered cluster Healthy and the `*-app` secret regenerated.

> RPO/RTO above are documented targets. They become **Bank-grade** (prod-readiness
> C6=3) only once a restore/failover drill has actually been rehearsed and attested
> (`openbank-libs/governance/attestations.yaml: mcp.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `mcp.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
