# 25. Per-currency ledger balancing, FX position accounts, and revaluation

Date: 2026-05-29
Status: Accepted

## Context

The double-entry ledger (`openbank-ledger-service`) is otherwise solid — debit/credit
sides, idempotent posting, reversals, partitioned journals — but its balance invariant is
accounting-incorrect for multiple currencies, which [ADR-0024](0024-multi-currency-account-single-iban-pockets.md)
now requires.

`JournalEntry.validateBalance()` computes per-currency debit/credit maps **and then
ignores them**, asserting only a single flat sum of `baseAmount` across all currencies:

```
totalDebits  = lines.filter { DEBIT  }.sumOf { it.baseAmount.amount }
totalCredits = lines.filter { CREDIT }.sumOf { it.baseAmount.amount }
require(totalDebits == totalCredits)
```

Summing across currencies via a base amount means an entry that debits CZK and credits EUR
can "balance" on the base total while being unbalanced in every actual currency. It hides
the FX gain/loss instead of booking it. A correct multi-currency ledger must balance
**within each currency**, and represent cross-currency economic events by routing through
**FX position accounts** so each currency self-balances, with the rate difference landing
on a P&L exchange-difference account (kurzové rozdíly).

Czech law mandates this: Act on Accounting 563/1991 §4(12)/§24 (foreign amounts converted
at the ČNB rate), Decree 501/2002 and ČÚS 108–110 for banks (foreign-currency position,
**daily revaluation**, exchange differences to profit/loss).

## Decision

1. **Balance per currency.** `JournalEntry.validateBalance()` requires, for every currency
   present in the entry, that the sum of debits equals the sum of credits **in that
   currency**. The balancing currency is the GL account's own currency (each GL account is
   single-currency; the line's `baseAmount.currency` equals the account currency, already
   enforced in `LedgerService.loadAndValidateGlAccounts`). The flat cross-currency base
   sum is removed as the invariant.

2. **FX position + exchange-difference accounts.** The chart of accounts gains, per traded
   currency, an **FX position account** (devizová pozice) and a shared **exchange-difference**
   P&L account (kurzové rozdíly). A cross-currency event (a customer FX conversion, an
   inbound payment converted to the primary pocket) is posted as a **multi-leg entry that
   self-balances in each currency**, e.g. EUR→CZK:

   ```
   DEBIT  customer EUR pocket            1000.00 EUR
   CREDIT FX position EUR                1000.00 EUR     (balances in EUR)
   DEBIT  FX position CZK               25000.00 CZK
   CREDIT customer CZK pocket           24900.00 CZK
   CREDIT FX margin income (or kurz.r.)   100.00 CZK     (balances in CZK)
   ```

3. **Daily revaluation.** A scheduled job reprices each open foreign-currency position to
   CZK at the end-of-day ČNB rate and books the movement to the exchange-difference P&L
   account. (Job lands in Phase 3; the GL accounts and posting helper land now.)

The `baseAmount`/`fxRate` fields stay, but their role is **reporting and revaluation** (a
consolidated trial balance in the functional currency), never the balancing basis.

## Alternatives considered

- **Keep base-currency balancing** — status quo. Rejected: accounting-incorrect, hides FX
  P&L, violates 501/2002 / ČÚS, and would let unbalanced cross-currency entries post.
- **Forbid multi-currency entries entirely (one currency per journal)** — simplest. Too
  restrictive: a single FX conversion is inherently two currencies; splitting it into two
  unlinked single-currency journals loses the atomic economic event and its linkage.

## Consequences

**Positive**
- The ledger is correct per currency; FX gain/loss is booked explicitly, not hidden.
- Satisfies ČÚS / Decree 501/2002 daily-revaluation and exchange-difference requirements.
- Enables a true per-currency trial balance and a separately-reportable bank FX position.

**Negative**
- Cross-currency callers must construct multi-leg entries through FX position accounts
  rather than a naive two-line entry; a posting helper mitigates this.
- Revaluation adds an end-of-day batch and a rate dependency (ČNB).

**Neutral**
- Existing single-currency entries are unaffected (debits already equal credits within the
  one currency); the change is a pure tightening of the invariant for them.

## Compliance impact

- CNB / accounting: Act 563/1991 §4(12)/§24; Decree 501/2002; ČÚS 108–110 — per-currency
  position, daily revaluation, exchange differences to P&L. This ADR is the mechanism.
- DORA: ledger correctness is an integrity control; revaluation job is a scheduled
  reconciliation surface.

## References

- [ADR-0024](0024-multi-currency-account-single-iban-pockets.md) — the account model this supports
- [ADR-0003](0003-transactional-outbox-for-kafka.md) — outbox the revaluation job reuses
- Act on Accounting 563/1991 Sb. §4(12), §24; Decree CNB 501/2002 Sb.; ČÚS for banks 108–110
- `docs/strategy/multicurrency-implementation-plan.md` — phased rollout
