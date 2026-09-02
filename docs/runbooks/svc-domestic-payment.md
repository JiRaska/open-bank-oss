<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-domestic-payment-service

> Operational runbook for the `domestic-payment` service. Data domain **payments**,
> classification **confidential**, datastore **PostgreSQL**.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-domestic-payment` |
| HTTP port | `8116` |
| Data domain | payments |
| Datastore | PostgreSQL (database `openbank_domestic_payments`) |
| Classification | confidential |
| Retention | 7 years |
| Lineage role | both |

## Dependencies

- **Upstream (this service consumes):** _none declared_
- **Downstream (depends on this service):** `transaction-service`, `aml-service`

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `domestic-payment`.

## Health & probes

- Readiness: `GET :8085/q/health/ready` · Liveness: `GET :8085/q/health/live`
- Metrics: scraped by the fleet PodMonitor (namespace `payments`); dashboards in Grafana.
- Logs: `kubectl logs -n payments -l app.kubernetes.io/name=domestic-payment-service -f`, or Loki
  `{namespace="payments"}`.

## Routine operations

- **Restart:** `kubectl argo rollouts restart domestic-payment-service -n payments` (Argo Rollout — plain `kubectl rollout restart` does NOT work on the CRD). Without the plugin: `kubectl patch rollout domestic-payment-service -n payments --type merge -p '{"spec":{"restartAt":"<RFC3339-now>"}}'`.
- **Scale:** `kubectl scale rollout/domestic-payment-service -n payments --replicas=<n>` (or edit the GitOps manifest — GitOps is source of truth, a later ArgoCD sync reconciles manual changes).
- **Config/secret change:** edit the GitOps manifest; ArgoCD syncs. Never `kubectl edit` in place.

## Durable idempotency cutover

The request-fingerprint migration is intentionally strict: a historical payment whose nullable
`request_fingerprint` was written by the old image cannot prove that a replay carries the same
body/actor. It therefore always returns `409 IDEMPOTENCY_KEY_REUSED`; there is no compatibility flag
that can make the old row authoritative again. The former Redis implementation supported a 24-hour
(`86400` second) replay window, so this preserves confidentiality/integrity at the explicit cost of
availability for old keys still inside that window.

Use a blue/green switch, not a mixed-version rolling interval:

1. Apply the additive V11/V12 schema while the old image still runs; do not activate delegated spend.
2. Quiesce only domestic-payment creates at the ingress. Drain every in-flight create for at least
   the configured maximum request timeout (currently 15 seconds) and verify zero old-image requests.
3. Switch all traffic to the fully healthy new image, verify that no old writer replica remains, then
   reopen creates. Do not run old and new writers concurrently.
4. Probe a newly created exact replay (same id and `X-Idempotency-Replayed: true`) and a changed-body
   replay (409). Confirm one payment row and one created outbox row.
5. For a legacy/ambiguous 409, use payment status lookup and operator reconciliation. Never tell the
   caller to retry with a new key: the old attempt may have committed, so a new key can duplicate it.

Do not roll back by reintroducing Redis as response authority or accepting an unprovable NULL
fingerprint. If the switch must be reversed, quiesce creates again and treat every ambiguous request
as a reconciliation case before any resubmission.

## Delegated reservation state bootstrap

`openbank.delegation.spend-reservation-state` is a one-partition compacted bootstrap stream, not an
arrival-ordered transition log. Delegation publishes one bounded key per revision:
`<reservationId>:v1` for RESERVED and `<reservationId>:v2` for either terminal state. Compaction
therefore retains at most two records per reservation. A v1 send whose acknowledgement was
ambiguous may complete after v2; this is expected and must not reopen the binding.

On a rebuild, validate that every payload revision is exactly 1 or 2, group all retained values by
payload `reservationId`, and apply only the greatest `reservationVersion`. Never apply the last
record observed as the current state. A terminal-first then stale-v1 replay must finish terminal.
Keep the topic at one partition; increasing it changes the cross-revision ordering protocol even
though correctness still comes from maximum-revision application rather than arrival order. Before
first activation, verify no legacy plain-`reservationId` key exists: this key format is frozen before
the default-off writer is enabled, and a mixed format would retain one additional stale slot.

Activate in this order: deploy the consumer from `earliest` while delegated create and the finalizer
remain off; wait for consumer lag to reach zero and verify the projection selected the maximum
revision for every reservation; then enable delegated create; enable finalization last. Do not use
"last record wins" or a zero lag reading alone as bootstrap proof — explicitly test terminal v2
followed by delayed v1 and require the projection to remain terminal.

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
> (`openbank-libs/governance/attestations.yaml: domestic-payment.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `domestic-payment.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
