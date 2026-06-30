# ADR-0110 — Same-account currency exchange (pocket FX swap)

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Delivery-Status** | Shipped |
| **Date** | 2026-06-28 |
| **Deciders** | Platform Team |
| **Supersedes** | ADR-0125 |
| **Superseded by** | — |

> **Consolidation note (2026-06-28).** This ADR merges two overlapping decisions that two
> sessions wrote for the *same* feature — a same-account pocket-to-pocket currency exchange
> reached at `…/pockets/{fromCurrency}/exchange`. The customer-edge entry point and app wiring
> were originally drafted as ADR-0125 (formerly numbered 0110, renumbered in #2424); the
> account-service execution path was drafted here as ADR-0110 (#2425). ADR-0125 is now
> **superseded by this ADR**, which owns the whole feature. Existing `ADR-0110` citations in
> `openbank-customer-edge`, `openbank-account-service`, and the account-service threat model are
> all correct against this consolidated record.

## Context

Customers who hold multiple-currency pockets on a single account need a first-class exchange path
that converts funds from one pocket currency to another without routing through a payment
instruction. Prior to this feature such a conversion required two separate payment flows with no
atomic guarantees and no FX rate record.

ADR-0109 added customer-managed pockets and ADR-0107 added convert-a-whole-pocket-to-primary, but
customers reported they "can't see how to exchange between currencies" and the drag-and-drop swap
"doesn't work":

- The swap UI (`PocketTransferScreen`, drag a pocket tile onto another) was reachable only from the
  Vklady/Vault screen — undiscoverable.
- Its `onApply` called the app's `syncPocketMove`, which **returned early for any cross-currency
  move** (`fromCur != toCur`) and any same-account move (`fromId == toId`). So a EUR→CZK (or
  CZK→EUR) swap updated local state only and reverted on the next refresh.
- Most accounts hold only CZK, so the swap screen showed a single tile — nothing to drag.

ADR-0107 D1 already gave transaction-service a **sell-specified cross-currency settlement** (honor
an explicit settlement amount), and its saga books a single-IBAN pocket→pocket move
(`crossCurrencyLines`, source == target). That is exactly the primitive a general exchange needs;
it just was not exposed or wired.

## Decision

**Expose a general same-account currency exchange, make it discoverable, and settle it as a single
ledger-authoritative move.** Sell a chosen amount of one pocket currency into another on the same
account, as an own-account (PSD2 RTS Art. 15 SCA-exempt) operation. ADR-0107's convert-to-primary
is the special case "sell the whole pocket into the primary".

### 1. Customer entry point — edge (authoritative settlement path)

`POST /customer/v1/accounts/{accountId}/pockets/{fromCurrency}/exchange`
Body `{ "toCurrency": "EUR", "amount": "1000.00" }` (amount in the *from* major unit).

Ownership-checked at the edge. Validates: distinct/valid currencies, target is product-supported
(allow-list), source pocket covers the amount; quotes the bid rate; computes the buy amount
(rounded down to the minor unit, spread/rounding to the FX-position GL). **Opens the target pocket
first if it does not exist**, so CZK→EUR works from a CZK-only account.

It then routes to transaction-service as a **single cross-currency `TRANSFER`** (`source == target ==
accountId`) with `baseCurrency`/`baseAmount` = the exact sell amount (source debits exactly),
`currency`/`amount` = the computed buy amount, and `scaExemption = OWN_ACCOUNT`. One atomic
double-entry journal — **the ledger stays the single source of truth** (ADR-0039), there is no
intermediate non-ledger state, and the FX spread lands on the FX-position GL inside the same
journal. `Idempotency-Key` is honoured. Returns `{sell/buy currency+amount, rate}`.

This is the path the customer app uses (`PocketApi.exchange` → edge), and it is the **authoritative
settlement mechanism** for a same-account exchange.

### 2. App

- `PocketApi.exchange(accountId, from, to, amount)` calls the edge endpoint.
- `syncPocketMove` no longer drops cross-currency / same-account moves — it calls `exchange` for
  them (same-currency cross-account stays on the existing `/transfers`).
- The swap screen shows **product-supported currencies the account hasn't opened yet** as
  zero-balance tiles, so a CZK-only account can drag CZK→EUR to open and fund EUR.
- A discoverable **"Směnit"** entry is added to the currency picker (not buried in Vault).

### 3. account-service operator/admin endpoint (alternative path, pending convergence)

`account-service` also exposes `POST /api/v1/accounts/{accountId}/pockets/{fromCurrency}/exchange`
(`ROLE_OPERATOR`/`ROLE_ADMIN`, `Idempotency-Key` required) for operator/admin-initiated exchanges.
This path was shipped by #2425 and settles differently:

1. Validate account ownership and `ACTIVE` status.
2. Call `fx-service POST /api/v1/fx/convert` to obtain the applied rate and converted amount
   (`FxConversionPort`); fx-service records the conversion (`conversionId`) for audit and computes
   the spread.
3. Call `transaction-service` **twice** — a DEBIT on the source pocket and a CREDIT on the target
   pocket (`FxSettlementPort`), each with a deterministic idempotency key (`{key}-debit` /
   `{key}-credit`).
4. Return `ExchangeResult` with `conversionId`, both amounts, and `appliedRate`.

Both ports are injected into `AccountService` per the hexagonal mandate (ADR-0002).

**This DEBIT-then-CREDIT mechanism is not atomic** (see Consequences) and is *not* the path the
customer app takes — the edge settles directly through transaction-service (§1). The two settlement
mechanisms for one logical operation are a known divergence; **converging the account-service
endpoint onto the single ledger-authoritative cross-currency `TRANSFER` of §1 is a follow-up
([#2433](https://github.com/JiRaska/open-bank-oss/issues/2433))** (so the dangling-debit failure mode and
the extra fx-service coupling are eliminated). Until then this
endpoint remains for operator/admin use and is documented in the account-service threat model.

## Consequences

- **Positive** — the swap finally persists through the ledger; works from a single-currency
  account; discoverable. The edge path (§1) reuses ADR-0107's primitive, adds **no new ledger
  capability**, and books the whole exchange in one atomic cross-currency journal.
- **Positive** — own-account, PSD2 RTS Art. 15 SCA-exempt; `Idempotency-Key` honoured on both
  entry points so client retries are safe.
- **Negative** — exchange is **same-account only** (cross-account cross-currency is not modelled by
  the single-IBAN journal); cross-account same-currency continues via `/transfers`.
- **Negative (account-service path, §3)** — two sequential REST calls (fx-service convert, then a
  separate transaction-service DEBIT + CREDIT) increase latency, add an `account-service → fx-service`
  dependency (FX-service unavailability blocks that endpoint), and a failure **between** the DEBIT
  and CREDIT leaves a dangling debit. The idempotent retry path recovers it only if the caller
  retries with the same `Idempotency-Key`; a monitoring alert on `debit`-without-matching-`credit`
  within 60 s is a follow-up. This failure mode does not exist on the edge path (§1), which is the
  reason §3 is slated to converge onto it ([#2433](https://github.com/JiRaska/open-bank-oss/issues/2433)).

## Alternatives considered

- **Single FX-service endpoint that calls transaction-service internally:** would couple fx-service
  to the transaction book — rejected to keep fx-service pure (rate data, not settlement).
- **Outbox event from account-service:** eventual consistency complicates the client's "exchange
  now" UX and the FX rate may change between publish and consume — rejected.

## References

- ADR-0021 (SCA scope), ADR-0024/0025 (pockets, per-currency ledger), ADR-0065 (edge proxy)
- ADR-0039 (ledger as golden source — why §1 settles in one journal)
- ADR-0109 (manage pockets), ADR-0107 (convert-to-primary; D1 sell-specified settlement)
- transaction-service `PaymentJournalFactory.crossCurrencyLines`, `TransactionService.resolveSettlement`
- Supersedes ADR-0125 (the original edge entry-point + app-wiring draft, consolidated here)
