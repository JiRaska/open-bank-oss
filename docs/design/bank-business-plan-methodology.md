# Bank business planning: methodology crosswalk and critical gaps

Design companion to [ADR-0381](../adr/0381-bank-business-planning-and-stress-scenarios.md).
Source review date: **2026-11-15**. Programme: [#16387](https://github.com/JiRaska/open-bank-oss/issues/16387).
This is a proposal and implementation acceptance map, not a legal opinion, complete paragraph-level
inventory, production validation or certification. Runtime applicability/evidence belongs to the
existing ADR-0367/0379 registers. Slice IDs below resolve in the ADR's implementation table.

## Applicability and source discipline

A Czech bank is not automatically an ECB directly supervised significant institution or an EBA
EU-wide stress-test participant. Select jurisdiction, institution category, supervisor, legal
entity/establishment and consolidation perimeter first. EBA guidelines, ECB supervisory
expectations, EU law, ČNB interpretation, individual decisions and internal targets have distinct
status. Apply proportionality to justified scope/depth, not to hiding a material risk.

M01 must enumerate every section of each applicable source with an implementation/control,
external process or reasoned exclusion; record exact paragraph/version and reviewer. Unresolved
source revisions are blockers, not silently inherited defaults. Publication date, application
date, reporting reference date and scenario date are independent. Preserve historic profiles.

## Primary-source register

| ID | Source and locator | Status / use as of review date |
| --- | --- | --- |
| S01 | [ECB BMA methodology, 2025](https://www.bankingsupervision.europa.eu/activities/srep/2025/html/ssm.srep202511_businessmodelassessmentsrepmethodology.en.html), phases 1–3 | SI supervisory methodology; assess business viability, strategic sustainability, profitability drivers and execution/over-cycle evidence; benchmark elsewhere only with explicit applicability |
| S02 | [EBA SREP EBA/GL/2022/03](https://www.eba.europa.eu/sites/default/files/document_library/Publications/Guidelines/2022/EBA-GL-2022-03%20Revised%20SREP%20Guidelines/1028500/Final%20Report%20on%20Guidelines%20on%20common%20procedures%20and%20methodologies%20for%20SREP%20and%20supervisory%20stress%20testing.pdf), Titles 4–9 and 11; [2026 revision](https://eba.europa.eu/sites/default/files/2026-06/fd5fbfa1-2efb-4122-8e91-4831469d8150/Final%20Report%20on%20revised%20SREP%20and%20supervisory%20stress%20testing%20Guidelines.pdf), application section | Retain current and future profiles. Revised guidance states application from 2027-01-01; verify national implementation and section renumbering before activation |
| S03 | [EBA institutions' stress-testing guidance](https://www.eba.europa.eu/guidelines-stress-testing?phase=consultation), final EBA/GL/2018/04, sections 4.1–4.8; [ČNB adoption notice](https://www.cnb.cz/cs/dohled-financni-trh/legislativni-zakladna/obecne-pokyny-evropskych-organu-dohledu/Sdeleni-CNB-o-obecnych-pokynech-EBA-k-zatezovemu-testovani-provadenemu-institucemi/) | Institutions' own programme: governance, infrastructure, scope, methods, severity, reverse stress and use. Landing-page retrieval was restricted; final-text paragraph inventory remains M01 acceptance |
| S04 | [ECB ICAAP Guide, July 2026](https://www.bankingsupervision.europa.eu/ecb/pub/pdf/ssm.supervisory_guides202607_icaap.en.pdf), §1.2 and Principles 1–7; [ECB ICAAP/ILAAP clarification, July 2026](https://www.bankingsupervision.europa.eu/ecb/pub/pdf/ssm.ICAAP_ILAAP_clarification_202607.en.pdf), §§2–3 and annex | The full July guide replaces the archived 2018 ICAAP PDF. The clarification covers continuous governance and submissions. Management buffer is the bank’s own planning assessment. Assess direct applicability for significant supervised entities and check national and institution-specific requirements separately. |
| S05 | [ECB ILAAP guide](https://www.bankingsupervision.europa.eu/ecb/pub/pdf/ssm.ilaap_guide_201811.en.pdf), principles 1–7 | Governance, economic/normative perspectives, material risks, buffers/funding, independent validation and stress. Read alongside July 2026 clarification, and check current revision at activation |
| S06 | [EBA ICAAP/ILAAP information EBA/GL/2016/10](https://www.eba.europa.eu/documents/10180/1645611/6fa080b6-059d-4b41-95c7-9c5edb8cba81/final%20report%20on%20guidelines%20on%20icaap%20ilaap%20%28eba-gl-2016-10%29.pdf), common, ICAAP-specific, ILAAP-specific and assurance information | Evidence-package structure, separate from calculation and filing eligibility; use S04's current submission clarification |
| S07 | [EBA EU-wide stress testing, 2027 methodology and templates](https://www.eba.europa.eu/risk-and-data-analysis/risk-analysis/eu-wide-stress-testing), publication 2026-11-09; [release](https://www.eba.europa.eu/publications-and-media/press-releases/eba-streamlines-2027-eu-wide-stress-test-more-55-reduction-requested-data) | Exercise-specific constrained/static approach, not universal planning method. Final methodology and draft templates/guidance are published; official scenarios are scheduled for January 2027. Pin exact artifacts and QA constraints in M07/M12; do not fabricate scenarios or claim participation |
| S08 | [ECB RDARR guide, May 2024](https://www.bankingsupervision.europa.eu/ecb/pub/pdf/ssm.supervisory_guides240503_riskreporting.en.pdf), supervisory expectations; [BCBS 239 publication](https://www.bis.org/media-releases/20130109-principles-effective-risk-data-aggregation-and-risk-reporting-final-document-issued-basel) | Data governance, integrated architecture, quality, timeliness and adaptability; BCBS scope and local extension must be recorded, not assumed universal |
| S09 | [EBA overall recovery capacity EBA/GL/2023/06](https://www.eba.europa.eu/sites/default/files/document_library/Publications/Guidelines/2023/EBA-GL-2023-06/1061158/Final%20Report%20on%20GLs%20on%20overall%20recovery%20capacity%20in%20recovery%20planning.pdf), recovery-option selection, adjustments and capacity | Feasible scenario-dependent actions and aggregate recovery capacity; actual BRRD recovery-plan governance/indicators remain explicit dependencies |
| S10 | [EBA ESG management guidance](https://www.eba.europa.eu/publications-and-media/press-releases/eba-publishes-its-final-guidelines-management-esg-risks), EBA/GL/2025/01, materiality and plans; [environmental scenario guidance](https://www.eba.europa.eu/sites/default/files/2025-11/170da4c8-9b56-4fb0-ad60-94d433b7e866/Guidelines%20on%20environmental%20scenario%20analysis.pdf), sections 4–6 | ESG management applies from 2026-01-11, with later treatment for small/non-complex institutions no later than 2027-01-11 subject to applicable implementation. Environmental scenario analysis applies from 2027-01-01; climate-first environmental scope must not be mislabelled as a mandatory social/governance scenario module |
| S11 | [EBA IRRBB/CSRBB EBA/GL/2022/14](https://www.eba.europa.eu/sites/default/files/document_library/Publications/Guidelines/2022/EBA-GL-2022-14%20GL%20on%20IRRBB%20and%20CSRBB/1041754/Guidelines%20on%20IRRBB%20and%20CSRBB.pdf), internal systems and CSRBB | Economic-value and earnings views, behavioural assumptions and spread risk. Supervisory shocks/thresholds need their own applicable RTS revision; reuse existing risk implementation and #11107 verification |
| S12 | [ČNB supervisory communication 3/2025](https://www.cnb.cz/export/sites/cnb/cs/dohled-financni-trh/.galleries/vykon_dohledu/dohledove_benchmarky/download/dohledove_sdeleni_2025_03.pdf), funding/liquidity principles; [2/2023](https://www.cnb.cz/export/sites/cnb/cs/dohled-financni-trh/.galleries/vykon_dohledu/dohledove_benchmarky/download/dohledove_sdeleni_2023_02.pdf), FX lending/funding | CZ applicability anchor for funding, liquidity and currency mismatch. The 2025 communication sets principles rather than prescribing this product's implementation |
| S13 | [CRD consolidated legal source](https://eur-lex.europa.eu/eli/dir/2013/0036), notably Articles 73, 74, 76, 86, 87a and 97 | Primary-law anchor; pin applicable consolidation, national transposition and bank-specific decisions. M01 also inventories applicable CRR/CRR3, LCR delegated rules, MREL/BRRD and accounting sources rather than deriving legal factors from this ADR |

An unavailable full source is not a licence to rely on a generated summary. Sources above support
the programme's scope; M01 closes exact-version/paragraph and local-applicability gaps before a
regulated profile is approved. No regulator is claimed to require this precise UI or an XLSX format
for every submission. Transport and format come from the specific authority specification.

## Requirements → implementation → falsification evidence

The capability column is the architecture's proposed response, not a verbatim regulatory rule.

| Requirement | Methodology locator | Capability / accountable function | Slice | Evidence that can fail |
| --- | --- | --- | --- | --- |
| R01 Applicable universe and change control | S02 application + S13; S04 revision caveat | Compliance owns section inventory, jurisdiction/SI/SNCI/exercise applicability, dates and approved exclusions | M01 | Missing section disposition, unsupported source revision or expired review prevents approval; future rules cannot alter prior runs |
| R02 Commercial viability and strategy sustainability | S01 phases 2–3; S02 Title 4 | CFO/strategy: short-term earnings, ≥3-year strategy, peers, concentrations, execution resources and initiative tracking | M03/M04/M10 | Plan shortfall attribution, dated external evidence, capacity-constrained growth and historical delivery record; missing judgment/evidence remains unresolved |
| R03 Integrated bank economics | S02 Title 4; S04 key-content areas | Finance: stock/flow, accrual/cash, P&L/OCI/tax/equity and funding bridges | M05 | Every projected month balances; profit reconciles to equity; a funding shortfall cannot disappear via a plug |
| R04 ICAAP two perspectives and capital quality | S04 Principles 3–5; S02 Titles 6–7 | Risk/finance: normative/economic bridge, material risks, total RWA, leverage, capital composition and requirements | M06 | Credit-only denominator rejected as total; P2G/internal targets distinguished from legal requirements; change in risk inventory affects scope |
| R05 ILAAP usable funding and liquidity | S05 principles 3–7; S12 | Treasury/risk: currency/entity survival, monetisation time, encumbrance, margin/collateral, concentration and transfer restrictions | M06 | Same collateral cannot be sold and pledged; illiquid HQLA cannot be spent at t=0; FX surplus cannot silently cover another currency gap |
| R06 Stress programme governance and use | S03 sections 4.1–4.5, 4.8 | CRO/board: policy, materiality inventory, cadence, ad-hoc triggers, challenge and recorded decisions | M01/M03/M07 | A published scenario without owner, severity rationale, review or action disposition fails approval |
| R07 Coherent broad risk transmission | S03 sections 4.6–4.7; S11 | Risk: combined/systemic/idiosyncratic shocks, migration/default, IRRBB/CSRBB, FX/market, concentration and feedback | M07/M06 | Severe-but-plausible joint scenario has traceable paths; duplicated losses and unsupported shock dimensions are detected |
| R08 Reverse stress and recovery capacity | S03 reverse-stress provisions; S09 | Risk/treasury: failure boundary, realistic actions, sequencing, timing and recovery indicators | M08 | Incompatible asset sales/repo and unavailable funding are excluded; solver reports non-convergence; post-action result differs from gross stress |
| R09 Independent validation and model uncertainty | S05 principle 6; S04; S03 methodology review | Independent validation: inventory, use restrictions, challenger, sensitivity, stability and findings | M09 | Developer cannot validate own model; leakage fixture fails; expired/failed material model validation blocks governed use |
| R10 Forecast accuracy and learning | S01 phase 3 track record; architecture control | Finance/risk: forecast vintages, benchmark comparison, bias/interval coverage and drift | M09/M03 | Backtest uses information available at that vintage; corrected data cannot silently improve historical forecast accuracy |
| R11 Data quality and reproducibility | S08; S03 infrastructure | Data owners: cut-offs, reconciliation, lineage, stale/missing status, retention and correction handling | M02 | Missing rates, duplicate events, wrong unit/currency or untied actuals block dependent outputs; original run replays after correction |
| R12 Perimeter and transferability | S02 scope; S05; ADR-0367/0380 | Finance/compliance: legal-bank/branch, solo/consolidated membership and eliminations | M02/M05 | Bank+branch are not double counted; overlapping groups reconcile; cross-context artifact/drill-down read is denied |
| R13 Environmental strategy resilience | S10 sections 4–6 | Strategy/risk: physical/transition paths, sector/geography data, short/medium/long horizon, adaptation | M11 | Long-term path survives beyond 36 months; missing exposure mapping prevents complete-coverage claim; transition and flood stress stay distinguishable |
| R14 Operational and structural strategy risks | S01 phase 3; S02 revised cross-cutting dimensions | Strategy/operations: cyber, outsourcing, conduct/legal, geopolitical, competition and business interruption | M11 | Outage changes revenue/cost/retention via evidenced assumptions; overlapping operational/credit loss is not double counted |
| R15 Reporting and board evidence | S06; S04 sections 2–3/annex; S07 | Finance/regulatory: signed adequacy statements, challenge records, model limitations, immutable evidence packs | M12 | Artifact ties to run and approvals; unapproved or synthetic run cannot be submitted as production; QA/template version mismatch blocks export eligibility |
| R16 Tabular explainability and controlled end-user computing | S08 reporting; product choice | Finance/admin UI: backend-backed grid, lineage, comparisons, safe XLSX and reviewed assumption import | M10 | UI/XLSX parity on actual artifact; altered calculated cell/imported formula is rejected; authorization cannot be bypassed by export |
| R17 Operational isolation and continuity | ADR-0367 isolation; S03 infrastructure; DORA architecture | Platform/risk: bounded asynchronous workloads, fair scheduling, cancellation and recovery | M13 | Concurrent load/failure/catch-up stays within approved customer SLO budgets and planning deadlines; source/API scan fallback fails boundary test |
| R18 Governed assistance | ADR-0031/0367; internal control | AI may propose/explain with sources; humans approve | M14 | Adversarial prompts cannot calculate authoritative figures, change validation status, book, file or reveal another context |

## Critical design corrections and release blockers

1. **Existing is not complete.** Current risk endpoints are valuable reuse, but the liquidity
   forecast explicitly excludes new business and stress. Its opening-HQLA convention is not a
   tested emergency monetisation model. M05/M06 extend the owner and preserve old semantics.
2. **A balanced model can still be wrong.** Accounting identities need independent economic
   oracles, calibration and risk completeness. Golden fixtures generated by the same engine are
   not independent validation. M09 and M13 require both positive and deliberately wrong cases.
3. **Capital is not credit RWA alone.** Market/operational/CCR/CVA, deductions, buffers and leverage
   need applicable coverage or explicit exclusion. Do not present an incomplete ratio as adequate.
4. **Management action optimism is a model defect.** Execution delay, stressed pricing, market
   depth, legal restrictions and mutually exclusive actions constrain benefits. Recovery capacity
   cannot be the arithmetic sum of every proposed option.
5. **Static exercise and dynamic strategy answer different questions.** Their replacement rules,
   allowed actions and horizons differ. A drop-down that changes a label is insufficient; use
   profile-specific acceptance fixtures and preserve result provenance.
6. **Data does not generate an approved strategy.** Actuals can initialise and calibrate a forecast;
   future acquisition, pricing, staffing and expansion remain owned assumptions, with uncertainty.
7. **Excel is a controlled surface.** No spreadsheet-only hidden formulas, external links or
   arbitrary executable imports. Frozen evidence and editable assumptions are separate artifacts.
8. **Methodology currency matters.** Complete paragraph-level EBA mappings, exercise artifacts as published, applicable legal texts
   and national decisions in M01/M12.
   Current source limitations are explicit; no claim of exhaustive paragraph-level compliance.
9. **Reuse must include infrastructure.** Depend on #16356 for isolation and ADR-0379 for the trace.
   Source contracts/fields still missing are fixed at their owner. No second data lake, approvals
   platform, return lifecycle or treasury execution service is authorized here.
10. **Human governance is real work.** Named reviewers, independent validation, board decisions,
    signed statements and exercise submission arrangements cannot be manufactured by code or AI.

## Completion rule

An internal pilot may be released with named scope restrictions after M01–M06, M09, M10 and M13
prove its core path; it must not imply all stress or regulatory modules are complete. Full programme
acceptance requires all applicable R01–R18 controls evidenced, unavailable dimensions resolved or
formally excluded, an independently reviewed combined-stress/recovery run and the exact applicable
supervisory artifact validations. Closing an implementation issue requires evidence, not only a PR.

## First wave: source and calculation contract

### M01 — version and applicability

Reviewed 15 November 2026. Keep the applicable SREP edition (EBA/GL/2022/03) separate
from the published successor (EBA/GL/2026/06, applicable 1 January 2027). Keep internal
stress testing (EBA/GL/2018/04) separate from the 2027 EU-wide exercise methodology.
The latter has a final methodological note; freeze the scenario and submission template
versions only once their official artifacts are available. Never roll a historic run onto a
new version implicitly.

The July ICAAP guide maps Principle 1 to board approval and annual capital adequacy
statement (M03/M12); Principle 2 to integration with strategy, risk appetite and recovery
(M03/M08); Principle 3 to distinct, mutually informative normative and economic views (M06);
Principles 4–5 to material risk inventory and eligible internal capital (M06);
Principle 6 to independent validation (M09); Principle 7 to adverse and reverse
stress testing (M07/M08). The normative capital plan covers at least three years across
baseline and adverse scenarios. Preserve the scope decision and challenge evidence for
each institution. Source verification does not substitute for that approval.

### M02 — first read-only integration

| Input or calculation | Existing owner and interface | Planning adapter behavior |
| --- | --- | --- |
| Actual treasury deals, scheduled flows and fixings | Treasury outbox → risk-engine `TreasuryDealConsumer`; treasury reads: deal versions, cash flows, fixings and close-of-day positions | Read the already-ingested risk positions and cash flows. Use treasury reads for source reconciliation, not a parallel event consumer or a second set of flows. Preserve source version and reconciliation status. |
| Risk opening book | `GET /api/v1/risk/snapshots/{id}` and its positions, instruments and cash-flows subresources | Capture the selected snapshot once, including the required scope, cutoffs and evidence references. If a source cannot prove its cutoff or coverage, flag it and block governed reuse. |
| Curves and valuation | Risk `/api/v1/risk/curve-sets/{id}`, snapshot valuation/sensitivity/IRRBB, and existing pricing domain | Pin curve set and FX inputs. Reuse snapshot outputs for opening reconciliation; use the existing domain calculators on isolated projected inputs for future points. |
| Cash flow and liquidity | `SnapshotCashFlowProjection`, `LiquidityForecast`, snapshot liquidity and liquidity-forecast reads | Reuse contractual runoff and applicable risk calculations. Separate planned treasury flows from actual opening exposure. Extend the current horizon, stress behavior and HQLA timing at the owning calculation before relying on it for ILAAP. |
| Capital | Snapshot capital read; `CapitalParameters` and existing capital domain | Reuse supported components with explicit coverage and factor versions; missing material components or unverified factors prevent complete capital evidence. |
| Accounting and commercial actuals | Ledger close and owner-approved portfolio and product inputs | Reconcile independently to a single opening perimeter. Ledger positions and the risk book describe overlapping exposures; do not sum them. Missing mappings or differences remain visible. |

First integration fixture: a booked deal with a subsequent cash-flow/fixing revision, a planned
deal, and an analytical scenario deal. Capture the booked deal only once in opening exposure.
The planned deal may contribute to forecast funding under the selected policy; the scenario deal
exists only in the isolated calculation. Reproduce the initial run after an upstream correction,
show the correction only in a new vintage, and fail a governed run when source scope, coverage
or version cannot be established. Reconcile opening nominal and signed scheduled flows before
testing projection deltas. No treasury write endpoint or core position mutation is required.
