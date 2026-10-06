# Data

This page currently documents only the outbox lifecycle; the rest of the data model is not yet written up.

## Outbox retention (SENT rows)

`incentive_outbox` is a delivery buffer, not the business transition record. `incentive_audit_event` records each transition's type, **actor**, and time in the same transaction as the outbox row; `incentive_offer` / `promo_reservation` retain current business facts. The reservation's offer, attribution, party, product, and status remain queryable after SENT purge. The generated outbox `eventId` is not reconstructed from those tables; `correlationId` equals the retained aggregate id. The V2 migration's remark that rows "remain valid audit evidence" concerns its rollback, not their retention (#11902).

Delivered rows (`status = 'SENT'`) are therefore deleted once their **`published_at`** is older than `openbank.outbox.retention.sent-days` (default **7**), by libs-runtime's shared `OutboxSentRetentionJob` (ADR-0329). This table predates the fleet's outbox shape: it records delivery in `published_at` (not `sent_at`) and orders on `occurred_at`, which `OutboxTableShape(sentAtColumn = "published_at", orderColumn = "occurred_at")` expresses without a migration.

- Runs nightly (`openbank.outbox.retention.cron`, default `0 17 3 * * ?`) in bounded batches; PENDING, FAILED, DISPATCHING and DEAD rows are never touched.
- After a purge, `incentive_outbox` holds fewer rows than `incentive_audit_event` — expected; the 1:1 relation holds only at write time.
- Signals: `openbank_outbox_purged_total{service="incentive",status="SENT"}`, `openbank_outbox_purge_failed_total`, workflow liveness `outbox-sent-retention`.
