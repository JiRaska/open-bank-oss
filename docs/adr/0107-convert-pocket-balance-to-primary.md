# 107. Convert a currency pocket's balance to the primary currency (sweep-to-close)

Date: 2026-06-23
Status: Accepted
Author(s): Jiří Raška

## Context

ADR-0109 shipped customer-managed currency pockets (add / remove). Its §4 ("safe
close: no orphaned balances") refuses to close a pocket that still holds money and
routes the customer to **convert the remainder to the primary currency (CZK) first**,
then close — "an explicit, confirmed own-account FX move via the existing
`PocketTransfer` flow". In practice that flow does **not** exist end-to-end:

- The app's `PocketTransferScreen` mutates local state and calls `syncPocketMove`,
  which **returns early for any cross-currency move** (`fromCur != toCur`) and for any
  same-account move (`fromId == toId`). So a EUR→CZK pocket conversion never reaches
  the backend — the only thing the "Převést do Kč" button does today is navigate.
- `customer-edge` exposes FX **rates** (`GET /customer/v1/fx/rates/...`) but no
  conversion that moves money, and no pocket-convert endpoint.
- `fx-service POST /api/v1/fx/convert` screens (AML) and *records* a conversion but
  **moves no money** on balances/ledger.
- `transaction-service` already models cross-currency money movement correctly
  (`PaymentJournalFactory.crossCurrencyLines`: DEBIT deposit-control(sell) on the
  pocket → CREDIT/DEBIT the FX-position GL for the spread → CREDIT deposit-control(buy)
  on the pocket; single IBAN ⇒ source == target). But its settlement is
  **buy-specified**: `resolveSettlement(amount, settlementCcy)` always derives
  `baseAmount = amount × rate`, so the caller specifies how much to *buy* and the
  *sell* is computed.

The sweep-to-close use case is naturally **sell-specified**: "sell my entire EUR
pocket (exactly E EUR) into CZK". With a buy/sell spread the two rates are not
reciprocal, so a buy-specified call leaves FX dust in the source pocket — and any
residue means the pocket still can't be closed. The capability is *almost* there; the
gap is a sell-specified own-account FX conversion exposed to the customer.

## Decision

**Add a sell-specified own-account FX conversion that sweeps a pocket to the primary
currency, built by extending the existing cross-currency transaction saga (so the
double-entry ledger stays authoritative) and exposing it through one new edge endpoint
and one app action.** No new ledger primitive; we reuse `crossCurrencyLines`.

### 1. transaction-service — honor an explicit settlement (sell) amount

`resolveSettlement` gains an optional authoritative `settlementAmount`. When present
(and the move is cross-currency), it is used **verbatim** as `baseAmount` (the sell)
and `fxRate` is taken as `settlementAmount / amount`, instead of deriving
`baseAmount = amount × rate`. When absent, behaviour is unchanged (buy-specified).
This makes the source-pocket debit exactly the full balance, so the pocket zeroes and
becomes closeable; the bank keeps the rounding sub-unit and spread in the FX-position
GL, as today. `InitiateTransactionRequest.baseAmount` already exists on the wire and is
simply plumbed through to the command.

### 2. customer-edge — `POST /customer/v1/accounts/{accountId}/pockets/{currency}/convert`

Thin, ownership-checked (ADR-0065), `OWN_ACCOUNT` SCA-exempt (PSD2 RTS Art. 15):
1. Ownership-check the account against the JWT party.
2. Read the source pocket balance E from balance-service (reject `pocket_empty` if 0).
3. Resolve the account's primary currency (CZK) from the pocket list.
4. Quote `rate = bid(EUR→CZK)` from fx-service; compute `buy = floor(E × rate)` in
   primary minor units.
5. Call transaction-service: `type=TRANSFER`, `sourceAccountId == targetAccountId ==
   accountId`, `currencyCode = primary` (buy), `amount = buy`, `baseCurrencyCode =
   {currency}` (sell), `baseAmount = E`, `scaExemption = OWN_ACCOUNT`,
   `Idempotency-Key`.
6. Return `{ soldAmount, soldCurrency, boughtAmount, boughtCurrency, rate }` for a
   confirmation UI.

A customer-facing rate quote is also surfaced so the app can preview before confirming.

### 3. app — wire "Převést do Kč" to a real conversion

`PocketApi` gains `convertToPrimary(accountId, currency)`. The `ManageCurrenciesSheet`
"Převést do Kč" affordance (today: navigate only) becomes a confirm step showing
*"Prodat 12,34 € → ~310 Kč (kurz 25,1)"*; on confirm it calls the edge, refreshes
pockets, and — once the pocket is zero — the existing close path succeeds. The legacy
`PocketTransferScreen` keeps its same-currency cross-account behaviour unchanged.

## Alternatives considered

- **A — Drive it through `balance-service` debit/credit + `fx-service /convert`
  directly from the edge.** Simpler and trivially sell-specified (debit the exact
  balance), and `/convert` gives AML screening + a conversion record. Rejected as the
  primary path: it bypasses the double-entry ledger (ADR-0025), so the GL would not
  balance — unacceptable for a production-faithful core. (We still record/screen via
  the saga's existing FX rate path.)
- **B — Buy-specified via the existing saga, then sweep the dust separately.**
  Rejected: two movements, residual-rounding gymnastics, and a window where the pocket
  is non-empty-but-untouchable.
- **C — Auto-sweep silently on close.** Rejected by ADR-0109 §C (conduct risk); the
  customer must see and approve the conversion.

## Consequences

**Positive**
- Completes ADR-0109's safe-close story; the "Převést do Kč" button finally works.
- Reuses the authoritative cross-currency ledger path; the GL stays balanced.
- Sell-specified ⇒ the pocket zeroes exactly and is immediately closeable.

**Negative**
- A surgical change to settlement resolution in transaction-service (guarded by the
  new optional amount; default path unchanged) — needs unit coverage for the
  reciprocal-rate / rounding cases.
- One more edge round-trip (balance read + rate quote) before the conversion.

**Neutral**
- No SCA (own-account, Art. 15 exempt), consistent with ADR-0109 §1 and ADR-0021.
- Idempotency via `Idempotency-Key`, consistent with the transfer/convert endpoints.

## Compliance impact

- **PSD2:** own-account FX move, RTS Art. 15 SCA-exempt (as ADR-0109). Audited via
  `requestedBy` (JWT `sub`).
- **CNB / AML:** cross-currency holdings already screened; the conversion uses the
  bank's quoted FX rate and books through the FX-position GL.
- **GDPR / PCI:** no new personal or card data.

## References

- ADR-0021 — SCA gates payments, not account servicing
- ADR-0024 / ADR-0025 — multi-currency pockets on one IBAN / per-currency ledger + FX revaluation
- ADR-0065 — customer edge as a thin authorizing proxy
- ADR-0109 — customer-managed currency pockets (this completes its §4 convert-before-close)
- transaction-service `PaymentJournalFactory.crossCurrencyLines`, `TransactionService.resolveSettlement`
- fx-service `POST /api/v1/fx/convert`; balance-service `/credit` `/debit`
