# ADR-0142 — Credit decisioning engine on the ML decisioning platform

Date: 2026-06-29
Decision-Status: Proposed   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: Planned    <!-- Planned | Partial | Shipped | N/A — decision-only -->
Author(s): Jiri Raska

## Context

ADR-0139 establishes a real-time ML decisioning platform, ADR-0140 the
point-in-time feature store, ADR-0141 model provenance. The platform was deliberately
proven on **fraud** first because it is the lowest-blast-radius consumer. This ADR
applies the same platform to its highest-value and highest-regulatory-bar consumer:
**credit decisioning** in the lending bounded context (ADR-0028 — origination,
servicing, collateral, IFRS 9).

Today lending origination has **no learned decisioning**: approve/refer/decline and
pricing are either static policy rules or a bureau score passed through. That is the
inverse of the incumbents' position — Temenos/Mambu carry mature origination
workflow but bolt ML scoring on; OpenBank has a clean, reproducible, explainable ML
substrate and a thin origination flow. Credit is where that substrate converts into
money: better risk separation means lower losses and sharper pricing.

But credit decisioning is **not fraud with bigger numbers** — it carries a class of
legal obligation fraud scoring does not:

- **Adverse-action explainability** is mandatory and individual: a declined or
  worse-priced applicant is entitled to the *specific principal reasons*
  (ECOA/Reg B analogue; CZ consumer-credit law; EBA Guidelines on Loan Origination &
  Monitoring). A score with no reason is not a lawful decision.
- **Right to human intervention** (GDPR Art. 22) for solely-automated decisions with
  legal/significant effect — a fully automated decline is exposed.
- **Fair lending / non-discrimination** — protected attributes and their proxies must
  be controlled and disparate impact monitored.
- **Affordability / responsible lending** — a hard regulatory floor independent of
  any model.

So the platform's existing controls (fail-closed floor, explainability, audit
binding, model provenance) are necessary but **not sufficient** here; this ADR adds
the credit-specific obligations on top. **Why now:** decision-only — it defines the
controls *before* any model touches a lending decision, so the build cannot take a
shortcut that is cheap to code and unlawful to ship.

## Decision

Build a **credit decisioning engine** as a lending-side consumer of the ADR-0139
platform, with **stricter controls than fraud**. Reuse everything reusable
(feature store 0140, model registry/provenance 0141, the ADR-0139 phased rollout with
the ADR-0034 OPA gate as runtime enforcer, audit chain 0133, the four-eyes *pattern*
of 0116 — the generic state machine, not its KYC-specific instance); add the
credit-specific obligations as non-optional contracts.

**Decision contract.** The engine emits `approve | refer | decline` **plus price**,
never a bare score. The output is invalid (and the request fails closed to manual
review) unless it carries a machine-readable **adverse-action reason set** mapped to
human-readable principal reasons. **No reasons ⇒ no decision** — explainability is a
hard precondition, not a logged nicety.

**Policy floor is deterministic and supreme.** Affordability and hard eligibility
(income verification, DTI/DSTI limits, exclusion rules) are **deterministic rules**,
not learned. The model operates *within* the policy-allowed set: it may **decline or
price up** inside policy, but it can **never approve outside the hard floor** — the
same fail-closed-floor invariant as ADR-0139, with the DST guarantee (ADR-0100) that
the ML-approved set is a subset of the policy-approved set.

**Human-in-the-loop for adverse and borderline outcomes.** A `decline` and any
borderline band route to a **mandatory four-eyes referral** (reuse ADR-0116), giving
the applicant the GDPR Art. 22 human-intervention path. Solely-automated *adverse*
decisions above a **significance threshold** are **not permitted** in the enforce
phase — "significant effect" is the load-bearing Art. 22 qualifier, so the concrete
threshold (e.g. credit amount / pricing-delta bands that trigger mandatory human
review) is **defined and justified in the phase-3 DPIA**, not left implicit.

**Fairness as a build-time and run-time control.** Protected attributes are excluded
at the feature-declaration boundary (ADR-0140) and proxy features are monitored;
fairness/disparate-impact metrics are **required fields on the model card (ADR-0141)
and a promotion gate** — a credit model cannot be promoted past shadow without them.
Disparate-impact drift is monitored in the ADR-0139 phase-2 drift pipeline.

**Heavier model-risk governance.** Promotion of a credit champion to any enforce
scope requires, beyond the money-path gate (2 approvals + threat model `docs/threat-
models/credit-decisioning.md`, ADR-0030): an **independent model-validation sign-off**
(ADR-0141 phase 2 CODEOWNERS rule) and a **DPIA**. Credit is a money-path *and*
fundamental-rights surface.

**Phasing (named, not hidden) — strictly gated, each step reversible by pointer.**
- *Phase 1 — shadow.* The model scores live applications in shadow (ADR-0139 shadow
  mode); decisions stay with the existing policy rules. Establishes calibration and
  fairness baselines on real data; moves nothing.
- *Phase 2 — challenger on pricing only.* Champion/challenger on **risk-based pricing
  within an approved decision** — the lowest-harm enforce surface (no approve/decline
  flips), with adverse-action reasons for price.
- *Phase 3 — enforce approve/refer/decline.* Full decisioning with mandatory
  adverse-action reasons + HITL referral for adverse/borderline; independent
  validation + DPIA signed; DST subset invariant green.
- *Deferred* — collateral/behavioural and IFRS-9-staging models (ADR-0028) reuse the
  platform once origination decisioning is proven.

## Alternatives considered

- **Bureau score only (status quo).** Simple and explainable, but a single external
  score is a coarse ceiling and cedes the risk-separation advantage. Rejected as the
  decisioning brain; **kept as an input feature** to the internal model.
- **Buy a credit-decisioning SaaS / external decision engine.** Fast functional
  breadth, but breaks cloud-agnostic in-cluster OSS (ADR-0027), exports applicant
  data, and re-opaque-boxes the very explainability we can own end-to-end. Rejected.
- **Fully automated straight-through decline to maximise throughput.** Cheapest to
  build, and unlawful for adverse decisions with significant effect (GDPR Art. 22).
  Rejected — HITL for adverse outcomes is non-negotiable.
- **LLM-based underwriting.** Non-deterministic, uncalibrated, unexplainable to a
  regulator, and a prompt-injection surface on a fundamental-rights decision.
  Rejected (consistent with ADR-0139); LLMs may *assist* an officer, never decide.
- **Reuse the fraud controls unchanged.** Necessary but insufficient — fraud has no
  adverse-action, fairness or affordability-floor obligation. Rejected as complete;
  this ADR is exactly the credit-specific delta.

## Consequences

**Positive**
- Converts the AI-moat substrate into the highest-value banking outcome (sharper risk
  separation, defensible pricing) with regulator-defensibility built in, not bolted
  on.
- Maximal reuse: 0139 serving, 0140 features, 0141 provenance, 0116 four-eyes, 0133
  audit — the credit delta is the obligation layer, not a new platform.
- The deterministic affordability floor + DST subset invariant make "the model can
  only be stricter than policy" a checked property, not a hope.

**Negative**
- The heaviest control surface in the platform: DPIA, independent model validation,
  fairness gates and HITL add real latency to *shipping a model* (not to scoring).
- Fairness/proxy control is genuinely hard; an undetected proxy is a fair-lending
  exposure even with protected attributes excluded.

**Neutral**
- Decision-only ADR; no lending decision changes until phase 2, and only pricing
  then. Origination workflow (ADR-0028) is unchanged in structure.
- Spans lending service code, `openbank-libs` (platform), and GitOps (pointers/cards).

## Compliance impact

- **GDPR Art. 22:** solely-automated adverse decisions restricted; HITL referral +
  per-decision reasons + model audit binding (ADR-0133) provide the rights path; DPIA
  required before enforce.
- **ECOA/Reg B analogue + CZ consumer credit + EBA LOM GL:** individual adverse-action
  principal reasons are a hard output contract; affordability/responsible-lending is a
  deterministic floor.
- **Fair lending / non-discrimination:** protected-attribute exclusion at the feature
  boundary, proxy monitoring, disparate-impact metrics as a promotion gate and drift
  signal.
- **Model risk (SR 11-7 / EBA GL on ML):** independent validation sign-off + the
  ADR-0141 provenance trail.
- **DORA:** credit decisioning is a money-path ICT asset; pointer rollback + drift
  auto-rollback are resilience controls.
- **IFRS 9 (ADR-0028):** out of scope here (provisioning/staging models are deferred);
  flagged so the boundary is explicit.

## References

- ADR-0028 — lending / credit bounded context (the consumer; origination & IFRS 9)
- ADR-0139 — ML decisioning platform (serving, shadow mode, fail-closed floor)
- ADR-0140 — feature store (protected-attribute exclusion at the feature boundary)
- ADR-0141 — model registry & provenance (fairness gates, independent validation, audit binding)
- ADR-0116 — KYC four-eyes gate (its generic four-eyes *pattern* reused for adverse/borderline HITL referral, not the KYC state machine)
- ADR-0034 — OPA enforcement gate (runtime enforcer once in an enforce scope; promotion phases defined by ADR-0139)
- ADR-0133 — tamper-evident audit chain (per-decision adverse-action evidence)
- ADR-0139 — ML decisioning platform (fail-closed floor; the ML-approved ⊆ policy-approved invariant, enforced via DST)
- ADR-0100 — deterministic simulation testing (the DST framework the subset invariant is checked under)
- ADR-0031 — AI agent governance (policy/audit machinery inherited via ADR-0141)
- ADR-0118 — GDPR data lifecycle (DPIA, adverse-action record retention, training-set erasure-impact)
- ADR-0030 — threat-model requirement for the enforce phase
