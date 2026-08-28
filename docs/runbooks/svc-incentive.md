<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-incentive-service

> Operational runbook for the `incentive` service. Data domain **open-banking**,
> classification **confidential**, datastore **PostgreSQL**.

## Deployment status — NOT DEPLOYED

**This service has no workload anywhere in `openbank-infra/gitops/`** — no Deployment,
no Rollout, and therefore no namespace, no CNPG cluster, no NetworkPolicy and no
PodMonitor coverage. It is a released component (it has a `version.txt`) that has never
run, so **every `kubectl` command below names a namespace that does not exist** and every
procedure here is a plan rather than a rehearsed one.

The production-readiness matrix reports it as **NOT-DEPLOYED** rather than NO-GO for the
same reason: the cells it fails are consequences of the absent workload, not controls
someone skipped, and none of them can be closed by a repo change. Whether this service
should be deployed is an owner decision — see the service's own `CLAUDE.md`.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-incentive-service` |
| HTTP port | `8156` |
| Data domain | open-banking |
| Datastore | PostgreSQL (database `openbank_incentive`) |
| Classification | confidential |
| Retention | 13 months |
| Lineage role | both |

## Dependencies

- **Upstream (this service consumes):** _none declared_
- **Downstream (depends on this service):** _none declared_

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `incentive`.

## Health & probes

- Readiness: `GET :8156/q/health/ready` · Liveness: `GET :8156/q/health/live`
- Metrics: scraped by the fleet PodMonitor (namespace `incentive`); dashboards in Grafana.
- Logs: `kubectl logs -n incentive deploy/incentive-service -f`, or Loki
  `{namespace="incentive"}`.

## Routine operations

- **Restart:** `kubectl rollout restart deploy/incentive-service -n incentive` (rolling, zero-downtime at >1 replica).
- **Scale:** `kubectl scale deploy/incentive-service -n incentive --replicas=<n>` (or edit the GitOps manifest — GitOps is source of truth, a manual scale is reverted by ArgoCD).
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
> (`openbank-libs/governance/attestations.yaml: incentive.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `incentive.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
