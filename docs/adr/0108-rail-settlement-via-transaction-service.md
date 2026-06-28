# 108. Rail settlement runs through transaction-service (ADR-0039), not settlement-service

Date: 2026-06-23
Author: Claude (paired with Jiří Raška)
Status: Accepted
Delivery-Status: Shipped

## Context

ADR-0104 made the payment rails production-faithful up to the network boundary: a SEPA/domestic/SWIFT
payment builds a real ISO 20022 `pacs.008`, submits it to the scheme gateway (the in-house
clearing-simulator), and — once the scheme accepts (`pacs.002` `ACSC`) — moves to **PROCESSING**.
This is live in the sandbox today (a real payment reaches PROCESSING end to end).

What is missing is the last leg: **PROCESSING → COMPLETED**, i.e. the *internal settlement* — debiting
the payer and booking the movement to the ledger so the customer's balance reflects the payment. The
rail does not do this itself; some service must own it.

Two candidates exist, and an investigation (2026-06-23) established their real state:

- **`openbank-settlement-service`** — a Temporal `SettlementWorkflow` (`debitPayer → creditPayee →
  bookToLedger`). On inspection it is **orphaned scaffold**: an in-memory repository
  (`store[id]`, no `create`), **no Kafka and no REST trigger**, compensation activities are
  no-op stubs that only log `"stub: wire reversal"`, and **zero external callers** (not even
  fx-service, which settles internally). It has never been wired into a payment flow.
- **`openbank-transaction-service`** — the **ADR-0039** settlement authority, and it is real and
  in production. `PaymentSagaOrchestrator` runs a synchronous saga: place an overdraft-aware cover
  hold (balance-service) → post the double-entry journal (ledger-service `POST /api/v1/journals`)
  → COMPLETED, with idempotent posting and compensation (reverse journal + release hold).
  `PaymentJournalFactory` already branches on direction — an **outbound** payment DEBITs the payer's
  deposit-control sub-account and CREDITs cash-clearing (ADR-0039 Phase D-2: ledger is the golden
  source, balance is a projection). Sub-ledger tie-out and the booked projection are live.

So the bank already has one correct, production settlement engine (transaction-service) and one
orphaned duplicate (settlement-service). Building the ADR-0104 settlement leg into settlement-service
would **duplicate ADR-0039** and create a second money-movement seam.

Two gaps in transaction-service, however, are real:

1. **No inbound path from the rails.** Transactions are created only via `POST /api/v1/transactions`
   (operator-only); there is no `@Incoming` consumer, and the rails do not call it. The `rail` field on
   `Transaction` is stamped at origination, anticipating a rail-driven creation that was never wired.
2. **`TransactionCompletedEvent` carries no settlement proof** (only a reference number), so a rail
   cannot reliably consume it to learn "the money is booked".
3. **No funds-in-transit GL account.** The outbound journal is `DEBIT deposit-control(payer) / CREDIT
   cash-clearing`; there is no asset-side leg representing "submitted to the scheme, not yet settled".
   That is acceptable for instant internal booking but leaves nothing to reconcile a settlement window
   against.

## Decision

**Rail settlement (the PROCESSING → COMPLETED leg of ADR-0104) is owned by `transaction-service`'s
existing ADR-0039 `PaymentSagaOrchestrator`. `openbank-settlement-service` is retired — not extended.**

Concretely:

1. **Rail → transaction (inbound).** When the scheme accepts (`ACSC` → PROCESSING), the rail emits a
   durable outbox event `payment.scheme-accepted` carrying the rail `paymentId`, debtor/creditor IBANs
   and resolved account ids, amount, currency, value date, and the `rail` tag. `transaction-service`
   gains an `@Incoming` consumer that maps this to an `InitiateTransactionCommand` and runs the existing
   saga. (The rail already mints the ISO message; transaction-service does the book.)
2. **Transaction → rail (completion).** `transaction-service` emits a richer **`TransactionSettledEvent`**
   carrying `transactionId`, the originating rail `paymentId`, `journalId`, and `bookingDate`. The rail
   consumes it and transitions the payment to **COMPLETED** — settlement proof, not a bare reference.
3. **Funds-in-transit GL account.** Add a `clearing-reserve` (settlement-in-progress) asset account so the
   outbound leg can represent value submitted-but-not-yet-settled, enabling reconciliation against the
   scheme's `pacs.002`/`camt.054` (ADR-0104). The journal becomes payer → clearing-reserve on submit,
   clearing-reserve → cash-clearing on settlement confirmation.
4. **Reject / return.** A `pacs.002` `RJCT` (rail emits `payment.rejected`) or a post-settlement `pacs.004`
   return (`payment.returned`) drives saga compensation / a reversal transaction in transaction-service.
5. **Retire settlement-service.** Remove it from the deploy set and release manifest once nothing
   references it (it currently has zero callers). Its Temporal-saga ambition is unnecessary: the
   PROCESSING → COMPLETED book completes synchronously, and durable long-running compensation, if ever
   needed for multi-day returns, is added to transaction-service rather than maintained as a parallel
   engine.

## Alternatives considered

- **A — Build the settlement into `settlement-service` (event consumer + persistence + saga).** This was
  the initial instinct, but it duplicates the ADR-0039 journal/projection engine, re-implements
  direction-aware posting and compensation that already exist and are tested, and leaves two money-movement
  seams to keep consistent. Rejected — it is the "second settlement engine" anti-pattern.
- **B — Rail calls a settlement service synchronously (REST/Temporal) and blocks.** Couples rail
  availability to the settlement engine and conflates the scheme-ack wait with internal booking. Rejected
  in favour of the decoupled, durable outbox/event path (ADR-0003/0004).
- **C — Rail books the ledger itself.** Spreads settlement/double-entry logic across every rail, the exact
  duplication ADR-0039 centralised. Rejected.

## Consequences

**Positive**
- One settlement authority (transaction-service / ledger golden source), no duplicate engine.
- Reuses production-tested direction-aware journaling, cover holds, idempotency, and compensation.
- The rail stays thin: build the message, exchange with the scheme, announce the result; booking is owned
  by the ledger path.
- A real funds-in-transit account makes the settlement window reconcilable against `pacs.002`/`camt.054`.

**Negative**
- New inbound consumer + event-mapping in transaction-service, a new `TransactionSettledEvent` (AsyncAPI
  bump), a new GL account + Flyway migration, and rail-side consume-to-COMPLETED — a multi-service,
  money-path increment requiring 2 approvals + threat-model review (ADR-0030).
- Retiring settlement-service touches the deploy/release manifests.

**Neutral**
- ADR-0104 D3/D4 (the message + scheme legs) are unchanged; this only defines the settlement leg behind them.
- The `rail` field on `Transaction` finally has a producer.

## Implementation

Shipped in PRs: #1869 (tx-consumer + `TransactionSettledEvent`), #1870 (rail consume-to-COMPLETED),
#1862 (funds-in-transit GL + Flyway migration), #1872 (clearing-reserve journal entries),
#1867 (settlement-service retire). All merged to `main`; sandbox-deployed.

## References

- ADR-0039 — Ledger as golden source, balance as projection (the settlement engine this reuses)
- ADR-0104 — Production-faithful payment rails (the PROCESSING state this completes)
- ADR-0003 — Transactional outbox; ADR-0004 — Saga for multi-service workflows
- ADR-0101 — Temporal durable execution (settlement-service's basis; superseded for this purpose)
- Tracking: #1718 (ADR-0104 rails). Investigation 2026-06-23: settlement-service orphaned; transaction-service `PaymentSagaOrchestrator`/`PaymentJournalFactory` is the live authority.
