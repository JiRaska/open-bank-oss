---
date: 2026-05-30
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ledger, accounts, transactions]
summary: "The ledger becomes the golden source of the booked balance and balance-service becomes its projection fed by per-account booked-delta events; balance-service keeps authority only over holds and reservations."
---

# Ledger as the golden source; balance-service as a ledger projection

## Context

Two money-path services currently own "the balance", and they do not agree with each other by
construction (multi-currency reality-check finding **N3**):

- **openbank-ledger-service** holds the accounting truth. `LedgerService.postJournal` records
  double-entry `JournalEntry` lines against **GL control accounts only** — per-currency *deposit
  control* (`a0000000-…-2101/2102/2103`, CZK `…-0002`), *customer cash clearing*, and per-currency
  *FX position* accounts (`PaymentJournalFactory`). `JournalEntry.validateBalance()` enforces
  debits == credits **within each currency**. A journal line carries `glAccountId`, `transactionId`,
  `fxRate`, `baseAmount` — but **no customer-account dimension**. The ledger has a full
  outbox → Kafka pipeline (`LedgerOutboxDispatcher`, `KafkaLedgerOutboxEventPublisher`) emitting
  `JournalPostedEvent` / `JournalReversedEvent`, neither of which carries a per-account balance delta.

- **openbank-balance-service** holds the customer-facing per-`(account, currency)` figures
  (`bookedAmount`, `availableAmount`, `reservedAmount`, `pendingAmount`, `arrangedOverdraftLimit`).
  It is mutated **directly** by its own commands (`credit`/`debit`/`placeHold`/`releaseHold`) and
  has **no `@Incoming` consumer** — it consumes no ledger event.

- The **payment saga** (`PaymentSagaOrchestrator.executeSteps`) is the dual-write seam:
  `balanceCoverPort.placeHold` → `ledgerCallGuard.postJournal` → `balanceCoverPort.debit` →
  `releaseHold`. The per-account delta is known to the *balance* command (`BalanceCoverPort.debit`
  carries `accountId, amount, currency`) but is **thrown away before the ledger**, which only ever
  sees the GL control account.

Consequence: if the ledger posts but the balance debit fails (or vice versa, or a compensation
half-applies), the customer balance and the accounting truth diverge **silently** — there is no
projection link and no reconciliation. For a bank this is the cardinal integrity defect: the
deposit-control GL account and the sum of customer balances it is supposed to control are never tied
out. Standard banking practice (zákon o účetnictví 563/1991 Sb.; vyhláška ČNB 501/2002 Sb. pro
banky) requires a control account to be backed by an **analytická evidence / sub-ledger** of
per-customer balances that provably sums to the control total.

## Decision

We will make the **ledger the single golden source of the booked balance**, and turn
**balance-service into a read-model (projection)** of the ledger for `bookedAmount`, while
balance-service **retains authority over the reservation layer** (holds / `pendingAmount` /
`reservedAmount`) — these are pre-ledger, latency-sensitive cover decisions, not yet accounting
events.

Concretely:

1. **Sub-ledger dimension on the ledger.** Add a nullable `subAccountId` (= customer account id) plus
   its currency to the *deposit-control* journal legs (the legs that represent customer money).
   FX-position, cash-clearing and other bank-internal legs carry no `subAccountId`. The ledger
   validates that, for each posted journal, the signed sum of a customer's sub-ledger legs equals the
   movement on the corresponding deposit-control control account (control-account tie-out by
   construction).

2. **Per-account booked-delta event.** The ledger derives, from the sub-ledger legs of each
   posted/reversed journal, an `AccountBookedChangedEvent(accountId, currency, delta, journalEntryId,
   entryDate, version)` and emits it through the **existing** ledger outbox → Kafka pipeline.

3. **Balance projection.** balance-service consumes `AccountBookedChangedEvent` (`@Incoming`) and
   applies `delta` to `bookedAmount`. The saga's direct `debit` dual-write is removed; the saga keeps
   only `placeHold` / `releaseHold` as the **synchronous, overdraft-aware cover gate**. The customer's
   spendable figure is `available = bookedAmount − activeHolds (+ arranged overdraft floor)`.

4. **Reconciliation as a standing control.** A scheduled job ties out, per `(account, currency)`,
   balance-service `bookedAmount` against the ledger sub-ledger sum; and per currency, the sum of
   sub-ledger balances against the deposit-control GL control total. Drift raises an alert and writes
   a reconciliation record — divergence can no longer be silent.

Because both ledger and balance are **money-path** services, this is delivered in independently
shippable, reversible phases, each its own PR with version bumps, threat-model updates, and the
2-approval gate:

- **Phase A — reconciliation as a read-only safety net (ship first).** No write-path change. Tie out,
  per currency, the ledger deposit-control GL balance against the sum of balance-service
  `bookedAmount`. Detects gross drift **today**, before any restructuring, at zero money-path write
  risk. (Limitation: only *aggregate per-currency* tie-out is possible until the sub-ledger dimension
  exists.)
- **Phase B — sub-ledger dimension.** Add nullable `sub_account_id` to journal lines; stamp it on the
  deposit-control legs in `PaymentJournalFactory`; ledger persists and validates the per-account
  tie-out. Backward compatible (nullable; existing journals untouched). Per-account reconciliation
  becomes possible.
- **Phase C — per-account booked-delta events.** Ledger emits `AccountBookedChangedEvent` from the
  sub-ledger legs via the existing outbox. New event schema, versioned backward-compatibly.
- **Phase D — balance projection cutover.** balance-service consumes the event and derives
  `bookedAmount`; the saga drops the direct `debit`; holds remain the synchronous cover gate.

> **Amendment 2026-06-19 — Phase D-2 complete.** The balance projection is live:
>
> - **#1313** — `PaymentJournalFactory` branches on payment direction (outbound DEBITs the payer);
>   `bookedAmount` cutover from saga dual-write to ledger projection (feature-flag `ledger.booked-projection`
>   default `true`). LOCK-STEP with #1314.
> - **#1314** — balance-service enables the ledger booked-balance projection as the sole `bookedAmount`
>   mover; saga `debit` removed. Projection was default-OFF so only the saga mover was live before.
>
> Status promoted to **Accepted**. Phases A–D are fully implemented. Reconciliation job (Phase A) was
> the first ship; sub-ledger + event emission (B/C) shipped in earlier PRs; projection cutover (D) is
> now done. Remaining: long-term monitoring of projection lag and the scheduled reconciliation alert.

## Alternatives considered

- **Aggregate reconciliation only, no sub-ledger.** Lighter; per-currency control-account tie-out
  catches gross drift. But it never makes the ledger the *per-customer* golden source — balance stays
  an independent writer and per-account divergence stays invisible. Rejected as the end-state, but
  **adopted as the Phase A interim** because it is immediately shippable and valuable.
- **Balance-service as sole authority, ledger derived from balance events.** Inverts the truth: the
  regulatory double-entry record would become a cache of a customer-facing store. Rejected — accounting
  primacy is non-negotiable.
- **Synchronous 2PC across ledger + balance.** Strong consistency, but synchronously couples two
  money-path services, hurting availability and latency and creating complex partial-failure modes.
  Rejected in favour of ledger-golden + async projection + reconciliation (eventual consistency on
  `bookedAmount`, with overspend still prevented synchronously by holds).

## Consequences

**Positive**
- One source of truth; the customer balance is a provable projection of the accounting record.
- Control-account ⇄ sub-ledger tie-out is auditable and continuously reconciled (CNB/účetnictví).
- Drift is detected and recorded, not discovered after the fact.
- Clean CQRS: ledger writes, balance reads; the saga's dual-write seam is removed.

**Negative**
- `bookedAmount` becomes **eventually consistent** (projection lag). Overspend is still prevented
  synchronously because the cover decision runs on holds at payment time, not on `bookedAmount`.
- A multi-phase migration across two money-path services; each phase needs 2 approvals + threat-model
  refresh.
- New event schema + a new consumer in balance-service to build and operate.

**Neutral**
- Holds / `reservedAmount` / `pendingAmount` remain a balance-service concern (pre-ledger).
- FX-position accounting and per-currency journal balancing are unchanged.
- No customer-visible API shape change for the balance read model.

## Compliance impact

- PCI DSS: not applicable.
- DORA:    data-integrity + reconciliation is an ICT operational-resilience control; drift alerting
           supports incident detection.
- GDPR:    not applicable (no new personal data; `subAccountId` is an existing account identifier).
- PSD2:    available-funds / cover decision preserved synchronously via holds — payment authorisation
           behaviour unchanged.
- CNB:     zákon o účetnictví 563/1991 Sb. a vyhláška 501/2002 Sb. — control account backed by
           analytická evidence (sub-ledger) with a provable tie-out; reconciliation evidences it.

## References

- ADR-0002 — hexagonal architecture (domain has zero framework imports).
- ADR-0024 / ADR-0025 — single IBAN + currency pockets; per-currency FX-position accounting.
- Multi-currency reality-check finding **N3** (single source of truth for balance).
- `openbank-ledger-service` `LedgerService`, `JournalEntry.validateBalance`, ledger outbox pipeline.
- `openbank-transaction-service` `PaymentSagaOrchestrator`, `PaymentJournalFactory`, `BalanceCoverPort`.
- `openbank-balance-service` `BalanceService`, `Balance`.
- Threat models (ADR-0030 D2): `docs/threat-models/ledger.md`, `docs/threat-models/balance.md`.
