---
date: 2026-09-05
decision-status: proposed
delivery-status: partial
followup: "openbank-app — profile switcher + business onboarding screens ship in the app repo; D8 owner-ecosystem hooks (loyalty, 360, catalog overlay, campaign segment) and the D9 agents are the unbuilt tail"
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [openbank-app]
tags: [onboarding, kyc, authz, mobile-app]
summary: "Legal entities onboard from a country-pack identifier verified against a public register; representation mandates link humans to entities, the edge switches profiles fail-closed, and the owner graph feeds loyalty, 360 and campaigns."
---

# ADR-0284 — Legal-entity onboarding, representation mandates and profile switching (KYB)

## Context

The platform onboards **natural persons** only. `PartyType` has carried `SOLE_TRADER`, `COMPANY`
and `TRUST` since the first migration, `EligibilitySegment.BUSINESS` exists in product-catalog,
and ADR-0232 designed delegation to be "SME-ready" — but no path creates a non-individual party,
nothing links a human to an entity they may act for, and the customer edge resolves *one* party
per token (`CustomerIdentity(partyId)` from the `party_id` claim). A sole trader (in the Czech
Republic an *OSVČ* — fyzická osoba podnikající) or an s.r.o. therefore cannot become a customer,
and the retail app has no notion of "which hat am I wearing".

What a legal-entity relationship needs that a retail one does not, generically and across
jurisdictions:

1. **A business identifier as the entry point, not a name.** Every jurisdiction issues one
   (CZ/SK *IČO*, PL *NIP/KRS*, DE *HRB*, GB company number, FR *SIREN*, NL *KVK*), and there are
   two cross-border ones: the **LEI** (ISO 17442, GLEIF — public, free, no registration) and the
   **EUID** (EU Business Registers Interconnection System, BRIS). DUNS is proprietary and not
   free; it is a *foreign key* we may store, never a source we verify against.
2. **A public register as the source of truth for who the entity is and who may act for it.**
   CZ **ARES** (Ministry of Finance) is a free REST API whose *veřejný rejstřík* view lists the
   statutory body, its members with dates of birth, and the *způsob jednání* (how the company is
   represented — alone, jointly, N-of-M). GLEIF gives legal name, legal form, address and parent
   structure worldwide, but no representatives. UK Companies House gives officers and *persons
   with significant control* (UBO) for a free API key. SK *RPO* (statistics.sk) lists statutory
   bodies. Where no free machine-readable source exists, the fallback is a **manual attestation**:
   the applicant uploads an extract and an operator verifies it.
3. **Representation rule → who must sign.** A sole trader signs alone. A company is bound by
   whoever the register says may bind it: "každý jednatel samostatně" needs one signature, "dva
   členové představenstva společně" needs two. The onboarding must (a) verify the *initiator* is a
   listed representative, (b) compute how many further signatures are needed, (c) let the
   initiator pick which listed representatives to invite, (d) require each invitee to onboard and
   verify their **own** identity before they sign — never a shared credential.
4. **Beneficial owners (UBO).** AMLD5 Art. 30 and the Czech AML Act (253/2008 Sb.) require
   identification of beneficial owners (25 % threshold; Regulation (EU) 2024/1624 keeps it). The
   Czech *evidence skutečných majitelů* has no free public API; the UK PSC register does. UBO
   capture is a KYB check with a register adapter where one exists and a self-declaration
   otherwise.
5. **A business-only customer who later wants retail products.** The human who onboarded for the
   company is already identity-verified. Their personal profile exists from that moment; it simply
   has no products until they open one. Nothing is re-onboarded. The reverse is the common case:
   an existing retail customer adds "my company" — and the bank should treat owner + company as
   one relationship it can reward, understand and serve, not two strangers.

**What already exists and must be built on, not beside.** ADR-0212 ships jurisdictional credit
law as *versioned, effective-dated JSON packs* — the shape any national overlay here must take.
ADR-0101/0211 make Temporal the durable-timer engine and keep the aggregate a persisted state
machine. ADR-0267 activates a party by the KYC + AML two-key gate off `PARTY_CREATED`. ADR-0232
owns employee-level entitlements. ADR-0282 (Lípa) defines the loyalty ledger, micro-segments,
per-party catalog overlays and Customer 360 transparency. ADR-0210 is Customer 360 as a query over
the analytics silver layer. ADR-0221/0263 are Campaign Studio and Temporal campaign journeys.
ADR-0203/0222/0244 are the business-plane agents, read-only with a human disposition, coordinated
over Temporal case workflows. ADR-0259 lets AI author catalog drafts. Each of those is a place the
owner ↔ company link has to land.

Regulatory frame, in plain words: EBA remote customer onboarding guidelines (EBA/GL/2022/15) for
the identification leg, AMLD5/6 and the Czech AML Act for CDD/KYB and UBO, PSD2 SCA for every
signature (ADR-0021), eIDAS for the electronic signature itself (ADR-0162), the EU AI Act for the
agents (ADR-0203 D7 constraints). BIAN vocabulary maps cleanly: *Party Reference Data Directory*
(party-service), *Legal Entity Directory* and *Customer Agreement* (this ADR's new service), *Party
Authentication* (sca), *Customer Relationship Management* (360 + loyalty).

## Decision

**D1 — A new bounded context, `openbank-kyb-service`, owns legal-entity verification and the
business onboarding case.** Port 8157, namespace `kyb`, database `openbank_kyb`, transactional
outbox onto `openbank.kyb.events` (AsyncAPI contract in `openbank-contracts/`). It owns:

- `LegalEntityIdentifier(scheme, value)` — a closed `IdentifierScheme` vocabulary (`CZ_ICO`,
  `SK_ICO`, `PL_NIP`, `PL_KRS`, `DE_HRB`, `AT_FN`, `GB_CRN`, `FR_SIREN`, `NL_KVK`, `LEI`,
  `EUID`, `EU_VAT`, `DUNS`) with per-scheme normalisation and checksum validation (IČO weighted
  mod-11, NIP, SIREN Luhn, LEI ISO 7064 mod 97-10), so a wrong digit is refused at the input field
  before any register is asked.
- `RegistryExtract` — the **normalised** view of a public-register record: legal name, legal-form
  code and a jurisdiction-neutral `LegalFormClass`, registered address, status, incorporation
  date, tax id, `representatives[]` (name, date of birth, body, role, since), a
  `RepresentationRule(mode: SOLE | JOINT_ALL | JOINT_N | UNKNOWN, requiredSigners, sourceText)` and
  `verification: VERIFIED | UNVERIFIED`. Register adapters implement one `RegistryAdapter`:
  `AresRegistryAdapter` (CZ, economic-subject + public-register views), `GleifRegistryAdapter`
  (LEI, worldwide), `ManualAttestationRegistryAdapter` (any scheme without a free register; the
  extract is `UNVERIFIED` until an operator attests it). A `RegistryRouter` picks by scheme and
  consults the attestation adapter LAST, so a register that is merely down propagates as an
  outage into manual review and never silently degrades to self-declaration.
- `BusinessOnboardingCase` — a persisted state machine (the ADR-0211 shape, not a BPM):

  ```
  IDENTIFIER_ENTERED → REGISTRY_VERIFIED → INITIATOR_MATCHED ─┬→ READY_TO_SIGN → SIGNED → ACTIVE
                              │                                └→ AWAITING_COSIGNERS ─┘
                              └→ MANUAL_REVIEW (unknown id, unparseable rule, unverified extract,
                                 initiator not listed, register down)     → REJECTED | ABANDONED
  ```

  `requiredSignatures` is derived from the representation rule; the initiator always counts as
  one signer; a sole trader is the degenerate case (one representative, `SOLE`, one signature).
  Each `Signer` carries an unguessable invitation token (192 bits), the matched representative
  index, the `partyId` once that person has onboarded and verified identity, and the signature
  reference from document-service's ceremony (ADR-0162). `SIGNED` needs the *required* number of
  distinct verified signers; `ACTIVE` needs `SIGNED` **and** the entity party's KYC + AML
  activation (ADR-0267), in either order.

**D2 — Jurisdictions are country packs: versioned, effective-dated data, the ADR-0212 shape.**
`country-packs/<cc>-v<N>.json` declares, per country: the identifier schemes it issues, which
register adapter answers and what it lists (representatives? representation rule?), how UBOs are
established (`publicApi`, `fallback`, threshold, legal basis), the legal-form code → class map with
cs/en labels, the evidence each legal-form class needs, and which free-text representation-rule
parser applies. `CountryPackRegistry` serves the newest pack effective on the day. The Czech
overlay is therefore `cz-v1.json` plus one parser — `CzechRepresentationRuleParser`, the only place
Czech legal language lives, biased to the safe side (`UNKNOWN` routes to review, never a lower
signer count). Adding a jurisdiction is a pack file and, where its register has one, an adapter —
never a branch. Activation is data today; when the second jurisdiction lands, the four-eyes pack
activation of ADR-0212 D2 applies unchanged.

**D3 — party-service gains the entity party and the `PartyMandate`.** A `COMPANY` or
`SOLE_TRADER` party is created at `REGISTRY_VERIFIED` (status `PENDING_KYC`) carrying `legalForm`
and `registrationCountry` next to `registrationNumber`/`taxId`, so ADR-0267's activation gate
applies unchanged. A `PartyMandate` links a human (`agentPartyId`) to an entity
(`principalPartyId`) with `role` (`OWNER`, `LEGAL_REPRESENTATIVE`, `AUTHORISED_SIGNATORY`),
`authority` (`SOLE`, `JOINT`), `source` (`REGISTRY`, `POWER_OF_ATTORNEY`, `MANUAL`) and lifecycle
`ACTIVE → REVOKED | EXPIRED`. kyb-service grants one mandate per signer the moment the case reaches
`SIGNED`; grants and revocations leave through the party outbox as `PARTY_MANDATE_GRANTED` /
`PARTY_MANDATE_REVOKED`, keyed on the entity. `GET /api/v1/parties/{agent}/acting-for` is the
profile switcher's source of truth. A mandate is a *fact about the register*; employee-level
access (treasurer, accountant, viewer) is **not** a mandate — it is an ADR-0232 delegation grant
issued by a mandate holder acting for the entity.

**D4 — The customer edge switches profiles with `X-Acting-For`, fail-closed.** The JWT still
identifies the human. `GET /customer/v1/profiles` lists the personal profile (with `hasProducts`)
plus one business profile per active mandate — always off the token, never off the header, so a
client can always find its way home. Every other route resolves `X-Acting-For: <entityPartyId>` at
the single identity chokepoint (`customer()`, and the same helper on the delegation and document
resources): the edge verifies an ACTIVE mandate for (`token human`, `entity`) against
party-service, caches a positive answer for a minute, and only then substitutes the entity id
downstream. No mandate, unreachable party-service, non-200, garbage body, malformed header, kill
switch off → **403**. This is the opposite failure mode from `PartyMergeResolver` (fail-open) on
purpose: an unhonoured merge shows a customer *less*; an unverified switch would show them someone
else's company. Every existing route — accounts, payments, cards, delegations, documents — works
for the entity without change, because they all take the party from `customer()`. Business
onboarding itself is always done *as the human* (`/customer/v1/business/*`), and a body naming
another initiator is refused at the edge before kyb-service refuses it again on
`X-Customer-Party-Id`.

**D5 — KYC becomes KYB for an entity party.** `KycCase` gains `subjectType`
(`INDIVIDUAL | BUSINESS`, derived from `partyType` on `PARTY_CREATED`) and three check types:
`REGISTRY_MATCH`, `REPRESENTATIVE_AUTHORITY`, `UBO_IDENTIFICATION`. A business case opens with
those three plus `SANCTIONS_SCREENING` and `ADVERSE_MEDIA`; identity and PEP screening apply to the
*representatives*, whose own individual cases already carry them. The four-eyes review and the KYC
→ party → account activation chain are reused as they are; the pack's `uboRegister.fallback`
decides whether `UBO_IDENTIFICATION` is a register look-up or a self-declaration for the operator.

**D6 — Temporal owns the case's durable timers; the outbox owns its events.** One
`BusinessOnboardingTimersWorkflow` per case (task queue `openbank-kyb-onboarding`), signalled on
every state change: in `AWAITING_COSIGNERS` it reminds the initiator at half the invitation TTL
and abandons at the full TTL; any other open state idles at most the case TTL before abandonment.
Timers fire through activities that re-check the state (`abandonIfInState`) so a stale timer is a
no-op. The adapter is build-time selected by `openbank.temporal.enabled` with the `%prod` default
`true` (lending's #6085 lesson) and a logged no-op otherwise; the worker is switched by
`openbank.kyb.worker.enabled`. A Temporal failure never fails the customer's step — the case row is
already committed; the miss is logged and counted (`openbank.kyb.timers.arming_failed`). The
operator review of a case is the natural next ADR-0244 *case workflow*: a chartered agent joins
mid-run, the human disposes.

**D7 — The app switches via the avatar.** The profile sheet's avatar header becomes the switcher:
personal + each business profile + "Add a business". The selected profile is held in one
`ActiveProfile` holder that stamps `X-Acting-For` on every edge call from one Ktor plugin, so no
screen needs to know. Business onboarding is reachable from the switcher and from the welcome
screen: country + identifier (the pack decides the input) → register preview → "which listed person
are you" → co-signer selection and invitation (share sheet) → signature ceremony. An invited
co-signer opens the app from the invitation link, onboards as themselves (the existing individual
flow), and lands on the pending signature. A business-only customer sees their personal profile
with "no products yet" and an open-account call to action; ADR-0270's relationship aggregate, when
built, is where that state becomes explicit.

**D8 — The owner graph is an ecosystem asset, and the same hooks serve it everywhere.** The
mandate is the edge that says "this human runs this company". It is published once
(`PARTY_MANDATE_GRANTED`) and consumed, never re-derived:

- *Customer 360 (ADR-0210).* analytics-sink ingests `openbank.kyb.events` and the mandate events
  into the silver layer; the 360 query gains an `acting_for` edge, so an operator (and, by
  ADR-0282 D8, the customer) sees the household **and the businesses** as one relationship.
- *Lípa loyalty (ADR-0282).* A business profile is a second closed-loop grove next to *Háj*:
  earn sources are the business's *financial-health* signals (on-time supplier payments, no
  overdraft use, e-invoicing adoption), never turnover; redemptions are the entity's fee waivers.
  The **owner bridge** is the one cross-profile rule: an owner's personal Lístky may be redeemed
  into their company's fee waivers and vice versa, capped per party per year by ADR-0282 D5's
  provisioning — bank-side benefit only, no transfer of value between legal persons, so no tax or
  AML event is created. Segment rules see the owner graph (`owns_business`, `business_age`,
  `business_health_tier`).
- *Product catalog and studio (ADR-0257/0259/0282 D7).* Business products are `BUSINESS`-segment
  catalog entries; the edge filters `/products` by segment while acting for an entity. The
  *owner bundle* (personal + business current account, shared card controls, consolidated
  statement) is a per-party **catalog overlay** produced by the deterministic overlay engine of
  ADR-0282 D7 from the owner graph — never a hand-priced special. AI may *draft* the bundle
  (ADR-0259); a human publishes it.
- *Campaigns (ADR-0221/0263).* Campaign Studio gains the `business-owner` and
  `business-representative` segment attributes; a journey may branch on the active profile;
  delivery stays consent-gated per party (ADR-0200) — a company's marketing consent is its own.
- *Delegation (ADR-0232).* SME role presets (`treasurer`, `accountant`, `viewer`) are data in
  delegation-service; only a mandate holder acting for the entity may issue them.

None of these consumers is on the money path and none is called synchronously from kyb-service or
the edge: they read events, so a slow loyalty job cannot slow a signature.

**D9 — AI agents, business-plane, human disposition, governed like every other agent.** Two
charters extend ADR-0203, both `plane: business`, read-only, no tool above `read` tier:

- **`kyb-analyst`** — reads a `MANUAL_REVIEW` case (extract, declared data, UBO declaration,
  sanctions hits) and drafts a *disposition proposal* with cited evidence: "resolve with 2
  signers", "reject — entity dissolved", "request power of attorney". It joins the review as an
  ADR-0244 case-workflow participant; the operator disposes; nothing about a case changes on the
  model's word. It also reads *způsob jednání* phrasings the parser marked `UNKNOWN` and proposes
  the count — the proposal, when accepted, becomes a new pinned parser test, so the model's
  judgement is captured as data rather than trusted at runtime.
- **`business-copilot`** — the customer-copilot (ADR-0089) charter extended with the acting-for
  scope: the same on-behalf-of token, now for the entity profile, the same proposal-only action
  tools. "Send the invoice from the company account" is a proposal into the edge SCA flow, exactly
  as today.

Both go through `agents.yaml` (charter, data scope, tool allow/deny, limits), the EU AI Act
register, and the OPA agent policy; neither is high-risk under Annex III because neither decides
creditworthiness or access to the service — the operator does.

**D10 — Products.** The edge filters `/products` by `eligibilitySegments` containing `BUSINESS`
when acting for an entity and excludes `BUSINESS`-only products otherwise. No catalog change.

## Alternatives considered

- **Model the entity as attributes on the human party** (a `companyIco` column) — rejected: a
  company with two representatives becomes two inconsistent copies, and every downstream
  ownership check (`partyId` on accounts, delegation resources) already assumes the owner is a
  party. The entity must be a party of its own.
- **Put representation into delegation-service as a grant** — rejected: a mandate is a *fact about
  the world* sourced from a public register, revocable by a register change; a delegation grant is
  a *choice by an owner*, SCA-bound on both sides. Modelling the former as the latter would let a
  representative "decline" their own statutory authority. Delegation stays the layer *below* the
  mandate (employees).
- **A second Keycloak user per business profile** — rejected: it multiplies passkeys, breaks the
  one-human-one-party invariant of ADR-0072, and puts a business relationship into the identity
  provider where nothing can audit or revoke it on a register change.
- **Extend kyc-service with the registry adapters instead of a new service** — rejected: the
  register lookup is needed *before* any party or case exists (the very first screen), has its own
  external dependencies, cache and country packs, and the multi-signer case is a long-lived
  aggregate that kyc's per-party case model does not fit. kyc-service keeps the *review*.
- **Whole-case Temporal workflow (event-sourced case inside Temporal)** — rejected, as ADR-0211
  rejected it for origination: the case is queried by three services and the admin console, so it
  belongs in Postgres; Temporal is the durable clock, not the record.
- **Country logic as Kotlin branches** — rejected: ADR-0212 already showed that jurisdiction is
  data that changes on a legal calendar; a second mechanism for the same concern is the drift the
  repo keeps paying for.
- **A paid KYB vendor first** — rejected as the default: the public registers cover the launch
  jurisdictions for free, the adapter port makes a vendor one more adapter, and the ADR would
  otherwise encode a procurement decision into a domain model.
- **Cross-profile loyalty as a points transfer between persons** — rejected: a transfer of value
  between two legal persons is a taxable, AML-relevant event; a bank-side benefit redeemable by
  either profile is not, which is why D8 phrases the owner bridge as redemption, never transfer.

## Consequences

**Positive**
- One generic model covers OSVČ, s.r.o., a.s. and foreign entities via LEI; a jurisdiction is a
  pack plus an adapter.
- Every existing customer route works for a business profile through one header, decided once at
  the chokepoint, fail-closed.
- Multi-signer agreement is derived from the register, not from the applicant's claim, and its
  clock survives restarts.
- The owner graph is produced once and consumed by loyalty, 360, catalog and campaigns without
  any of them touching the onboarding path.

**Negative**
- Register availability joins the onboarding critical path; `MANUAL_REVIEW` and the attestation
  adapter are the mitigation, and an operator queue is required from day one.
- The representation-rule parser is heuristic over free text; an `UNKNOWN` costs a manual review
  rather than a wrong signer count — the safe side, but a real cost until the agent-proposed
  phrasings accumulate as pinned tests.
- A new service is a new gitops component, OPA bundle, Kafka user, Temporal namespace and database.
- The entity party carries a synthetic, non-deliverable contact email (party-service requires one
  and a company has no login); contact channels are the representatives'. ADR-0270's relationship
  aggregate is the right place to make that explicit.

**Neutral**
- Employee access reuses ADR-0232 unchanged.
- onboarding-service's cockpit needs a business funnel projection from `openbank.kyb.events`
  (follow-up); until then the review queue is `GET /api/v1/kyb/cases?status=MANUAL_REVIEW`.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is touched.
- DORA:    new ICT third-party dependency on public registers (ARES, GLEIF) and on Temporal;
           registered in the ADR-0174 third-party register with the manual-attestation exit path.
- GDPR:    representatives' names and dates of birth from public registers are processed under a
           legal obligation (AML CDD); stored only inside the onboarding case and the mandate,
           never on the event wire, and erased with the party (Art. 17 path unchanged).
- PSD2:    each signature and each profile switch that leads to a payment is SCA-bound (ADR-0021);
           the acting-for header carries no authority of its own.
- CNB:     KYB checks and UBO identification per the Czech AML Act (253/2008 Sb.) are recorded on
           the kyc case with four-eyes review (ADR-0068); the agent of D9 proposes, staff decide.

## References

- ADR-0069 customer onboarding journey · ADR-0072 identity unification · ADR-0101/0211 Temporal
  and durable timers · ADR-0162 e-signature ceremonies · ADR-0203/0222/0244 business-plane agents
  and case workflows · ADR-0210 Customer 360 · ADR-0212 jurisdictional packs · ADR-0221/0263
  campaigns · ADR-0232 delegated access · ADR-0257/0259 catalog kernel and AI authoring ·
  ADR-0267 event-driven account lifecycle · ADR-0270 relationship aggregate · ADR-0282 Lípa
- ARES REST API (Ministry of Finance CZ): `https://ares.gov.cz/ekonomicke-subjekty-v-be/rest`
- GLEIF LEI look-up API: `https://api.gleif.org/api/v1/lei-records`
- UK Companies House Public Data API (officers, persons-with-significant-control)
- EBA/GL/2022/15 remote customer onboarding · Directive (EU) 2015/849 Art. 30 · Regulation (EU)
  2024/1624 · zákon č. 253/2008 Sb. · zákon č. 37/2021 Sb.
