<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-admin-ui

> Operational runbook for the `admin-ui` service. Data domain **platform**,
> classification **internal**, datastore **PostgreSQL**.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-admin-ui` |
| HTTP port | `?` |
| Data domain | platform |
| Datastore | PostgreSQL (database `—`) |
| Classification | internal |
| Retention | transient |
| Lineage role | consumer |

## Dependencies

- **Upstream (this service consumes):** _none declared_
- **Downstream (depends on this service):** _none declared_

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `admin-ui`.

## Health & probes

- Readiness: `GET :8085/q/health/ready` · Liveness: `GET :8085/q/health/live`
- Metrics: scraped by the fleet PodMonitor (namespace `admin-ui`); dashboards in Grafana.
- Logs: `kubectl logs -n admin-ui deploy/admin-ui -f`, or Loki
  `{namespace="admin-ui"}`.

## Routine operations

- **Restart:** `kubectl rollout restart deploy/admin-ui -n admin-ui` (rolling, zero-downtime at >1 replica).
- **Scale:** `kubectl scale deploy/admin-ui -n admin-ui --replicas=<n>` (or edit the GitOps manifest — GitOps is source of truth, a later ArgoCD sync reconciles manual changes).
- **Config/secret change:** edit the GitOps manifest; ArgoCD syncs. Never `kubectl edit` in place.

## Common failure modes

- **Pod CrashLoopBackOff at boot:** usually a missing/invalid config or secret
  (`ExternalSecret` not synced). Check `kubectl describe pod` events and the
  first 50 log lines.
- **Readiness flapping:** this service owns no database, but check its **PostgreSQL**
  connectivity before ruling out the datastore — an upstream dependency below, or the
  OPA sidecar if `AUTHZ_ENFORCE` is on (with no reachable PDP, `@Authorize` fails
  closed), are the other likely causes.
- **Downstream errors:** verify the upstream dependencies above are healthy before
  assuming the fault is local.

## Disaster recovery

- **RPO/RTO: not this service's to promise** — it owns no database. Its **PostgreSQL** state has its own recovery posture; see the mechanism below before assuming zero impact.
- **Mechanism:** this service owns no database — there is no managed backup to restore, and none is expected. It does hold state in **PostgreSQL**.
- **Before assuming zero impact:** check this service's own `governance.yaml` and `PostgreSQL` keys for anything with a long or no TTL (a durable credential, not a session cache) — losing that requires its own recovery path, not a redeploy.
- **Restore:** re-sync the ArgoCD Application (or `kubectl rollout restart` the Deployment). Verify against the `PostgreSQL` cluster's own health/backup posture, which this runbook does not track.
- **Verify:** health endpoint green, then re-drive one request end to end.

> RPO/RTO above are documented targets. They become **Bank-grade** (prod-readiness
> C6=3) only once a restore/failover drill has actually been rehearsed and attested
> (`openbank-libs/governance/attestations.yaml: admin-ui.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `admin-ui.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
