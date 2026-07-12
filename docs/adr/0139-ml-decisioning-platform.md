# ADR-0139 — Real-time ML decisioning platform: feature store, model serving, champion/challenger governance

Date: 2026-06-29
Decision-Status: Accepted   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: Partial    <!-- Planned | Partial | Shipped | N/A — decision-only -->
Author(s): Jiri Raska

**Delivery note (updated 2026-07-12):**
- **Foundation** — ✅ Ready: governance substrate (ADR-0031 extended by ADR-0141) in place; feature-store topology and model-serving architecture sketched.
- **Phase 1 (substrate + shadow)** — ✅ Shipped: the feature-store side (velocity aggregates as declared features, ADR-0140), the shadow-mode plane (`MlModelPort`, `FraudScoringService.runShadow`, zero-drift test), and now the in-process ONNX Runtime adapter itself (`OnnxFraudModel`, phase-1b) — it loads a bundled ONNX model (`gen_onnx_baseline_model.py`) encoding the same deterministic logistic `BaselineFraudModel` used to compute directly, so behaviour is unchanged and only the execution engine is real. This note previously read "not yet started" (later "ONNX adapter itself not built yet"); both were stale for a while — the first led to a duplicate feature-store port being built from scratch (reverted, see ADR-0140's delivery note).
- **Phase 2 (registry, drift, explainability)** — ⬜ Pending, blocked on ADR-0141 (no model registry/model-card/champion-challenger code exists anywhere in the repo — verified by grep, this one is not stale).
- **Phase 3 (enforce in fraud)** / **Phase 4 (credit decisioning, ADR-0142)** — ⬜ Pending.

## Context

OpenBank's AI maturity is asymmetric. On the **build/operate** axis it is ahead of
incumbent core-banking platforms: agents-as-code with policy-gated MCP and
human-in-the-loop (ADR-0031), an AI-FinOps agent (ADR-0112), an AI-DevOps agent
(ADR-0119), HolmesGPT RCA (ADR-0091). On the **decide-over-customer-and-risk**
axis — the axis a bank is actually regulated and monetised on — it has *no machine
learning at all*.

Every runtime risk decision today is a hand-coded deterministic rule:

- `openbank-fraud-service` (ADR-0084) scores transactions with `FraudRuleEngine` —
  a sum of integer `scoreDelta`s from velocity-cap rules, tagged with a
  `ruleVersion` string. No model, no inference, no learned signal.
- `openbank-kyc-service` (ADR-0116) is a risk-*based* rules + four-eyes gate.
- There is no **feature store**, no **model serving**, no **champion/challenger**
  evaluation, no model registry or provenance, no drift monitoring.

This is the inverse of the incumbents' weakness. Mambu / Temenos / Thought Machine
carry decades of functional breadth OpenBank cannot match soon — but their ML
decisioning is bolted onto legacy data plumbing. OpenBank already has the one thing
real-time ML needs and they retrofit with pain: a **clean, event-driven substrate**
(a Kafka transactional outbox on every event-producing service, ADR-0003/0013) that
is a feature pipeline waiting to be tapped. **Why now:** the moat is widest before incumbents finish their GenAI
retrofits, and the lowest-blast-radius entry point already exists — fraud scoring
has a defined latency budget, an explicit `FraudScore.reasons` output for
explainability, and a deterministic rule layer that can remain the floor while ML
augments it. We establish the platform on fraud, then reuse it for the
higher-value, higher-regulatory-bar credit decisioning (lending, ADR-0028).

## Decision

Introduce a **real-time ML decisioning platform** as a shared capability in
`openbank-libs` (runtime split per ADR-0122), first consumed by
`openbank-fraud-service`. Every model is a **governed asset through the machinery of
ADR-0031** (agents-as-code, policy gating, audit attribution), extended to ML models
— model cards, model registry, provenance — by ADR-0141: registered, versioned,
policy-gated, model-carded, audit-attributed. Delivered in phases. The deterministic rule layer is **never removed**: ML augments the
guardrail, it does not replace it.

**Platform primitives.**

- **Feature store** — online + offline, fed *only* from existing domain events (no
  new ingestion). Offline = the Kafka event log materialised for training; online =
  low-latency reads from the Valkey already in the stack. **Point-in-time
  correctness** is mandatory: a feature served at inference must be reconstructable
  as-of decision time, or training/serving skew silently poisons the model. The
  feature contract and skew guarantees are specified separately (ADR-0140).
- **Model serving** — **in-process ONNX Runtime** inside the consuming service, not
  a network sidecar. The money path has a hard latency budget and a network hop plus
  an extra failure domain is unacceptable in the synchronous scoring plane. Models
  are immutable, versioned artifacts pulled from a registry; their provenance
  reuses the **SBOM / attestation chain (ADR-0121)** — a model artifact is
  supply-chain material exactly like a JAR (registry specified in ADR-0141).
- **Champion / challenger / shadow** — a model's output is a new contributor to the
  existing `FraudRuleEngine`, gated through the same **advisory → enforce** ladder
  proven for OPA authz (ADR-0034): *shadow* (logged, moves nothing) → *challenger*
  (A/B against champion) → *champion* (enforces). **Fail-closed and floored:** a
  hard deterministic rule can always block; ML may *tighten* a decision but can
  never silently *loosen* one above a rule threshold. If the model, a feature, or
  the store is unavailable, the engine degrades to rules-only — never to ML-only.

**Explainability is a first-class output, not an afterthought.** Each inference
emits top-contributing-feature reason codes (SHAP-style) into the existing
`FraudScore.reasons` and the **tamper-evident audit chain (ADR-0133)**. This is the
non-negotiable precondition for phase 4 (credit decisioning), where adverse-action
explainability is a legal requirement, not a nicety.

**Phases (named, not hidden).**

- **Phase 1 — substrate + shadow (no money-path risk).** Feature store (online reads
  over existing velocity / event data) + in-process ONNX serving + **shadow mode**
  in fraud-service: the ML score is computed and logged *alongside* the rule score
  and changes no decision. Proves latency, skew-freedom and the governance wiring
  with zero behavioural change.
- **Phase 2 — registry, drift, explainability.** Model registry with model cards and
  provenance (ADR-0121/0141), a champion/challenger evaluation harness, drift
  monitoring with auto-rollback on metric regression, reason codes into the audit
  chain.
- **Phase 3 — enforce in fraud (money-path gate).** Promote a validated champion to
  *enforce* in the fraud scoring plane. Inherits the full money-path controls:
  2 approvals + threat model (`docs/threat-models/ml-decisioning.md`, ADR-0030) + a
  DST invariant (ADR-0100) that ML can only intersect, never widen, the
  rule-allowed set.
- **Phase 4 — credit decisioning (the moat).** Reuse the platform for lending
  origination (ADR-0028): risk-based pricing and approve / refer / decline with
  mandatory adverse-action explainability (ECOA-style / ČNB-defensible). Separate
  ADR (ADR-0142) — this phase carries the heaviest regulatory load.

## Alternatives considered

- **Network model-serving sidecar (KServe / Seldon / Triton).** Standard MLOps
  shape, but a network hop and a second failure domain in the synchronous money
  path, plus operational weight for one consumer today. Rejected for the real-time
  plane; acceptable later for offline/batch scoring. In-process ONNX wins on latency
  and blast radius.
- **Managed cloud ML (SageMaker / Vertex).** Fast, but breaks the cloud-agnostic,
  in-cluster-OSS principle (ADR-0027) and exports customer risk features to a
  managed plane. Rejected.
- **LLM-as-decisioner (use the existing model gateway for risk).** A generative model
  for a money-path approve/decline is non-deterministic, hard to calibrate,
  unexplainable to a regulator and a prompt-injection surface. Rejected — LLMs stay
  on the assist/ops axis (ADR-0031/0089); risk decisions use calibrated, explainable,
  versioned ML.
- **Keep extending the rule engine by hand.** Status quo. Cheap and fully
  explainable, but it cannot learn — it will lose to fraud that adapts and leaves
  money on the table in pricing. Rejected as the ceiling; *kept* as the
  deterministic floor.
- **Build the feature store as a new ingestion pipeline.** Rejected — the Kafka
  outbox is already the event log; a parallel pipeline is duplicate plumbing and a
  new skew source.

## Consequences

**Positive**

- Closes OpenBank's single biggest AI gap and turns its clean event substrate into a
  durable advantage incumbents retrofit with difficulty.
- Reuses existing assets: the rule engine as guardrail, Kafka as feature pipeline,
  ADR-0031 governance, ADR-0121 provenance, ADR-0133 audit, the ADR-0034
  advisory→enforce ladder.
- Explainability and the fail-closed floor make the money-path and credit phases
  regulator-defensible by construction.

**Negative**

- A new competency surface (feature engineering, training, drift, model-risk
  management) and a new artifact class (models) in the supply chain.
- Point-in-time correctness and skew control are subtle and fail silently when
  wrong. Phase 1 shadow mode exists precisely to surface this before any decision
  depends on it.

**Neutral**

- No money moves and no decision changes until phase 3; phases 1–2 are pure substrate
  behind a shadow flag.
- The platform lands in the `openbank-libs` runtime split (ADR-0122) → fleet-rebuild
  and libs-merge friction apply (a known cost).

## Compliance impact

- **PCI DSS:** features must exclude PAN/CVV; cardholder data never enters the store.
- **DORA:** model serving in the money path is an ICT asset — it falls under the
  existing resilience controls; drift auto-rollback is an operational-resilience
  improvement.
- **GDPR:** features derived from personal data → purpose limitation, retention and
  the right to explanation (Art. 22, automated decision-making); the explainability
  output (ADR-0133) is the mitigation and the credit phase needs a DPIA.
- **PSD2:** SCA / TRA exemption logic may consume the fraud score — it must stay
  auditable.
- **ČNB / ECOA-style:** phase 4 credit decisions require documented, reproducible
  adverse-action reasons; model cards plus the audit chain provide the evidence
  trail.

## References

- ADR-0084 — fraud bounded context (the `FraudRuleEngine` this augments)
- ADR-0116 — KYC risk engine (next rules-based candidate for ML augmentation)
- ADR-0031 — AI agent governance (models register via its machinery, extended for ML by ADR-0141)
- ADR-0003 / ADR-0013 — transactional outbox + shared libs primitives (the event log feeding features)
- ADR-0034 — OPA unified authz (the advisory→enforce ladder reused for model rollout)
- ADR-0028 — lending bounded context (phase 4 credit-decisioning target)
- ADR-0027 — cloud-agnostic, in-cluster OSS (rejects managed ML)
- ADR-0121 — SBOM / attestation (extended to cover model-artifact provenance)
- ADR-0133 — tamper-evident audit chain (explainability / adverse-action evidence)
- ADR-0122 — libs domain/runtime split (where the platform lands)
- ADR-0030 — threat-model requirement for the phase 3 money-path consumer
- ADR-0100 — deterministic simulation testing (phase 3 ML-intersects-not-widens invariant)
- ADR-0140 — feature store topology & point-in-time correctness (follow-on)
- ADR-0141 — model registry & provenance (follow-on)
- ADR-0142 — credit decisioning engine (follow-on)
