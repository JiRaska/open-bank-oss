# ADR-0141 — Model registry and provenance for ML decisioning

Date: 2026-06-29
Decision-Status: Proposed   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: Planned    <!-- Planned | Partial | Shipped | N/A — decision-only -->
Author(s): Jiri Raska

## Context

ADR-0139 introduces ML models into the runtime as **in-process ONNX artifacts** that
can ultimately enforce money-path (fraud) and credit decisions. ADR-0140 makes the
*training data* reproducible (offset-pinned, point-in-time). What is still missing is
the governance of the **model artifact itself** between training and serving: where
it comes from, what trained it, whether it may be trusted, which version is live, and
how a bad one is rolled back.

OpenBank already has the adjacent machinery, but none of it covers models:

- **ADR-0121** gives every *service* a self-reported SBOM + supply-chain attestation.
  A model is a deployable artifact with its own dependency closure (training
  pipeline, framework versions) — it needs the same rigor, but ADR-0121 stops at
  service JARs/images.
- **ADR-0031** governs AI *agents* as code (policy-gated, audit-attributed) — it says
  nothing about ML models, model cards, validation, or promotion.
- **ADR-0034** gives an advisory→enforce ladder; ADR-0133 gives a tamper-evident
  audit chain. Both are reused here but neither defines *what identifies a model*.

Without a registry, a model that declines a customer's payment or loan is an opaque
binary in an object store: no record of its training set, its offline metrics, who
approved it for that decision scope, or what the previous champion was. That is
unacceptable for a regulated automated decision (GDPR Art. 22, model-risk
expectations à la SR 11-7 / EBA GL on the use of ML). **Why now:** the registry is a
hard precondition for ADR-0139 phase 2 (the first trained model) — a model cannot be
promoted past shadow without an identity, provenance and an approval record.

## Decision

Treat a **model as a first-class, governed, provenance-bearing artifact** — the
exact treatment ADR-0121 gives a service, plus an ML-specific **model card** and a
declarative **deployment pointer**. The registry is **GitOps-native** (model cards in
the repo, artifacts + signatures in the existing registry/object store), not a new
stateful service.

**Model card (declarative, in-repo, the unit of governance).** One file per model,
reviewed by PR, carrying:
- identity: `model-id`, semantic `version`, owner, intended decision scope
  (`fraud-shadow` | `fraud-enforce` | `credit-*`) — a model may only be wired into a
  scope its card declares;
- **training-set identity**: the offset-pinned dataset hash from ADR-0140 (so the
  exact training data is reconstructable) + the code commit that trained it;
- **features consumed**: references to ADR-0140 feature declarations (the card cannot
  name a feature that is not declared — closes a skew/leak vector at review time);
- **evaluation**: offline metrics, calibration, and — for credit (ADR-0142) —
  fairness/disparate-impact metrics, all required before promotion past shadow;
- **provenance**: a signed attestation linking artifact → training-set identity →
  code commit, reusing the ADR-0121 SBOM/attestation chain (a model artifact is
  signed and SBOM'd exactly like a JAR).

**Artifact provenance.** The ONNX artifact is content-addressed, **signed**, and
carries an in-toto-style attestation (ADR-0121). Serving (ADR-0139) **verifies the
signature and that the loaded artifact's scope matches its wiring before it is
allowed to score** — an unsigned or scope-mismatched model fails closed (rules-only).

**Deployment pointer = config, not code.** Which model version is *champion* /
*challenger* / *shadow* for a given decision scope is **declarative GitOps config**,
flipped by PR following the phased-rollout pattern of ADR-0139 (shadow → challenger →
champion); the ADR-0034 OPA gate is the **runtime enforcer** once a model sits in an
`*-enforce` scope, not the promotion mechanism itself. Promotion to any `*-enforce`
scope inherits the money-path gate (2 approvals + threat model, ADR-0030).
The pointer is the single switch for **rollback**: drift auto-rollback (ADR-0139
phase 2) reverts the champion pointer to the previous version — no redeploy, no code
change.

**Audit binding.** Every inference records `model-id@version` into the tamper-evident
audit chain (ADR-0133) alongside its reason codes, so any past automated decision is
traceable to the exact model, its training set, and its approval record. This is the
spine of adverse-action defensibility in ADR-0142.

**Phasing (named, not hidden).**
- *Phase 1 (with ADR-0139 phase 2)* — model-card schema + the first card for the
  first trained fraud model; artifact signing + serve-time verification; the GitOps
  champion/challenger/shadow pointer; audit binding. Fraud only, shadow/challenger.
- *Phase 2* — fairness/validation fields enforced as promotion gates (required for
  credit); independent-validation sign-off as a CODEOWNERS rule on the card; **stale
  model-card metric detection** (a deployed card whose offline metrics have gone stale
  vs. live drift is flagged, closing the freshness gap named in Consequences).
- *Deferred* — adopt MLflow Model Registry (or similar) **iff** the GitOps model-card
  approach outgrows hand-maintained cards; the schema here is deliberately
  MLflow-mappable so the move is mechanical.

## Alternatives considered

- **Artifacts in an object store, no registry.** Status quo if we did nothing.
  Zero provenance, zero audit, no champion concept — unacceptable for a regulated
  decision. Rejected (this is the problem).
- **MLflow Model Registry now.** The obvious OSS fit and a credible phase-2+ target,
  but it is a stateful service with its own DB and UI, heavyweight for one fraud
  model, and its provenance/signing story is weaker than reusing ADR-0121. Deferred,
  not rejected; the card schema is MLflow-shaped.
- **Managed model registry (SageMaker / Vertex Model Registry).** Breaks
  cloud-agnostic in-cluster OSS (ADR-0027) and exports model + metadata to a managed
  plane. Rejected.
- **Fold models into ADR-0031 agents-as-code unchanged.** Tempting (reuse the agent
  governance verbatim), but a model is not an agent — it has training data, metrics,
  fairness and validation concerns an agent does not. Rejected in favour of a
  model-specific card that *reuses* 0031's policy/audit machinery without conflating
  the two asset types.
- **Sign nothing; trust the build.** Rejected — an unsigned money-path/credit model
  is an unaccountable supply-chain hole; ADR-0121 already set the signing bar for
  artifacts and models are not exempt.

## Consequences

**Positive**
- A model that enforces a decision is as accountable as the code that ships it:
  identity, training data, metrics, approval and rollback are all on record.
- Reuses ADR-0121 (signing/SBOM/attestation), ADR-0034 (promotion ladder), ADR-0133
  (audit) — no new stateful service; the registry is just reviewed config + signed
  artifacts.
- Provides the audit spine ADR-0142 needs for adverse-action defensibility.

**Negative**
- Hand-maintained model cards have a freshness/discipline cost; a card that drifts
  from the deployed artifact is a governance gap (mitigated by serve-time scope
  verification and the offset-pinned training-set hash).
- Signing + verification adds a step to the training→serving path and a key-management
  dependency.

**Neutral**
- No money path or decision change here; the registry governs models that other ADRs
  (0139/0142) wire in behind their own gates.
- Lands partly in `openbank-libs` (verification) and partly in GitOps (cards/pointer).

## Compliance impact

- **DORA:** models are ICT assets — the card is their entry in the asset/provenance
  register; rollback is an operational-resilience control.
- **GDPR:** Art. 22 automated-decision accountability — the audit binding
  (`model-id@version` + reasons) is the record of *which* logic decided; the
  training-set link supports data-provenance and erasure-impact analysis (ADR-0118).
- **Model risk (SR 11-7 / EBA GL on ML):** model cards + independent-validation
  sign-off + champion/challenger records are the documented model-risk-management
  trail.
- **PCI DSS / PSD2:** neutral — metadata only; no cardholder data in cards.

## References

- ADR-0139 — ML decisioning platform (consumes the registry to wire champion/shadow)
- ADR-0140 — feature store (training-set identity + feature declarations referenced by cards)
- ADR-0121 — SBOM / attestation (extended here to cover model artifacts)
- ADR-0031 — AI agent governance (policy/audit machinery reused, not conflated)
- ADR-0034 — OPA enforcement gate (runtime enforcer for an enforce-scope model; the promotion phases themselves are defined by ADR-0139)
- ADR-0133 — tamper-evident audit chain (per-inference model binding)
- ADR-0030 — threat-model requirement for `*-enforce` promotion
- ADR-0118 — GDPR data lifecycle (training-set erasure-impact)
- ADR-0142 — credit decisioning engine (the heaviest consumer of model provenance)
