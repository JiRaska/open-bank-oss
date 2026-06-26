<!--
SPDX-License-Identifier: MPL-2.0
Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
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
                     └────────────────────────────────────────────────────────────────────────────┘
```

Trust boundaries crossed: (1) external caller → REST; (3) service → Postgres;
(4) Kafka (`openbank.transactions.transaction.initiated`) → `TransactionSignalConsumer` → (5)
`velocity_aggregates` Postgres. Domain layer (`FraudRuleEngine`, model) has **zero** framework
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
- **Load test the p99 ≤ 150 ms latency budget** before flipping any surface to challenge/enforce.

## 6. Change log

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
