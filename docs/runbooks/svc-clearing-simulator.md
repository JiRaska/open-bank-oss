<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-clearing-simulator

> Operational runbook for the `clearing-simulator` service. Data domain **payments**,
> classification **internal**, datastore **none**.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-clearing-simulator` |
| HTTP port | `8139` |
| Data domain | payments |
| Datastore | none (database `—`) |
| Classification | internal |
| Retention | none (stateless — persists nothing) |
| Lineage role | internal |

## Dependencies

- **Upstream (this service consumes):** `sepa-payment`
- **Downstream (depends on this service):** _none declared_

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `clearing-simulator`.

## Health & probes

- Readiness: `GET :8087/q/health/ready` · Liveness: `GET :8087/q/health/live`
- Metrics: scraped by the fleet PodMonitor (namespace `payments`); dashboards in Grafana.
- Logs: `kubectl logs -n payments deploy/clearing-simulator -f`, or Loki
  `{namespace="payments"}`.

## Routine operations

- **Restart:** `kubectl rollout restart deploy/clearing-simulator -n payments` (rolling, zero-downtime at >1 replica).
- **Scale:** `kubectl scale deploy/clearing-simulator -n payments --replicas=<n>` (or edit the GitOps manifest — GitOps is source of truth, a later ArgoCD sync reconciles manual changes).
- **Config/secret change:** edit the GitOps manifest; ArgoCD syncs. Never `kubectl edit` in place.

## Common failure modes

- **Pod CrashLoopBackOff at boot:** usually a missing/invalid config or secret
  (`ExternalSecret` not synced). Check `kubectl describe pod` events and the
  first 50 log lines.
- **Readiness flapping:** this service holds no datastore, so look outward — an
  upstream dependency below, or the OPA sidecar if `AUTHZ_ENFORCE` is on (with no
  reachable PDP, `@Authorize` fails closed).
- **Downstream errors:** verify the upstream dependencies above are healthy before
  assuming the fault is local.

## Disaster recovery

- **RPO: n/a** — no persistent state. **RTO target:** ≤ 10 min (image pull + rollout).
- **Mechanism:** none needed — this service declares no primary datastore, so it holds no state to lose. Recovery is a redeploy from the GitOps manifests, which are the source of truth.
- **Restore:** re-sync the ArgoCD Application (or `kubectl rollout restart` the Deployment). Any state this service reads lives in its upstream services above — recover those first, using their own runbooks.
- **Verify:** health endpoint green, then re-drive one request end to end against an upstream that is already known-good.

> RPO/RTO above are documented targets. They become **Bank-grade** (prod-readiness
> C6=3) only once a restore/failover drill has actually been rehearsed and attested
> (`openbank-libs/governance/attestations.yaml: clearing-simulator.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `clearing-simulator.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
