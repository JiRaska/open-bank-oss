# Data

This page currently documents only the limit-event outbox and its dedup table; the rest of the data model is not yet written up.

## Limit-event outbox and dedup (ADR-0313 D9, ADR-0329)

- **`risk_limit_event_dedup`** (`dedup_key` PK, `event_id`, `created_at`) is the permanent record that a limit event was emitted, exactly once per (run, limit id, limit-set id, limit-set version). It is **never purged**: tens of rows a day at most, no personal data, and keeping it forever makes exactly-once unconditional.
- **`risk_outbox`** is the delivery buffer. `PgRiskOutbox.recordNonOk` claims the key in the dedup table and inserts the outbox row only from a new claim, in the evaluation's single transaction. A replayed EOD run, or two pods ticking together, therefore writes nothing new — even after the original outbox row has been purged.
- **SENT retention:** delivered rows older than `openbank.outbox.retention.sent-days` (default 7) are deleted nightly by libs-runtime's `OutboxSentRetentionJob`. Before each bounded delete, `PgRiskOutbox` copies eligible keys into the permanent dedup table in the same transaction and deletes only rows with a durable claim. This also covers an older pod that wrote after V10's one-time backfill. PENDING, FAILED, DISPATCHING and DEAD rows are never touched. Signals: `openbank_outbox_purged_total{service="risk",status="SENT"}`, `openbank_outbox_purge_failed_total`, workflow liveness `outbox-sent-retention`.
- **Rollback of V10:** an older binary dedups on `risk_outbox.dedup_key` only and cannot honor claims for rows already purged. Disable retention before rolling back; this prevents further loss of guards but cannot restore purged rows. Do not replay runs whose old outbox rows were purged under an older binary. Keep `risk_limit_event_dedup` while any binary reads it.

## Treasury deals, stored cash flows and curve instruments (ADR-0344 D2, D4, D8)

- **`treasury_deal`** carries the deal `kind` (`ACTUAL`, `PLANNED`, `HYPOTHETICAL`; a deal seen before V11 is `ACTUAL`), `book_id`, the published `legs`, the `schedule_hash`, and `legs_error`. A deal whose legs cannot be built is stored and reported as a data gap, never dropped.
- **`treasury_deal_cash_flows`** keeps the latest stored cash-flow version per deal. The engine consumes these flows for the bank's own deals and derives flows only as a fallback.
- **`curve_set_quote.instrument`** is `DEPOSIT`, `FRA` or `SWAP`; existing rows are deposits. Uniqueness now includes the instrument.
- **`snapshot_run`** records the curve and valuation model versions of a run; older runs have none.
- **Rollback of V11:** the migration header lists the statements. Dropping the new columns discards legs and stored flows; they are re-created from replayed deal events.
