# Data

This page currently documents only the outbox lifecycle; the rest of the data model is not yet written up.

## Outbox retention (SENT rows)

`treasury_outbox` is a delivery buffer, not a record. Rows that reached the broker (`status = 'SENT'`) are deleted once their `sent_at` is older than `openbank.outbox.retention.sent-days` (default **7**), by libs-runtime's shared `OutboxSentRetentionJob` (ADR-0329, ADR-0327 D8). The repository opts in by delegating `SentOutboxRetention` to `PanacheOutboxRetention`.

- Runs nightly (`openbank.outbox.retention.cron`, default `0 17 3 * * ?`) on every replica; each delete is bounded (`batch-size` 5 000, at most `max-batches` 200 per run), so a long-unpurged table drains over several nights.
- PENDING, FAILED, DISPATCHING and DEAD rows are never touched — DEAD rows are the producer-side DLQ.
- Replaying an event older than the window comes from the Kafka topic or audit-service, not from this table.
- Signals: `openbank_outbox_purged_total{service,status="SENT"}`, `openbank_outbox_purge_failed_total`, and workflow liveness `outbox-sent-retention`.
- Opt-out: `openbank.outbox.retention.enabled=false` (logs a WARN at boot). Don't lower `sent-days` below what any reader of SENT rows needs.

## Postings name an accounting class, not a GL code (ADR-0341 D3)

A posting leg now carries `accountingClass` + `LedgerRole` (principal, accrued interest, interest income/expense) + currency and no GL code; the ledger resolves the account from its own map. Each `ProductType` declares its accounting class (e.g. `INTERBANK_PLACEMENT`, `CNB_LOMBARD_BORROWING`), and nostro reconciliation uses `TreasuryChart.nostroCode`. A legacy-posting oracle test pins that the resulting journals are unchanged.

## Reference data and the cash-flow ledger (ADR-0344 phase 0)

- **`reference_records`** (V16, V17) holds effective-dated, versioned reference data: calendars, indices, counterparties, settlement instructions, books, product types and the rest of the ADR-0344 kind catalogue. A version is PENDING until a different human approves it. Approving a `counterparty` that raises a limit needs the senior approver. `row_version` is an optimistic lock. The limit check reads the ACTIVE `counterparty` version; the `counterparties` table is only a projection for the deals' foreign key. `reference_commands` stores each command's Idempotency-Key.
- **`deal_versions`, `deal_cash_flows`, `cash_flow_overrides`, `cash_flow_conflicts`** (V18, V19) store each terms version and its generated cash-flow ledger. Submission writes the ledger and `treasury.cashflow.generated.v1` with the deal in one transaction. An override is four-eyes and keeps the original date and amount. It survives regeneration only while its leg-spec hash is unchanged; otherwise it becomes a listed conflict. `deal_cash_flows.row_version` is an optimistic lock.
- In phase 0, only the five existing products book. Draft refuses every leg-described product (400) until its phase delivers posting rules.
- **Rollback:** V16 to V19 are additive. Existing deals become kind `ACTUAL`, version 1, schedule version 0. Drop the new tables only while they are unused; each migration header carries the statements.
