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
