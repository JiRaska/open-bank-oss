# Withholding remittance and annual Vyúčtování — evidence and transition proposal

Status: implementation proposal for #12429; requires tax-owner approval before changing filing behavior or reporting past submissions. This note corrects the cadence premise of ADR-0180 without reclassifying any historic record.

## Verified rules

- The current [daňový řád, § 137(2)](https://e-sbirka.gov.cz/sb/2009/280) requires Vyúčtování within three months after the calendar year. Section 33(4) moves a deadline falling on a weekend or holiday to the next working day. The [Financial Administration's 2026 calendar](https://financnisprava.gov.cz/cs/dane/dane/dan-z-prijmu/fyzicke-osoby/obecne-informace/danovy-kalendar.aspx?month=4&year=2026) lists the 2025 withholding Vyúčtování on 1 April 2026.
- [The same calendar](https://financnisprava.gov.cz/cs/danovy-kalendar?month=3&year=2026) lists the February 2026 withholding **cash remittance** on 31 March 2026. Cash and return have distinct periods, records, deadlines, and evidence.
- [Daňový řád §§ 138 and 141](https://e-sbirka.gov.cz/sb/2009/280) distinguish replacement before the regular deadline from an additional Vyúčtování after it. [The Financial Administration's form instructions](https://financnisprava.gov.cz/cs/dane/danove-tiskopisy?rok=2025) also recognize partial-year returns in exceptional circumstances; a tax owner must approve their exact handling.

## Current data semantics

- ADR-0180 and `FilingPeriod` call a calendar month the §38d filing period and assign next-month due date. `/api/v1/tax/filings/{YYYY-MM}/filed` records a reference for that month. The schema enforces uniqueness by `(period_year, period_month)` and has no revision key. These are not sufficient to evidence an annual return or corrections.
- `tax_observed_remittance` is keyed by the producer's batch UUID and preserves source month and total. The event `interest.withholding.remitted.v1` is emitted when interest-service **assembles** the monthly batch, before the separate settlement consumer books the cash. Its payload has a batch ID and total, but no individual withholding IDs or settlement reference. Therefore it cannot alone prove cash payment or complete annual source detail.
- An old monthly `tax_filing.status = FILED` means only that the current API recorded an operator reference against a monthly record. It must not be relabelled as an annual return, even when twelve monthly records exist.

## Additive transition

1. Inventory monthly rows and source events in each environment before migration: period, status, reference, actor, timestamps, batch IDs, totals, and actual settlement outcome. Escalate any `FILED` monthly rows to the tax owner to determine what was submitted. Do not fabricate an annual submission from them.
2. Keep `tax_observed_remittance` and the old `tax_filing` table readable as **legacy monthly observations** with unchanged IDs and references. Introduce separate annual return and revision records keyed by tax year, return kind, revision number, and (for an approved exception) actual covered dates. Use a source-link table keyed by batch UUID, a frozen source digest, distinct assembler and approver/submitter identities, and a submission reference recorded only after external submission evidence. Corrections append revisions; never overwrite the prior attestation.
3. Add a separately sourced settlement-evidence record keyed by batch UUID and a reconciliation state. The existing pre-settlement event may contribute the expected amount, but may never set `PAID`. Verify the settlement source contract and idempotency before implementing this link.
4. Expose annual endpoints and OpenAPI with explicit year, revision, source completeness, settlement reconciliation, EPO capability and submission evidence. Keep legacy monthly GETs during transition; gate or retire legacy monthly `filed` writes only with an operator migration plan. Never silently redirect a monthly write to an annual return.
5. Backfill annual **drafts** only after all twelve months (or an approved partial-year interval) have been reconciled and source completeness checked. Backfill never sets `FILED`. Compare annual totals to the individual monthly batches and settlement evidence; discrepancy blocks approval. Require distinct human actors for assembly and approval/submission.
6. Repoint deadline metrics to annual returns, adding a separate cash-remittance overdue metric. Observe both during a dual-read window; then retire misleading monthly-filing alerts after the owner confirms that no workflow depends on them.

Rollback: retain legacy tables/API and do not delete source rows. New annual tables are additive and can be disabled without reverting old observations. Once annual submission references exist, preserve their records and audit trail; roll back API traffic or rendering, not the evidence. Reconcile both models before any later cleanup.

## Decisions required before code that files or marks payment

- Tax owner: exact §38d scope, form year, tax authority identity, exceptional partial-year periods, deadline calendar, and treatment of historic monthly `FILED` references. This includes whether any existing monthly reference is only a cash-payment confirmation.
- Producer owner: immutable source for each withholding item and authoritative successful cash-settlement evidence. The current batch event alone is insufficient for either individual item lineage or cash proof.
- Reporting owner: correction model under §§ 138/141, EPO schema and receipt evidence, four-eyes roles, and retention of submitted revisions.

No pension withholding source should be connected until #12427/#12388 separately verify taxable payout classes and actual cash movement.
