---
date: 2026-11-15
decision-status: proposed
delivery-status: planned
authors: [Codex]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture, compliance, regulatory-reporting, libs]
summary: "CZ-to-EU expansion uses one market-admission foundation, explicit FOS or branch booking context, and approved country packs. Shared library types and deterministic checks support domain-owned rules; each market launches only on evidence."
---

# ADR-0380 — EU passporting through a common foundation and country packs

## Context

OpenBank needs to support a Czech-authorised credit institution operating in any other EU
Member State through either freedom to provide services (FOS) or an establishment (BRANCH).
SK/FOS and AT/BRANCH are the first validation scenarios, not hard-coded product variants.
The scope is the same Czech legal entity in both cases. A branch is an establishment of that
entity; a subsidiary with its own licence is outside this decision. Non-EU parents, EEA-only
states and non-bank licences require separate applicability decisions.

The code assessment started at `0ed07139a6b6d087438248c7dc9bbeb7443e769a` and the documentation
branch was updated to `b777e9d9d639800aa26f4b391d68c34ac5e90392` on 2026-11-15. This is repository
evidence, not a deployed-environment assessment or proof of a bank's licence:

| Existing seam | Reuse and remaining boundary |
|---|---|
| `MarketContext` in product-catalog; published revision pinning in account-service | Countries/locales can be authored, but the account-opening pin checks product status, currency and revision without a market-authorisation decision. |
| `Account`, `JournalEntry`, `GlAccount` | Multi-currency primitives exist; these models do not carry a regulatory branch/book context. The legacy account migration's `branch_code` alone is not an enforced branch model. |
| KYB `CountryPackRegistry`; lending compliance packs | Country-specific rules have an existing home. The checked-in KYB index lists CZ and GB; lending supplies a CZ consumer-credit pack. Identifier recognition is not a complete SK/AT onboarding pack. |
| `WithholdingTaxPolicy` | The current Czech implementation returns `DEFERRED_FX` for non-CZK interest. A EUR launch needs a completed, evidence-backed tax route. |
| `CrsFatcaReturnDataAdapter`, `UnavailableReturnWireRenderer` | Reporting lines exist; the adapter is single-company and the generic regulator wire renderer is unavailable. Older ADR statements that reporting is entirely absent are not the implementation baseline. |
| `GsftSnapshotAdapter`, `GsftReturnDataAdapter` | Assembly requires a complete snapshot; unresolved input gaps are explicitly retained. Having a renderer does not prove an executable depositor-data delivery. |
| ADR-0317 shared-library boundaries; ADR-0367 obligations; ADR-0379 posture proposal | Reuse dependency rules and the obligation/control/evidence vocabulary rather than introducing another compliance dashboard or universal rules engine. |

CRD Articles 33, 35–36 and 39 distinguish the permitted activities and notification routes;
passporting does not remove applicable host-country obligations. A country pack is software
configuration and evidence, not a licence. The legal inventory must be reviewed for the intended
launch date; this ADR does not certify the law of all 26 other EU members.

## Decision

We will implement a common foundation for market admission and business attribution, with
separate FOS/BRANCH profiles and independently approved country/product packs. Every other
EU member is an addressable target; none is enabled merely by belonging to the EU.
The [implementation plan](../strategy/eu-passporting-implementation-plan.md) defines delivery slices.

### D1. Separate identity, market, booking and customer facts

Use stable, typed identifiers and explicit effective dates:

| Concept | Meaning and authority |
|---|---|
| `BankLegalEntityId`, `homeCountry` | The licensed bank, with CZ as the initial home; controlled institutional configuration, never a customer's company/party id. |
| `EstablishmentId`, `hostCountry` | The bank's home establishment or registered regulatory branch; branch belongs to exactly one bank. Physical offices are subordinate locations, not separate regulatory branches or banks. |
| `MarketKey` | Bank + host country + `FOS`/`BRANCH` + activity/product scope + customer category/channel. A grant for one mode or product never authorises another. |
| `BookingContext` | Bank + establishment + book, attached to the account/contract and its accounting events. FOS uses the approved home booking route; BRANCH uses the approved branch route. |
| `CustomerJurisdictionFacts` | References to verified residence, legal seat, tax residencies, classification and their evidence/as-of time. Party/KYC/KYB remain their owners. |
| `AdmissionDecision` | Operation, selected market, binding input digest, grant revision, pack-set hashes, decision time/expiry, reasons and evidence reference. Persisted with the accepted business action. |

Do not infer any of these from UI language, currency, IBAN prefix, IP geolocation or a JWT
claim supplied by an external caller. Residence, nationality, place of marketing, contracting
establishment and tax residence can differ. Conflicting or insufficient facts route to a
review with a reason; unknown never means CZ or permission to sell.

A single bank may serve the same host through both FOS and BRANCH. The server selects the
approved route before contracting. A later change of residence triggers reassessment;
moving an existing contract to a branch is an explicit, evidenced transfer, not a profile edit.

### D2. One authoritative market-admission boundary, enforced at commitment

Product-catalog owns a narrowly scoped **market-access registry** alongside its offerings:
references to the bank/establishment, passport scope and evidence, effective dates, applicable
pack-set, readiness attestations, and market state. It owns permission to offer/contract;
it does not become the system of record for tax facts, statutory filings or bank accounting.
Use existing proposal/approval infrastructure. Actual institutional identifiers and regulatory
correspondence are access-controlled runtime records, not public repository seed data.

A server-side admission API combines the approved registry with authoritative customer facts
and a product revision. Customer-edge may filter discovery, but account opening and lending
origination must enforce the same decision independently of the UI. Cover direct REST,
operator-assisted, agent/MCP and asynchronous routes; renewal and material amendments are
explicit operation types. Customer claims alone cannot select a less restrictive pack.

Admission is bound to the exact operation, subject, product revision, booking route and
idempotency key. A pending onboarding is not authority to execute: revalidate immediately
before legal commitment/activation, and again for a later disbursement where applicable.
Define a short validity window and authoritative revocation check. Catalogue issues a pending
permit; consuming that permit and checking the grant epoch occur in one catalogue transaction
serialised with suspension/revocation. That consumption is the admission fence. A permit revoked
before consumption cannot authorise commitment; one consumed before suspension may complete its
bound operation, with that ordering recorded. Services durably retain a pending operation before
consumption and finish it idempotently afterwards; an outbox/reconciler resolves crash windows.
No account/loan becomes live before consumption. The consumed permit is single-operation authority,
not a reusable token. An unbounded local cache cannot provide this guarantee.

Registry unavailability, missing evidence, an unsupported schema, or a missing/ambiguous pack
refuses new commitment with a stable reason. Replaying a completed idempotent request returns
its original result. It does not manufacture a new contract under today's rules.

### D3. Country packs compose explicit obligations, not executable arbitrary code

A `CountryPackManifest` binds a host country, mode, product/activity and customer scope to
immutable, domain-owned pack versions. The common envelope contains schema version,
effective interval, content hash, source references with reviewed/applicable dates, accountable
owners, required controls, compatibility range and evidence requirements. The pack-set is one
atomic activation; consumers must agree on its digest before admission is enabled.

Separate three layers: common platform invariants; reviewed EU/CZ-home obligations; host-country
obligations and operational adapters. The host layer cannot override a mandatory home/EU rule
by simple precedence. Domain owners publish explicit applicability and combination rules;
contradictions fail activation for legal review. Applicable mandatory changes can affect existing
contracts: pin the original terms and decisions, then append an effective-dated servicing-rule
assignment and notice evidence instead of rewriting contract history or freezing law forever.

A country manifest references:

- identity/representation and acceptable remote identification evidence;
- product availability, consumer/business scope, disclosures, language and durable-medium templates;
- AML/CFT responsibilities, local FIU routing and record-retention obligations;
- tax classification, source/treaty rules, forms, remittance and information exchange;
- statutory/statistical reporting scope, thresholds, schemas, deadlines and recipient adapters;
- deposit-guarantee disclosures and payout-cooperation requirements;
- payment/IBAN schemes where needed, calendars, cut-offs and time zones;
- complaints, dispute resolution, accessibility, servicing, arrears and termination obligations;
- outsourcing, privacy, operational resilience and any local record-access obligations.

Country-specific rates, thresholds and legal assertions stay in reviewed domain packs. If a
jurisdiction cannot be represented by an existing typed schema, extend that domain schema and
its tests first; do not embed a script or copy a service. Packs may reference purpose-built
registry/submission adapters. Not every new country is necessarily a data-only release.

### D4. Share the contract and deterministic mechanics in openbank-libs

Introduce **`openbank-libs-market`**, a framework-free bounded-context module following
ADR-0317. Its first production consumers are product-catalog and account-service in the same
vertical delivery slice; lending then adopts it. The additional module is justified by a
cross-service admission contract and policy churn that must not rebuild every core consumer.
It must not be created as an unused abstraction-only release.

| Location | Shared responsibility | Explicit boundary |
|---|---|---|
| Existing `openbank-libs-domain` | Reuse `Money`, identifiers where compatible, `BusinessCalendar`, person-bound `MakerChecker` and `DecisionRecord`. | Do not move market policy, tax tables or institutional registries into platform core. |
| New `openbank-libs-market` (`com.openbank.libs.market`) | `MarketKey`, mode, typed bank/establishment/book ids, `BookingContext`, minimal fact references, manifest/pack references, admission result/reasons; pure effective-date, scope and required-evidence evaluation. | No CDI/JPA/HTTP/Jackson adapters, DB, mutable singleton registry, clock reads, national rates, templates, secrets, TINs or PII. Inputs include explicit time and trusted facts. |
| Existing `openbank-libs-runtime` | Reuse transport/authentication, outbox, audit and observability plumbing. | No `api()` re-export or dependency on `libs-market`; service-local adapters map framework objects to market types. Extract an opt-in market-runtime module only after measured duplication warrants it. |
| Existing `openbank-libs-lending` | Keep credit compliance semantics and its pack evaluator. | Adapt market admission into lending's domain; do not promote credit-specific rules into a universal engine. |
| Existing `openbank-libs-testing` | Reuse containers and auth/approval test facilities. | Market conformance vectors live as `libs-market` test fixtures consumed by real services; do not force market dependencies on every testing consumer. |

Dependency direction is `service -> libs-market -> libs-domain`, and
`libs-lending -> libs-domain`; core and generic runtime never depend on a business module.
Transport schemas remain explicit OpenAPI/event contracts; a Kotlin dependency is not a wire
compatibility guarantee. The library carries reference types, not a second source of truth.
Reuse existing country/identifier validators where semantics agree, and preserve old APIs
through adapters until consumers migrate. Add ABI, dependency-direction, canonical-hash and
cross-consumer conformance checks. Country activation changes data, not shared-library versions;
a schema or evaluator change is a normal reviewed code release.

### D5. Branch accounting and reporting preserve the bank boundary

Account, lending, ledger and downstream projections propagate a durable booking context.
Use bank/establishment/book dimensions for posting, closing, access, reconciliation, reporting
and revenue/cost attribution. A balanced bank total does not prove correct branch allocation:
reconcile each book/currency and the bank total. Inter-branch movements use explicit clearing
and elimination rules; they are not automatically inter-company transactions. Return scope
(bank, branch, tax establishment, consolidation) is separate from a customer's party type.

The tax owner selects treatment by the effective establishment, income event, verified
residencies and treaty evidence. EUR does not mean Austrian taxation and CZK does not prove
Czech residence. Reuse interest-service's income-event tax boundary and tax-reporting-service's
filing lifecycle; local reporting adapters extend existing owners (including FINREP/AnaCredit)
rather than duplicating their books. Remove single-company assumptions before branch activation.

Deposit protection is scoped to the relevant licensed bank/scheme, not a fresh allowance per
country or branch. For the usual Czech-bank EU-branch case, retain home-scheme coverage and
support host-scheme payout cooperation (DGSD Article 14). Aggregate eligible deposits across
home, FOS and branches for the same depositor; account for joint/special balances under the
applicable rules. No pack may silently allocate another coverage limit for the same bank.

Local IBAN issuance is a separate, approved capability. A foreign EU IBAN is not itself a
reason to reject SEPA payment access (Regulation 260/2012 Article 9). Do not enable SK/AT
issuance by replacing the Czech prefix: national BBAN validation, identifiers, routing and
scheme participation must exist. A country's currency, calendar or tax treatment must not
be inferred from its IBAN prefix.

### D6. Activation is a scoped operational decision with evidence

Use `DRAFT -> REVIEWED -> READY -> ACTIVE`, with `SUSPENDED_FOR_NEW_BUSINESS` and `RETIRED`
transitions. REVIEWED means legal applicability and pack contents were reviewed; READY means
technical evidence is complete; ACTIVE additionally requires actual passport/branch start
conditions and accountable legal/compliance/operations sign-off. Technical four-eyes approval
is never proof that a supervisor has completed a procedure. Use distinct people and existing
strong-authentication/approval controls for activation.

Maintain a versioned EU membership reference and a readiness matrix per country, mode,
product and customer scope. Missing cells are NOT_ENABLED, not inherited approval. Entry
into force, review expiry and dependency failures are evaluated automatically. Connect
obligation -> control -> evidence to ADR-0367 and ADR-0379's trace; AI can explain or draft
but cannot certify applicability, approve activation or submit filings.

Suspension blocks new business while preserving explicitly permitted servicing, withdrawals,
repayment, complaints, statutory reports and orderly exit. Use existing account/party standing
and sanctions controls for freezes, not the market switch. A branch closure must settle or
transfer books through a governed process and retain historical identifiers and evidence.

### D7. Migrate without inventing history

Add nullable context fields first, version contracts/events backward-compatibly, inventory
legacy records, and backfill only where source evidence proves the attribution. Keep
`LEGACY_UNRESOLVED` as a migration state, never as a selectable market or default permission.
Quarantine unknown attribution for remediation; do not guess a branch from an address/IBAN.
Before cross-border launch, prove that reporting and deposit aggregation include all relevant
legacy holdings as well as new accounts. Otherwise withhold the launch approval.

Shadow only the new admission policy while preserving existing controls. Compare decisions
and reconcile projections before enforcing new cross-border commitments. Readers deploy
before writers. Rollback disables new origination and restores a compatible application build;
it must retain booked context, old pack versions and audit evidence. Do not drop columns or
rewrite completed postings. No cross-border fallback to the legacy CZ path is allowed.

## Alternatives considered

- **Only add country/locale to the catalogue.** Small change, but existing country metadata does
  not bind the contractual route, statutory obligations or branch accounts. Insufficient.
- **Clone the stack for each country.** Provides deployment isolation, but duplicates policy
  changes and cannot by itself prove bank-wide customer exposure or deposit aggregation.
  Separate deployment remains possible for a justified operational requirement, with the same
  canonical bank/branch model and reconciliation.
- **A universal country rules engine in libs-domain.** Convenient imports, but mixes legal
  ownership, expands rebuild fan-out and forces tax/credit/reporting semantics into one DSL.
  Keep shared mechanics narrow and domain rules with their owners.
- **Create a market-access microservice immediately.** Could give independent scaling and
  ownership; no demonstrated need yet. Start with the existing catalogue admission seam and
  extract only on measured operational or ownership pressure.

## Consequences

**Positive:** one consistent market decision, reusable country onboarding, attributable books,
replayable compliance evidence, and an explicit distinction between software support and launch permission.

**Negative:** additional market-admission availability requirements; nontrivial historical
attribution work; legal and operational certification per country/product; library/schema
compatibility and revocation fencing become explicit delivery obligations.

**Neutral:** current domestic banking is not certified by this ADR. No market is activated,
no deployment is changed, and no claim is made that investment, insurance or pension products
inherit banking passport permission. Their extra regimes need product-specific applicability gates.

## Compliance impact

- PCI DSS: no cardholder-data path changes in this documentation; card rollout retains its existing scope and reviews.
- DORA: country/branch launch evidence includes outsourcing, incident responsibilities and continuity; a domestic test is not evidence of branch recovery.
- GDPR: minimise fact propagation, purpose-limit tax reads and protect approval documents; intra-EU expansion does not automatically require country-local hosting. Assess actual processors and transfers separately.
- PSD2: retain payment/SCA controls and assess local payment obligations; market approval cannot bypass payment admission.
- CNB: home licence/passport scope, bank-wide books and reporting remain explicit; host obligations are recorded alongside them, not substituted for them.
- CRD / DGSD: Articles 33, 35–36, 39 of CRD and Article 14 of DGSD inform the two modes and deposit-protection boundary. Legal owners validate applicable national implementation and launch dates.

## References

- [CRD consolidated 11 July 2026](https://eur-lex.europa.eu/legal-content/EN/TXT/PDF/?uri=CELEX%3A02013L0036-20260711), Articles 33, 35–36, 39.
- [Passport notification forms, Regulation 2022/193 amending 926/2014](https://eur-lex.europa.eu/eli/reg_impl/2022/193/oj/eng).
- [DGSD, Directive 2014/49/EU](https://eur-lex.europa.eu/legal-content/EN/TXT/PDF/?uri=CELEX%3A32014L0049), Article 14.
- [European Commission: IBAN discrimination](https://finance.ec.europa.eu/consumer-finance-and-payments/payment-services/payment-services/iban-discrimination_en).
- [NBS: bank passporting](https://nbs.sk/en/financial-market-supervision1/supervision/banking/the-provision-of-banking-services-on-the-basis-of-an-authorisation-granted-in-another-eu-member-state-passporting/).
- [FMA: EEA banks in Austria](https://www.fma.gv.at/en/banks/licensing-notification/eea-banks-in-austria-under-the-freedom-to-provide-services-and-freedom-of-establishment/).
- Existing decisions: ADR-0122 / ADR-0317 (library boundaries), ADR-0212 (credit packs), ADR-0284 (KYB), ADR-0339 / ADR-0340 (tax), ADR-0336 / ADR-0367 (returns), ADR-0379 (compliance trace), ADR-0374 (payment admission).
- Source URLs reviewed 2026-11-15. Per-country legal applicability and complete product coverage remain launch deliverables, not conclusions of this ADR.
