---
date: 2026-10-24
decision-status: accepted
delivery-status: planned
authors: [Jiri Raska]
supersedes: [0347]
superseded-by: []
delivery-repos: []
tags: [fraud, aml-sanctions, ai-agents, libs]
summary: "AML and fraud strategies become signed, versioned data packs evaluated by pure bounded interpreters. Business vocabularies are data; AI proposes and learns from reviewed outcomes, while independent humans control activation."
---

# ADR-0354 — Financial Crime as Code: governed packs, pure evaluation and reviewed learning

## Context

ADR-0084 chose a separate fraud context and code-defined rules; ADR-0347 moved money
thresholds into approved data but kept rule structure and count caps in code. ADR-0352
chose typed IR, a pure interpreter and reviewed publication for products. This decision
applies that lifecycle to AML and fraud, including vocabulary and feedback, rather than
merely moving constants or enum copies into libs. It amends only
0084 section 3 strategy authoring and its corresponding rule-engine rejection; the service,
verdict and enrichment decisions stand. It supersedes 0347's threshold-only architecture,
retaining its delivered implementation until the migration below is proven.

Adjacent decisions remain authoritative: 0032 sanctions screening; 0047 policy control;
0139/0140/0141 feature/model provenance; 0148 AI assurance; 0197 licensing; 0203 business
agents; 0224 MCP authority; 0244/0271 collaboration; 0258 bounded schemas; 0268 sandbox AML;
0304 investigation; 0317 context libraries; 0322/0323 decision/outbox evidence; 0346 library
quality. None defines this pack lifecycle. Proposed 0348–0351 cover model-assisted triage,
not strategy ownership. Product packs are a reusable pattern, not a fraud schema.

At audit base `ad6142433d30ab7ec36dab1852942d97d22488e5`, the enum ratchet scans
729 Kotlin enums and reports 101 baselined findings. It checks numeric names and copies,
not whether a single-owner enum is appropriate. PR #13766 (ADR-0353) and #13770 separately
introduce shared financial-crime vocabulary. Their deduplication is a compatibility step;
it does not make risk taxonomies or the hard-coded screening threshold configurable.
The [audit and delivery map](../design/financial-crime-as-code-roadmap.md) records scope,
existing issue owners and measurement limits.

The owner requested this direction on 2026-10-24. There is no invented regulatory deadline
or production-readiness date. Runtime delivery remains planned, tracked in #13775.

## Decision

### 1. Preserve domain authority

Fraud owns transaction-fraud assessments and reviewed fraud findings. AML owns its cases
and typologies; sanctions owns screening and disposition under ADR-0032. KYC owns customer
risk rating. Case severity, customer rating, sanctions match and fraud verdict are distinct:
never convert them by ordinal or display name. No universal crime score is introduced.

Retained from 0084: fraud's bounded context, synchronous verdict contract, asynchronous
source-backed enrichment, currency isolation, server-enriched inputs, deterministic floor,
explicit degraded observations and separately approved rollout. Existing shadow stays
shadow; #4403 owns rebuilding payment enforcement. Missing scoring is not a real ALLOW.
The sanctions gate remains unchanged; a party hold is a separate ADR-0247 decision.

### 2. Data owns business vocabulary; types own executable semantics

Enums remain suitable where adding a member needs interpreter behavior or a state
transition: IR operators, lifecycle states, ALLOW/CHALLENGE/REVIEW/DECLINE and explicit
unavailable/abstained outcomes. Wire parsing must be total; unknown cannot mean ALLOW,
CLEAR or low risk. An executable verdict set is not a configurable list of new actions.

Typologies, scenario IDs, reason-code catalogues, risk/severity scales and ordering,
bands, thresholds, review cadence, routing policy and calibration settings are immutable,
effective-dated pack data. Typed codes resolve against `vocabularyId + revision + hash`;
labels are not identity. JSON Schema may enumerate **one revision's** allowed codes
without a Kotlin enum per taxonomy. Unchecked strings and ordinal comparisons are not
alternatives. The kernel understands bounded operators, not named business categories.
Regulatory reference data also needs source/version/effective-date provenance.

Existing enum APIs, persisted values, UI parsers and events remain compatibility contracts
until migrated individually. Shared AmlCaseSeverity in PR #13770 is a compatibility type,
not the future configurable scale. Never send a new data code to an old strict consumer:
use a versioned adapter or reject the unsupported revision, never silently map to LOW/OTHER.

### 3. One reproducible lifecycle

Git source → validated bounded IR → tests/replay → behavioral diff → independent approvals
of exact content → signed immutable revision → activation epoch → decision evidence →
reviewed feedback. UI, CLI and AI authoring produce the same canonical artifact and use the
same validators. Catalogue, documentation and control coverage are generated from sources.

Packs pin domain/purpose, schema/vocabulary revisions, feature contracts, rule graph,
typed units/currencies/parameters, validity, reasons, optional model/calibration references,
approval policy and synthetic golden scenarios. Compilation produces canonical bytes and
a digest. Pure total interpreters accept explicit clock and snapshots: no implicit I/O,
random state, logging or model calls. Bound graph depth/size, iteration/windows, input,
execution and memory. No arbitrary recursive rules or runtime scripts/JAR loading.

Any build-time Kotlin DSL runs as untrusted code in a bounded isolated worker without
production secrets or network access. Its output must equal committed IR; compiler
execution confers no publishing authority.

### 4. Bind approval, signing and activation to evidence

PR review approves source. Independent domain principals approve the same artifact and
evidence hashes; maker cannot check. Risk/MLRO/model-risk responsibilities are declared
policy bound to real identity, never caller-supplied reviewer names. Agents propose only:
no approval, signing, activation, clearance, filing, case closing or money movement.
Sandbox auto-clear is neither approval evidence nor a clean label (ADR-0268).

The trusted publisher verifies source, tests and independent approvals, then signs using
trust separate from the artifact/card that an author can edit together. Reuse #12922 model
signing and existing supply-chain primitives. Any dependency change invalidates approval;
activation rechecks authority and signer revocation.

Activation atomically records a monotonic epoch, verified revision and effective instant;
no backdating. Cache loading and replica acknowledgements are separate from approval.
A replica cannot claim ready for an epoch it cannot serve. Each request pins one epoch;
rollback creates a new approved epoch referring to an earlier verified artifact, never
rewrites history. Evaluation uses a verified local snapshot, no remote hot-path lookup.
Before admitting traffic, prove consistent request routing/pinning across two replicas.

| Failure | Required behavior |
|---|---|
| Unknown schema/code, invalid signature, revoked/expired artifact | Reject activation; never substitute latest |
| Active snapshot or required feature missing | Explicit incomplete/degraded result; preserve mandatory controls and the owning workflow's hold/review policy |
| Shadow model/assistant unavailable | Deterministic baseline continues; count abstained/unavailable, never success |
| Evidence persistence unavailable on an enforcing transition | No state commit without transactional evidence |
| Rollback during evaluation | New requests use new epoch; in-flight and historical pins remain reconstructible |

### 5. Reuse libraries and governance

Follow 0317/0346 and the concurrent ADR-0353 vocabulary work. Use openbank-libs-fincrime
for justified shared framework-free financial-crime contracts/evaluator primitives with
separate fraud/AML semantics. No duplicate libs-fraud/libs-aml modules merely mirroring
services. Adapters and ownership stay in services; further extraction needs consumer,
licensing and fanout evidence. No business vocabulary returns to the platform core.

Reuse Money, feature ports, ObjectStorePort, canonical JSON, Proposal, DecisionRecord,
outbox and governed MCP/LLM infrastructure. Typed model contracts use the proposed com.openbank.libs.inference.typed package,
initially beside com.openbank.libs.llm in libs-domain and later moved with ADR-0317
phase 2 (#11056); no ML-contracts package is claimed to exist today. Never reuse lending's
com.openbank.libs.decision namespace. Agent code stays on ADR-0197's
AGPL side; Apache services/libs cannot depend backwards on it. No tenant dimension is
added to the single-bank ADR-0152 deployment.

Register any library in settings, purity/architecture policy, dependency selection,
CODEOWNERS and quality/provenance checks. Use today's conservative dependency graph,
not the stale LIBS_MODULES convention (#11604/#13448). Pure unit/property/golden tests
complement real HTTP, DB, concurrency, compatibility and historical-consumer linkage tests.

### 6. AI assistance and reviewed learning

Start one investigator (#13776) and one synthetic AML plus one fraud pack (#13775) in
parallel. Investigator output is a cited timeline/hypothesis with contradictions and
missing data. Source text is untrusted. Every read/simulation checks current assignment,
purpose and OPA; exact references work before the full graph. A model statement or graph
edge is not a confirmed finding. Pin prompt/model/tool revisions and measure actual work.

#10236 owns reviewed outcomes: source decision, reviewer, observation/maturity time,
correction/reopen events and unresolved/inconclusive states. Fraud labels remain separate
from AML/sanctions dispositions; proposal approval is not proof of factual truth.
#12750 owns offline snapshots and availability-time-safe joins at the **original decision
time**, not case closure. #13777 owns reproducible fitted challengers, registry and drift.
Time/entity-group splits, preserved holdouts, sampling-bias analysis and delayed-label
handling precede training. Review-queue precision is not population recall. Drift proposes
candidates, never edits live policy or silently promotes. AI authoring/calibration follows.

#13778 and proposed 0348–0351 use schema-bound typed outputs with an explicit mode;
probability >= 1.0 is reachable and cannot disable AUTO. Each consumer qualifies separately.
Sanctions ordering cannot clear hits or starve lower-ranked cases. Fraud/AML collaboration
needs its own charter/evals/threat model: incident-only #5839 is not authorization.
Reuse #3000/#3737 and the same outcomes rather than another agent framework.

### 7. Compatible delivery by evidence

Retain 0347's approved FraudThresholdSet versions, maker/checker separation, effective
dates, currency behavior and version pins. Initially an adapter combines the current
engine with its approved threshold set; no second writer bypasses it. Baseline import
records actual provenance, never fictional risk approval. Move rule structure/count caps
only after same-input parity and explained behavioral diffs. Existing per-score DB reads
stay until the activation/cache consistency proof passes. Preserve all historical pins.

Version API/events and provide Flyway/rollback notes per migration. Adding enum values is
not automatically nonbreaking for strict consumers. No bulk enum-to-string rewrite, gate
baseline reset or payment enforcement accompanies this documentation decision.

P0: case/outcome ownership, investigator and K0. P1: point-in-time replay, signing, fitted
challenger and measured resilience. P2: AI authoring/calibration, typed ranking and qualified
collaboration. #4403 enforcement is a separate release after evidence and risk approval;
mandatory sanctions controls continue throughout. One issue owner per deliverable is in
the delivery map.

## Alternatives considered

- **Shared enums plus runtime thresholds.** Cheap migration; useful for closed outcomes.
  Rejected as the final business-vocabulary model: adding scales/categories still requires
  coordinated binaries and has no independently pinned effective-dated meaning.
- **Generic remote rules/workflow engine.** Flexible UI authoring. Rejected here: another
  hot-path dependency and arbitrary execution surface; pure bounded IR reuses our platform.
- **Put AML/fraud in product-catalog's IR/service.** One catalogue, but different authority,
  evidence and operational semantics. Reuse primitives rather than its domain model.
- **Autonomous adaptive AI.** Faster tuning, but unreviewed feedback can silently alter
  controls. Use shadow challengers and independently approved promotion.

## Consequences

**Positive**
- Strategy changes, docs and evidence derive from versioned sources; replay and production
  share semantics, and domain owners approve business policy.
- AI improves through measured reviewed outcomes without gaining disposition authority.

**Negative**
- Pack stewardship, consistent activation and legacy adapters add work. Good labels and
  unbiased evaluation take time; synthetic success cannot prove production superiority.

**Neutral**
- Existing enforced enum-reference-data-ratchet checks numeric enum names and copies only;
  it does not detect hard-coded money thresholds. ADR-0347's old gate attribution was too
  broad. catalog-kernel-industry-neutral covers product-catalog, not all services.
- New fincrime-pack-conformance is **planned** (#13775), enforced before activation ships:
  bounded IR/data boundary, reproducible hashes, actual evaluation counts and negative
  fixtures. Runtime uses one publisher/evaluator path plus DB constraints. No existing gate
  is claimed to deliver this ADR. #13778 owns typed-schema/explicit-AUTO checks.

### Delivery check

Required future commands, **not implemented or passing in this documentation revision**:

```bash
python3 .github/scripts/check-fincrime-pack-conformance.py --self-test
python3 .github/scripts/run-gates.py --only fincrime-pack-conformance
```

Both must exit 0 with at least two evaluated domain scenarios, nonzero negative fixtures
and mode enforced. Zero evaluated cases fails. Evidence must prove CLI/MCP/runtime parity,
forged/stale approval rejection, bad signature/unknown code rejection, two-replica
activation, rollback, real-DB atomicity and historical replay. A grep alone is insufficient.

## Compliance impact

- PCI DSS: packs contain no cardholder data; future card inputs require scoped assessment.
  Public fixtures are synthetic.
- DORA: controlled changes, bounded dependencies, measured recovery and signed provenance
  support ICT controls; effectiveness requires executed evidence.
- GDPR: purpose, current access, minimized inputs, retention/erasure and dataset provenance
  apply; pseudonymization does not remove personal-data obligations. Assess residency and
  processing before real-data model use; public git contains no personal data.
- PSD2: monitoring and later enforcement remain separately qualified; no SCA exemption or
  payment outcome changes through this ADR.
- CNB: risk/AML ownership, independent approval and replay support supervisory evidence;
  neither a gate nor a model score constitutes a compliance attestation.
- Threat models exist for fraud, sanctions, domestic-payment and agent-service; update each
  touched model with implementation. Add an AML-specific model before its activation path.
  Required money-path reviews remain distinct from runtime maker-checker.

## References

- [Delivery map and enum audit](../design/financial-crime-as-code-roadmap.md)
- #13775, #13776, #13777, #13778 — missing implementation slices
- #10236, #12750, #12922, #1035, #4403, #3000, #3737, #5839 — existing owners
- #13434, #13435, #13436, #13438, #13439, #13440, #13441, #11604, #13448 — existing follow-ups
- PR #13766 / #13770 — concurrent vocabulary work, not merged-delivery evidence
