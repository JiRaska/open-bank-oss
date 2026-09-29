<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-billing-service

> Operational runbook for the `billing` service. Data domain **payments**,
> classification **internal**, datastore **PostgreSQL**.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-billing-service` |
| HTTP port | `8132` |
| Data domain | payments |
| Datastore | PostgreSQL (database `openbank_billing`) |
| Classification | internal |
| Retention | 7 years |
| Lineage role | both |

## Dependencies

- **Upstream (this service consumes):** _none declared_
- **Downstream (depends on this service):** _none declared_

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `billing`.

## Health & probes

- Readiness: `GET :8085/q/health/ready` · Liveness: `GET :8085/q/health/live`
- Metrics: scraped by the fleet PodMonitor (namespace `billing`); dashboards in Grafana.
- Logs: `kubectl logs -n billing deploy/billing-service -f`, or Loki
  `{namespace="billing"}`.

## Routine operations

- **Restart:** `kubectl rollout restart deploy/billing-service -n billing` (rolling, zero-downtime at >1 replica).
- **Scale:** `kubectl scale deploy/billing-service -n billing --replicas=<n>` (or edit the GitOps manifest — GitOps is source of truth, a later ArgoCD sync reconciles manual changes).
- **Config/secret change:** edit the GitOps manifest; ArgoCD syncs. Never `kubectl edit` in place.

## Common failure modes

- **Pod CrashLoopBackOff at boot:** usually a missing/invalid config or secret
  (`ExternalSecret` not synced) or a Flyway checksum mismatch. Check
  `kubectl describe pod` events and the first 50 log lines.
- **Readiness flapping:** datastore (PostgreSQL) unreachable or saturated — check the
  datastore pod/cluster health and connection-pool metrics.
- **Downstream errors:** verify the upstream dependencies above are healthy before
  assuming the fault is local.

### Dead-lettered outbox rows

Alerts: `BillingOutboxDeadLetteredNew` (critical, the DEAD count just rose) and
`BillingOutboxDeadLettered` (warning, DEAD rows are still unresolved). A `billing_outbox` row
in `DEAD` is a fee assessed but never posted to the ledger (or, for
`billing.annual-fee-summary.ready`, a summary never sent to Kafka). Nothing retries it.

1. **Read before touching anything.** Read-only, on the billing database:
   `SELECT id, event_type, aggregate_id, attempt_count, last_error, created_at, updated_at
   FROM billing_outbox WHERE status = 'DEAD' ORDER BY created_at;`
2. **Classify by `last_error`.** A transport error (`circuit breaker is open`, connection refused,
   `localhost:9092`) means the target was unreachable or not configured when the row died.
   Confirm that cause is fixed before any replay. Anything else (4xx from ledger,
   deserialization) is a per-row defect: fix the cause first, or the replay dead-letters again.
3. **Check the ledger before replaying a charge or reversal.** The journal POST is idempotent
   on the payload's `idempotencyKey`, so a replay of a row the ledger already booked is a
   no-op. Still confirm the fee's state in `billing_assessment` so the disposition is recorded.
4. **Replay** is a data change on a money-path table: four-eyes, attributable, via the
   approved change process. Set the chosen rows back to `PENDING` with `attempt_count = 0`
   and `last_error` cleared; the dispatcher picks them up on its next tick. Never bulk-requeue
   without step 2.
5. **Write-off** (the fee should not be charged) is a business decision: record it, then move
   the row out of `DEAD` the same attributable way so the warning clears.

The warning clears only when no `DEAD` rows remain; the critical alert clears once the count
stops rising.

## Disaster recovery

- **RPO target:** ≤ 5 min (continuous archiving). **RTO target:** ≤ 30 min (restore + warm-up).
- **Mechanism:** CloudNativePG continuous WAL archiving + base backups to S3 (`barmanObjectStore`). Point-in-time recovery (PITR).
- **Restore:** create a `Cluster` with `bootstrap.recovery` pointing at the backup object store; CNPG replays WAL to the target time. See runbook 0003 (PG major upgrade) for the cluster-recreate mechanics.
- **Verify:** `kubectl cnpg status <db>-rw -n <ns>` shows the recovered cluster Healthy and the `*-app` secret regenerated.

> RPO/RTO above are documented targets. They become **Bank-grade** (prod-readiness
> C6=3) only once a restore/failover drill has actually been rehearsed and attested
> (`openbank-libs/governance/attestations.yaml: billing.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `billing.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
