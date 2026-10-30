---
date: 2026-10-15
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [tax, regulatory-reporting, privacy-gdpr, compliance]
summary: "CRS/FATCA reporting is a new return family in tax-reporting-service — the ADR-0033 G7 reporting home — reading party, account and income facts per period behind a purpose-limited TIN boundary; no service improvises its own reporting."
followup: "#12933 — the reporting build this ADR decides"
---

# ADR-0340 — CRS/FATCA information-exchange reporting is a tax-reporting-service capability and the sole TIN consumer

## Context

ADR-0339 section C decided *what* CRS/FATCA automatic exchange of information is —
a **reporting capability** over party + account + income data, not a product property
and not a responsibility of any income-producing service — and recorded that the
capability does not exist. The facts on the ground today:

- **The flags exist and nothing consumes them.** `openbank-party-service` stores the
  self-declared `taxResidencies` (ISO 3166-1 alpha-2 countries with TINs, CZ excluded
  from TIN by validation) and `usPerson`, and derives `CrsStatus` / `FatcaStatus` in
  `PartyAmlProfile` (migration V2 added the columns, V25 the profile table). The fleet
  honestly holds reportable-person flags with no filing capability behind them.
- **The reporting home already exists.** `openbank-tax-reporting-service` is the
  ADR-0033 G7 reporting capability in operation: it assembles the §38d withholding
  remittance (Vyúčtování) from withholding events (`WithholdingRemittedConsumer`),
  runs a generic statutory-returns engine (`ReturnDefinition` with datapoints and
  `SumEquals` / `NonNegative` validation, `ReturnCatalogue`, the
  ASSEMBLED → APPROVED → SUBMITTED lifecycle, breach list), renders EPO XML, and
  already serves one return family (pension regulatory returns) on that machinery.
- **The TIN boundary is written but not yet crossed.** ADR-0339: TINs never leave
  party-service — *except* that "a consumer that needs them is a reporting consumer".
  No reporting consumer exists, so today nothing reads TINs at all. CRS/FATCA filings
  legally require the TIN (or a permitted substitute) of each reportable person, so
  the boundary needs an explicit, purpose-limited gate, not an ad-hoc read.
- **The reporting input is period state, not the claim snapshot.** The
  `interest_accruals.claimed_*` freeze from ADR-0033 exists so a crash-retry replays
  the identical *withholding* decision; it is never re-read as current state and must
  not become a reporting source either — reporting reads each authoritative service
  for the reporting period.

## Decision

### A. The capability homes in tax-reporting-service — a new CRS/FATCA return family, not a new service

CRS and FATCA filings are built as return families on the existing statutory-returns
machinery in `openbank-tax-reporting-service` — catalogue-driven definitions,
datapoint validation, the ASSEMBLED → APPROVED → SUBMITTED lifecycle and the breach
list — next to the pension returns and the §38d filings. No new service is created;
neither party-service nor any income-producing service gains reporting endpoints.

### B. Assembly reads authoritative sources per period and freezes at ASSEMBLED

A CRS/FATCA return assembles its lines by reading, for the reporting period: party
residency, `CrsStatus` / `FatcaStatus` and TIN from party-service; account holdings
and period-end balances from account-service; income aggregates (interest credited
today, dividends and redemption proceeds as those domains arrive) from the income
events the fleet already emits. The residency/TIN/account snapshot freezes when the
return transitions to ASSEMBLED — the same lifecycle point that already freezes the
return's totals — so what is reviewed and submitted is exactly what was assembled.

### C. The reporting capability is the sole TIN consumer — a purpose-limited, audited boundary

The reporting assembly crosses the ADR-0339 TIN boundary through one outbound port,
with three constraints: (1) the read is purpose-limited to CRS/FATCA report assembly
(service identity + explicit scope, enforced in policy, audited); (2) TINs persist
only inside assembled report lines — the legal artefact itself — never in a general
residency cache or secondary table; (3) retention follows the filing obligation and
statutory archive rules, after which report contents age out. Withholding and every
other consumer continue to read residency countries only.

### D. Formats and cadence are catalogue data, not code forks

The OECD CRS XML schema and the FATCA XML (per the Czech implementation of the IGA /
EPO transport) are catalogue entries — return definitions with their datapoints,
validation rules, annual periodicity and deadline — rendered through the existing EPO
export infrastructure. A format revision is a catalogue revision, not a service fork.

### E. Out of scope for v1

- **Due diligence / self-certification** — owned by party-service onboarding; this
  capability consumes its output, it does not change it.
- **Withholding changes** — ADR-0033 / ADR-0339 already ship that; reporting reads
  the same income events, never the tax decision.
- **Amended / correction filings** — a follow-up once v1 files; the lifecycle has the
  room (a new revision of the same return), this ADR does not design it.

## Alternatives considered

- **A new dedicated crs-fatca-service.** Pros: a clean room, single-purpose. Rejected:
  it would re-implement the return catalogue, datapoint validation, lifecycle and
  breach machinery that tax-reporting-service already operates in production, and the
  fleet does not need another service for a filing family that shares the assemble →
  approve → submit governance with pension returns and §38d.
- **Reporting owned by party-service.** Pros: the data is already there. Rejected:
  party-service owns authoritative facts, not derived filing artefacts; tax-administration
  schema churn and filing governance do not belong in the authoritative party store,
  and the read side would still need account + income data party-service must not hold.
- **Each income service reports its own credits** (interest-service emits CRS for its
  accounts). Rejected in ADR-0339 C and unchanged here: reportability is a
  cross-cutting party × account × income determination; per-service reporting
  double-counts or misses accounts and has no single residency snapshot.
- **Stretching the §38d filing endpoints to carry CRS.** Rejected: §38d is the
  remittance of *withheld tax* — a payment with a Vyúčtování; CRS/FATCA is information
  exchange with no money movement, a different legal basis and schema lifecycle.
  Same service, different return family.

## Consequences

**Positive**
- The `CrsStatus` / `FatcaStatus` flags finally have a consumer; the bank can state a
  filing capability exists instead of flags without reporting.
- One filing surface, one audit trail, one governance path (assemble → approve →
  submit with breach visibility) for every regulatory return the bank files.
- The TIN boundary becomes an enforced, auditable gate instead of a prose rule — and
  every other consumer stays on residency-countries-only by construction.

**Negative**
- `openbank-tax-reporting-service` now stores TINs in assembled report lines — the
  GDPR Article 30 record, access auditing and retention schedule all grow accordingly,
  and the service joins the set of stores that hold especially sensitive identifiers.
- New read dependencies (party-service, account-service) at assembly time; a dependency
  outage must fail assembly visibly rather than emit a partial report — another
  fail-safe to design and test.
- Filing accuracy is bounded by declaration quality: a client who mis-declares
  residency produces a mis-filed report; the capability surfaces discrepancies, it
  does not adjudicate them.

**Neutral**
- Pension returns, corporate register and §38d filings are untouched; the catalogue
  gains entries.
- Withholding (ADR-0033, ADR-0339), remittance (ADR-0038) and the §38d filing cadence
  (ADR-0180) do not change; this ADR adds the information-exchange filing alongside them.

## Compliance impact

- PCI DSS: not applicable — no cardholder data involved.
- DORA: new inter-service read dependencies (party, account) at assembly — resilience
  (timeout, retry, visible failure) is part of the build; no ICT operational-risk
  architecture change beyond that.
- GDPR: TINs and tax residencies are personal data; this ADR creates a new processing
  purpose (reporting assembly under the legal-obligation basis), restricts it by
  purpose limitation and audited access, and pins retention to the filing obligation
  plus statutory archive — the Article 30 record must be extended accordingly.
- PSD2: not applicable.
- CNB: a Czech bank is a reporting institution under the automatic tax-information
  exchange regime (CRS-style MCAA and FATCA-style IGA); this ADR records where the
  capability will live and that today no filing capability exists — it claims nothing
  is filed until the return family ships.

## References

- ADR-0033 — Withholding tax on credit interest at capitalization (finding G7: the
  reporting capability this ADR extends)
- ADR-0038 — Withholding tax remittance and lifecycle
- ADR-0180 — Withholding-tax statutory filing (§38d Vyúčtování) cadence and transport
- ADR-0339 — Tax residency, information exchange and product taxation (section C:
  exchange is a reporting capability; the TIN boundary the reporting port crosses)
- `openbank-party-service` — `PartyAmlProfile` (tax residency declaration, TINs,
  `CrsStatus` / `FatcaStatus` derivation), migrations V2 / V25
- `openbank-tax-reporting-service` — statutory-returns engine (`ReturnDefinition`,
  `ReturnCatalogue`, ASSEMBLED → APPROVED → SUBMITTED), §38d filings,
  `WithholdingRemittedConsumer`, EPO XML export
- Issue #12933 — the actionable build this ADR decides
