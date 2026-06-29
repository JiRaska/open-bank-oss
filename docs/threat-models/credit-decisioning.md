<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — Credit decisioning engine

STRIDE/DFD threat model for the credit decisioning engine, per ADR-0030 D2. The engine is a
lending-side consumer of the ML decisioning platform (see `ml-decisioning.md`) and inherits all
of its controls; **this model covers the credit-specific delta** — adverse-action explainability,
fair lending, affordability supremacy, and the GDPR Art. 22 human-intervention path. Reviewed in
PR; referenced from ADR-0142 (phase 3 enforce).

- **Status:** Draft (decision-only; no model decides a lending case — see §0)
- **Last reviewed:** 2026-06-29
- **Owner:** lending CODEOWNERS + platform/ML owner + (credit) model-validation owner
- **Related ADRs:** ADR-0142 (credit decisioning — this surface), ADR-0028 (lending bounded
  context), ADR-0139 (ML platform; fail-closed floor), ADR-0140 (feature store; protected-
  attribute exclusion), ADR-0141 (model registry; fairness gate + independent validation),
  ADR-0116 (four-eyes pattern — reused for HITL referral), ADR-0133 (audit chain),
  ADR-0118 (GDPR lifecycle; DPIA), ADR-0100 (DST), ADR-0030 (this threat model)

## 0. Posture (read first)

This is a **forward-looking skeleton for the target (enforce) state**; no model decides a real
lending case today. Blast radius bounds:

- **Shadow → pricing-only → full enforce (ADR-0142 phases).** In shadow the model scores live
  applications but the **existing policy rules decide**; nothing the model does reaches the
  applicant. The first enforce surface is **pricing within an already-approved decision** (no
  approve/decline flips), the lowest-harm step.
- **Deterministic affordability floor is supreme.** Affordability/eligibility (income
  verification, DTI/DSTI, exclusions) are deterministic rules; the model operates **within** the
  policy-allowed set — it may decline or price up, **never approve outside the hard floor**
  (DST subset invariant, ADR-0100). The worst case of a fully compromised model is *over*-
  conservative lending, not unlawful approval.

Because credit decisions carry **legal/fundamental-rights** effect, the threats below treat
explainability, fairness and the human-intervention path as **assets**, not features.

## 1. Scope & assets

In addition to the platform assets in `ml-decisioning.md`, the credit engine protects:

1. **Adverse-action correctness & completeness** — the machine-readable reason set behind every
   `decline` / worse-price, mapped to lawful principal reasons. A missing or wrong reason set is
   an unlawful decision, not just a bug.
2. **The human-intervention path** — the mandatory four-eyes referral for adverse/borderline
   outcomes (GDPR Art. 22). Its bypass removes a legal right.
3. **Fairness** — non-discrimination across protected attributes and their proxies.
4. **The affordability floor** — the deterministic responsible-lending rules the model may not
   override.
5. **The adverse-action record** — the immutable, regulator-facing evidence of why each applicant
   was declined/priced, retained per ADR-0118.

## 2. Data-flow diagram (textual)

```
        ┌──────────────── trust boundary: credit decisioning (lending-side) ─────────────────┐
[Application]──1──┼─▶ AffordabilityFloor (deterministic) ──▶ policy-allowed set                 │
 + bureau score   │            │  (supreme; hard reject outside floor)                          │
 (input feature)  │            ▼                                                                │
[ML platform]──2──┼─▶ CreditModelRunner (ml-decisioning.md) ─▶ score + reason codes (SHAP)      │
                  │            │   ML ⊆ policy-allowed set                                       │
                  │            ▼                                                                 │
                  │   DecisionAssembler ─▶ approve | refer | decline + price + adverse-actions   │
                  │            │   (invalid without reason set ⇒ fail-closed to manual review)   │
[Operator]────3───┼─▶ Four-eyes referral (ADR-0116) for decline/borderline  ──▶ human decision   │
                  │            ▼                                                                  │
                  │   adverse-action record ──▶ audit chain (ADR-0133, immutable, retained)      │
        └──────────────────────────────────────────────────────────────────────────────────────┘
```

Trust boundaries: (1) application intake → affordability floor; (2) feature/model platform →
decisioning; (3) decisioning → human referral. The affordability floor and the assembler's
fail-closed contract are deterministic domain logic (ADR-0002).

## 3. STRIDE analysis (credit-specific delta)

| # | Element | Threat (STRIDE) | Mitigation | Residual |
|---|---------|-----------------|------------|----------|
| C1 | Adverse-action reasons | **Tampering / absence** — a decline ships with no or fabricated reasons ⇒ unlawful decision | Reasons are a **hard output contract**: no machine-readable reason set ⇒ the decision is **invalid and fails closed to manual review**; reasons derive from the model's SHAP-style contributions, not free text | Reason-to-principal mapping quality — validated with legal before enforce; *open* |
| C2 | HITL referral | **Elevation / bypass** — an adverse/borderline case is auto-decided, skipping human review | Decline + borderline bands **must** route to four-eyes referral (ADR-0116); solely-automated adverse decisions above the significance threshold are **forbidden** in enforce (threshold set in the DPIA) | Threshold calibration — defined/justified in the phase-3 DPIA; *open* |
| C3 | Affordability floor | **Tampering** — the model (or its inputs) approves outside responsible-lending policy | Floor is **deterministic and supreme**; DST invariant asserts ML-approved ⊆ policy-approved (ADR-0100); model can only tighten | Floor-rule errors — code review + DST; standard |
| C4 | Protected attributes / proxies | **Information disclosure → discrimination** — direct or proxy discrimination in pricing/approval | Protected attributes **excluded at the feature-declaration boundary** (ADR-0140); proxy monitoring; **fairness/disparate-impact metrics are a model-card promotion gate** (ADR-0141) + a drift signal | Undetected proxy — genuinely hard; independent validation + ongoing disparate-impact monitoring; *open* |
| R1 | Adverse-action record | **Repudiation** — dispute over why an applicant was declined | Immutable per-decision record (decision + price + reasons + `model-id@version`) in the audit chain (ADR-0133), retained per ADR-0118 for the regulatory window | — |
| I1 | Decision data | **Information disclosure** — applicant financial data / model logic leaks | Role-gated, never applicant-facing beyond the lawful reason set; features whitelisted; reasons coarse-mapped; GDPR retention/erasure (ADR-0118) | Standard data-handling residual |
| E1 | Model promotion | **Elevation** — an unvalidated credit model reaches enforce | Promotion requires the money-path gate (2 approvals + this threat model) **plus independent model-validation sign-off + a DPIA** (ADR-0141/0142) before any enforce scope | Validation independence — org/process; *open* |
| D1 | Decisioning path | **DoS** — slow scoring stalls origination | Origination is not the synchronous payment hot path; standard timeouts; degrade to policy-rules-only decision (still lawful, more conservative) | Low |

## 4. Key invariants (must never regress)

- **No decision without a machine-readable adverse-action reason set** — absence ⇒ fail closed to
  manual review (never a silent auto-decline).
- **Adverse/borderline ⇒ mandatory human (four-eyes) referral** — no solely-automated significant
  adverse decision in enforce (GDPR Art. 22).
- **The affordability/eligibility floor is deterministic and supreme** — ML-approved ⊆
  policy-approved (DST-checked, ADR-0100); the model may tighten, never approve outside policy.
- **Protected attributes are excluded at the feature boundary**; fairness metrics are a promotion
  gate, not an afterthought (ADR-0140/0141).
- **Every credit decision is an immutable, retained adverse-action record** bound to
  `model-id@version` (ADR-0133/0118).
- **Enforce requires independent model validation + a DPIA** in addition to the money-path gate.

## 5. Open items / follow-ups

- **Reason-to-principal mapping (C1):** validate the adverse-action reason taxonomy with legal
  (ECOA/Reg B analogue, CZ consumer credit, EBA LOM) before enforce.
- **Significance threshold (C2):** define and justify the Art. 22 "significant effect" threshold in
  the phase-3 DPIA (credit amount / pricing-delta bands that force human review).
- **Proxy/fairness monitoring (C4):** stand up disparate-impact monitoring + independent fairness
  review; an undetected proxy is the load-bearing residual.
- **Independent model validation (E1):** establish the validation function + CODEOWNERS sign-off on
  the credit model card before any enforce flip.
- **DPIA:** complete before the first non-shadow credit decision; gates phase 3.
- **IFRS 9 staging models (ADR-0028):** out of scope here; deferred, flagged so the boundary is
  explicit.

## 6. Change log

- **2026-06-29** — Initial skeleton created alongside ADR-0142 (decision-only). No model decides a
  lending case; shadow + affordability-floor-supreme posture documented as §0. Inherits
  `ml-decisioning.md`; this file is the credit-specific delta (explainability, fairness, HITL,
  affordability). Written for the target enforce state so controls are designed in from the start.
