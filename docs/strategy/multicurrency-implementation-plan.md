# Multi-currency current account — implementation plan

Status: in progress (started 2026-05-29)
Decisions: [ADR-0024](../adr/0024-multi-currency-account-single-iban-pockets.md) (single IBAN + currency pockets),
[ADR-0025](../adr/0025-per-currency-ledger-balancing-and-fx-revaluation.md) (per-currency balancing + FX position + revaluation)

This plan turns the analysis findings (N1–N5) and the production gaps into a phased,
verifiable rollout. Each phase is independently shippable. Build verification: `openbank-libs`
and per-service **unit** tests run offline with JDK 25
(`JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :<svc>:test --offline`); integration
tests need Docker DevServices (Postgres) and run in CI.

## Findings being addressed

| Id | Finding | Phase |
|----|---------|-------|
| N1 | Ledger balances across currencies via base amount (accounting-incorrect) | 1 |
| N2 | No overdraft; `OverdraftConfig` is dead config; debit hard-requires booked ≥ amount | 2 |
| N3 | Three sources of truth for balance (ledger / balance-service / account-service) | 2 |
| N4 | Debit checks `booked` not `available`; `canDebit` only checks currency+status | 2 |
| N5 | FX disconnected — `TransactionService` sends `fxRate=null`, fx-service never called | 3 |
| G1 | No business-day calendars (TARGET2/CERTIS), cut-off, value-date derivation | 3 |
| G2 | No FX revaluation / exchange differences / open FX position | 3 |
| G3 | No withholding tax on interest (15% CZ) | 4 |
| G4 | No statements (camt.053 / MT940 / PDF) | 4 |
| G5 | AML/sanctions screening not wired into payments or FX | 4 |
| G6 | No SEPA SDD mandates | 4 |
| G7 | No AnaCredit/FINREP/CRS feed; no CZK current account product | 4 |

## Phase 1 — Ledger accounting correctness (foundational, verifiable offline)

1. **N1: per-currency balancing.** Rewrite `JournalEntry.validateBalance()` to require
   debits == credits within each currency (group by `baseAmount.currency`). Add multi-currency
   balanced/unbalanced unit tests. *(in progress)*
2. **FX position + exchange-difference GL accounts.** Seed per-currency FX position accounts
   (devizová pozice) + a kurzové-rozdíly P&L account in the ledger CoA migration.
3. **Cross-currency posting helper.** A pure helper that builds a self-balancing multi-leg
   entry (customer pocket ↔ FX position ↔ counter pocket + margin/exchange-diff). Unit-tested.

## Phase 2 — Account pockets, overdraft, single source of truth

4. **N3/N4: source of truth (ADR-0039).** Make `ledger` the golden source; `balance-service` a
   read-model fed by the ledger outbox + reconciliation; remove account-service's own
   `AccountBalance`. Align `available` semantics. Phased A→D:
   - **Phase A — done.** Read-only reconciliation (no write-path change): a daily job ties out, per
     currency, the ledger deposit-control balance against the sum of `balance-service` booked
     balances and records drift (`balance_reconciliation`, `GET/POST /api/v1/balances/reconciliation`).
   - **B** — sub-ledger `subAccountId` on the deposit-control journal legs.
   - **C** — ledger emits per-account `AccountBookedChangedEvent` via its outbox.
   - **D** — `balance-service` consumes it as a projection; the saga drops the direct `debit`, holds
     remain the synchronous cover gate.
5. **N2: overdraft.** `available = booked + arrangedLimit − reserved`; arranged vs unarranged
   debit; negative balance reclassified to a receivable (loan) GL position; wire
   `OverdraftConfig` from the product.
6. **Currency pockets (ADR-0024).** `Account.primaryCurrency` + `CurrencyPocket` set;
   migration; inbound routing selects pocket by `payment.currency` with `AUTO_CREATE /
   CONVERT_TO_PRIMARY / REJECT` rule; indicative consolidated balance (ČNB rate, never used
   for cover).

## Phase 3 — FX, value dating, revaluation

7. **N5: FX in payments.** `PaymentSaga` calls fx-service for rate+margin; cross-currency
   between pockets posts the atomic, idempotent multi-leg entry from Phase 1; drop `fxRate=null`.
8. **G1: calendars + cut-off + value date.** TARGET2 (EUR) and CERTIS (CZK) holiday calendars
   in libs; cut-off times; automatic T+0/T+1/T+2 value-date derivation per currency/rail.
9. **G2: daily FX revaluation.** Scheduled job reprices open FX positions at the ČNB rate,
   books exchange differences to P&L (reuses the outbox/poller pattern).

## Phase 4 — Regulatory / product completeness

10. **G5: screening.** Wire AML/sanctions screening into payment execution and FX conversion.
11. **G3: withholding tax** on credit interest (15% CZ) in interest-service. *(done — withhold at
    capitalization: ADR-0033; monthly remittance + lifecycle advance RECORDED→REMITTED: ADR-0038,
    `Vyúčtování daně vybírané srážkou` per §38d, payment to FÚ delegated via
    `interest.withholding.remitted.v1`.)*
12. **G4: statements** — camt.053 + MT940 + PDF, per pocket and consolidated. *(done — `openbank-statement-service`, ADR-0035; on-demand render, metadata-only period-close)*
13. **G6: SEPA SDD** mandate lifecycle. *(ADR-0036 — `openbank-sdd-service`, debtor-side mandate vault + R-transaction decisions)*
14. **G7: reporting + product** — AnaCredit feed for overdraft exposures; CZK current account
    product + multi-currency umbrella product in the catalogue. *(done — reporting: ADR-0037 +
    `openbank-anacredit-service`, derive-only credit dataset, legal-entity scope + €25k per-debtor
    threshold, overdrafts first; product: `CURRENT_CZK` (prod-014) + `CURRENT_MULTICURRENCY_UMBRELLA`
    (prod-015) seeded in `openbank-product-catalog`.)*

## Edge cases tracked across phases

Negative pocket with positive total (no netting), FX rounding residual → rounding account,
hold currency vs settlement currency drift, concurrent debits (optimistic lock), currency
removed from product (grandfather), per-pocket vs account freeze/dormancy, garnishment on a
specific pocket + protected minimum, hold expiry sweeper, closing a pocket with a non-zero or
negative balance, deposit-insurance aggregation across pockets at the decisive-day rate.
