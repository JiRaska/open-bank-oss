---
date: 2026-05-29
decision-status: accepted
delivery-status: partial
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [accounts, fx, ledger]
summary: "A multi-currency current account is one Account with a single IBAN and N currency pockets, each with its own balance and overdraft; balances are never netted for cover and the consolidated view is indicative only."
---

# 24. Multi-currency current account: single IBAN + currency pockets

**Delivery note (updated 2026-07-17):**
- **Model decision** — ✅ Complete: one IBAN + N currency pockets per account, pocket-level balance/overdraft; architecture settled and documented.
- **Implementation** — 🟡 Partial: the CurrencyPocket schema (`V7__account_pockets.sql`, `AccountPocketEntity`, `CurrencyPocketRepositoryImpl`), pocket routing / missing-pocket policy (`PocketRouter` + `AccountService.resolvePocket`, unit-tested), and pocket lifecycle (add/close/list via `AccountResource` `/pockets`) are **shipped** and now underpin ADR-0107/0109/0110. Still ⬜ Pending: **per-pocket statement dates, deposit-insurance aggregation, AnaCredit exposure tie-out** — not yet coded; sequenced in multicurrency-implementation-plan.

## Context

The product catalogue advertises multi-currency current accounts
(`MultiCurrencyConfig` in `openbank-product-catalog`), but the running system does not
implement them. Today the model is rigidly **one `Account` = one IBAN = one currency**
(`Account.currency: CurrencyCode`, `Account.accountNumber: Iban`), while balances are
*already* keyed per `(accountId, currency)` in `openbank-balance-service`
(`UNIQUE(account_id, currency)`). The product wants a current account that holds a
**primary currency** and lets the customer add further currency components ("pockets" /
"subaccounts"), with a consolidated view in the primary currency.

The forcing question was whether each currency needs its **own IBAN** or whether one
IBAN can carry several currencies. Investigation of the code settled it:

- `Iban` (`openbank-libs .../domain/account/Iban.kt`) is `countryCode + checkDigits +
  bban` validated mod-97. **An IBAN does not — and per ISO 13616 cannot — encode a
  currency.** It identifies an *account*, not a currency.
- Payment messages carry the currency themselves (SEPA/`DomesticPayment`/`SepaPayment`,
  SWIFT, clearing all have a `currency` field), but routing today resolves the target
  purely by IBAN (`AccountRepositoryImpl.findByIban`); the currency is stored and ignored.

So one IBAN can serve multiple currencies: the inbound payment's currency selects the
pocket. This is the classic Czech/EU "multiměnový účet". The per-currency-IBAN model
(Revolut/Wise) exists for multi-entity / multi-scheme reasons (a Lithuanian EUR IBAN, a
UK GBP sort code), **not** for an accounting reason. The internal bookkeeping is identical
either way (see [ADR-0025](0025-per-currency-ledger-balancing-and-fx-revaluation.md)).

## Decision

We model a multi-currency current account as **one umbrella `Account` with a single
primary IBAN and N currency pockets**, not one IBAN per currency.

- An `Account` gains a `primaryCurrency` and owns a set of **`CurrencyPocket`** records,
  one per held currency. The pocket in the primary currency always exists and cannot be
  removed while the account is open. The account's single IBAN is the primary pocket's
  addressing identifier.
- **Inbound routing:** resolve the account by IBAN, then select the pocket by the
  payment's currency. If no pocket exists for that currency, apply a deterministic,
  product-configured rule — `AUTO_CREATE`, `CONVERT_TO_PRIMARY`, or `REJECT` — never an
  implicit guess.
- **Each pocket has its own balance and its own overdraft policy.** Solvency and funds
  cover are evaluated **per pocket**: a CZK debit is covered only by the CZK pocket (plus
  its arranged overdraft). Balances across currencies are **never netted** for cover.
- **The consolidated balance in the primary currency is indicative only** — computed at
  the ČNB reference rate, labelled as such, and **never** used in a funds-availability or
  overdraft decision.
- **Cross-currency movement between a customer's own pockets is an explicit FX
  conversion**, posted through per-currency FX position accounts
  ([ADR-0025](0025-per-currency-ledger-balancing-and-fx-revaluation.md)). Automatic
  "borrow from the EUR pocket to pay CZK" is an opt-in product feature with an agreed
  rate/margin, not default behaviour.

## Alternatives considered

- **One IBAN per currency (neobank model)** — each pocket independently addressable;
  cleaner per-currency statements and garnishment targeting. Rejected as the default: it
  multiplies account identifiers for the customer, is driven by multi-entity concerns we
  do not have, and the accounting is no different. Remains available later for products
  that genuinely need a dedicated per-currency IBAN.
- **Keep one account = one currency, open N separate accounts** — status quo. Rejected:
  it pushes "I have CZK and EUR" into N unrelated accounts with no umbrella, no
  consolidated view, and no shared product/limits — exactly the product we are trying to
  build.
- **Net all currencies into a single base-currency balance** — simplest UX. Rejected as
  unlawful/unsafe: netting currencies for cover hides FX risk, breaks per-currency
  bookkeeping, and would let an EUR balance silently fund a CZK overdraft.

## Consequences

**Positive**
- One account number for the customer; currency resolved from the payment, as real CZ/EU
  multicurrency accounts work.
- Builds on existing per-`(accountId, currency)` balance rows — minimal new infrastructure
  in `balance-service`.
- Per-pocket cover and overdraft keep FX risk and credit exposure explicit and auditable.

**Negative**
- Routing gains a currency-selection + missing-pocket step that must be deterministic and
  well tested (currency mismatch is a common real-world incident).
- The "indicative consolidated balance" must be carefully fenced off from every cover
  decision; a leak there is a correctness/compliance bug.

**Neutral**
- The per-currency-IBAN model is not foreclosed; it becomes a product option, not the core
  model.

## Compliance impact

- PSD2 / ZoPS 370/2017: per-pocket value dating & availability; consolidated statement
  obligations apply per pocket.
- SEPA (Reg. 260/2012): the EUR pocket is SEPA-reachable via the single IBAN (SEPA carries
  only EUR, so currency selection is unambiguous for inbound SEPA).
- CNB / accounting: per-pocket positions feed per-currency GL balancing and revaluation —
  see [ADR-0025](0025-per-currency-ledger-balancing-and-fx-revaluation.md).
- Deposit insurance (Act 21/1992, Garanční systém): the insured claim must aggregate all
  pockets of a depositor, converted to CZK at the decisive-day ČNB rate (cap EUR 100k).
- AnaCredit: a pocket in arranged/unarranged overdraft is a credit exposure and must feed
  AnaCredit (threshold EUR 25k).

## References

- [ADR-0025](0025-per-currency-ledger-balancing-and-fx-revaluation.md) — the accounting model behind pockets
- [ADR-0009](0009-postgres-per-service.md) — Postgres-per-service (balance rows live in balance-service)
- ISO 13616 (IBAN) — no currency component in the identifier
- `docs/strategy/multicurrency-implementation-plan.md` — phased rollout
