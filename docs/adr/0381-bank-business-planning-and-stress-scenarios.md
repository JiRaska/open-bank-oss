---
date: 2026-11-15
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture, admin-ui, regulatory-reporting, analytics]
summary: "Bank planning owns approved assumptions and forecast vintages; treasury and risk retain facts and calculation ownership. One reconciled projection powers business plans, stress, recovery and evidence under versioned methodology profiles."
---

# ADR-0381 — Bank business planning and stress scenarios on shared treasury and risk engines

## Context

ADR-0313 brings forecasting, profitability, capital and treasury into scope; ADR-0314 owns risk
snapshots, and ADR-0344 refines treasury cash-flow and valuation ownership. They do not decide
who owns a bank's approved commercial plan, successive forecast vintages, strategic initiatives,
or the reconciliation between commercial drivers and financial/prudential projections. This ADR
makes those decisions without replacing the existing engines. Delivery tracker: #16387.

The product is **the bank's business model and plan**, with stress testing of that same model:
customers, channels, products, volumes, prices, costs, profitability, funding and capital. Risk
ratios alone cannot establish that a strategy is commercially viable or executable.

### Verified starting point and reuse

Inspected main at `ddf5a3184b` on 2026-11-15; source presence is not deployment evidence.
Historical issue checklists are leads, not a current capability inventory.

| Existing owner / evidence | Reuse | Gap this programme must expose |
| --- | --- | --- |
| `openbank-risk-engine/.../domain/model/SnapshotRun.kt`, snapshot APIs | As-of inputs, provenance, reconciliation and retained runs | A commercial plan is not a snapshot; pin business and knowledge cut-offs and all source revisions |
| `.../domain/cashflow/SnapshotCashFlowProjection.kt`, `.../domain/liquidity/LiquidityForecast.kt` | Customer contractual/behavioural cash flows; existing funding ladder | Current forecast explicitly excludes new business and stress, caps horizon at 365 days and assumes opening HQLA monetisable on day zero |
| Risk `RiskResource` and `PricingResource` | IRRBB, LCR/NSFR, credit capital, limits, valuation, sensitivities, VaR and hypothetical pricing | API existence proves neither full regulatory scope nor validated calibration; total capital needs more than credit RWA |
| `openbank-treasury-service` cash-flow, market-data, reference and portfolio resources; ADR-0344 | ACTUAL/PLANNED/HYPOTHETICAL semantics, stored/versioned flows, fixing facts, collateral and book references where available | No re-generation that overwrites fixed/overridden flows; no implicit execution of a forecast |
| `openbank-libs-finance` (ADR-0344) | Schedule, accrual, day-count, calendar and pricing primitives | Extend shared primitives only at their owner; no planning-local financial math fork |
| Product catalog, lending, billing, interest and analytics-sink | Pinned product terms, credit/fee facts and governed commercial aggregates | Acquisition, churn, staffing and external market data need explicit source contracts; absence is not zero |
| ADR-0096/0097/0367, `/regulatory`; #16356 | Attested close, returns, reporting context, isolated ingestion/worker design | Projections are distinct from filed actuals; new plan exports are not new filing authority |
| `/balance-sheet`, `/treasury`, shared admin BFF | Existing navigation, drill-downs and authorization | Add `/strategy` with the existing UI primitives, not another treasury console |
| ADR-0379 compliance trace; ADR-0141 model provenance | Obligation/control/evidence and model-governance conventions | Proposed dependencies remain labelled; do not build a competing compliance or model registry |

Existing backlog ownership is retained: #10618 risk/treasury programme; #11107 unverified
regulatory mappings/factors; #13434 benchmark reference definitions; #13440 numeric bands;
#9891 COREP C 07.00; #16356 reporting isolation/context. Implementation must re-read each ticket
and current code before adding work. Completed slices are consumed, not re-opened wholesale.

### Methodology and critical assessment

The [methodology crosswalk](../design/bank-business-plan-methodology.md) records primary sources,
section-level expectations, applicability, delivery slices and falsifiable evidence. It is a design
crosswalk, not another live compliance register. Its requirements migrate into the ADR-0379 trace.

The original UI proposal was incomplete in six material ways: no strategy/competitive evidence;
no complete three-statement and prudential bridge; no independent model-validation lifecycle;
no realistic recovery capacity; no long-horizon environmental analysis; and no evidence that
heavy modelling cannot degrade customer services. These are required delivery scope below.

As of 2026-11-15, EBA's revised SREP guidance and environmental scenario guidance point to
2027 application; EBA's 2027 exercise methodology is published but its official scenarios are
scheduled for January 2027. Those are external methodology dates, **not an invented launch
commitment for this repository**. Institution applicability and national implementation must be
reviewed before enabling a profile. The old ECB ICAAP PDF is marked outdated; the July 2026
clarification is verified, and the replacement full guide must be pinned before ICAAP approval.

## Decision

### D1. Planning owns intentions; existing domains own facts and calculations

Create a logically separate `planning` package in `openbank-risk-engine` with domain/application/
infrastructure layering and plan-specific tables/APIs. It owns plans, assumption revisions,
forecast vintages, scenario composition, action portfolios and run orchestration. Start in the
existing service; this ADR does not authorize a new always-on service or duplicate warehouse.
Extract a service only after measured isolation or lifecycle needs justify a further decision.

Calculation ownership remains explicit:

- Risk engine: curves, valuation, ALM, liquidity, credit-capital/risk measures and projected
  financial reconciliation, with separate adapters for each methodology.
- Treasury: actual deals and cash-flow facts, market/reference data and execution. Scenario-only
  instruments remain hypothetical overlays. Existing PLANNED deals are referenced by revision
  and included only by an explicit scenario selection; never counted again as generated funding.
- `openbank-libs-finance`: reusable pure schedule/pricing primitives. Lending/IFRS 9 and product
  interpreters retain their policies; scenario adapters reuse them or a versioned validated
  projection model, never duplicate a live credit decision or silently promote a projection.
- Ledger/statutory close: booked actuals and attested closes. Reporting services: statutory
  mappings, return validation and submission lifecycle. Analytics: governed source aggregates.

There is no plan-to-ledger posting, automatic deal booking, rate publication or filing permission.
A planning suggestion may become a separate human-governed proposal at the owning service.

### D2. Source contracts, perimeters and reproducible vintages precede computation

A `PlanningRun` pins the plan revision, scenario/methodology profile, source snapshot manifest,
source watermarks, business date and recorded-time cut-off, curves/fixings, product revisions,
model/parameter versions, rounding, code version and any random seed. Hash canonical inputs and
outputs; retain referenced bytes/artifacts under an approved retention policy, not only hashes.

Reuse ADR-0367/0380 institution, establishment, booking and reporting contexts. Identify solo /
subconsolidated / consolidated perimeter and effective membership/elimination rules. A branch
is not another bank; FOS is not a subsidiary. Separate deployments stay separate authorities
(ADR-0152/0337); cross-book aggregation requires authorized inputs and explicit eliminations.
Do not add a generic tenant axis to every service. Unknown context blocks publication.

Actuals are split into attested closed periods and labelled provisional observations. Corrections
create new source revisions and reruns; a historic approved run remains reproducible. Frozen
approved plan, latest forecast and prior forecasts never share a mutable result identity.

Each input declares owner, field/API/event, keys, unit/currency, stock/flow/time convention,
coverage, history, refresh expectation, privacy classification and reconciliation rule. External
market/competitor data has source date, licence and freshness. HR/procurement inputs are governed
imports where no owner API exists. Synthetic facts are excluded from production scope according
to the applicable policy; mixed or incomplete inputs cannot become unlabelled production output.

### D3. Business plans are driver-based and explainable

Support a minimum 36-month monthly plan, annual strategic extensions, and linked daily/intraday
liquidity analysis where material. A different horizon is selected by methodology, not forced
through the current 365-day liquidity API. Long-term environmental analysis extends to at least
ten years where required; precision and uncertainty are shown honestly.

Drivers include acquisition/conversion/churn, cohorts, channel and product mix, utilisation,
new originations, amortisation/prepayments, deposit migration and price elasticity, transaction
activity/fees, rate pass-through, staffing/capacity, fixed/variable/step costs, capex/depreciation,
taxes, dividends, capital issuance and funding costs. Stocks use roll-forwards; rates use
appropriate average balances and cash-flow dates, not year-end balance times annual rate.

Use pinned catalog/fee/pricing definitions (ADR-0341/0352 direction), and shared finance maths.
Model FTP as an extension of the ADR-0313 risk capability: one approved versioned curve/policy,
not a second service. Internal FTP charges/credits eliminate in bank totals; segment profit
reconciles to bank profit with explicit unallocated overhead. RAROC, ROE and cost/income carry
numerator/denominator definitions; undefined ratios remain undefined.

Record strategic objectives, market/peer positioning, concentrations, distribution/outsourcing
dependencies, initiative owners, milestones, budgets and execution capacity. Retain historical
plan-versus-actual and forecast error by vintage. Narrative assertions link to evidence and a
human judgment; the platform does not invent a supervisor's BMA score.

### D4. One integrated financial projection, distinct accounting and risk views

For every period reconcile assets = liabilities + equity; beginning cash + movements = ending
cash; retained earnings bridge from opening balance through profit, distributions and adjustments.
Keep P&L, OCI, impairment, write-offs/recoveries, tax/DTA and regulatory deductions distinct.
Reconcile gross/net carrying amounts, off-balance commitments and FX translation.

Funding gaps remain visible. Only an explicit constrained funding action may close one. Include
settlement timing, encumbrance, collateral reuse restrictions, haircuts, margin calls, maturity
concentration, depositor concentration and currency/entity transferability. Extend the existing
HQLA day-zero assumption at the risk owner with monetisation timing and eligibility evidence.

Capital views include own-funds composition, total applicable RWA (credit, market, operational,
counterparty/CVA where applicable), CET1/Tier 1/total ratios, leverage and MREL where applicable.
Distinguish Pillar 1, P2R, combined buffers, P2G, internal management targets and recovery triggers;
never add them into an undifferentiated legal minimum. Pin requirement effective dates and bank-
specific decisions. An incomplete denominator cannot be labelled total capital adequacy.

ICAAP economic and normative perspectives inform each other but retain their own definitions.
ILAAP contractual/behavioural survival and regulatory ratios remain separate views. No generic
scalar converts EVE, economic capital, CET1 or cash into each other. Missing material dimensions
block the affected approval; implementation of credit RWA alone is not ICAAP delivery.

### D5. Scenarios are coherent, purpose-bound transformations

A scenario has a narrative, purpose, geography/currency/sector scope, horizon, macro/market paths,
severity justification, source revision, transmission functions and assumptions about responses.
Distinguish sensitivity, historical, hypothetical, combined/idiosyncratic/system-wide, reverse
stress and environmental strategy resilience. Cover credit migration/default/collateral,
interest/basis/option/spread/FX, funding/run-off, concentration, operational/conduct/legal,
cyber/third-party, geopolitical and physical/transition environmental drivers where material.

Use one risk inventory and transmission graph. Joint shocks and second-round effects are bounded
and validated; do not multiply unrelated sliders and call that a coherent recession. Preserve
baseline, gross stressed impact, automatic behavioural response and discretionary management
actions separately. Avoid counting one loss in credit, operational and strategic buckets twice.

Profiles distinguish dynamic business planning, run-off and constrained/static supervisory
exercises. Static does not mean frozen data or no contractual replacement: apply the pinned
exercise's exact replacement, growth, floor/cap and action restrictions. EBA exercise adapters
are versioned independently of internal forecasting; unreleased official scenarios stay missing.

### D6. Feasible management actions and reverse stress use the same projection

Actions carry owner, approval, trigger, earliest start, completion curve, cost, capacity, legal/
operational prerequisites, counterparty/market availability and effects on growth, P&L, cash,
capital and franchise. Model mutual exclusions, dependencies, asset/collateral consumption and
execution uncertainty. Show gross and net benefits; do not sum mutually incompatible recovery
options or assume new capital/funding is instantly available during a market-wide crisis.

Reverse stress states a failure criterion (including business-model non-viability), search domain,
constraints, solver version, convergence and sensitivity. Report a conditional boundary, not a
probability of failure or a unique global minimum without proof. Recovery-capacity evidence
reuses the action set and BRRD/EBA constraints; a dashboard is not an approved recovery plan.

### D7. Governed forecast/model and plan lifecycles

Separate model developer, independent validator, plan maker, approver and read-only auditor.
Plans progress through draft, review and approved immutable revision, with supersession rather
than mutation. Board/ALCO decisions, challenge, objections, limitations, approvals and capital/
liquidity adequacy statements are linked evidence. Reuse approval/audit primitives (ADR-0227/
0133); backend checks enforce separation, not only UI buttons.

Forecasting starts with transparent benchmarks and backtesting; calibrated statistical/ML
models enter only with point-in-time training data, no look-ahead, out-of-time validation,
uncertainty/interval coverage, bias and stability checks, champion/challenger comparison and
approved fallback. Model inventory covers deterministic, expert and statistical models, including
behavioural deposits/prepayments and macro-to-credit relationships. Record independent validation,
materiality, permitted uses, expiry/review triggers and outstanding findings.

AI may draft assumptions/scenarios and explain result attribution with run/cell/source citations.
It cannot calculate authoritative figures, mark a model valid, approve a plan, waive a quality
block, book or file. No invented causal explanation when decomposition is unavailable.

### D8. Tabular workbench and exports share a backend result

`/strategy` provides business-model overview, assumptions, customers/products, P&L, balance sheet,
cash flow, funding/capital, scenario comparisons, actions, model validation and evidence. Reuse
existing treasury/risk drill-down routes. Provide actual/approved-plan/latest-forecast/prior-
forecast/stress/post-action comparisons, period and cohort breakdown, and additive variance
attribution with explicit interaction/residual terms.

Rows and periods form an accessible spreadsheet-like grid with units, dates, origin, freshness,
quality and input/calculated status. Only assumptions are editable; calculations stay backend-
owned. Human labels precede identifiers. Enforce current server authorization on job, cell,
source drill-down and artifact access; a filtered UI is not a perimeter boundary.

XLSX contains pinned values, assumption/formula descriptions, definitions, controls, provenance
and approval state from the exact same result object as the UI; an explanatory formula is not a
second authoritative engine. Escaping, formula injection, external links/macros and resource
bounds are tested. Imported workbooks use a constrained assumption template and create a reviewed
new revision, never overwrite actuals/results or execute arbitrary spreadsheet formulas.

ALCO/board/ICAAP/ILAAP evidence packs carry the same manifest. Official EBA/ČNB templates require
separate versioned adapters, required validation and applicable reporting lifecycle under
ADR-0367. A convenient XLSX is never labelled a filed or compliant return merely because it opens.

### D9. Methodology coverage is versioned evidence, not a badge

Extend existing obligation/control/evidence sources (ADR-0367/0379) with methodology profiles:
source URL and retained revision/hash, section/paragraph, legal/guide/exercise/internal status,
effective dates, jurisdiction/institution/perimeter, proportionality, materiality, owner,
interpretation, implementation and evidence references. Source retrieval is not legal approval.

Use explicit APPLICABLE / NOT_APPLICABLE_WITH_REASON / UNRESOLVED decisions, separate from
implementation and evidence freshness. A requirements universe comes from section-by-section
review of each in-scope source, including reasoned exclusions; this ADR's crosswalk is the starting
inventory, not a claim to have encoded every paragraph. Changes trigger reviewed profile deltas
and revalidation, never silent retrospective recalculation. #11107 remains the source-factor
verification owner; uncertainty propagates through dependent measures and exports.

### D10. Isolated execution and measurable release gates

Reuse #16356 ingestion/manifest/worker isolation contracts: plan runs and exports use bounded
queues, pools and CPU/memory/IO budgets, cancellation, fair scheduling, restart/replay and retained
artifacts. A long run holds no operational transaction and never falls back to scanning live
customer APIs. Recomputing a plan is not a synchronous dependency of a payment or account request.
Retain domain ownership while separating expensive workers physically as evidence requires.

Test customer peak traffic concurrently with planning peak, backfill, export, worker/database/
broker failure and catch-up. Record source load and customer latency/errors against existing SLOs
and approved incremental budgets, plus planning completion deadlines. Degraded inputs or queue
starvation must be visible; zero interference is not asserted without measurement.

## Alternatives considered

| Alternative | Assessment |
| --- | --- |
| Independent spreadsheet as the model of record | Flexible but duplicates formulas, source cuts and approvals; retain it as controlled input/export instead |
| New planning service with its own ALM/treasury engines | Independent deployment but creates calculation and ownership forks; start with a bounded module and isolated workers |
| Extend treasury into the bank-wide planning authority | Mixes hypothetical strategy and real deal execution; treasury remains the fact/execution owner |
| Treat existing liquidity forecast as the complete business plan | Cannot represent commercial growth, costs or strategy and explicitly excludes stress; extend shared primitives, preserve endpoint semantics |
| One universal regulatory/forecast scenario | Incompatible static/dynamic, accounting/economic and jurisdiction assumptions; use explicit profiles over shared primitives |

## Consequences

- One plan explains growth, profit, funding and capital, with provenance and realistic action costs.
- This is a multi-slice programme, not a UI-only enhancement. Data history, market/HR inputs,
  calibration and independent validation remain delivery costs, even when code is reused.
- A current limitation in risk or reporting is inherited visibly until its owner closes it;
  publishing an attractive ratio does not remove that limitation.
- Privacy applies to contract/cohort data and small groups. Use minimized aggregates, purpose-
  bound drill-down, current access checks and an institution-approved retention/erasure policy.
- Proposed enforcement: `planning-run-integrity` (manifest/quality/invariants/replay),
  `planning-methodology-coverage` (complete source-section disposition and resolved evidence),
  `planning-no-execution` (negative authorization and owner-boundary tests), and real-backend
  workbook parity tests. Introduce each gate with positive and negative fixtures at its feature
  slice; enforce before approval/publication is enabled. None is claimed to exist today.
- Reuse existing `treasury-parity-fixtures`, ADR registry/delivery checks, source/contract and
  compliance trace gates; code presence or a referenced test alone is not successful execution.

### Delivery check

The following future checks intentionally fail until delivery exists. Their paths are an
acceptance contract for the implementation issues, not commands claimed available today.

```bash
python3 .github/scripts/check-planning-run-integrity.py --enforce # expect exit 0 after delivery
python3 .github/scripts/check-planning-methodology-coverage.py --enforce # expect exit 0 after delivery
python3 .github/scripts/check-planning-no-execution.py --enforce # expect exit 0 after delivery
```

Release additionally needs a retained real-backend synthetic run: reconciled opening snapshot,
36 monthly periods, baseline plus combined stress, feasible actions, identical UI/XLSX cells,
repeatable original run after a correction, rejected cross-context reads and prohibited writes,
independent validation, and reviewed load/failure evidence. A skipped test is not proof.
Delivery remains `planned` until implementation evidence exists; initial usable slices move it
to `partial`, with the unbuilt scope linked. Source review and issue creation do not count as build.

### Implementation sequence

Children of #16387 are the actionable backlog. The methodology crosswalk uses their stable slice
IDs so requirement ownership does not depend on issue titles.

| Slice | Deliverable | Depends on |
| --- | --- | --- |
| [M01 #16389](https://github.com/JiRaska/open-bank-oss/issues/16389) | Version methodology applicability and obligation-to-evidence coverage | — |
| [M02 #16390](https://github.com/JiRaska/open-bank-oss/issues/16390) | Bind planning actuals to reconciled source snapshots and historical perimeters | M01 |
| [M03 #16391](https://github.com/JiRaska/open-bank-oss/issues/16391) | Add immutable business-plan revisions and forecast-vintage approval lifecycle | M01, M02 |
| [M04 #16392](https://github.com/JiRaska/open-bank-oss/issues/16392) | Project commercial drivers and reconciled segment profitability with shared FTP | M02, M03 |
| [M05 #16393](https://github.com/JiRaska/open-bank-oss/issues/16393) | Reconcile projected P&L, balance sheet, cash flow and funding needs | M02, M03, M04 |
| [M06 #16394](https://github.com/JiRaska/open-bank-oss/issues/16394) | Integrate complete applicable capital and usable liquidity into the plan | M01, M02, M05 |
| [M07 #16395](https://github.com/JiRaska/open-bank-oss/issues/16395) | Execute coherent stress profiles and constrained supervisory scenarios | M01, M05, M06 |
| [M08 #16396](https://github.com/JiRaska/open-bank-oss/issues/16396) | Model feasible management actions, reverse stress and recovery capacity | M06, M07 |
| [M09 #16397](https://github.com/JiRaska/open-bank-oss/issues/16397) | Validate forecasts and behavioural models independently with vintage backtests | M01, M02, M03 |
| [M10 #16398](https://github.com/JiRaska/open-bank-oss/issues/16398) | Build the strategy workbench and safe backend-identical XLSX | M03, M04, M05, M06, M09 |
| [M11 #16399](https://github.com/JiRaska/open-bank-oss/issues/16399) | Add environmental and operational strategy-resilience transmission | M01, M04, M07, M09 |
| [M12 #16400](https://github.com/JiRaska/open-bank-oss/issues/16400) | Produce board and supervisory evidence packs through existing reporting | M01, M06, M07, M08, M09, M10, M11 |
| [M13 #16401](https://github.com/JiRaska/open-bank-oss/issues/16401) | Prove isolated planning execution and end-to-end release evidence | M02, M03, M05 |
| [M14 #16402](https://github.com/JiRaska/open-bank-oss/issues/16402) | Add source-cited planning assistance with proposal-only authority | M03, M07, M09, M10, M13 |

Sequence: M01–M03 establish authority and data; M04–M06 deliver bank economics; M09
starts alongside calculation work, and M13 establishes isolation early. M07–M08 and M11
add stress/recovery breadth. M10 exposes proven results; M12 packages applicable evidence.
M14 follows validated deterministic attribution and never gates the useful non-AI product.

Each implementation PR uses explicit scope, contract tests for API changes, forward-only
migrations with rollback notes, and the normal ship checks. Money-path changes at treasury,
ledger or lending retain two reviews and threat-model updates. Documentation approval here
neither approves production models nor authorizes money movement.

## Compliance impact

- **ECB/EBA/ČNB:** supports business-model assessment, capital/liquidity adequacy, stress and
  recovery evidence under the crosswalk. Applicability, approved methods and actual evidence
  remain necessary; no blanket compliance or supervisory-score claim.
- **DORA:** model/reporting workload isolation and operational scenarios reuse resilience
  controls; this programme does not replace ICT incident or continuity processes.
- **GDPR:** minimized commercial/contract data, scoped artifacts and model-training controls;
  aggregated data is not automatically anonymous.
- **PCI DSS:** no cardholder data is required; use aggregated activity only.
- **PSD2/SCA:** no new payment-execution capability. Any later execution handoff retains the
  owning service's approval and authentication rules.

## References

- ADR-0022/0023/0026/0286: analytics, reconciliation and governed reads.
- ADR-0031/0034/0133/0141/0227: AI boundaries, authorization, audit, model provenance and approvals.
- ADR-0078/0096/0097/0207/0342: close, actuals, reporting and business-date authority.
- ADR-0152/0337/0367/0379/0380: deployment/perimeter, reporting and compliance ownership.
- ADR-0313/0314/0315/0317/0318/0341/0344/0352: risk, treasury, finance primitives and product semantics.
- [Primary-source methodology crosswalk and critical gaps](../design/bank-business-plan-methodology.md).
- [Delivery tracker](https://github.com/JiRaska/open-bank-oss/issues/16387).
