<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-copilot-service

> Operational runbook for the `copilot` service. Data domain **platform**,
> classification **confidential**, datastore **Redis**.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-copilot-service` |
| HTTP port | `8131` |
| Data domain | platform |
| Datastore | Redis (database `—`) |
| Classification | confidential |
| Retention | 1 year |
| Lineage role | internal |

## Dependencies

- **Upstream (this service consumes):** _none declared_
- **Downstream (depends on this service):** `balance-service`, `transaction-service`, `ledger-service`, `fx-service`

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `copilot`.

## Health & probes

- Readiness: `GET :8131/q/health/ready` · Liveness: `GET :8131/q/health/live`
- Metrics: scraped by the fleet PodMonitor (namespace `platform`); dashboards in Grafana.
- Logs: `kubectl logs -n platform deploy/copilot-service -f`, or Loki
  `{namespace="platform"}`.

## Routine operations

- **Restart:** `kubectl rollout restart deploy/copilot-service -n platform` (rolling, zero-downtime at >1 replica).
- **Scale:** `kubectl scale deploy/copilot-service -n platform --replicas=<n>` (or edit the GitOps Deployment — GitOps is source of truth, a manual scale is reverted by ArgoCD).
- **Config/secret change:** edit the GitOps manifest; ArgoCD syncs. Never `kubectl edit` in place.

## Common failure modes

- **Pod CrashLoopBackOff at boot:** usually a missing/invalid config or secret
  (`ExternalSecret` not synced). Check `kubectl describe pod` events and the
  first 50 log lines.
- **Readiness flapping:** this service owns no database, but check its **Redis**
  connectivity before ruling out the datastore — an upstream dependency below, or the
  OPA sidecar if `AUTHZ_ENFORCE` is on (with no reachable PDP, `@Authorize` fails
  closed), are the other likely causes.
- **Downstream errors:** verify the upstream dependencies above are healthy before
  assuming the fault is local.

## Disaster recovery

- **RPO/RTO: not this service's to promise** — it owns no database. Its **Redis** state has its own recovery posture; see the mechanism below before assuming zero impact.
- **Mechanism:** this service owns no database — there is no managed backup to restore, and none is expected. It does hold state in **Redis**.
- **Before assuming zero impact:** check this service's own `governance.yaml` and `Redis` keys for anything with a long or no TTL (a durable credential, not a session cache) — losing that requires its own recovery path, not a redeploy.
- **Restore:** re-sync the ArgoCD Application (or `kubectl rollout restart` the Deployment). Verify against the `Redis` cluster's own health/backup posture, which this runbook does not track.
- **Verify:** health endpoint green, then re-drive one request end to end.

> RPO/RTO above are documented targets. They become **Bank-grade** (prod-readiness
> C6=3) only once a restore/failover drill has actually been rehearsed and attested
> (`openbank-libs/governance/attestations.yaml: copilot.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `copilot.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
