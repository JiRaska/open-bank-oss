---
date: 2026-10-11
decision-status: accepted
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture, accounting-close, resilience, onboarding]
summary: "Support transformed funds with versioned plan entitlements alongside DPS units, and define complete investment, accounting, migration, deployment and recovery boundaries for a standalone pension platform."
---

# ADR-0338 — Transformed funds and complete pension-platform operations

## Context

[ADR-0334](0334-pension-fund-platform.md) separates participant administration from fund
administration, but explicitly excludes legacy Czech transformed funds. A platform migrating
an existing pension company's complete portfolio must preserve those contracts, plans and
benefits, not merely accept a transfer into DPS. The current `ProductLine` contains DPS and
DIP; the target below is new work, not a statement of implemented capability.

Transformed-fund pension insurance (PP, *penzijni pripojisteni*) has plan-specific participant
rights and profit attribution. A DPS unit register alone cannot express those rights. Fund
portfolio valuation, distributable result and participant entitlements are distinct facts.
The MF overview describes the product distinction, and the CNB financial-stability report
explains the non-negative-return guarantee. Neither supplies the exact historical plan rules
or calculation assumptions needed by an implementation.

The existing service slices also need an explicit system-wide contract for installation,
investment settlement, accounting, close, migration, upgrades and recovery. Individually
correct services do not establish a complete operational lifecycle.

## Decision

We extend the target platform to administer existing transformed funds alongside DPS and
DIP, preserving their distinct financial models. This ADR **amends the transformed-fund
exclusion in ADR-0334**, rather than superseding its entire architecture. ADR-0337's separate
company ledger remains applicable. Existing implementation PRs remain the delivery path.

The scope is a complete Czech pension-platform technical lifecycle. Occupational pensions,
PEPP and other countries require separate capability decisions; this ADR does not claim their
implementation. Support is enumerated by **product × plan × operation × instrument**, with
explicit rejection of unsupported combinations. Synthetic development needs no production
legal identity or approval.

### 1. Plan-bound entitlements, separate from units

Introduce a pension-insurance product discriminator and a transformed-fund regime. Names
below describe proposed responsibilities; they do not prescribe a released wire enum.

- `PensionPlanRevision`: immutable source document/digest, effective interval, supported
  benefits, calculation rules and assumptions. A national jurisdiction pack cannot replace
  individual historical pension plans.
- `ContractPlanBinding`: the contract's plan revision and history of effective changes.
  Preserve both business-effective time and recorded time for retroactive processing.
- `ParticipantEntitlementLedger`: append-only contributions, attributed returns, consumed
  rights, refunds and corrections, with source, period, tax attributes and plan-specific
  benefit components. Distinguish participant, employer and state money.
- `BenefitEntitlement` and `BenefitSchedule`: eligibility, entitled persons, benefit basis,
  consumed/remaining rights and payment history.

Importing or taking over an existing contract is a different command and permission from
originating a contract. Migration must not bypass the closed-product origination policy.
Missing history is an explicit incomplete/quarantined state, never manufactured from an
opening balance. Corrections append linked entries; they do not rewrite prior entitlements.

### 2. Result attribution and guarantee funding

`ProfitAllocationRun` freezes its period, accounting result, eligible entitlements, fees,
rule/plan revisions and rounding inputs. Calculated, approved, posted and reconciled are
separate persisted states. Deterministic posting keys, concurrency control and resumable
batches prevent double attribution. An approved correction creates a linked corrective run,
including where a participant has already received a benefit or transferred out.

`GuaranteeAssessment` and `GuaranteeFundingObligation` distinguish a deficit, the funding
obligation, payment instruction and confirmed cash receipt. Clamping a negative return to
zero is not settlement of the obligation. Company and fund books have separate accounting
legs, matched to actual funding; no journal spans legal-entity books. Independent calculation
and reconciliation must explain all allocation and rounding residuals before completion.

### 3. Benefits and long-lived payout obligations

Support the applicable plan's retirement, service, disability and survivor pensions,
lump-sum settlement and surrender. A partial benefit consumes only its specified entitlement
components and preserves remaining rights. No universal age, duration, percentage or payout
formula is embedded in this ADR.

Where required, a versioned actuarial calculation port pins assumptions, tables and formula
versions and is checked against independent reference examples. A generic instalment
scheduler is not an actuarial engine. Persist the resulting obligation separately from its
payment schedule and execution. Death, recipient changes, failed delivery, suspension,
resumption and corrections must not duplicate benefit consumption or payments.

### 4. Coexistence and transitions

Keep three workflows distinct:

1. Technical migration to another system, retaining the same product and rights.
2. Transfer of portfolio administration, retaining the required historical provenance.
3. Product conversion from PP to DPS, with its own eligibility and settlement rules.

PP/DPS coexistence uses versioned contribution and incentive rules. External information
about other contracts has a source and validity period; a local database cannot establish
facts about every external provider. Unknown eligibility remains explicit.

PP-to-DPS conversion preserves funds provenance, qualifying duration, tax history and the
audit of surrendered/transferred rights. Target units are created against received money at
the applicable valuation, not by relabelling legacy balances. Assign ownership of in-flight
contributions, partial benefits, refunds and corrections throughout cutover. Do not infer
that the reverse product conversion is permitted.

### 5. Investment operations, books and close

For every supported instrument class, specify order, trade, partial settlement, cancellation,
fees, position, valuation, income and corporate-action handling. Include dividends, coupons,
maturities and applicable corporate actions; reject unsupported instruments instead of using
a generic pricing fallback. Shared adapters must not mix bank, company and fund positions.

Maintain distinct books for the company and funds, and an explicit mapping from each business
event to postings, reversal/correction and reconciliation. Reconcile external cash and
custodian positions, fund accounting, participant units or entitlements, and company books.
A separate deployment provides isolation but does not create these accounting mappings.

A close coordinator freezes the dependency order for cash intake, cut-off, business calendars,
value dates, positions/prices, fees, valuation, attribution and reports. Late data and reopening
are controlled operations. Portfolio valuation and PP profit attribution are separate jobs.
Reports distinguish company, participant fund and transformed fund and retain cell-to-source
lineage. Generation, transmission and acknowledgement remain separate outcomes.

### 6. Standalone deployment and external contracts

Define a versioned single-company deployment profile containing mandatory/optional services,
compatible image/API/event/schema versions, dependencies, initialization, upgrades and restore.
It must install into an empty supported environment without an undeclared banking deployment.

For identity, accounts, payments, custody, execution, pricing, documents and other collaborators,
declare whether the selected profile hosts an OpenBank component or binds an external adapter.
Each binding defines authentication, idempotency, timeout/retry, reconciliation and a simulator.
An external-bank binding must be an explicit supported option, not an assumed internal account.

Provider-specific database credentials, identities, keys, messaging routes, workflow namespaces
and document stores must preserve isolation during normal operations, export and recovery.
Two concurrent deployments must pass real-token positive and cross-provider negative tests;
sequential test JVMs are insufficient evidence.

### 7. Recovery, upgrades and operational exceptions

A restore plan reconciles databases, workflow histories, event delivery and documents to a
consistent recovery boundary. Query the external result of an uncertain payment before
retrying it. Test recovery after remote confirmation but before local acknowledgement.
Measure recovery objectives for the selected topology; individual database restore is not
proof of business-process recovery.

Specify mixed-version compatibility for APIs, events, schema and long-running workflows.
Upgrades preserve active pension schedules and historical calculation rules. Declare the
point after which forward repair replaces rollback. Rotate keys and certificates while
preserving access to required historical documents.

Reuse a common exception workbench for unmatched cash, pending valuation, settlement breaks,
failed benefits and corrections. Cases carry provider, stable correlation, reason, age,
permitted actions and actor history. Use controlled APIs and applicable maker/checker rules,
not direct SQL repair.

### 8. Migration, initialization and implementation boundaries

Import plans, contracts, entitlement components, contributions, returns, benefit consumption,
qualifying duration, tax history, entitled persons, documents and in-flight operations. Preserve
source identifiers, completeness controls and reconciliation evidence. Support final-delta
import, restart, independent parallel comparison and a documented cutover/rollback boundary.
Export the same information in a versioned, independently readable form with checksums.

Test clean initialization separately from migration: company setup, DPS fund setup, first NAV
and first subscription. Initializing a transformed-fund instance loads existing provenance;
it does not fabricate newly originated legacy contracts.

Reuse pension-service for participant orchestration and an isolated PP domain module;
pension-fund-service retains fund and portfolio responsibilities. Reuse infrastructure and
accounting primitives, not the DPS unit calculator for PP rights. A separate microservice is
not required merely for a product label; reassess only against demonstrated ownership or
operational boundaries.

Classify changes to `ProductLine`, OpenAPI and events for strict existing consumers. Additive
JSON enum values can still break them. Introduce Flyway migrations, versioned events, BFF/UI
updates and consumer/provider contracts with rollback/forward-repair notes. Do not reclassify
existing DPS/DIP records. Each plan package requires its own independently calculated tests.

### 9. Delivery and acceptance

The first deliverable is the capability matrix, data dictionary, source-of-truth/port map,
posting matrix and failure/recovery matrix. Do not start independent calculators without these
shared contracts. No new implementation PR is required to duplicate existing work.

| Work package | Existing tracking / follow-up | Acceptance boundary |
|---|---|---|
| Plan scope and contracts | #12475 | Product/plan/benefit/operation matrix and versioned inputs |
| PP entitlements and benefits | #12566 | Durable rights, plan-specific benefits and actuarial reference cases |
| TF result and guarantee | #12567 | Reproducible attribution, funded obligations and reconciled postings |
| Investments, books and close | #12477, #12425; ADR-0337 | Instrument lifecycle and event-to-book mappings |
| Migration and PP/DPS transitions | #12478, #12475 | Complete history, independent comparison and safe cutover |
| Standalone installation and isolation | #12479, #12472 | Empty-environment profile and concurrent real-token isolation |
| Recovery, upgrades and exceptions | #12479, #12474 | Failure-boundary recovery, workflow upgrades and supported operations |
| TF reporting and complete verification | #12425, #12426, #12427, #12392, #12479 | Reconciled reports/documents and one complete integrated lifecycle |

Plan contracts precede entitlement and attribution acceptance. Investment/accounting design,
deployment profile and migration-tool design can proceed in parallel. End-to-end acceptance
follows the integrated implementations; an open issue or accepted ADR does not prove delivery.

Required system scenarios:

- Two historical plans and two simultaneous companies, reusing source identifiers, with
  correct calculations and no cross-provider access.
- Period attribution with rounding, concurrent execution, partial restart and correction
  after benefit payment; identical reconciled results and no duplicate credit.
- Negative fund result with an explicit deficit and unfulfilled funding obligation until
  actual cash confirmation; no artificial success state.
- Partial service benefit preserving other rights, long-lived pensions, death and survivor
  benefits, compared with independent plan-specific calculations.
- PP/DPS coexistence and PP-to-DPS conversion preserving provenance without double cash
  allocation or unsupported incentive claims.
- Corporate action, partial settlement and late pricing across close, with matched positions,
  cash and participant accounting.
- Incomplete-history migration quarantined with an explainable reconciliation difference.
- Restore after confirmed external payment and upgrade of an active pension workflow without
  a second payout or schedule loss.
- Empty installation through TF import and DPS setup, contribution, close, benefit/conversion,
  report, export and restore in two isolated company deployments.

## Alternatives considered

- **Keep transformed funds out of scope.** The prior ADR's choice cannot support migration of
  a complete portfolio containing legacy contracts; retain it only as historical context.
- **Represent PP as another DPS strategy/unit type.** Rejected: plan rights, profit attribution
  and long-lived benefit obligations do not have the same semantics as subscriptions/redemptions.
- **Create a new service solely for the product label.** Not selected: first isolate the domain
  module and reuse orchestration/ports; deployment separation needs an actual operational reason.

## Consequences

**Positive**
- Existing portfolios retain historical rights and reproducible calculations.
- Installation, money, investment, accounting and recovery boundaries become explicit.
- Shared capabilities remain reusable without mixing distinct financial models.

**Negative**
- Historical plans and actuarial inputs require an explicit versioned model and reference data.
- Complete instrument support and recovery need cross-service evidence, not only unit tests.
- Contracts and migrations must account for long-lived workflows and strict enum consumers.

**Neutral**
- Decision accepted; implementation planned. No release, deployment or certification is implied.
- Synthetic development remains independent of production onboarding.

## Compliance impact

- PCI DSS: not applicable to this decision; it introduces no card-data processing.
- DORA: recovery, upgrade and third-party boundaries require deployment-specific applicability
  and control evidence; this ADR prescribes no universal statutory timing target.
- GDPR: historical participant/beneficiary data requires scoped access, retention, legal-hold,
  export and deletion handling across replicas, documents and backups.
- PSD2: payment adapters reuse the selected rail's controls; this decision grants no payment
  permission and does not weaken authentication.
- CNB: preserve plan-specific rights, segregated records and reporting lineage; source rules
  and assumptions need versioned verification, not inferred compliance from software structure.

## References

- [ADR-0334](0334-pension-fund-platform.md), [ADR-0337](0337-pension-company-books-as-a-separate-ledger-deployment-per-legal-entity.md)
- #12350 and the delivery table above
- [MF: pension-system overview](https://mf.gov.cz/cs/financni-trh/soukrome-penzijni-systemy/iii-pilir-doplnkove-penzijni-sporeni-a-p/zakladni-informace)
- [CNB: Financial Stability Report, Spring 2025](https://www.cnb.cz/export/sites/cnb/cs/financni-stabilita/.galleries/zpravy_fs/fs_2025_jaro/zfs_jaro_2025.pdf)
- Applicable pension-plan source documents and their immutable revisions are implementation
  inputs; overview pages and proposed reforms are not executable rule specifications.
