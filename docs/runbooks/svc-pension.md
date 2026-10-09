<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-pension-service

> Operational runbook for the `pension` service. Data domain **open-banking**,
> classification **confidential**, datastore **PostgreSQL**.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-pension-service` |
| HTTP port | `8171` |
| Data domain | open-banking |
| Datastore | PostgreSQL (database `openbank_pension`) |
| Classification | confidential |
| Retention | 10 years |
| Lineage role | producer |

## Dependencies

- **Upstream (this service consumes):** _none declared_
- **Downstream (depends on this service):** _none declared_

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `pension`.

## Health & probes

- Readiness: `GET :8090/q/health/ready` · Liveness: `GET :8090/q/health/live`
- Metrics: scraped by the fleet PodMonitor (namespace `pension`); dashboards in Grafana.
- Logs: `kubectl logs -n pension deploy/pension-service -f`, or Loki
  `{namespace="pension"}`.

## Routine operations

- **Restart:** `kubectl rollout restart deploy/pension-service -n pension` (rolling, zero-downtime at >1 replica).
- **Scale:** `kubectl scale deploy/pension-service -n pension --replicas=<n>` (or edit the GitOps manifest — GitOps is source of truth, a later ArgoCD sync reconciles manual changes).
- **Config/secret change:** edit the GitOps manifest; ArgoCD syncs. Never `kubectl edit` in place.

## Common failure modes

- **Pod CrashLoopBackOff at boot:** usually a missing/invalid config or secret
  (`ExternalSecret` not synced) or a Flyway checksum mismatch. Check
  `kubectl describe pod` events and the first 50 log lines.
- **Readiness flapping:** datastore (PostgreSQL) unreachable or saturated — check the
  datastore pod/cluster health and connection-pool metrics.
- **Downstream errors:** verify the upstream dependencies above are healthy before
  assuming the fault is local.

## Pension business and technical signals

The `OpenBank — Pension Business` and `OpenBank — Pension Technical` dashboards are
defined in `openbank-infra/gitops/components/observability/`. Their state panels are
database snapshots from the `pension-state-gauges` workflow. A missing series on a
cold pod is **unknown**, not a zero. If the snapshot-age alert fires, do not use a
displayed old queue value as evidence that the queue is still empty or unchanged.

| Alert | First check | Resolution evidence |
|---|---|---|
| `PensionUnmatchedPaymentsAgeing` | Compare the open queue count and oldest age with the underlying parked-payment records. | An authorised reconciliation decision is recorded for the payment; do not infer the contract from an identifier in a metric. |
| `PensionStateContributionDeadlineBreached` | Identify the claim-filing, claim-payment or return deadline class. | Match the submitted batch/return and its acknowledgement to the statutory period; a successful scheduler invocation is not a receipt. |
| `PensionPaymentInstructionsStuck` | Check the pending instruction and downstream settlement outcome. | Confirm one settled instruction or a documented cancellation before any replay, so a payout is not duplicated. |
| `PensionNotificationsSkippedWhileEnabled` | Check the publisher's enabled gauge, request outcome and available template. | One broker-acknowledged request after correction; `enqueued` does not mean delivered to a device. |
| `PensionStateGaugeRefreshStale` | Check the snapshot scheduler and pension database availability. | A new recorded workflow success and fresh state-gauge scrape. |
| `PensionPaymentEventsDlqNonEmpty` | Inspect the retained payment-event DLQ and consumer errors. | Reconcile and safely replay the affected event, then verify the resulting contract/payment state. |

The DLQ alert compares each partition's latest and oldest retained offsets from
Kafka Exporter. A positive span means a record remains in this delete-only topic;
the span is **not** an exact message count. The technical dashboard separately
shows new arrivals. A quiet alert requires both offset series to be scraped;
missing telemetry is not proof that the DLQ is empty.

## Disaster recovery

- **RPO target:** ≤ 5 min (continuous archiving). **RTO target:** ≤ 30 min (restore + warm-up).
- **Mechanism:** CloudNativePG continuous WAL archiving + base backups to S3 (`barmanObjectStore`). Point-in-time recovery (PITR).
- **Restore:** create a `Cluster` with `bootstrap.recovery` pointing at the backup object store; CNPG replays WAL to the target time. See runbook 0003 (PG major upgrade) for the cluster-recreate mechanics.
- **Verify:** `kubectl cnpg status <db>-rw -n <ns>` shows the recovered cluster Healthy and the `*-app` secret regenerated.

> RPO/RTO above are documented targets. They become **Bank-grade** (prod-readiness
> C6=3) only once a restore/failover drill has actually been rehearsed and attested
> (`openbank-libs/governance/attestations.yaml: pension.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `pension.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
