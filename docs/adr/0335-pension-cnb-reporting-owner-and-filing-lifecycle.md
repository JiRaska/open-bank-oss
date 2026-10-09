---
date: 2026-10-10
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [pension, regulatory-reporting, compliance]
summary: "Own PSP and PEF return records on the pension-provider side, reuse the proven filing lifecycle, and make deadlines and return definitions versioned jurisdiction data."
---

# ADR-0335 — Pension ČNB returns: owner, versioning and filing lifecycle

## Context

ADR-0334 separates the participant side (`pension-service`) from the provider/fund side
(`pension-fund-service`). Its own-provider mode entails recurring company and per-fund reports to
ČNB. The [source-checked inventory](../research/cz-pension-regulatory-reporting.md) distinguishes
monthly PSP, quarterly PEF, annual PEF and company/fund-specific scopes. No pension runtime module
exists on `main` yet. `tax-reporting-service` has an `OPEN → ASSEMBLED → FILED` record, maker/checker
separation, deadline and submission reference (ADR-0180), but it is explicitly a §38d/FÚ tax context;
`finrep-service` is bank capital reporting. Putting pension records into either table would mix
different legal entities and regulator workflows.

## Decision

This is a **proposed implementation decision** until reviewed. The pension-provider side owns the
return ledger for both the pension company and each managed fund. Extend the planned
`pension-fund-service` instead of creating a third pension reporting service. It assembles through
read-only extraction ports implemented against the participant, fund, accounting and investment
sources. Source read models and adapters are separate follow-ups; an absent source fails validation.

Reuse the `TaxFilingRecord` lifecycle *semantics*, with a small generic filing core extracted from
that model only when a second concrete use exists. Do not move the tax aggregate or its FÚ fields
into the pension context. A pension return is keyed by `(legal entity, fund if applicable,
return code, reference period, revision)` and pins the effective-dated Czech jurisdiction pack,
ČNB methodology version, extraction snapshot IDs and canonical content digest. A changed pack or
source snapshot makes an existing approval stale; it cannot silently rewrite an assembled return.

The state machine is `OPEN → ASSEMBLED → VALIDATED → APPROVED → SUBMITTED`. Assembly freezes source
IDs, definition version and content digest. Validation must prove every required source, reporting
unit and control total present. Two distinct authorised human identities approve the **same digest**
after validation; neither may be the assembler. Submission recording requires a receipt/reference,
submitted timestamp, submitted digest and channel. This is evidence of an actual external submission,
not an automatic transition after approval. Corrections create a linked new revision, preserve the
original receipt and its digest, and must identify affected later returns. A submission artefact
cannot be marked SDAT-compatible while its cell-level specification remains unverified.

Deadlines are data in the reviewed pack, bound to return code **and** reporting unit. In particular,
PSP 34-12 has a monthly 20-day deadline for each fund but a quarterly 30-day deadline for the
company. The deadline monitor enumerates expected returns from the actual licensed company and
managed-fund roster, including returns not yet opened; counting only persisted `OPEN` records would
hide missing returns. A low-cardinality breach gauge includes return code, reporting-unit *type*,
jurisdiction and period, never personal or fund identifiers. The live SDAT obligation register may
override a pack only through a reviewed, recorded entity-specific obligation change.

## Delivery order and proof

1. Land this inventory/decision with source review. Reverify primary law and current SDAT
   methodology before activation; 425/2012's linked consolidated PDF states 2021, 314/2013's
   states 2025, while the published pension methodology plan lists `PEF20260101`.
2. After the pension provider service exists, extract the reusable lifecycle invariant and add the
   first PSP/PEF definition pack with effective dates, reporting-unit scope and deadline rules.
   Tests must distinguish company and fund PSP 34-12 cadence, leap/year boundaries, missing funds,
   and stale pack revisions. No dummy zero rows or assumed SDAT cells.
3. Implement durable assembly, source-snapshot attestation, validation, two-person approval,
   immutable revision/receipt history and deadline gauge. Prove against a real database and
   authenticated HTTP path, including concurrent approvals and rejection of changed digests.
4. Connect independently verified source read models, then separately verify wire rendering and
   submission/acknowledgement against ČNB's current SDAT specification.

No deadline is inferred for annual reports or tax administration forms until their separate legal
basis and recipient rules are verified. ADR-0334 calls transformed funds out of platform scope,
while 425/2012 covers managed transformed funds. An own-provider deployment that actually manages
one must include it in the reporting roster; otherwise this gap blocks a claim of complete ČNB
reporting. Distributor mode records the partner provider as the filing owner and does not create
our own PSP/PEF obligations without a legal basis.

## Alternatives

- Add tables to `tax-reporting-service`: rejected because its current §38d aggregate, API and FÚ
  workflow do not own ČNB reporting or segregated pension fund data.
- Add a dedicated pension reporting service: deferred; the provider-side fund service is already
  planned and owns the entity roster and NAV evidence. Split later only with measured operational
  isolation need.
- Use `finrep-service`: rejected because that is the bank's reporting entity and taxonomy, while
  pension company and funds must be separately reported.

## References

- [ADR-0334](0334-pension-fund-platform.md)
- [ADR-0180](0180-withholding-tax-statutory-filing-38d-vy-tov-n-owner-cadence-transport.md)
- [ČNB 425/2012 consolidated text](https://www.cnb.cz/export/sites/cnb/cs/legislativa/.galleries/vyhlasky/vyhlaska_425_2012_uplne_zneni_k_20210101.pdf)
- [ČNB 314/2013 consolidated text](https://www.cnb.cz/export/sites/cnb/cs/legislativa/.galleries/vyhlasky/vyhlaska_314_2013_uplne_zneni_01_07_2025.pdf)
- [ČNB pension reporting method](https://www.cnb.cz/cs/statistika/metodicke-informace/penzijni_spolecnosti/)
