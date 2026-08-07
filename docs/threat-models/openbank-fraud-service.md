<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-fraud-service

STRIDE/DFD threat model for the fraud-detection bounded context, per ADR-0030 D2.
Money-path service (it sits in the synchronous payment path once wired). Reviewed in PR;
referenced from ADR-0084.

- **Status:** Draft (lightweight, first pass for ADR-0084 Phase 1 skeleton)
- **Last reviewed:** 2026-06-14
- **Owner:** fraud CODEOWNERS
- **Related ADRs:** ADR-0002 (hexagonal), ADR-0084 (fraud bounded context — this service),
  ADR-0032 (sibling sanctions/AML screening gate), ADR-0021/0073 (SCA),
  ADR-0065 (customer edge — device/IP context source), ADR-0067 (feature flags, four-eyes
  on money-path flips), ADR-0077 (DomainMetrics), ADR-0009 (Postgres-per-service)

## 0. Phase-1 posture (read first)

This is a **skeleton**. The rule engine holds a single deterministic **always-ALLOW** baseline
rule (`ruleVersion = "v0"`, score 0, reason `baseline-allow`) and **no payment surface calls it
yet**. Two consequences for this threat model:

- **Fail-open by construction.** Every verdict in Phase 1 is `ALLOW`. Per ADR-0084 §1, scoring
  unavailability degrades to `ALLOW` + alert during rollout (availability of payments beats an
  immature control); the failure mode is itself a feature flag so the posture can be flipped to
  fail-closed (`CHALLENGE`) once SLOs prove the service out. **The service provides no fraud
  protection until Phase 2 rules land and a surface is wired** — it must not be relied on as a
  control yet. The `fraud-monitoring` compliance control is therefore `partial`, not `enforced`.
- **No live blast radius.** Because nothing calls `POST /api/v1/fraud/score`, a compromise of this
  service cannot today block, challenge, or wave through a real payment. The threats below are
  written for the **target** state (surfaces wired) so the controls are designed in from the start.

## 1. Scope & assets

The fraud service is the **behavioural risk layer** protecting the customer and the bank from
unauthorized or manipulated transactions (as opposed to ADR-0032 AML/sanctions, which protects the
financial system). At maturity it is consulted synchronously in the payment hot path.

Assets protected, in priority order:

1. **Verdict integrity** — the ALLOW/CHALLENGE/REVIEW/DECLINE decision the payment surface acts on.
   A forged or tampered verdict directly enables fraud (false ALLOW) or denial of service (false
   DECLINE).
2. **Rule set + rule version** — the deterministic, versioned, code-reviewed rules (ADR-0084 §3).
   Rules are config-as-code, never UI-editable; an unreviewed rule change is a money-path change.
3. **Scoring audit trail** (`fraud_scores`) — the immutable per-decision record; the reference
   fraud-rate dataset RTS Art. 18 needs and the evidence for every verdict.
4. **Behavioural aggregates** (Phase 2) — per-account/per-party velocity counters, known-payee
   sets, device history; derived from Kafka domain events.

## 2. Data-flow diagram (textual)

```
                     ┌──────────────────── trust boundary: fraud-service ───────────────────────┐
 [Payment surface]   │                                                                            │
 ROLE_SERVICE   ──1──┼─▶ REST (FraudResource)  ──▶  FraudScoringService  ──▶  FraudRuleEngine     │
   JWT (Keycloak)    │      @RolesAllowed             (use case)              (pure domain)        │
   (Phase 2 caller)  │      POST /fraud/score              │                  BaselineAllowRule    │
                     │           │                         ▼                                       │
 [Operator]     ──2──┼─▶ (future review queue)     FraudScoreRepositoryImpl ──▶ [Postgres] ──3──   │
   JWT @RolesAllowed │                                     │                     fraud_scores       │
                     │                                     ▼                                       │
 [Kafka events] ──4──┼─▶ TransactionSignalConsumer ──▶ VelocityAggregateRepositoryImpl ────────  │
   transaction.init  │     (H1/H24/D7 rolling counters)    velocity_aggregates (Postgres) ──5──   │
                     │                              └──▶ PayeeHistoryRepositoryImpl ──────────  │
                     │                                    (per-account/payee history)  payee_history (Postgres) ──6──   │
                     └────────────────────────────────────────────────────────────────────────────┘
```

Trust boundaries crossed: (1) external caller → REST; (3) service → Postgres;
(4) Kafka (`openbank.transactions.transaction.initiated`) → `TransactionSignalConsumer` → (5)
`velocity_aggregates` Postgres and (6) `payee_history` Postgres (ADR-0084 §3 v4 — same Kafka signal,
same trust boundary, no new topic). Domain layer (`FraudRuleEngine`, model) has **zero** framework
imports (ADR-0002), so verdict logic is unit-testable in isolation.

## 3. STRIDE analysis

| # | Element | Threat (STRIDE) | Mitigation | Residual |
|---|---------|-----------------|------------|----------|
| S1 | REST in | **Spoofing** — caller forges identity to obtain or influence a verdict | Bearer JWT (Keycloak); `@RolesAllowed("ROLE_SERVICE","ROLE_OPERATOR","ROLE_ADMIN")` on `score`; no `@PermitAll` endpoint | OPA fine-grained authz (ADR-0034) advisory, not yet enforced — *open* |
| T1 | Verdict | **Tampering** — a false ALLOW waves through fraud, or a false DECLINE denies a legitimate payment | Verdict is computed server-side in the pure domain from a versioned rule set; never accepted from the caller; the surface acts only on the response body | Phase-1 stub always ALLOWs (no protection yet) — accepted, documented §0 |
| T2 | Rule set | **Tampering** — an unreviewed/malicious rule flips verdicts at scale | Rules are code-as-config (no runtime mutation, ADR-0033); every change is a money-path PR (2 approvals + this threat model, ADR-0030); `ruleVersion` pinned into every persisted decision | Signed rule-set provenance — *planned* |
| T3 | `fraud_scores` rows | **Tampering** — direct DB mutation of the audit trail | App-only write path; reactive Panache; forward-only Flyway; rows are append-only (no update/delete in the adapter); DB creds via Vault (ADR-0017) in deployed env | DB-admin insider — infra scope |
| R1 | Scoring decision | **Repudiation** — dispute over what verdict was returned for a payment | Every decision persisted immutably (`fraud_scores`: request context + verdict + `score` + `reasons` + `rule_version` + `created_at`) | Wire signed audit / evidence bundle (ADR-0029) — *planned* |
| I1 | Verdict reasons | **Information disclosure** — reason codes / score leak the rule logic to an attacker probing the boundary | Reason strings are coarse, non-PII (`baseline-allow`); endpoint role-gated to service/operator/admin, never customer-facing; customer-safe decline reason mapping is a surface concern | Reason-code taxonomy reviewed as Phase-2 rules land — *open* |
| I2 | Metrics / persisted columns | **Information disclosure** — high-cardinality labels or audit columns enable per-customer inference / leak PII | DomainMetrics deferred (Phase 1) — when added (#850 follow-up), `openbank_fraud_scores_total` is tagged **only** by `verdict` (a closed 4-value set), never account/party/amount (ADR-0077 cardinality contract). `account_id`/`counterparty_id` live in row columns (access-controlled), not metric labels. `/q/metrics` is cluster-internal | Low — verdict tag is bounded |
| D1 | Scoring path | **DoS** — flood of `/score` calls, or a slow rule set, starves the payment hot path | Reactive non-blocking stack; per-service k8s limits; latency budget p99 ≤ 150 ms (ADR-0084); **fail-open** flag bounds caller blast radius if the scorer is slow/unavailable | Gateway rate-limiting — infra scope; load test before enforce phase |
| E1 | Roles | **Elevation** — a read/operator role obtains a privileged action | Single endpoint, no privilege tiers within it yet; deny-by-default once OPA enforce is on (ADR-0034) | OPA still advisory — *open* |
| S2 | OIDC client secret | **Spoofing (shared-credential blast radius)** — fraud reuses the shared `openbank-services` Keycloak confidential client / shared Vault key (like the rest of the fleet). Compromise of that one key mints tokens accepted across services. | Secret Vault-projected (never in git/state); confidential (not public) client; endpoint additionally requires a service/operator/admin role | **Shared-credential blast radius accepted for sandbox only.** Dedicated Vault path + per-service Keycloak client = hardening before prod. **Prod go-live requires the second money-path approver to sign off this residual** (ADR-0030). — *open* |
| T4 | Bundled ONNX model | **Tampering** — a modified `baseline-fraud-v1.onnx` on the classpath changes shadow-score behaviour without a rule-set code review (T2's mitigation is code-as-config; a binary model artifact isn't source-reviewable the same way) | Model file is repo-committed (in `src/main/resources`, same PR review + signed-commit gate as code, ADR-0030); loaded from the service's own classpath only, never a runtime-fetched path; `OnnxFraudModel.loadSession` catches any parse/load failure and disables shadow scoring rather than propagating a bad model | No cryptographic signature/provenance on the model artifact itself yet — deferred to ADR-0141's model registry (model card + artifact signing reusing ADR-0121's chain), *tracked, not blocking since output stays shadow-only (never affects a verdict) until ADR-0139 phase 3* |
| D2 | ONNX Runtime session | **DoS** — a malformed/oversized `.onnx` payload consumed pathologically by ONNX Runtime's native inference engine stalls or crashes the JVM | Model is a fixed, repo-bundled, size-known (269 B) artifact — never accepts an externally-supplied model at runtime; `scoreShadow` wraps inference in a try/catch and degrades to `null` (rules-only) rather than propagating | Native-library CVEs in `onnxruntime` itself — covered by the fleet's existing Trivy/SBOM scan (ADR-0121), not a fraud-service-specific gap |

## 4. Key invariants (must never regress)

- The **verdict is always computed server-side** from the versioned rule set; it is never read from
  the request body.
- Every `score()` call **persists exactly one immutable `fraud_scores` row** with the `rule_version`
  that produced it — no verdict without an audit trail.
- The **domain layer is framework-free** (ADR-0002); the rule engine is deterministic and
  unit-tested in isolation.
- **No endpoint is `@PermitAll`** — `/api/v1/fraud/score` is role-gated.
- Rules are **code-as-config** — no runtime-mutable rule engine (ADR-0033); every rule change is a
  money-path PR and bumps `ruleVersion`.
- Once DomainMetrics is added, verdict labels are **low-cardinality and PII-free** (the closed
  `verdict` set only; never account/party/amount/id — ADR-0077).

## 5. Open items / follow-ups

- **Phase-1 fail-open / no protection (§0).** The stub always ALLOWs and is unwired; this is *not*
  yet a control. Phase 2 (real velocity/behavioural rules + signal-plane aggregates) and surface
  wiring (shadow → challenge → enforce, ADR-0084 §4) close this. Until then `fraud-monitoring` is
  honestly `partial`.
- **DomainMetrics deferred (#850 Phase 1 follow-up):** `openbank_fraud_scores_total{verdict}` —
  deferred to avoid an openbank-libs fleet rebuild; add when libs next ships.
- **Surface integration not yet built:** no gitops/ArgoCD manifest, no payment-surface call. The
  service is inert; deployment is a separate follow-up.
- **Dedicated OIDC credential (S2):** provision a per-service Vault path + dedicated confidential
  client before prod; sandbox risk-accepted.
- **OPA enforce (S1/E1):** authz is advisory; enforce fine-grained policy (ADR-0034) before the
  enforce rollout phase.
- **Signed audit / evidence bundle (R1, ADR-0029 D2)** for non-repudiation of verdicts.
- **`velocity_aggregates` has no redelivery guard (v4 finding, T-class).** Unlike the new
  `payee_history` upsert (guarded on `last_transaction_id`), a redelivered/duplicate Kafka message
  for the same underlying transaction still double-counts `velocity_aggregates.transaction_count`
  and `total_amount` — a pre-existing gap surfaced during the v4 work, not newly introduced. Adding
  the same idempotency-key guard there is a follow-up, not blocking v4 (payee_history's fail mode —
  a payee wrongly appearing established a message earlier than it should — is materially less
  severe than velocity's — an inflated count nudging REVIEW).
- **Load test the p99 ≤ 150 ms latency budget** before flipping any surface to challenge/enforce.

## 6. Change log

- **2026-08-05** — Prohibit the customer-edge M2M principal from `fraud.score` (#3734).
  `operator-fraud-write` was role-only over the whole `fraud.*` namespace, and `rules.yaml`'s
  `role_action_matrix` grants `fraud.score` to `ROLE_OPERATOR`. The customer-facing edge identity
  (`service-account-openbank-edge`, HUMAN-classified, ROLE_OPERATOR) was therefore admitted to
  the real-time scoring gate via base `matrix-allows` — the same escalation class fixed for
  interest in #3698. Fleet caller audit: **no fraud caller exists in customer-edge at all** (no
  `fraudServiceUrl` anywhere in the edge); the only M2M scorer is the shared backend client
  (fx-service shadow scoring, graduated via `service-fraud-scoring`). Tightening is two-layered:
  `operator-fraud-write` now excludes every `service-account-*` principal (which also closes any
  FUTURE `fraud.*` rule shape to M2M), and an edge-scoped `prohibited` clause vetoes
  `fraud.score` at the allow head, beating the matrix grant. Edge-scoped rather than
  all-service-accounts because the shared client IS the legitimate scoring caller. The analyst
  review-queue read (`fraud.review.read`) rides base `operator-read-any` — the pre-existing
  fleet-wide M2M read over-grant, tracked separately in #3734, out of scope here. Falsified by
  `fraud_rest_ext_test.rego` (stripping either layer turns 3 of 6 tests red); the ext moved from
  a generator heredoc to a standalone `fraud_rest_ext.rego` so `opa test` can load it. Rollback:
  revert the ext — no live caller is lost, as no edge fraud path exists.
- **2026-06-23** — ADR-0084 §2 Phase 2: Kafka signal plane implemented.
  New asynchronous trust boundary: Kafka topic `openbank.transactions.transaction.initiated` →
  `TransactionSignalConsumer` (`@Incoming @Blocking`) → `VelocityAggregateRepositoryImpl` →
  `velocity_aggregates` Postgres table (Flyway V2). `FraudRuleEngine` bumped to `v2` with two
  deterministic rules: `VelocityH1ReviewRule` (≥ 10 transactions/h → REVIEW, delta 30) and
  `VelocityH24ReviewRule` (≥ 50 transactions/24h → REVIEW, delta 20). Both rules fire in
  **shadow mode only** — no payment surface has been wired yet. `ScoreRequest` enriched with
  `velocityH1Count`/`velocityH24Count` (defaults 0; backward-compatible with all callers).
  **New STRIDE additions**: S3 (Kafka spoofing — cluster-internal topic, no mTLS yet; accepted
  for sandbox), T4 (Tampered transaction amount/account in transit), I3 (velocity_aggregates
  contain account_id but no PII beyond what fraud_scores already held; DB access-controlled).
  Rollback: remove Kafka consumer config + revert FraudRuleEngine to v1 + drop velocity_aggregates
  table. No new HTTP endpoint; no change to `/api/v1/fraud/score` contract.

- **2026-07-07** — ADR-0084 §3 v3 (issue #529): rule set expanded, no new trust boundary.
  `FraudRuleEngine` bumped to `v3` with two amount-based rules: `LargeSingleTransactionReviewRule`
  (single transaction ≥ threshold → REVIEW, delta 25) reading only `ScoreRequest.amount` (present
  since Phase 1), and `VelocityH1HighValueReviewRule` (rolling 1h transacted amount ≥ cap → REVIEW,
  delta 35) reading a new `ScoreRequest.velocityH1TotalAmount` field sourced from the *existing*
  `velocity_aggregates.total_amount` column (already persisted by the Phase-2 signal plane, no
  schema change). Both rules fire in shadow mode only, same as v2. No new data source, no new
  Kafka topic, no new endpoint — asset/threat inventory (§1–§4) is unchanged. T2 (rule-set
  tampering) mitigation continues to apply: both rules are code-as-config, reviewed in this PR
  under the existing money-path 2-approval gate. Rollback: revert `FraudRuleEngine` to `v2`
  (`ScoreRequest.velocityH1TotalAmount` defaults to zero and is additive/backward-compatible, so no
  data cleanup is required on rollback).

- **2026-07-08** — ADR-0084 §3 v3 follow-up (PR #546, adversarial review finding): cross-currency
  false-ALLOW fixed, no new trust boundary. The initial v3 cut compared raw `BigDecimal` amounts
  against a single currency-blind threshold per rule — a large EUR payment (e.g. EUR 480,000,
  worth roughly CZK 12,000,000) could sail under a CZK-calibrated cap (CZK 500,000), a genuine
  T1-class verdict-integrity gap (false ALLOW on a large payment). Both v3 rules now key their
  threshold off `ScoreRequest.currency` via a `Map<String, BigDecimal>` (CZK/EUR populated; see
  ADR-0084 for the exact figures). **An unmapped currency now fails CLOSED** — the rule fires
  REVIEW unconditionally rather than silently never firing — consistent with this service's
  existing "no silent pass on missing signal" posture (velocity rules are silent-on-zero only for
  *mapped* currencies; the count is genuinely known to be zero, whereas an unmapped currency means
  the rule has no calibrated threshold at all and cannot be trusted to stay silent). This changes
  no data source, no endpoint, and no trust boundary — asset/threat inventory (§1–§4) unchanged.
  Deliberately did **not** add a synchronous FX-conversion call into the scoring path (would be a
  new cross-service dependency with its own fail-open/closed decision — out of scope for this fix).
  Rollback: revert to the single-threshold v3 rules (git revert this commit); no data migration.

- **2026-07-09** — ADR-0084 §3 v4 (issue #625): new-payee + high-amount combination rule
  implemented, closing the roadmap item deferred at Phase-1 launch for lack of a payee-history
  signal. New asynchronous data path within the **existing** Kafka trust boundary: the same
  `TransactionSignalConsumer` that updates `velocity_aggregates` now also reads `targetAccountId`
  from `openbank.transactions.transaction.initiated` (already published by transaction-service,
  simply not previously consumed) and upserts `payee_history` (V3__create_payee_history.sql) via
  `PayeeHistoryRepositoryImpl` — a new (account_id, payee_identifier) table, no new Kafka topic, no
  new consumer, no new HTTP endpoint. `ScoreRequest` gains `isNewPayee: Boolean` (default `false`),
  set server-side in `FraudScoringService.enrichWithPayeeHistory` from the payee_history lookup —
  never accepted from the caller, same non-negotiable as every other scoring input (§4 invariants).
  `FraudRuleEngine` bumped to `v4` with `NewPayeeHighAmountReviewRule`: REVIEW when `isNewPayee` AND
  amount exceeds a per-currency threshold set at roughly half of `LargeSingleTransactionReviewRule`'s
  (CZK 250,000 / EUR 10,000) — same `Map<String, BigDecimal>` fail-closed-on-unmapped-currency
  pattern as the v3 amount rules (T2 mitigation: code-as-config, reviewed under the money-path
  2-approval gate).
  **New consideration — idempotent replay (T-class, `payee_history` specific):** unlike the
  pre-existing `velocity_aggregates` upsert (which has no redelivery guard and double-counts on a
  redelivered Kafka message — a known, not newly introduced, gap), the `payee_history` upsert guards
  on `last_transaction_id` (the signal's `aggregateId`) so a redelivered/duplicate message for the
  same underlying transaction does not double-count `payment_count` or flip an established payee
  back to appearing new. Verified against a real Postgres in `PayeeHistoryRepositoryImplIT`.
  **Information disclosure (I-class):** `payee_identifier` is an internal account UUID (as text),
  the same class of identifier already held in `fraud_scores.counterparty_id` — no new PII category.
  Rollback: revert `FraudRuleEngine` to `v3` (`ScoreRequest.isNewPayee` defaults to `false` and is
  additive/backward-compatible, so no data cleanup is required); `payee_history` table can be
  dropped independently since nothing else reads it.

- **2026-07-12** — ADR-0139 phase-1b: `OnnxFraudModel` replaces the throwaway
  `BaselineFraudModel` behind `MlModelPort`. New asset: a repo-bundled ONNX model file and an
  in-process ONNX Runtime native dependency (`com.microsoft.onnxruntime`). No new trust boundary,
  no new endpoint, no new data source — the adapter is purely a serving-engine swap behind the
  existing shadow-mode plane (`FraudScoringService.runShadow`, output logged, never honoured).
  **New STRIDE additions**: T4 (unsigned model artifact — accepted risk until ADR-0141's registry
  lands, since output stays shadow-only), D2 (native inference engine DoS — bounded by a
  fixed/repo-bundled model and try/catch degrade-to-null). Rollback: revert this commit
  (`BaselineFraudModel` restored verbatim); no data migration, no config change.
