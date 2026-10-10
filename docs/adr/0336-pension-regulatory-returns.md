---
date: 2026-10-10
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [regulatory-reporting, compliance, tax]
summary: "Pension-company ČNB returns (PSP/PEF) are versioned jurisdiction data run on tax-reporting-service's filing lifecycle — assemble, validate, four-eyes attest, record submission, deadline gauge — not a new reporting module."
---

# ADR-0336 — Pension regulatory returns on the statutory-filing lifecycle

## Context

ADR-0334 adds a penzijní společnost (PS) and its funds. The research note
`docs/research/cz-pension-regulatory-reporting.md` (#12422) lists the PS's reporting duties. The
periodic ones are the ČNB PSP (ČNB) returns under vyhláška 425/2012 Sb. (monthly, quarterly and
annual, for the PS and for each fund) and the ČNB/ECB statistical PEF (ČNB) returns under vyhláška
314/2013 Sb., as amended by 217/2018 Sb. In total that is roughly a dozen return types, each with a
statutory deadline, and they multiply by the number of funds.

The repo already has two reporting shapes:
- `openbank-finrep-service` (ADR-0097) is stateless. It derives FINREP/COREP from the attested
  ledger close and keeps no record of what was filed.
- `openbank-tax-reporting-service` (ADR-0180) is a system of record for a statutory filing. A
  period goes OPEN, then ASSEMBLED with frozen totals, then FILED with the submission reference. It
  enforces four-eyes (the assembler cannot file), refuses to re-total a period once assembled, and
  reports a filing as overdue against its statutory deadline. Transport is human: no public
  real-time API exists, so an operator submits the return and records the reference.

The PS returns need exactly the second shape. They are also not bank-prudential, which finrep's
data source (the bank ledger close) assumes.

## Decision

We will host pension regulatory returns in `openbank-tax-reporting-service` as a generic
**statutory return** aggregate, next to the §38d filing and on the same lifecycle rules. We will
not build a new module.

1. **Returns are data, not code.** Each jurisdiction ships a versioned catalogue resource
   (`statutory-returns/<jurisdiction>/<catalogue>.v<N>.json`). The catalogue gives each return's
   code, its scope (the company, or each fund), its periodicity, the deadline expressed as days
   after the period end or a fixed month-day, its required datapoints, and its validation rules
   (`NON_NEGATIVE`, `SUM_EQUALS`). Domain Kotlin holds no country literal. A new jurisdiction or a
   new vyhláška version is a new resource.
2. **Lifecycle:** ASSEMBLED → APPROVED → SUBMITTED.
   - *Assemble* pulls the datapoints for (return, period, entity) through a data-extraction port
     and validates them. A return that fails validation is refused and never stored half-valid.
   - *Approve* is four-eyes: the approver must differ from the assembler. Approval records an
     **attestation**, a SHA-256 over the canonical content.
   - *Submit* records the regulator reference. It is refused if the content no longer matches the
     attested hash. Corrections (vyhláška 425/2012 §7) are a new revision, never an edit.
3. **Deadlines** come from the catalogue. A return not SUBMITTED by its due date counts as a
   breach. The gauge `openbank_statutory_return_overdue` is refreshed by a scheduler, and it
   counts expected-but-never-assembled returns as well as unsubmitted ones, because the silent
   failure is the return nobody started.
4. **Data ports.** `PensionReportingDataPort` is the extraction seam. pension-service and
   pension-fund-service do not yet expose the aggregates the returns need (balance sheet per fund,
   unit statistics, flows by type, holdings). Until they do, the bound adapter reports every
   datapoint as **unavailable**, so assembly fails loudly and never files zeroes. Follow-ups track
   the read models.
5. **Wire format.** The ČNB SDAT data-file dictionary for PSP/PEF was not verified from a public
   source. Like ADR-0180's EPO port, the renderer is an explicit unavailable port, and the API
   reports that it is unavailable. The assembled datapoints are the filing figures. An operator
   submits them and records the reference.

## Alternatives considered

- **Extend finrep-service.** It owns supervisory returns. However, it is deliberately stateless
  with no datastore, and it derives everything from the bank ledger close. Four-eyes approval,
  attestation and an overdue register all need durable state. Adding a database to finrep would
  change its whole posture, and PS figures do not come from the bank close. Rejected.
- **A new `openbank-pension-reporting-service`.** This would duplicate the filing lifecycle,
  four-eyes and overdue logic that ADR-0180 already built and tested, and it would add another
  undeployed released component. Rejected in favour of reuse.
- **Hard-coded return classes per výkaz.** Rejected by the ADR-0334 rule that jurisdiction rules
  are effective-dated data packs.

## Consequences

**Positive**
- One lifecycle, one four-eyes control and one overdue gauge for every statutory filing.
- New returns, and later vyhláška amendments, are reviewed data changes.

**Negative**
- The service name ("tax") understates its scope. A rename is a separate decision.
- tax-reporting-service is not deployed (#5760), so these returns run nowhere until that is
  decided.
- No returns can be assembled until the source read models exist.

**Neutral**
- §38d withholding on pension payouts stays with ADR-0180's filing. It needs a pension withholding
  event, filed as a follow-up.

## Compliance impact

- PCI DSS: not applicable, no card data.
- DORA: not applicable, no ICT-risk control changes.
- GDPR: aggregated returns carry no personal data. PEF 15-01 (participant statistics) is aggregate
  only.
- PSD2: not applicable, no payment-service change.
- CNB: supervisory and statistical reporting by a penzijní společnost under vyhláška 425/2012 Sb.
  and vyhláška 314/2013 Sb.

## References

- `docs/research/cz-pension-regulatory-reporting.md`
- ADR-0180 (statutory filing lifecycle), ADR-0097 (FINREP/COREP), ADR-0334 (pension platform)
- Issue #12422, umbrella #12350
