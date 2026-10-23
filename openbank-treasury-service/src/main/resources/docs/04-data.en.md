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


## Fixings and market data (ADR-0344 phase 1a)

- **`observation_batches`, `index_observations`, `fixing_cases`** (V20) hold index observations. A batch is PENDING until a different human approves it, and only ACTIVE observations are used. The quality gate checks MISSING, STALE and SUSPICIOUS before any fixing; a failure opens a case and fixes nothing.
- **`deal_cash_flows.fixing_source`, `fixing_sha256`, `fixed_at`** (V20) record where a fixed rate came from. Only the fixing repository writes them, with a guarded UPDATE.
- The hourly fixing pass (`openbank.treasury.fixing.enabled`, default on) fixes due floating flows and publishes `treasury.cashflow.fixed.v1`.
- IRS and FRA are now drafted, submitted and fixed. They settle only once the posting-rule matrix lands (phase 2).

## Payments and the institution-transfer rail (ADR-0344 phase 1b)

- **`treasury_payments`, `treasury_payment_flows`** (V21) hold own-account payments and the FIXED flows each one pays. A flow belongs to at most one payment; the link is written only by a guarded UPDATE, never by the whole-row flow save. V22 adds the transfer rail and the intermediary BIC. V23 makes a payment's idempotency key unique among non-cancelled payments only, so a cancelled payment can be proposed again.
- The daily proposal run (`openbank.treasury.payments.enabled`, default on) proposes payments for FIXED flows paid within `cut-off-window-days` (2), netted per netting agreement and addressed from the SSI effective on the value date. Only `ROLE_TREASURY_PAYMENT_RELEASER` releases one, and never a term author of a netted deal.
- Release writes `treasury.payment.released.v1` to `openbank.treasury.payments` in the same transaction. swift-service turns it into a pacs.009 and answers on `openbank.swift.institution-transfers`; treasury moves the payment to SENT, SETTLED or FAILED. A status it cannot apply goes to `openbank.dlq.treasury.institution-transfer-status-in`.
- Cancelling or reversing a deal refuses while one of its payments is released (409 PAYMENT_RELEASED) and otherwise withdraws its PROPOSED payments in the same transaction.
- **Rollback:** V21 to V23 are additive. Drop the tables only while no payment row exists; each migration header carries the statements.

## The posting-rule matrix (ADR-0344 phase 2a)

- **`deal_journals`** (V24) now admits the matrix events (`TRADE`, `SETTLEMENT`, `ACCRUAL`, `PAYMENT`, `AMORTISATION`, `MATURITY` and the rest) next to the legacy `SETTLED`, `ACCRUED`, `MATURED`, `REVERSED`. A matrix journal's key is `treasury:<dealId>:<event>:<flowId|date>`, so each paid flow and each accrual day posts once.
- `PostingMatrix` is versioned data: one row per product, event, IFRS 9 category and flow selector, served at `GET /api/v1/treasury/posting-rules`. A leg names an accounting class and role; the ledger maps them to its V35 instrument accounts.
- IRS and FRA now settle, accrue, pay and mature through the matrix. The other leg-described products keep their rows dormant until their phase lands.
- A SENT or SETTLED outgoing payment posts its flows' PAYMENT journals at once, under the same keys the daily run uses. If the ledger is down, the next replay or the daily run posts them, and `openbank.treasury.payments.outflow.failed` counts the miss.
- **Rollback:** V24 only widens a CHECK. Restore the old constraint once no matrix journal row exists; the statement is in the migration header.

## FX forwards and swaps, bills, loans, revaluation and analysis (ADR-0344 phase 3)

- FX_FORWARD, FX_SWAP, BILL_OF_EXCHANGE and LOAN are now drafted and settle, accrue, pay and mature through the matrix. Each has independent-oracle parity fixtures and its original golden journals.
- **`deal_revaluations`** (V26) holds one row per deal and valuation date: the risk engine's clean fair value on the TIED_OUT snapshot of that date, the carrying amount, the cumulative adjustment and the change posted as the matrix REVALUATION event (FVOCI to the revaluation reserve, FVTPL and derivatives to unrealised P&L). The ledger key is `treasury:<dealId>:revaluation:<date>`. Dates per deal only increase; an earlier date is refused, never back-filled. A deal the engine did not value is a reported gap, never a zero.
- The daily run (`openbank.treasury.revaluation.enabled`, default on, cron `openbank.treasury.revaluation.cron`) and `POST /api/v1/treasury/revaluations/run` re-post owed rows first; `openbank_treasury_revaluation_unposted` counts what the ledger has not accepted yet and drives `TreasuryRevaluationUnposted`.
- **`internal_rate_sheets`** (V27) hold four-eyes approved internal rates (`/market-data/internal-rate-sheets`). One ACTIVE sheet per name; approving supersedes the previous one. Nothing prices from them yet.
- `POST /api/v1/treasury/analysis/cash-flows` aggregates the stored cash-flow ledger by currency (gross, net or cumulative). It is read-only and converts only at an approved `FX_<CCY>_<REF>` fixing.
- **Rollback:** V26 and V27 are additive. Drop the tables while no row exists; each migration header carries the statement. Posted revaluation journals stay in the ledger.
