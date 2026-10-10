---
date: 2026-10-10
decision-status: proposed
delivery-status: partial
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ledger, accounting-close, regulatory-reporting, architecture]
summary: "A second legal entity's books (the pension company, ADR-0334) are a separate ledger-service deployment with its own database, not an entity column in the bank's ledger; returns read its frozen period closes, summed since the books opened."
followup: "#12425 — the pension-company ledger instance (gitops, Keycloak client grant for tax-reporting, books-opened config) is not deployed yet"
---

# ADR-0337 — Pension company books as a separate ledger deployment per legal entity

## Context

ADR-0334 §2 says the pension company is a separate legal party "with its own GL books", but does
not say where those books live. ADR-0336 needs them: ČNB PSP 10-12 PS, PSP 20-12 PS and PEF 12-04
PS are the company's own balance sheet and P&L, and until now tax-reporting refused them as having
"no source system in this platform".

The ledger cannot hold a second entity's books today, by schema: `gl_accounts.code` is globally
unique (`uq_gl_accounts_code`, V1), a statutory close is unique per `(period_type, period_from)`
(`uq_closed_period`, V22) and the period lock, year close and accounting day are all entity-less.
ADR-0096/0097 built the attested close and FINREP's fail-closed read of it for ONE entity.
ADR-0152 makes single-tenancy an invariant — "no tenant dimension may enter any schema" — and an
entity column on the GL is that dimension in all but name.

## Decision

We will keep one ledger per legal entity: the pension company's books are a **separate
deployment of the same ledger-service image with its own database**, configured like any
ledger. Nothing in the ledger's schema or code changes.

1. **Isolation is structural.** No journal can debit one entity and credit the other, because
   there is no shared table; asset segregation (ADR-0334 §1) holds by construction, not by a
   filter on every query.
2. **Read path is reused.** Consumers read the company instance exactly as FINREP reads the bank's
   (ADR-0097): `GET /api/v1/ledger/periods/{type}/{date}/frozen-trial-balance`, which answers 409
   unless the period is FROZEN with LINES_V1 evidence. The rest-client keeps the provider's
   configKey (`ledger-service`); only the URL names the company instance.
3. **Balances are summed from frozen periods.** A frozen period trial balance holds that period's
   movements, so a balance at a period end is the sum of one frozen YEAR close per prior year
   since the books opened plus the frozen MONTH closes of the current year. Every period must be
   frozen, balanced and in the reporting currency; one gap refuses the return (503) rather than
   reporting a balance wrong by exactly the gap. The opening date is configuration
   (`openbank.statutory-returns.company-books.opened`), because the earliest missing period is
   otherwise indistinguishable from the start of the books.
4. **The second instance runs no bank automation.** Its outbox dispatch, tie-out, FX revaluation
   and other bank schedulers are disabled or pointed at its own topics by its deployment config;
   the ledger has no Kafka consumers, so nothing reaches it except what is posted to it.

## Alternatives considered

- **An entity (book) column on the bank's ledger** — `gl_accounts`, journals, closes, period lock
  and year close all keyed by entity. Rejected: it changes every money-path query and invariant
  of the bank's GL to host someone else's books, it is the tenant dimension ADR-0152 forbids, and
  segregation would then rest on every query remembering the filter.
- **An external accounting system adapter** — the pension company keeps its books elsewhere and
  tax-reporting reads an export. Viable for a group that already runs one, and still possible
  behind the same port, but it leaves the reference platform with no books for the entity
  ADR-0334 makes it host. Not chosen as the default.
- **A pension-company ledger built into pension-service** — rejected: a second GL implementation,
  without the attested close, period lock and evidence the ledger already has.

## Consequences

**Positive**
- PSP 10-12 PS, PSP 20-12 PS and PEF 12-04 PS assemble from attested evidence of the right entity.
- No change to the bank ledger, its money-path code or its threat model.

**Negative**
- One more ledger deployment to run (database, backups, close calendar) per legal entity.
- A balance read costs one evidence request per prior year plus one per month of the year.
- PSP 34-12 PS (the company's own portfolio: instruments and their count) is NOT a ledger fact and
  stays unsourced; it needs a holdings source for the company's own investments.

**Neutral**
- Enforcement is structural: there is no shared schema to leak across. No gate is added; the
  existing `frozen-trial-balance` fail-closed contract is the control on the read side.

### Delivery check

`grep -rl "openbank-ledger-service" openbank-infra/gitops/components/pension*` lists a ledger
deployment for the pension company, and tax-reporting's gitops env sets
`OPENBANK_STATUTORY_RETURNS_COMPANY_BOOKS_OPENED`. Today both print nothing: the code path is
delivered (`CompanyBooksCalculator`, golden IT), the instance is not — hence `partial`.

## Compliance impact

- PCI DSS: not applicable — no card data.
- DORA:    not applicable — no change to ICT risk controls beyond one more deployment of an existing service.
- GDPR:    not applicable — company books carry no personal data.
- PSD2:    not applicable — no payment or account-access interface involved.
- CNB:     the pension company's ČNB balance-sheet and P&L returns (ADR-0336) are sourced from its own attested books.

## References

- ADR-0334 §2, ADR-0336, ADR-0096 D1, ADR-0097, ADR-0152
- `openbank-ledger-service/src/main/resources/db/migration/V1__init_ledger.sql`, `V22__closed_period.sql`

## Amendment 2026-10-10: PSP 34-12 PS needs a holdings source, and treasury is not one yet

The Consequences above leave PSP 34-12 PS (the company's own portfolio: instrument class, ISIN,
quantity, valuation at period end) unsourced. We evaluated the obvious candidate, a second
deployment of `openbank-treasury-service` (ADR-0315) for the pension company, the same pattern
this ADR applies to the ledger. It cannot answer the return today:

- **No securities product.** `ProductType` is `MM_PLACEMENT`, `MM_BORROWING`,
  `CNB_DEPOSIT_FACILITY`, `CNB_LOMBARD`, `FX_SPOT`. ADR-0315 D2 plans `BOND_PURCHASE` /
  `BOND_SALE` but neither exists, and no equity or fund-unit product is planned.
- **No instrument identity or quantity.** A `Deal` carries `principal` and `rate` (ACT/360
  money-market terms). No migration V1–V14 has an ISIN, an instrument class or a unit count.
- **No GL accounts to post securities to.** The posting map (`Postings.kt`) knows nostro,
  placement, ČNB facility, borrowing, accruals and FX position. ADR-0315 D5's "bonds by IFRS 9
  category" accounts were never added to the ledger.
- **No valuation source anywhere in the fleet.** Treasury's only market input is the risk
  engine's curve set (rates, not security prices). `openbank-wealth-service` fetches no prices
  (customer-declared figures only), and `openbank-pension-fund-service` holds the funds' own ISIN
  and NAV, not the instruments the funds or the company invest in.

Making treasury derive holdings from deals therefore needs, in order: securities products on the
`Deal` aggregate (ISIN, instrument class, quantity, clean price), the ledger GL accounts and
posting rules for them in each entity's ledger, and a price source. That is a money-path feature
of ADR-0315, not a read model.

### Decision (amended)

1. **The holdings source of record for PSP 34-12 PS is the depositary's period-end statement of
   holdings** (ISO 20022 `semt.002`). The pension company's own investments sit with a custodian,
   and the custodian's statement already carries ISIN, quantity and a valuation at the statement
   date. A `treasury-pension-co` deployment ingests it the way treasury already ingests nostro
   `camt.053` statements (ADR-0315 D7): an idempotent upload keyed by account and statement date,
   one immutable snapshot per date.
2. **The read contract stays as agreed with tax-reporting:**
   `GET /api/v1/treasury/portfolio/period-end?date=YYYY-MM-DD` answers the snapshot for exactly
   that date (`asOf`, `currency`, positions of `instrumentClass`, `isin`, `quantity`,
   `valuation`, `valuationCurrency`, decimals as strings), OPA action `treasury.portfolio.read`
   granted to tax-reporting's service account by `principal.id` only. With no snapshot for the
   date it answers **409**, never an empty list: an empty portfolio and a missing statement must
   not look the same, the same fail-closed rule as the frozen trial balance in Decision 2.
3. **Instrument class is mapped, not guessed.** `semt.002` carries a CFI code per ISIN (ISO
   10962); the ČNB class is a declared CFI-prefix mapping in configuration, and an unmapped CFI
   refuses the snapshot at ingestion.
4. **Deals-derived holdings come later and must reconcile to the statement.** When ADR-0315 gains
   securities products, holdings derived from settled deals become a second view, reconciled per
   ISIN and quantity against the custodian snapshot like nostro breaks. The statement stays the
   source of record for the return.
5. **The `treasury-pension-co` instance runs no bank automation**, as Decision 4 requires of the
   ledger: simulated market, interest accrual, nostro break sweep and outbox dispatch to the
   bank's topics are off, and any GL posting points at `ledger-pension-co`, never the bank's
   ledger.

### Alternatives considered (amendment)

- **Build securities deals in treasury first, then derive holdings.** The correct end state for
  the deal lifecycle and GL, but it adds three missing pieces (products, GL accounts, prices)
  before the return gets any answer, and still needs a price source that does not exist.
- **Store the portfolio in pension-fund-service.** Rejected: that service administers the
  participants' funds. Putting the company's own assets there breaks the segregation ADR-0334
  §1 and this ADR's Decision 1 rely on.
- **Hand-entered positions in tax-reporting.** Rejected: the return would be its own source
  system with no evidence behind it.

### Delivery check (amendment)

`grep -rn "portfolio/period-end" openbank-treasury-service/src/main/resources/openapi.yaml` and
`ls openbank-infra/gitops/components | grep treasury-pension-co` both print nothing today.
PSP 34-12 PS stays unsourced, and tax-reporting must keep refusing it, until both print a line.
