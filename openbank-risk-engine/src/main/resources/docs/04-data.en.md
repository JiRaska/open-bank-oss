# Data

This page currently documents only the limit-event outbox and its dedup table; the rest of the data model is not yet written up.

## Limit-event outbox and dedup (ADR-0313 D9, ADR-0329)

- **`risk_limit_event_dedup`** (`dedup_key` PK, `event_id`, `created_at`) is the permanent record that a limit event was emitted, exactly once per (run, limit id, limit-set id, limit-set version). It is **never purged**: tens of rows a day at most, no personal data, and keeping it forever makes exactly-once unconditional.
- **`risk_outbox`** is the delivery buffer. `PgRiskOutbox.recordNonOk` claims the key in the dedup table and inserts the outbox row only from a new claim, in the evaluation's single transaction. A replayed EOD run, or two pods ticking together, therefore writes nothing new — even after the original outbox row has been purged.
- **SENT retention:** delivered rows older than `openbank.outbox.retention.sent-days` (default 7) are deleted nightly by libs-runtime's `OutboxSentRetentionJob` (`PgRiskOutbox` implements `SentOutboxRetention` with its own `event_id`-keyed delete). PENDING, FAILED, DISPATCHING and DEAD rows are never touched. Signals: `openbank_outbox_purged_total{service="risk",status="SENT"}`, `openbank_outbox_purge_failed_total`, workflow liveness `outbox-sent-retention`.
- **Rollback of V10:** an older binary dedups on `risk_outbox.dedup_key` only, so rows purged after V10 would be unguarded there. Set `openbank.outbox.retention.enabled=false` before rolling back, and never drop `risk_limit_event_dedup` while a binary that reads it is running.
