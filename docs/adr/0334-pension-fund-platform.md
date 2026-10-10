---
date: 2026-10-09
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [product-catalog, onboarding, compliance, architecture]
summary: "DPS and DIP pension savings get a full digital client lifecycle and fund/strategy administration in a segregated pension-fund context, with state incentives, tax and payout rules as effective-dated jurisdiction packs (CZ first)."
---

# ADR-0334 — Pension fund platform: client lifecycle, fund and strategy management, jurisdiction packs

## Context

OpenBank has no retirement product. The goal is a fully digital third-pillar pension offering
covering the whole client lifecycle — discovery in the product catalog, onboarding (new contract
and transfer-in from another provider), contributions (client, employer, state), strategy changes,
early termination, regular termination and payout, death — and the provider side: funds, their
strategies, unit pricing and investment management.

Forces:

- **Licensing.** In most EU member states a supplementary pension fund is run by a separately
  licensed entity, not by a bank. In the Czech Republic supplementary pension savings
  (*doplňkové penzijní spoření*, Act 427/2011 Coll.) may only be provided by a pension company
  (*penzijní společnost*) licensed by the CNB, with a depositary and segregated fund assets.
  A bank may itself provide the newer long-term investment product (*DIP*, income-tax act),
  and may distribute a pension company's product. The platform therefore cannot assume the
  bank's own legal entity is the provider.
- **Asset segregation.** Fund assets belong to the fund's participants, not to the bank. They must
  never appear on the bank's balance sheet, in the bank's treasury book (ADR-0315) or in the
  customer-position ledger as bank liabilities.
- **Jurisdictional variance.** Every country shapes the same lifecycle differently: state matching
  contributions (CZ *státní příspěvek*, DE *Riester-Zulage*), tax relief instead (SK, PL IKZE),
  employer-contribution exemptions, minimum age and duration for payout, clawback of incentives on
  early exit, transfer rules and deadlines. Hard-coding CZ would make every next country a rewrite.
  ADR-0212 already established jurisdictional rules as versioned, effective-dated data for credit.
- **No industry template.** BIAN has no pension service domain; the closest are Product Directory,
  Investment Account, Investment Portfolio Management, Customer Product/Service Eligibility and
  Regulatory Reporting. The EU PEPP regulation (EU 2019/1238) is the most complete EU-level
  description of a personal pension product (switching, decumulation forms, key information,
  annual benefit statement) and is used here as the reference shape, not as the product.

### Critical review of the earlier draft (why this ADR differs from it)

The first draft proposed "DIP first, distribute a partner's DPS, own pension company maybe later",
with a `pension-service` that held only contracts. Review found four problems:

1. **It optimised for licensing, not for the platform.** OpenBank is a reference implementation
   (as with `clearing-simulator`), so the domain must model the provider side completely; licensing
   decides only which *deployment mode* is switched on, not what is built.
2. **It had no unit register.** A pension fund is unit-based: contributions buy units at a NAV,
   strategy changes and payouts sell them. Money positions in the ledger cannot express that.
3. **It implied treasury could run fund investments.** Treasury (ADR-0315) manages the bank's own
   book. Mixing fund assets into it breaks segregation — the defining legal property of a fund.
4. **It treated state contributions as a CZ feature.** They are one instance of a generic
   "incentive rule" that every jurisdiction pack parameterises differently.

## Decision

We will build the pension offering as two new bounded contexts plus a jurisdiction-pack mechanism,
reusing existing services wherever the concern is not pension-specific.

### 1. Bounded contexts

**`openbank-pension-service` — the participant (client) side.** Owns:
- `PensionContract` (product, participant, provider entity, jurisdiction pack version, status
  lifecycle `DRAFT → PENDING_ACTIVATION → ACTIVE → SUSPENDED (contributions paused) →
  TERMINATING → PAID_OUT | TRANSFERRED_OUT | CLOSED`);
- `StrategyElection` (chosen strategy or lifecycle strategy, history, effective dates). A lifecycle
  strategy is an *offered* strategy, never a statutory default: in CZ the lifecycle default is only
  proposed by the government bill "Lepší penzijko" (approved by the government 2026-08-24, proposed
  effect 2027-01-01, not law as of 2026-10), so a pack may mark it as recommended but must not apply
  it as a default until a pack version effective from the enacted date says so;
- `ContributionSchedule` and `Contribution` (source: `PARTICIPANT | EMPLOYER | STATE | TRANSFER_IN`);
- `Beneficiary` designations; `PayoutRequest` (form: lump sum, annuity, phased withdrawal,
  early withdrawal, surrender); `TransferRequest` (in and out);
- `IncentiveClaim` (state contribution claimed/received/returned) and `TaxYearSummary`.

**`openbank-pension-fund-service` — the fund (provider) side.** Owns:
- `Fund` (segregated sub-ledger, depositary reference, ISIN/LEI, risk class) and `FundStrategy`
  (target allocation, rebalancing bands, mandatory-conservative flag, lifecycle glide path);
- the **unit register**: per-contract unit holdings per fund, unit transactions
  (`SUBSCRIBE | REDEEM | SWITCH_OUT | SWITCH_IN | FEE`), all priced at a forward NAV;
- daily **NAV calculation** from positions, accruals and the management/performance fee,
  four-eyes publication, NAV correction with participant compensation;
- investment orders, depositary reconciliation and limit/concentration checks;
- **strategy administration** (create, change allocation, close or merge a fund) with
  governance approval and mandatory participant notification before effect.

Clients never see fund-service directly; pension-service calls it through a
`FundAdministrationPort`, so a deployment that **distributes** another provider's fund binds the
same port to an external adapter instead.

### 2. Deployment modes (one domain, three bindings)

| Mode | Provider entity | Fund side | Use |
|---|---|---|---|
| `OWN_PROVIDER` | licensed pension company as its own tenant | pension-fund-service | full stack, reference default |
| `DISTRIBUTOR` | third-party pension company | adapter to partner API | bank only distributes |
| `BANK_WRAPPER` | the bank | investment orders into existing funds | DIP offered by the bank itself |

The provider entity is a separate legal party (party-service) with its own GL books, so a
deployment can host a pension company beside the bank without commingling.

### 2a. Product lines — DPS and DIP are both first-class

The platform is a full-featured solution for a pension provider, so it carries **both** product
lines from the start, sharing one contract, unit-register and lifecycle engine:

- **Pension fund product** (CZ `DPS`) — contributions buy units of the provider's own funds under
  a strategy; state incentive and tax relief per pack.
- **Long-term investment product** (CZ `DIP`) — a tax-advantaged wrapper whose holdings may be
  the provider's own funds, third-party funds or other permitted instruments; no state matching
  contribution, tax relief and employer exemption per pack, MiFID suitability and a PRIIPs KID
  where the instrument requires one.

Which legal entity may act as provider of each line is pack data (`permittedProviderTypes`),
validated at contract creation — the CZ pack must state, after legal review, which entity types
may provide a DIP, and a group deploying both lines places each on an entity that holds the
required licence. The difference between the lines lives in the pack and the product
specification, not in separate services.

### 3. Jurisdiction packs

Pension rules ship as versioned, effective-dated packs in the shape ADR-0212 established for
credit, one per `(country, product type)`; the first two are `CZ/DPS` and `CZ/DIP`, delivered together. A pack declares:
- eligibility (age, residency, legal capacity, minors with guardian);
- incentive rules as a generic model — `matching` (rate, band, cap per period), `flat`, `tax-relief`
  (deduction cap, shared caps across products), `employer-exemption` — plus the claim channel
  (batch to a state agency, tax return, none) with its filing cadence, and clawback rules on early
  exit. The CZ state contribution is computed per month but claimed **quarterly**: the company
  files one application to MF in the calendar month after each calendar quarter, and MF's returns
  are reported and settled separately (ZDPS 427/2011 §§ 14, 16, 18; see
  `docs/research/cz-state-pension-contribution.md`). It is not a monthly filing;
- payout conditions (minimum age, minimum duration, allowed forms, early-withdrawal penalties
  and tax recapture), transfer rules (deadlines, fees, what moves with the transfer);
- required disclosures and their templates, cooling-off period, regulatory report set.

The domain evaluates packs; no country literal appears in Kotlin. The CZ pack is the first
implementation; adding a country is a new pack plus, where needed, a claim-channel adapter.
Statutory values are pack data, reviewed by a lawyer per pack version, never constants in code.

### 4. Lifecycle — fully digital

Each lifecycle step runs as a Temporal workflow (`openbank-libs-temporal`) with SCA-signed client
intent (sca-service) and documents from document-service:

1. **Discover & simulate** — catalog offering + retirement projection per strategy.
2. **Onboard (new)** — KYC reuse, suitability/appropriateness and ESG-preference questionnaire,
   strategy recommendation, key-information document, e-signature, cooling-off, activation.
3. **Onboard (transfer-in)** — same as new plus a `TransferRequest` to the ceding provider;
   accumulated units, incentive history and contract start date move per pack rules; the
   contract activates when funds arrive. Reverse direction (transfer-out) is the mirror workflow.
4. **Contribute** — standing order / SEPA direct debit (standing-order-service, sdd-service),
   employer bulk contributions (B2B channel over kyb-service), state incentive claim and receipt.
5. **Manage** — change strategy (switch units at next NAV), change contribution, pause,
   beneficiaries, statements and tax certificate.
6. **Early termination** — client notice, pack-driven surrender value, incentive clawback,
   tax recapture, payout.
7. **Regular termination & payout** — eligibility check, form selection, decumulation schedule.
8. **Death** — beneficiary or estate payout.

### 5. Reuse map

| Need | Reuse | New |
|---|---|---|
| Product definition | product-catalog `retirement` pack (ADR-0257) | — |
| Identity, KYC, AML | party, kyc, aml, sanctions services | suitability questionnaire |
| Payments in/out | standing-order, sdd, domestic/sepa payment | — |
| Money | ledger (cash legs only) | unit register |
| Market data, order execution | treasury-service (ADR-0315) market-data and execution adapters | fund book segregated from bank book |
| Documents, consent, notifications | document, consent, notification services | templates |
| Tax reporting | tax-reporting-service | pension tax certificate |
| Net worth | wealth composition at the edge (ADR-0301) | — |

### 6. Classification

Both new services are added to `rules.yaml: money_path_services` and get threat models.

## Alternatives considered

- **Contract-only `pension-service`, distribution first (the earlier draft).** Cheapest, but
  cannot model units, NAV or strategies, and a later own-provider mode would be a rewrite.
  Rejected; kept as the `DISTRIBUTOR` binding.
- **Run fund investments in treasury-service.** Reuses deal booking, but breaks asset
  segregation. Rejected; only market-data and execution adapters are shared.
- **One service for participant and fund side.** Fewer moving parts, but the two sides belong to
  different legal entities in the `DISTRIBUTOR` mode and have different change cadence. Rejected.
- **Build on PEPP as the product.** EU-portable, but without national tax relief or state
  contributions it does not compete. Rejected as product; used as the reference shape.

## Consequences

**Positive**
- Full lifecycle and fund administration in one domain, deployable as provider, distributor or
  bank wrapper.
- New countries arrive as data packs plus adapters.
- Asset segregation is structural (separate service, books and entity), not a convention.

**Negative**
- Two new money-path services; unit register and NAV are a substantial new capability.
- Statutory parameters need legal review per pack version.
- Rough effort: catalog pack and read model ~1 month; participant lifecycle ~4 months (2–3 FTE);
  fund administration with NAV and strategies ~5 months (2–3 FTE); CZ DPS and DIP packs with the
  state-incentive adapter ~2–3 months. A real production licence (pension company, CNB) is a separate 12–18 month
  non-engineering track.

**Neutral**
- Legacy CZ transformed funds (closed to new contracts, capital guarantee) are out of scope;
  a transfer-in from one is handled like any other ceding provider.
- Occupational pensions (IORP) are out of scope.

## Compliance impact

- PCI DSS: not applicable — no card data.
- DORA: the `DISTRIBUTOR` adapter to a partner provider is an ICT third-party arrangement and
  enters the outsourcing register; both services fall under the existing ICT-risk framework.
- GDPR: new personal data (beneficiaries, contribution history, suitability answers) — retention
  driven by pension law per pack; covered by the existing privacy controls.
- PSD2: not applicable — contributions use existing payment services.
- CNB: supervises the pension company and its funds under Act 427/2011 Coll.; reporting and
  disclosures are declared per jurisdiction pack.

## References

- ADR-0002 hexagonal architecture
- ADR-0212 jurisdictional credit compliance packs
- ADR-0257 industry-neutral product catalog kernel
- ADR-0301 wealth service and net-worth composition
- ADR-0315 treasury service domain
- Regulation (EU) 2019/1238 on a pan-European Personal Pension Product (PEPP)
- Czech Act 427/2011 Coll. on supplementary pension savings
