<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — ML decisioning platform

STRIDE/DFD threat model for the real-time ML decisioning platform (feature store, model
serving, champion/challenger), per ADR-0030 D2. The platform augments the deterministic
`FraudRuleEngine` in the money-path `openbank-fraud-service`; this model covers the **new
attack surface ML introduces** on top of the rules already covered by
`openbank-fraud-service.md`. Reviewed in PR; referenced from ADR-0139 (phase 3 enforce).

- **Status:** Draft (decision-only; no model is wired — see §0)
- **Last reviewed:** 2026-06-29
- **Owner:** fraud CODEOWNERS + platform/ML owner
- **Related ADRs:** ADR-0139 (ML decisioning platform — this surface), ADR-0140 (feature
  store; point-in-time correctness), ADR-0141 (model registry & provenance),
  ADR-0084 (fraud bounded context — the augmented service), ADR-0034 (OPA enforce),
  ADR-0133 (tamper-evident audit chain), ADR-0121 (SBOM/attestation), ADR-0100 (DST),
  ADR-0030 (this threat model), ADR-0002 (hexagonal)

## 0. Posture (read first)

This is a **forward-looking skeleton written for the target (enforce) state** so the controls
are designed in from the start; it is **not** a description of a live control. Two facts bound
today's blast radius:

- **Shadow moves nothing (ADR-0139 phases 1–2).** The ML score is computed and logged
  *alongside* the rule verdict; the returned `FraudScore` is byte-identical to rules-only. A
  compromise of any ML component in shadow **cannot change a single payment decision** — the
  rule engine alone decides.
- **Fail-closed floor (ADR-0139).** Even at enforce, ML may only **tighten** a decision, never
  silently **loosen** one above a rule threshold; if a model, feature, or store is unavailable
  the engine degrades to **rules-only**. The deterministic floor in `openbank-fraud-service.md`
  remains the worst-case control. The threats below describe what ML could do **once it is
  allowed to tighten**, and the controls that keep that bounded.

## 1. Scope & assets

The platform adds learned signal to the behavioural risk layer. Assets it introduces, in
priority order:

1. **Model artifact integrity** — the served ONNX model. A substituted or poisoned model
   produces attacker-chosen scores; signing + serve-time verification (ADR-0141) is the spine.
2. **Feature integrity (online store)** — `feature:<name>:<entity-id>` values in Valkey. A
   poisoned or stale feature skews every score that reads it.
3. **Training-set integrity & confidentiality** — the offset-pinned offline dataset (ADR-0140).
   Poisoning corrupts the next champion; leakage exposes customer behaviour.
4. **Decision provenance** — the per-inference `model-id@version` + reason codes bound into the
   audit chain (ADR-0133); the evidence that a given score came from a given governed model.
5. **The fail-closed floor itself** — the invariant that ML intersects, never widens, the
   rule-allowed set (DST-checked, ADR-0100).

## 2. Data-flow diagram (textual)

```
        ┌──────────────── trust boundary: ML decisioning (in fraud-service) ─────────────────┐
[Kafka outbox]──1──┼─▶ FeatureUpdater ──▶ [Valkey online store]  feature:<name>:<id>           │
 domain events     │     (idempotent on offset)        │ (value + as-of ts + offset)           │
                   │                                    ▼                                       │
[Scoring call]──2──┼─▶ FraudScoringService ─▶ FeatureReader ─(freshness assert)─▶ ModelRunner   │
 (payment surface) │            │                                   │            in-proc ONNX   │
                   │            ▼                                    │  (verify signature+scope) │
                   │      FraudRuleEngine (floor) ◀── combine ───────┘  ──▶ reasons + score      │
                   │            │   ML may only tighten                                          │
                   │            ▼                                                                │
[Model registry]─3─┼─▶ signed ONNX artifact + model card (GitOps)   ──▶ audit chain: model@ver  │
[Offline store]──4─┼─▶ event-log replay + Parquet snapshot (training; point-in-time < t)        │
        └──────────────────────────────────────────────────────────────────────────────────────┘
```

Trust boundaries: (1) Kafka → FeatureUpdater → Valkey; (2) payment surface → scoring; (3) model
registry/artifact store → in-process serving; (4) event log → offline training. The combine step
and `FraudRuleEngine` are pure domain (ADR-0002); ML output enters as one more contributor, never
as the sole authority.

## 3. STRIDE analysis

| # | Element | Threat (STRIDE) | Mitigation | Residual |
|---|---------|-----------------|------------|----------|
| M1 | Model artifact | **Tampering** — a substituted/poisoned model serves attacker-chosen scores | Content-addressed, **signed** artifact + in-toto attestation (ADR-0121); serving **verifies signature and that artifact scope == wiring before scoring**; unsigned/scope-mismatch ⇒ fail-closed (rules-only) | Signing-key compromise — KMS/key-management scope; *open* until key custody hardened |
| M2 | Model scope | **Elevation** — a model wired into a scope its card did not declare (e.g. a shadow model silently enforcing) | Serve-time scope check against the model card (ADR-0141); promotion to any `*-enforce` scope is a money-path PR (2 approvals + this threat model) flipping a GitOps pointer | Pointer-flip review discipline — process; *open* |
| F1 | Online feature | **Tampering** — a poisoned feature value skews scores | Online store is written **only** by the outbox consumer (no external write path), **idempotent on event offset**; values carry as-of ts + source offset for audit | Compromise of the updater pod — infra scope |
| F2 | Stale feature | **Tampering / DoS** — a stale feature silently drives a confident wrong score | **Freshness assertion**: a value older than its TTL is returned `Stale` and treated as **missing** ⇒ rules-only floor; never served as confident | Low — degrades safe |
| T1 | Training set | **Tampering** — data poisoning corrupts the next champion | Offline set is offset-pinned & reproducible (ADR-0140); point-in-time `< t` join (anti-leakage); champion promotion gated on offline metrics + (credit) fairness | Slow/long-horizon poisoning — monitored via drift (ADR-0139 phase 2); *open* |
| I1 | Training/feature data | **Information disclosure** — customer behaviour leaks via features, snapshots, or model inversion | Features whitelisted at declaration (no PAN/CVV, ADR-0140); snapshot inherits GDPR retention/erasure (ADR-0118); access-controlled store; reason codes coarse | Model-inversion / membership inference — research residual; *open* |
| R1 | Decision | **Repudiation** — dispute over which model produced a score | Every inference binds `model-id@version` + reason codes into the tamper-evident audit chain (ADR-0133), traceable to training set + approval (ADR-0141) | — |
| D1 | Serving path | **DoS** — in-process ONNX inference adds latency to the synchronous money path | In-process (no network hop); recorded p99 latency budget; **degrade to rules-only** on timeout/unavailability; load test before any enforce flip | Pathological-input slowdown — input validation; *open* |
| A1 | Model boundary | **Adversarial evasion** — an attacker crafts transactions to slip under the ML score | Deterministic rule floor still fires regardless of ML; drift/disparate-performance monitoring; reason-code review | Inherent to ML; bounded by the rule floor — accepted, monitored |

## 4. Key invariants (must never regress)

- **ML may only tighten, never silently loosen** a rule decision; the ML-approved set is a
  **subset** of the rule/policy-approved set (DST-checked, ADR-0100 via ADR-0139).
- An **unsigned or scope-mismatched model is never served** — verification failure ⇒ rules-only.
- A **stale feature is treated as missing** ⇒ rules-only; never served as a confident value.
- **Serving/feature/store unavailability degrades to rules-only**, never to ML-only.
- **Every inference is bound** to `model-id@version` + reasons in the audit chain (ADR-0133).
- The **domain combine + rule floor are framework-free** (ADR-0002); ML enters as one contributor.

## 5. Open items / follow-ups

- **Signing-key custody (M1):** harden KMS/key management for model-artifact signing before enforce.
- **Pointer-flip discipline (M2):** champion promotion is a money-path PR; the second approver
  signs off the residual before any `*-enforce` flip.
- **Drift + adversarial monitoring (T1/A1):** stand up drift/disparate-performance monitoring
  (ADR-0139 phase 2) before enforce; long-horizon poisoning is otherwise undetected.
- **Latency load test (D1):** prove the in-process ONNX p99 budget under load before the enforce flip.
- **Model-inversion / membership inference (I1):** residual ML-privacy risk; revisit if features
  widen beyond velocity counters.
- **DPIA:** required for the credit consumer (see `credit-decisioning.md`); the fraud enforce phase
  reuses the existing fraud DPIA posture.

## 6. Change log

- **2026-06-29** — Initial skeleton created alongside the ADR-0139/0140/0141 family (decision-only).
  No model wired; shadow posture (no blast radius) + fail-closed floor documented as §0. Written for
  the target enforce state so controls are designed in from the start.
