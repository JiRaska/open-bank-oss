# Financial Crime as Code — delivery map and vocabulary audit

Decision: [ADR-0354](../adr/0354-financial-crime-as-code-governed-packs-and-learning.md).
Review date: 2026-10-26. Source baseline: `c2d5d7db65c81a77cf3628c81c994cabcd6b5efd`.
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

`python3 .github/scripts/check-enum-reference-data.py` scans **747 production Kotlin enums**
and reports **105 baselined occurrences** at this base. This is not 105 distinct semantic
defects. The parser covers `openbank-*/**/src/main`; its two heuristics detect numeric names
and cross-module overlap. It does not inspect business meaning, single-owner catalogue
choices, Kotlin numeric constants, SQL CHECK constraints, OpenAPI/JSON Schema enums or
TypeScript literal sets. A green ratchet means no new/stale heuristic finding, not a clean
architecture. This review combines that whole-tree scan with ADR history and the concrete
cross-layer traces below; it does not claim manual semantic certification of every enum.

| Finding / source trace | Conclusion and existing owner |
|---|---|
| KYC `domain/model/KycCase.kt: RiskLevel`; AML `domain/model/AmlCase.kt: AmlRiskLevel` alias to shared `AmlCaseSeverity`; PID rating; four rail `ScreeningPorts.kt` mirrors | Customer rating and case severity are distinct, not incompatible copies of one concept. #13435 owns scales/compatibility; no ordinal conversion. Shared severity alone is not as-code completion. |
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
- Proposed 0348 now names a new `com.openbank.libs.ml.typed` package, initially next
  to existing `com.openbank.libs.domain.feature`, later moving to `openbank-libs-analytics`
  with #11056, not to `openbank-libs-agent`. The typed package does not exist yet. `com.openbank.libs.decision` belongs to lending under 0317.
- 0348–0351 validate pinned schemas and use explicit AUTO enablement; 1.0 is reachable.
  0148 gets a dated clarification, leaving the original record visible.
- 0350 separates unassessed priority from a fabricated probability and requires aging to
  prevent queue starvation. A closed output schema does not eliminate prompt injection.
- 0347's claim that the enum ratchet enforces no hard-coded money constants is too broad:
  the checker scans enum names/value overlap only. 0354 records the limitation and the
  future pack conformance gate; no false claim of present enforcement is substituted.
- PR #13770 merged ADR-0353, `openbank-libs-fincrime` and producer compatibility types
  on 2026-10-25 (`bc4533123a2d8b52ba0b5e3bdfefaa91a712d6f2`); #13766 closed without
  a separate merge. Shared severity is compatibility, not the final data catalogue.
  Remaining rail/PID migrations stay with #13435/#13439; no duplicate library is needed.

## Prioritized delivery and non-duplication

| Priority | Deliverable | Single issue owner | Prerequisite / exit |
|---|---|---|---|
| P0 | Authoritative fraud case, assignment, reviewed outcome/correction contract | #10236 (extend existing) | Minimal case first; reviewed identity/provenance and explicit inconclusive state; graph later |
| P0 | One investigator with case-scoped MCP tools | #13776 (existing slice) | Exact authorized references; human disposition; real HTTP/injection/revocation tests |
| P0 | K0: one AML + one fraud pack, synthetic end to end | #13775 (existing slice) | Golden/replay → diff → approval/hash/signature → SHADOW epoch → nonzero evidence; second vocabulary requires no kernel edit |
| P0 prerequisite | Canonical rating/severity and closed verdict boundaries | #13435 / #13439 (remaining migrations; #13770 merged) | Distinct concepts, compatibility, policy separate from outcome type |
| P1 | Point-in-time feature snapshots and replay inputs | #12750 (extend existing) | Original decision-time availability, label maturity/corrections and manifest; honor its existing model-platform dependencies |
| P1 | Independent model signature trust | #12922 (extend existing) | Model+card replacement alone cannot establish trust; expired/revoked signer rejected |
| P1 | Trained challenger + lifecycle/promotion evidence | #13777 (existing slice) | #10236/#12750/#12922; independent holdout and measured gain, no silent learning |
| P1 | Library consistency, purity and quality proof | #13448 / #11604 (existing) | Dependency graph is authoritative; real consumers, pinning and recovery evidence |
| P1 | AML event integration | #1035 (existing) | Explicit source/event semantics and idempotency; do not create a second ingestion platform |
| P1/P2 | Reference-data migration outside fincrime | #13434/#13436/#13438/#13440/#13441 (existing) | One owner per cluster; per-consumer migration, no universal enum rewrite |
| P2 | AI pack author and calibration assistant | #13775 (later acceptance) | Proposals with citations/replay; no signing or activation; isolated DSL worker |
| P2 | Typed triage/ranking and calibrated consumers | #13778 (existing slice) | Revised 0348–0351; explicit AUTO policy and per-consumer evaluation |
| P2 | Delegation anomaly agent | #3000 (existing) | Consume same evidence/outcomes; no autonomous grant suspension |
| P2 | Qualified collaboration | #3737 / #5839 (existing) | Reuse convergence evals; incident pilot is not fraud/AML authorization |
| Separate release | Domestic fraud enforcement | #4403 (existing) | Real Temporal hold/release, qualified model/rules, risk approval and threat model |
| Parallel supporting work | Context graph and authorized retrieval | #9945 / #10237 (existing) | Never a prerequisite for simple exact-source investigation |

Only four missing implementation issues were opened. No new global enum sweep, threshold
store, graph project, feature store, signature issue or swarm framework was created.
P0 investigator and K0 are parallel slices; real-data training waits for trusted outcomes
and point-in-time data. A dependency blocks its consumer, not unrelated synthetic work.

## First K0 implementation slice: vocabulary revision kernel

`openbank-libs-fincrime` now contains the pure `fincrime.vocabulary` contract used to
start #13775 and #13435. `VocabularySource` is data, not a new enum: it declares the
concept's domain/purpose, provenance, effective interval and stable codes with labels,
meanings and optional explicit ranks. `FincrimeVocabulary.compile` validates bounded
input, snapshots it, reuses the kernel canonical JSON/SHA-256 and exposes an exact
`VocabularyRef(id, revision, contentHash)`. List order is not scale order. Ordered scales
require a unique nonnegative rank for every code; unordered reasons have no ranks.

Resolution requires that exact reference and an explicit decision instant. Another
revision/hash/concept, an unknown code and an out-of-validity instant have distinct
closed refusal outcomes. There is no name/ordinal conversion or LOW/OTHER fallback.
The compiled snapshot cannot be changed through either the source list or its public
entries. Synthetic AML severity and fraud reason fixtures exercise the same kernel;
a pinned canonical golden fixes the byte/hash contract, and negative tests cover
unknown schema, malformed Unicode, bounds, duplicates and partial/ambiguous ranks.

This is a library foundation, **not completion of K0 or #13435**. No production caller,
legacy enum, API, event, DB or FraudThresholdSet is changed. The digest proves content
identity only: a successful compile/resolve is neither an approval nor a signature.
Authenticated source loading, immutable revision storage, retirement/revocation,
pack IR/evaluation, replay/diff, independent approvals/signing, SHADOW epochs and the
`fincrime-pack-conformance` gate remain under #13775. AI/MCP adapters must consume the
same contracts when added, without authority to approve or activate. Existing
#13776/#10236 case work, #13777 learning and #13778 typed decisioning keep their owners.
ADR-0354 remains delivery-status **planned**; this slice grants no activation authority.

## Next K0 slice: bounded count IR, evaluation and behavioral replay

`fincrime.pack` adds a deliberately small, framework-free authoring/compiler/evaluator
path in the same library. `CountPackSource` pins its domain, purpose, validity, vocabulary
revisions and COUNT feature contracts. Compilation snapshots at most 64 flat
`COUNT_AT_LEAST` instructions, rejects unknown output codes and conflicting feature pins,
and produces canonical bytes/hash with the existing kernel codec. Counts are exact Long
values serialized as decimal strings; no money, floating-point scoring, recursive graph,
runtime script or implicit aggregation is introduced. AML triage uses an ordered data
scale; fraud uses the existing executable FraudVerdict. Domain and refusal enums describe
interpreter behavior, never new business taxonomies.

Evaluation requires the exact pack reference, explicit time and bounded observations with
observed/available timestamps. Missing, stale, future, malformed or wrongly pinned input
produces `Incomplete` even if another rule matched. A real zero is distinct from missing.
Successful results include the pack reference, time, canonical input hash, actual rule
count and findings carrying exact reason/severity vocabulary pins. Empty findings is not
an ALLOW or a clean-case assertion. Inputs and hashes assert content identity; they do not
authenticate their source or establish availability-time provenance by themselves.

`CountPackReplay` runs the same evaluator on identical snapshots for baseline/candidate
of the same pack/domain/purpose, rejecting empty or oversized corpora and duplicate case
identities. Per-case differences retain both artifact references and results, including incomplete outcomes.
Metadata-only revision changes do not masquerade as changed findings; changing a pinned
vocabulary meaning remains visible in findings. Synthetic AML/fraud boundary scenarios,
independent canonical golden/hash and negative tests exercise this slice.

Reuse is explicit: FincrimeVocabulary, FraudVerdict and canonical hashing remain their
existing owners. This is not another feature store: feature materialization/real-data
snapshots stay with ADR-0140/#12750. ProductState/PredicateEvaluator have product-specific
semantics (including missing-as-false), so they are not reused as a crime-risk evaluator.
No second DecisionRecord transport, revision store, approval flow or signing mechanism is
created. No service, REST/MCP adapter, production caller, database, event or threshold writer
changes. At that stage, source parsing/schema remained open; the next section records its
bounded JSON adapter. Money/window operations, authentic snapshot adapters, DecisionRecord
integration, independent exact-hash approval/signature, activation epochs, replica
consistency and the full conformance gate remain required under #13775. This
slice is simulation only and does not mark K0 or ADR-0354 delivery complete.

## K0 source boundary: bounded JSON count packs

`CountPackJson.compile` accepts a versioned JSON count source and independently supplied
compiled vocabularies, then invokes the existing `CompiledCountPack` validator. The same
canonical form and hash are preserved when reading the committed fraud golden or the
synthetic AML source. These sources run actual rules and same-input behavioral replay;
no new evaluator or source-authentication claim is introduced.

The adapter rejects unknown/missing fields at every object, duplicate JSON keys, unknown
operators/effects/verdicts, ambiguous count representations, fractional/out-of-range
revision numbers and oversized documents. The pre-parse limit is 65,536 UTF-8 bytes;
callers must additionally bound transport reads before constructing the string. Parsing
reuses the kernel's framework-free `IrJson` syntax reader with its depth bound, not the
product interpreter. COUNT values remain exact nonnegative Long decimal strings.

The bundled `fincrime/count-source-v1.schema.json` describes the closed structural
contract, including AML/fraud effect separation. Executed schema-agreement fixtures cover
both domains and structural negatives. Compilation remains authoritative for semantic
checks (exact vocabulary pins/codes, validity, Long range, rule identity and feature
consistency), source size and duplicate-key rejection. JSON Schema alone is not a trust
check. Vocabulary authoring remains typed; this slice does not introduce a vocabulary
JSON parser or a registry.

Still required under #13775: authentic source/snapshot adapters, CLI/HTTP/MCP integration,
DecisionRecord integration, evidence-bound independent approval/signature, SHADOW epochs,
replica consistency, real HTTP/DB/outbox/concurrency tests and the full enforced
fincrime-pack-conformance gate. No approval, signature, activation, production caller,
threshold writer or payment enforcement is enabled by decoding a source.

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
| Existing decision? | Amend only 0084's strategy authoring; replace 0347 threshold-only scope; reuse other platforms | Full DIGEST plus 0084/0139–0141/0304/0317/0346–0352; merged ADR-0353 / #13770 |
| Decision or work? | Bounded pack lifecycle is an architectural choice; implementation stays in issues | Alternatives in 0354 versus shared enums, remote engine and product-domain reuse; ADR-0052 |
| Enforcement? | Existing enum/catalog gates are narrower; fincrime-pack-conformance is planned | `.github/gates/gates.yaml`, `check-enum-reference-data.py`, rules.yaml enum_reference_data |
| Falsifiable? | Nonzero two-domain end-to-end evidence plus negative fixtures; no fake current pass | 0354 Delivery check; #13775/#13778 acceptance |
| Regulated scope/deadline? | Fraud and sanctions are money-path; AML is protected but not on that list; no invented deadline | rules.yaml money_path_services; existing fraud/sanctions/domestic/agent threat models; add AML model for new path |

## Review corrections

The external review correctly identified the missing roadmap during authoring, overly broad
0084 supersession and nonexistent ML-package wording; these are corrected in PR #13781.
At the pinned source, fraud **is** money-path (rules.yaml), so removing the
`money-path` label from #13775/#13777 was incorrect; restore it for their fraud scope.
This does not reclassify AML or replace path-based CI review requirements.
The authoritative WIP configuration is `max_open_prs: 5`, `agent_branch_prefixes: []`,
not the older summary's three/prefix list. Verify current policy and PR state before opening;
never grant oneself an exception or alter policy to admit this work. Accepted/planned in
0354 records the owner's requested architectural direction, not review completion or runtime
delivery. Normal PR governance still applies.
