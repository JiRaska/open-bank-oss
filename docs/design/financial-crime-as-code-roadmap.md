# Financial Crime as Code — delivery map and vocabulary audit

Decision: [ADR-0354](../adr/0354-financial-crime-as-code-governed-packs-and-learning.md).
Audit date: 2026-10-24. Source baseline: `ad6142433d30ab7ec36dab1852942d97d22488e5`.
This is a source/backlog audit and implementation plan, not a deployment or compliance
attestation. Priorities indicate dependency order, not promised dates or capacity.

## Vocabulary audit: what belongs in code

The distinction is **who can change the meaning and whether new executable semantics are
required**, not whether the spelling is a Kotlin enum, a TypeScript union or a JSON enum.
A schema may close one artifact revision's value set while the catalogue evolves as data.
Moving a business enum to libs fixes copies, not policy versioning. Replacing it with an
unchecked string fixes neither.

| Class | Representation | Change evidence |
|---|---|---|
| Interpreter operators, protocol/transition outcomes | Closed enum/sealed type with exhaustive handling | Code review, tests, versioned contracts and total unknown parsing |
| Risk/severity scales, typologies, reasons, categories, thresholds, bands, review cadence | Typed stable codes + immutable effective-dated definition/schema revision | Domain approval, behavioral diff, replay and pinned data provenance |
| External scheme/regulatory reference data | Owned versioned dataset + explicit adapter | Source/license/validity, compatible mapping and historical versions |
| Legacy API/DB enum | Frozen compatibility representation until consumer migration | Actual API/event diff, DB migration, rollback, old/new consumer tests |

### Measured scope and limits

`python3 .github/scripts/check-enum-reference-data.py` scans **729 production Kotlin enums**
and reports **101 baselined occurrences** at this base. This is not 101 distinct semantic
defects. The parser covers `openbank-*/**/src/main`; its two heuristics detect numeric names
and cross-module overlap. It does not inspect business meaning, single-owner catalogue
choices, Kotlin numeric constants, SQL CHECK constraints, OpenAPI/JSON Schema enums or
TypeScript literal sets. A green ratchet means no new/stale heuristic finding, not a clean
architecture. This review combines that whole-tree scan with ADR history and the concrete
cross-layer traces below; it does not claim manual semantic certification of every enum.

| Finding / source trace | Conclusion and existing owner |
|---|---|
| KYC `domain/model/KycCase.kt: RiskLevel`; AML `domain/model/AmlCase.kt: AmlRiskLevel`; PID rating; four rail `ScreeningPorts.kt` mirrors | Customer rating and case severity are distinct, not incompatible copies of one concept. #13435 owns scales/compatibility; no ordinal conversion. Shared severity alone is not as-code completion. |
| Four rail `domain/screening/ScreeningPolicy.kt`; sanctions `SanctionsCheck.isHighRisk` and `SanctionsService.HIT_THRESHOLD` | CLEAR/REVIEW/BLOCK are executable outcomes; numeric 0.85 and routing policy are domain-owned versioned data. #13439 owns the existing cluster; #13775 supplies pack activation. |
| Fraud `domain/rules/FraudRuleEngine.kt` and FraudThresholdSet | Preserve shipped per-currency threshold versions (#13437); migrate rule structure/count caps only through #13775 parity proof. No duplicate threshold platform. |
| Party `domain/model/PartyAmlProfile.kt`; KYB `domain/model/AmlDisclosures.kt` | Turnover bands encode business thresholds and differ in period/scale. #13436: explicit currency/period/bounds and pinned definitions, no relabeling old rows. |
| Interest RateIndex, lending LoanRateIndex, risk CurveIndex | Benchmark identity/tenor/conventions are reference data, with typed adapters for actual algorithms. #13434; align treasury ADR-0344, do not put business lists into platform core. |
| KYB OwnershipBand, libs-lending DelinquencyBucket, pension AgeBand, risk TimeBucket | Numeric/reporting bands need typed bounds plus source/version/effective dates; regulatory stability is not perpetual immutability. #13440. |
| Product `Product.kt` legacy vocabulary; `catalog-packs`; CardNetwork in card-issuance | ADR-0257/0345 already freeze v1 and place new business words in packs. #13438/#13440 cover migration, not another growing shared enum. |
| FindingStatus, ChatRole, ProposalState, suppression, onboarding mirrors, engagement/product kinds | #13441 already owns classification. Some are real independent state machines or anti-corruption adapters; generic shared labels do not prove shared meaning. Decide per boundary; do not erase the whole baseline. |
| Admin UI `src/lib/aml/amlCaseContract.ts`, `parties/kycEvidenceContract.ts`; service OpenAPI, mappers and DB values | Kotlin deduplication does not update strict UI/wire consumers. Include their compatibility and unknown-value tests in #13435/#13439; generate schema-derived UI choices for new revisions. |
| `dispute/.../ComplaintModels.kt`, proposed ADR-0348/0349/0351 | Enum-only generic output port would hard-code future taxonomies. #13778 changes it to pinned schema/value decoding; existing ComplaintCategory stays a compatibility adapter. |

### ADR consistency corrections in this change

- 0084 remains the founding fraud-service decision. 0354 amends strategy authoring only.
  0347's delivered threshold model is preserved during its explicit replacement.
- 0257/0258/0345/0352 already demonstrate data-defined vocabulary and bounded interpreters;
  they do not authorize arbitrary plugins or runtime code execution.
- Proposed 0348 now names a new `com.openbank.libs.inference.typed` package, initially next
  to existing `com.openbank.libs.llm` and moved with #11056. No existing ML package is
  claimed. `com.openbank.libs.decision` belongs to lending under 0317.
- 0348–0351 validate pinned schemas and use explicit AUTO enablement; 1.0 is reachable.
  0148 gets a dated clarification, leaving the original record visible.
- 0350 separates unassessed priority from a fabricated probability and requires aging to
  prevent queue starvation. A closed output schema does not eliminate prompt injection.
- 0347's claim that the enum ratchet enforces no hard-coded money constants is too broad:
  the checker scans enum names/value overlap only. 0354 records the limitation and the
  future pack conformance gate; no false claim of present enforcement is substituted.
- Pending vocabulary decision in PR #13766 distinguishes rating/severity correctly; #13770 centralizes
  protocol vocabulary. Its shared severity is compatibility, not the final data catalogue.
  This PR does not modify another agent's branch or claim those PRs are merged.

## Prioritized delivery and non-duplication

| Priority | Deliverable | Single issue owner | Prerequisite / exit |
|---|---|---|---|
| P0 | Authoritative fraud case, assignment, reviewed outcome/correction contract | #10236 (extend existing) | Minimal case first; reviewed identity/provenance and explicit inconclusive state; graph later |
| P0 | One investigator with case-scoped MCP tools | #13776 (new) | Exact authorized references; human disposition; real HTTP/injection/revocation tests |
| P0 | K0: one AML + one fraud pack, synthetic end to end | #13775 (new) | Golden/replay → diff → approval/hash/signature → SHADOW epoch → nonzero evidence; second vocabulary requires no kernel edit |
| P0 prerequisite | Canonical rating/severity and closed verdict boundaries | #13435 / #13439 (existing; PR #13766/#13770) | Distinct concepts, compatibility, policy separate from outcome type |
| P1 | Point-in-time feature snapshots and replay inputs | #12750 (extend existing) | Original decision-time availability, label maturity/corrections and manifest; honor its existing model-platform dependencies |
| P1 | Independent model signature trust | #12922 (extend existing) | Model+card replacement alone cannot establish trust; expired/revoked signer rejected |
| P1 | Trained challenger + lifecycle/promotion evidence | #13777 (new) | #10236/#12750/#12922; independent holdout and measured gain, no silent learning |
| P1 | Library consistency, purity and quality proof | #13448 / #11604 (existing) | Dependency graph is authoritative; real consumers, pinning and recovery evidence |
| P1 | AML event integration | #1035 (existing) | Explicit source/event semantics and idempotency; do not create a second ingestion platform |
| P1/P2 | Reference-data migration outside fincrime | #13434/#13436/#13438/#13440/#13441 (existing) | One owner per cluster; per-consumer migration, no universal enum rewrite |
| P2 | AI pack author and calibration assistant | #13775 (later acceptance) | Proposals with citations/replay; no signing or activation; isolated DSL worker |
| P2 | Typed triage/ranking and calibrated consumers | #13778 (new) | Revised 0348–0351; explicit AUTO policy and per-consumer evaluation |
| P2 | Delegation anomaly agent | #3000 (existing) | Consume same evidence/outcomes; no autonomous grant suspension |
| P2 | Qualified collaboration | #3737 / #5839 (existing) | Reuse convergence evals; incident pilot is not fraud/AML authorization |
| Separate release | Domestic fraud enforcement | #4403 (existing) | Real Temporal hold/release, qualified model/rules, risk approval and threat model |
| Parallel supporting work | Context graph and authorized retrieval | #9945 / #10237 (existing) | Never a prerequisite for simple exact-source investigation |

Only four missing implementation issues were opened. No new global enum sweep, threshold
store, graph project, feature store, signature issue or swarm framework was created.
P0 investigator and K0 are parallel slices; real-data training waits for trusted outcomes
and point-in-time data. A dependency blocks its consumer, not unrelated synthetic work.

## Evidence required before claiming completion

- Pin source/schema/pack/model/prompt/input versions, approvals and activation epoch on
  DecisionRecord; preserve historical reconstruction, not latest-state reinterpretation.
- Verify real MCP routing/auth, revoked assignment, cross-case isolation, injected source
  instructions, data minimization and denial of all disposition/activation tools.
- Verify DB-backed maker/checker identity, conditional concurrent activation, outbox
  atomicity, signature revocation, replica readiness, missing snapshot and rollback.
- Compare replay/production outputs, calibrated precision and qualified recall, PR-AUC,
  segment/rare-class performance, uncertainty, review backlog and analyst time. Record
  actual sample/work counts and skipped/unrun evidence. Vendor claims and synthetic runs
  cannot establish superiority or regulatory compliance.
- Establish runtime latency/allocation/recovery budgets from representative measurements
  before promotion. Models failing native initialization cannot break unrelated endpoints.
- Keep runtime sign-off, source PR review and legal/regulatory assessment distinct. No
  production activation, merge or deployment is authorized by this planning document.

## ADR-grill record

| Question | Verdict | Evidence |
|---|---|---|
| Existing decision? | Amend only 0084's strategy authoring; replace 0347 threshold-only scope; reuse other platforms | Full DIGEST plus 0084/0139–0141/0304/0317/0346–0352; concurrent PR #13766 patch |
| Decision or work? | Bounded pack lifecycle is an architectural choice; implementation stays in issues | Alternatives in 0354 versus shared enums, remote engine and product-domain reuse; ADR-0052 |
| Enforcement? | Existing enum/catalog gates are narrower; fincrime-pack-conformance is planned | `.github/gates/gates.yaml`, `check-enum-reference-data.py`, rules.yaml enum_reference_data |
| Falsifiable? | Nonzero two-domain end-to-end evidence plus negative fixtures; no fake current pass | 0354 Delivery check; #13775/#13778 acceptance |
| Regulated scope/deadline? | Fraud and sanctions are money-path; AML is protected but not on that list; no invented deadline | rules.yaml money_path_services; existing fraud/sanctions/domestic/agent threat models; add AML model for new path |

## Review corrections

The external review correctly identified the missing roadmap during authoring, overly broad
0084 supersession and nonexistent ML-package wording; these are corrected before PR.
At the pinned source, fraud **is** money-path (rules.yaml), so #13775/#13777 retain that label.
The authoritative WIP configuration is `max_open_prs: 5`, `agent_branch_prefixes: []`,
not the older summary's three/prefix list. Verify current policy and PR state before opening;
never grant oneself an exception or alter policy to admit this work. Accepted/planned in
0354 records the owner's requested architectural direction, not review completion or runtime
delivery. Normal PR governance still applies.
