<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-interest-service

- **Date:** 2026-07-18
- **Status:** Lightweight STRIDE (ADR-0030 D2). **Money-path** (capitalization + write-off GL journals, withholding-tax remittance).
- **Purpose:** Interest accrual, capitalization, and withholding-tax remittance for customer accounts.

## 1. Scope & purpose

The interest service accrues interest on eligible accounts, capitalizes it into the customer's
balance (`capitalize()`), posts the corresponding write-off/capitalization journals to the ledger,
and remits withholding tax on interest paid to the tax authority. Both capitalization and
withholding-tax remittance move real value: capitalization credits the customer's balance via a
ledger posting, and remittance debits an amount owed to the state (#999). This is a direct
money-path service, not adjacent.

## 2. Data flow (DFD)

```
[Scheduler / Admin-UI] --HTTPS/internal--> [openbank-interest-service]
                                                |
                     account lookup        ---|---> [account-service] (AccountDirectoryAdapter)
                     capitalization journal ---|---> [ledger-service] (RestLedgerPostingAdapter, CapitalizationJournalFactory)
                     debit remittance       ---|---> [transaction-service] (TransactionServiceClient)
                     settlement ack         <---|--- [Kafka: withholding-remittance-settlement]
                     outbox events          ---|---> [Kafka: interest.capitalized, interest.withholding.remitted]
```

- **External entities:** scheduler (internal trigger, no external caller), admin-UI (ROLE_OPERATOR/ADMIN for read/ops endpoints).
- **Trust boundaries:** service → ledger-service (mTLS + OIDC, fail-closed via `LedgerCallGuard`); service → transaction-service (mTLS + OIDC); service → Kafka (mTLS, consumer/producer ACLs).
- **Assets:** accrued/capitalized interest amounts, withholding tax rate and remittance amounts, account balances (indirectly, via the ledger postings this service issues).

## 3. Authn/Authz

- Operator-facing REST endpoints: `@RolesAllowed` (ROLE_OPERATOR/ADMIN).
- Capitalization and remittance runs are triggered internally (scheduled job), not by an external caller — no unauthenticated inbound trigger surface.
- Calls to `ledger-service` and `transaction-service` are service-to-service (OIDC client credentials, OPA policy, four-eyes verbs per `rules.yaml`).

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Rogue caller triggers capitalization or remittance for an arbitrary account | Internal scheduler trigger only; `@RolesAllowed` gate on any manual/admin trigger endpoint; OIDC service identity on downstream calls |
| **T**ampering | Alter capitalized amount or withholding rate in flight | TLS in transit; journal amounts derived server-side from `WithholdingTaxPolicy`/`WithholdingRemittancePolicy`, never accepted as caller input; idempotency key on ledger posting |
| **R**epudiation | Dispute over whether interest was capitalized or tax remitted for a period | AuditEvent per capitalization/remittance action; outbox event (`interest.capitalized`, `interest.withholding.remitted`) is the durable record; ledger journal itself is the authoritative, immutable trail |
| **I**nfo disclosure | Expose per-account interest/withholding amounts via error bodies or metrics | Error bodies carry codes only; metrics are low-cardinality (no account-id/amount labels, ADR-0077/0079) |
| **D**oS | Flood the manual capitalization/remittance trigger, or replay settlement events | `@RolesAllowed` gate on manual triggers; idempotency key on both the ledger posting and the transaction-service debit guards duplicate runs |
| **E**oP | Use the withholding remittance path to move funds unrelated to actual accrued tax | Remittance amount computed solely from `WithholdingRemittancePolicy` against the service's own accrual ledger, not from caller-supplied input; downstream `transaction-service` is the authoritative amount boundary |

## 5. Residual risks / assumptions

- **Withholding-tax remittance to the tax authority is not yet wired to an external filing system** (#999 tracks actual remittance-to-authority; today the debit lands in an internal remittance-holding account). The money-path risk this threat model covers is the *internal* debit/capitalization flow, which is live.
- **Interest rate configuration** is operator-managed and out of scope for this document (covered by the product-catalog/pricing threat surface).
- **Settlement ack consumer** (`WithholdingRemittanceSettlementConsumer`) trusts the Kafka topic's mTLS/ACL boundary as its authentication; no additional payload-level signature.

## 6. Change log

- **2026-07-24** — Bind currency to the rate config (issue #1265). Previously nothing tied a currency
  to `InterestRateConfig`, and `interest_accruals.currency` defaulted to `'EUR'` while the seeded
  product is CZK, so an account could accumulate a mixed-currency ACCRUING set (the scheduler reads
  each account's booked-balance currency). `capitalize()` correctly refuses to sum incommensurable
  currencies, but there was **no operator or API path to unwedge the set** — a permanent
  availability/correctness hole on the money path (interest silently never capitalized for that
  account). Fix: `InterestRateConfig` gains a `currency`; `accrue` resolves a rate only in the
  accrual's own currency and fails closed with `RateConfigNotFoundException` (HTTP 422) when the
  account has no rate in that currency; the `interest_accruals` UNIQUE key now includes `currency`
  (V12), so two same-date rows in different currencies can never collapse into one capitalize set.
  The `mixedCurrencyFailure` guard is retained as an unreachable defence-in-depth assertion — the
  last check before a GL journal is posted. No new trust boundary or external caller; the 422 is a
  fail-closed on a config gap, never a fund movement. Rollback: revert the commit + V12 (safe only
  before two currencies coexist for one account/product/date).
- **2026-07-22** — Activate monthly capitalization (issue #999). `capitalizeAll` was a stub returning 0,
  so the already-assessed `capitalize()` money-path (claims accruals, posts a GL journal via
  `LedgerPostingPort`, records withholding) had never run at scale. A new `InterestCapitalizationScheduler`
  now drives it monthly (cron `0 0 2 1 * ?`), over a work-list read from the accrual table
  (`findAccountsWithPendingCapitalization`). No new trust boundary or external caller — this activates
  existing money-path logic on a schedule; per-pair failures are recovered (a wedged account can't stop
  the batch) and each capitalization keeps its existing ledger idempotency key, so a retry is safe. The
  downstream effect is that withholding tax is now actually assembled and remitted (the §38d statutory
  filing owner is decided separately in ADR-0180). Rollback: revert the commit (the scheduler stops).
- **2026-07-18** — Initial lightweight threat model (ADR-0030 D2), added alongside `openbank-interest-service`'s addition to `money_path_services` (#1478).
