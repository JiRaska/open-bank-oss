---
date: 2026-10-14
decision-status: proposed
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [tax, compliance, product-catalog, aml-sanctions]
summary: "Tax residency is a party attribute owned solely by party-service, product-yield taxation is decided by a pure policy at the income event, and CRS/FATCA exchange is a reporting capability — none of it is a product property."
followup: "none — the unbuilt halves are already tracked elsewhere: account-to-party tax resolution is the documented fast-follow of ADR-0033 and CRS/FATCA reporting is its G7 reporting capability."
---

# ADR-0339 — Tax residency, information exchange and product taxation are party and event attributes, not product properties

## Context

Three related questions arrived from client work at once: (1) how a client's **tax
residency** is established and maintained, (2) how the bank **exchanges information**
about it (CRS / FATCA reporting to tax administrations), and (3) how **taxation of
individual products** (withholding on a savings account's interest vs. a term deposit's
interest, exemptions, gross vs. net crediting) is applied. The architectural question
behind all three: *is any of this a property of a banking product?*

The codebase already answers implicitly, grown rather than decided:

- **Residency lives on the party.** `openbank-party-service` stores the client's
  self-declared `taxResidencies` (list of ISO 3166-1 alpha-2 countries with TINs,
  CZ excluded from TIN by validation), `usPerson`, and derives `FatcaStatus` /
  `CrsStatus` in `PartyAmlProfile` (migration V2 added the columns, V25 the profile
  table). Residency is captured once per client at onboarding, not per product.
- **Taxation lives at the income event.** ADR-0033 withholds on credit interest at
  *capitalization*, decided by the framework-free `WithholdingTaxPolicy` from the
  beneficiary's `TaxProfile` (taxpayer type, residency, treaty rate, exemption),
  resolved through a `TaxProfilePort`. The product the account belongs to is not an
  input to the decision. ADR-0038 and ADR-0180 handle remittance and the §38d filing
  of what was withheld.
- **Exchange of information does not exist yet.** There is no CRS/FATCA reporting
  capability anywhere in the fleet — `crs_status` / `fatca_status` are flags computed
  from the declaration, and ADR-0033's G7 records the reporting build as a fast-follow
  that has not started.
- **The catalog's `TaxTreatment` is price semantics.** `openbank-product-catalog`
  carries `TaxTreatment { INCLUSIVE, EXCLUSIVE, EXEMPT, UNSPECIFIED }` on
  `PriceComponent` — whether a quoted price includes or excludes indirect tax. It is
  ceník terminology, unrelated to a client's income taxation, and the name invites
  confusion with everything above.

Nobody has written this boundary down, which is how the question "isn't this a product
property?" gets asked repeatedly and how a future PR ends up duplicating residency or
rates onto catalog items.

## Decision

### A. Tax residency is a party attribute — owned solely by party-service

A client's tax residency is a fact about the **client**, established by the self-declaration
in `PartyAmlProfile` and versioned there. It is stored exactly once, never copied onto
accounts, products, or contracts. Other services read it through party-service APIs
(or a resolved `TaxProfile` via a port); they hold no authoritative copy. A change of
residency is a party-lifecycle event (re-declaration), not a product-lifecycle event.

Two boundaries keep that true in practice:

- **The sanctioned exception is the claim-time snapshot, not a copy.** The income-producing
  service freezes the *resolved profile* with the claim at the income event
  (`interest_accruals.claimed_*`, #1355) so a crash-retry replays the identical decision
  instead of re-resolving. That is the ADR-0033 idempotency mechanism for the **event's**
  decision input — the columns are never re-read as current state, never re-resolved from,
  and expire with the claim. They are not a second residency owner.
- **Consumers read only what the policy needs.** The party AML-profile API returns TINs
  alongside residency countries; withholding resolution consumes the residency countries
  only, and TINs are never persisted outside party-service. TINs exist for the CRS/FATCA
  reporting capability (C) — a consumer that needs them is a reporting consumer, not a
  tax-at-an-event consumer.

### B. Product taxation is a property of the income event and the beneficiary — not of the product

Whether a product's yield is taxed, at what rate, gross or net, is decided at the moment
the taxable income is produced (capitalization for interest, settlement for other yield),
by a pure domain policy (ADR-0033 shape), from the beneficiary's tax profile resolved via
an outbound port. The policy inputs are statutory (zákon č. 586/1992 Sb. §36 rates,
treaty rates) plus client-specific facts (residency, treaty rate on file, exemption with
evidence) — none of which vary by product revision, so none of it belongs in the catalog.

Resolution is a two-step read of party-owned facts, and its shape is fixed by what
party-service actually stores: the account's `partyId` and the party's `partyType` come
from the party record; **only then** does an `INDIVIDUAL` beneficiary read the AML
profile's residency countries (the profile is person-only — a legal entity has no profile
to read, and `taxpayerType` is decided by `partyType`, never by the profile). Two inputs
the policy supports are not stored anywhere today and are deliberately left on the
statutory-default path until they become party attributes: double-tax-treaty rates on file
(`treatyRate`) and the non-cooperating-state list (`nonCooperatingState`). If two products
ever genuinely differ in tax treatment, the difference is expressed as a
flag on the *event/product configuration* feeding the policy — e.g. the existing
`DEFERRED_FX` treatment for non-CZK interest — never as a duplicated tax table on the
product.

### C. CRS / FATCA information exchange is a reporting capability over party + account + income data

The obligation to report arises from the combination of a reportable party
(`CrsStatus.REPORTABLE`, `FatcaStatus.US_PERSON`), a reportable account, and reportable
income. It is therefore a **reporting capability** — a service that reads party residency,
account holdings, and income/ redemption events and emits the administration's format —
not a property of any of those things and not a responsibility of the income-producing
service's ledger. Until that capability lands (ADR-0033 G7), the fleet honestly holds
flags without reporting, and no service may improvise its own reporting from local copies.

### D. Catalog `TaxTreatment` keeps its price meaning and is not reused

`TaxTreatment` on `PriceComponent` remains ceník semantics (price quoted tax-inclusive /
-exclusive / exempt). It must not be read, extended, or renamed to carry client income
taxation; the two domains keep separate vocabulary.

## Alternatives considered

- **Tax residency as a catalog / product attribute** — per-product residency rules and
  reportability copied onto each product the client holds. Rejected: the same client
  holds many products, so the fact would be stored N times and drift; residency is
  client-inherent, and CRS reportability is derived from the party, not from what they
  bought. This is the "attribute of the product" hypothesis the client work asked about,
  and the answer is no.
- **Withholding rates and exemptions as product configuration** — a tax table per
  product revision. Rejected: rates are statutory and change by law (not by catalog
  revision), and treaty/exemption facts are client-specific, so a product-level table
  would be wrong for most holders of the same product.
- **Reporting owned by the income-producing service** — interest-service emits CRS
  reports for its own credits. Rejected: reportability is a cross-cutting determination
  (party × account × income) and each service would improvise a partial view, double-count
  or miss accounts, with no single place holding the residency snapshot the format
  requires.
- **Status quo (no ADR)** — the model as grown is sound, but undocumented; this ADR
  ratifies the emergent boundaries and is the record that closes the recurring question.

## Consequences

**Positive**
- One owner for residency (party-service) and one decision point for taxation (the
  policy at the income event) — no duplication to drift, one place to change when a law
  changes.
- The fail-safe property of ADR-0033 (never under-withhold) survives, because every
  consumer resolves through the port and no local copies can silently go stale.
- The catalog stays industry-neutral: products describe price, eligibility and
  packaging — not fiscal law.

**Negative**
- Until the ADR-0033 fast-follow lands, interest-service withholds on the fail-safe
  CZ-resident-individual default for every account — conservative, but not yet
  residency-accurate.
- CRS/FATCA reporting remains unbuilt; the flags exist but nothing consumes them. That
  gap is now explicit instead of implied.

**Neutral**
- Existing catalog `TaxTreatment` is untouched; the ADR only fences its meaning.
- Consumers needing tax facts (interest today; dividend/wealth services later) all
  depend on party-service availability, which the port contract already fails safe on.

## Compliance impact

- PCI DSS: not applicable — no cardholder data involved.
- DORA: not applicable — no ICT operational-risk change; reporting capability design
  will be assessed when the G7 build starts.
- GDPR: tax residencies and TINs are personal data processed under the AML/legal-obligation
  basis in party-service; this ADR adds no new processing, it forbids copying the data
  into further systems, which limits the Article 30 record surface.
- PSD2: not applicable.
- CNB: a Czech bank is a reporting institution under the international tax-cooperation
  regime (CRS / FATCA-style automatic exchange); this ADR records where the reported
  facts live and that the exchange capability itself is not yet built — it claims no
  filing capability exists today.

## References

- ADR-0033 — Withholding tax on credit interest at capitalization (policy, port, fail-safe)
- ADR-0038 — Withholding tax remittance and lifecycle
- ADR-0180 — Withholding-tax statutory filing (§38d Vyúčtování) cadence and transport
- `openbank-party-service` — `PartyAmlProfile` (tax residency declaration, FATCA/CRS
  derivation), migrations V2 / V25
- `openbank-interest-service` — `WithholdingTaxPolicy`, `TaxProfile`, `TaxProfilePort`,
  `DefaultTaxProfileProvider`
- `openbank-product-catalog` — `PriceComponent.taxTreatment` (price semantics only)
