# 125. Same-account currency exchange (the app's currency swap)

Date: 2026-06-23
Status: Accepted
Delivery-Status: Shipped
Author(s): Jiří Raška

## Context

ADR-0109 added customer-managed pockets and ADR-0107 added convert-a-whole-pocket-to-
primary. But customers reported they "can't see how to exchange between currencies" and
the drag-and-drop swap "doesn't work":

- The swap UI (`PocketTransferScreen`, drag a pocket tile onto another) is reachable only
  from the Vklady/Vault screen — undiscoverable.
- Its `onApply` calls the app's `syncPocketMove`, which **returns early for any
  cross-currency move** (`fromCur != toCur`) and any same-account move (`fromId == toId`).
  So a EUR→CZK (or CZK→EUR) swap updated local state only and reverted on the next refresh.
- Most accounts hold only CZK, so the swap screen shows a single tile — nothing to drag.

ADR-0107 D1 already gave transaction-service a **sell-specified cross-currency settlement**
(honor an explicit settlement amount), and its saga books a single-IBAN pocket→pocket move
(`crossCurrencyLines`, source == target). That is exactly the primitive a general exchange
needs; it just was not exposed or wired.

## Decision

**Expose a general same-account currency exchange and make it discoverable.** Sell a chosen
amount of one pocket currency into another on the same account, as an own-account
(PSD2 RTS Art. 15 SCA-exempt) sell-specified cross-currency `TRANSFER` through
transaction-service — so the double-entry ledger stays authoritative. ADR-0107's
convert-to-primary becomes the special case "sell the whole pocket into the primary".

### 1. Edge — `POST /customer/v1/accounts/{accountId}/pockets/{fromCurrency}/exchange`
Body `{ "toCurrency": "EUR", "amount": "1000.00" }` (amount in the *from* major unit).
Ownership-checked. Validates: distinct/valid currencies, target is product-supported
(allow-list), source pocket covers the amount; quotes the bid rate; computes the buy amount
(rounded down to the minor unit, spread/rounding to the FX-position GL). **Opens the target
pocket first if it does not exist**, so CZK→EUR works from a CZK-only account. Routes to
transaction-service as a `TRANSFER` with `baseAmount` = the exact sell amount (source debits
exactly) and `scaExemption = OWN_ACCOUNT`. Returns `{sell/buy currency+amount, rate}`.

### 2. App
- `PocketApi.exchange(accountId, from, to, amount)` calls the edge endpoint.
- `syncPocketMove` no longer drops cross-currency / same-account moves — it calls `exchange`
  for them (same-currency cross-account stays on the existing `/transfers`).
- The swap screen shows **product-supported currencies the account hasn't opened yet** as
  zero-balance tiles, so a CZK-only account can drag CZK→EUR to open and fund EUR.
- A discoverable **"Směnit"** entry is added to the currency picker (not buried in Vault).

## Consequences

**Positive** — the swap finally persists through the ledger; works from a single-currency
account; discoverable. Reuses ADR-0107's primitive — no new ledger capability.

**Negative** — exchange is **same-account only** (cross-account cross-currency is not modelled
by the single-IBAN journal); cross-account same-currency continues via `/transfers`.

**Neutral** — own-account, Art. 15 SCA-exempt; `Idempotency-Key` honoured.

## References
- ADR-0021 (SCA scope), ADR-0024/0025 (pockets, per-currency ledger), ADR-0065 (edge proxy)
- ADR-0109 (manage pockets), ADR-0107 (convert-to-primary; D1 sell-specified settlement)
- transaction-service `PaymentJournalFactory.crossCurrencyLines`, `TransactionService.resolveSettlement`
