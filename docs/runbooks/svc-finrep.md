<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-finrep-service

> Operational runbook for the `finrep` service. Data domain **compliance**,
> classification **confidential**, datastore **none**.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-finrep-service` |
| HTTP port | `8140` |
| Data domain | compliance |
| Datastore | none (database `—`) |
| Classification | confidential |
| Retention | 10 years |
| Lineage role | consumer |

## Dependencies

- **Upstream (this service consumes):** _none declared_
- **Downstream (depends on this service):** `ledger-service`

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `finrep`.

## Health & probes

- Readiness: `GET :8085/q/health/ready` · Liveness: `GET :8085/q/health/live`
- Metrics: scraped by the fleet PodMonitor (namespace `finrep`); dashboards in Grafana.
- Logs: `kubectl logs -n finrep deploy/finrep-service -f`, or Loki
  `{namespace="finrep"}`.

## Routine operations

- **Restart:** `kubectl rollout restart deploy/finrep-service -n finrep` (rolling, zero-downtime at >1 replica).
- **Scale:** `kubectl scale deploy/finrep-service -n finrep --replicas=<n>` (or edit the GitOps manifest — GitOps is source of truth; a later ArgoCD sync reconciles manual changes).
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
> (`openbank-libs/governance/attestations.yaml: finrep.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `finrep.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
