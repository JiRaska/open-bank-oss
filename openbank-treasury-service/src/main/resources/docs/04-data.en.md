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
- **`deals.row_version`** (V21) versions the deal row. Every lifecycle command (edit, submit, approve, confirm, settle) saves through one path that locks the row and compares the version; a concurrent change answers 409 `DEAL_CHANGED` instead of being overwritten. An override is accepted, approved and applied only while the flow is PROJECTED or FIXED, has no payment and no payment or amortisation journal, and its deal is not terminal; the last check repeats under the flow-row lock, and a failure answers 409 `FLOW_NOT_OVERRIDABLE`.
- In phase 0, only the five existing products book. Draft refuses every leg-described product (400) until its phase delivers posting rules.
- **Rollback:** V16 to V21 are additive (V21 adds one column with default 0). Existing deals become kind `ACTUAL`, version 1, schedule version 0. Drop the new tables only while they are unused; each migration header carries the statements.


## Fixings and market data (ADR-0344 phase 1a)

- **`observation_batches`, `index_observations`, `fixing_cases`** (V20) hold index observations. A batch is PENDING until a different human approves it, and only ACTIVE observations are used. The quality gate checks MISSING, STALE and SUSPICIOUS before any fixing; a failure opens a case and fixes nothing.
- **`deal_cash_flows.fixing_source`, `fixing_sha256`, `fixed_at`** (V20) record where a fixed rate came from. Only the fixing repository writes them, with a guarded UPDATE.
- The hourly fixing pass (`openbank.treasury.fixing.enabled`, default on) fixes due floating flows and publishes `treasury.cashflow.fixed.v1`.
- IRS and FRA are drafted, submitted and fixed; since phase 2 they also settle (below).

## Posting matrix, credit exposure and day completion (ADR-0344 phase 2)

- Every product is now drafted. The leg-described products settle, accrue, pay and mature through `PostingMatrix` (served at `GET /posting-rules`); journal keys are `treasury:<dealId>:<event>:<flowId|date>`, and `deal_journals` accepts the matrix events (V28). A flow with no matching rule is refused.
- Every product with counterparty credit risk consumes its line through `CreditExposure`: derivatives count |notional| × the `derivative-conversion-factor` for their product and residual maturity, a reverse repo the cash lent less the `collateral-haircut` collateral, a repo the collateral delivered less the cash received. Collateral is valued at the bank's own price: the latest ACTIVE (four-eyes) market-data observation keyed by the collateral ISIN, at most `collateralPriceMaxAgeDays` (a `limit-settings` field, default 0) before the trade date; a price stated on the deal is only checked against it, within `collateralPriceTolerancePercent` of the same record (none effective: only an equal price). A missing record or price is a 422 (`CONVERSION_FACTOR_MISSING` / `COLLATERAL_HAIRCUT_MISSING` / `COLLATERAL_PRICE_MISSING`), a deal price outside the tolerance `COLLATERAL_PRICE_OUT_OF_TOLERANCE`, never zero. The figure is stored as `limit_deal_amount` and the locked line query sums it. V27 seeds synthetic factors and a default haircut, all `verified = false`.
- **`treasury_day_completion`** (V29) holds the last outcome of each step per bank day. The accrual and fixing runs record theirs; `GET /day-completion/{date}` lists every step, an unrecorded one PENDING. A ledger `409 ACCOUNTING_DAY_CLOSED` is recorded as REFUSED.
- **`position_closes`, `deal_filters`** (V26): close-of-day position snapshots (back office, idempotency key required) and each user's saved blotter filters.
