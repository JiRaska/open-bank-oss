<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-fraud-service

STRIDE/DFD threat model for the fraud-detection bounded context, per ADR-0030 D2.
Money-path service (it sits in the synchronous payment path once wired). Reviewed in PR;
referenced from ADR-0084. This document is the threat-model basis for the fraud-dev-engineer
money-path agent pilot (ADR-0031 phase 4, gap analysis issue #12885, shadow-only scoring per
#4403) — see §6.

- **Status:** Full — STRIDE/DFD (ADR-0030 D2), money-path
- **Last reviewed:** 2026-10-15
- **Owner:** fraud CODEOWNERS
- **Related ADRs:** ADR-0002 (hexagonal), ADR-0009 (Postgres-per-service),
  ADR-0021/0073 (SCA), ADR-0030 (money-path controls), ADR-0031 (AI-agent governance —
  phase 4 full review), ADR-0032 (sibling sanctions/AML screening gate),
  ADR-0033 (rules code-as-config), ADR-0034 (OPA enforce mode),
  ADR-0050 (transactional outbox), ADR-0065 (customer edge — device/IP context source),
  ADR-0067 (feature flags, four-eyes on money-path flips), ADR-0068 (operator review),
  ADR-0077 (DomainMetrics cardinality contract), ADR-0084 (fraud bounded context — this
  service), ADR-0137 (Kafka mTLS + KafkaUser ACLs), ADR-0139 (ML decisioning platform,
  shadow plane), ADR-0140 (feature store topology), ADR-0141 (model registry provenance),
  ADR-0220 (fraud-hold engagement signal), ADR-0230 (backoffice review queue),
  ADR-0252 (synthetic taint)

## 0. Phase-1 posture (read first)

This is a **Full** review of a service that is still **pre-enforcement**. Be precise about
what is and is not true today:

- **Scoring is wired, shadow-only.** Since #4221 (2026-08-09) all four payment surfaces
  (domestic-payment, sepa-payment, sepa-instant, fx-service) call
  `POST /api/v1/fraud/score` on every payment, as the shared `service-account-openbank-services`
  principal, through the `payments`/`fx` NetworkPolicy edges. Every call site is shadow-only —
  the verdict is logged, never honoured (`shadowFraudScore` in domestic/sepa,
  `scoreFraudShadow` in sepa-instant) — and the `*_FRAUD_ENFORCEMENT_ENABLED` flags that would
  change that have no reader in Kotlin. `fraud_scores` therefore holds **live payment amounts,
  currencies, rails and account identifiers at payment volume**, and the service's availability
  and latency sit inside the shadow path of four payment rails. Because every caller's adapter
  is fail-open (synthetic ALLOW when this service is unreachable/refused), a fraud-service
  outage degrades observability, not payment flow — but the per-payment latency this service
  adds is on the hot path whether or not anyone acts on the answer.
- **The rule engine is real but young.** `FraudRuleEngine` is at `RULE_VERSION = "v4"`: one
  always-ALLOW baseline stub plus five deterministic REVIEW rules (velocity H1/H24 counts,
  large single transaction, H1 transacted-amount, new-payee + high-amount). No rule produces
  CHALLENGE or DECLINE yet. The thresholds are first-pass, per-currency calibration committed
  as code constants, not risk-team-approved figures. **The service provides detection signal
  (REVIEW flags for the analyst queue), not yet an executed control** — `fraud-monitoring`
  remains `partial`, not `enforced`.
- **Fail-open vs fail-closed, per path** (the table reviewers should check first):

  | Path | Failure posture | Where decided |
  |------|-----------------|---------------|
  | Rule scoring, velocity/payee signal missing | **Fail-silent**: absent aggregates read as zero / `isNewPayee = false`, so no velocity rule fires; documented as a real detection hole, not a control | `FraudScoringService.enrichWithVelocity` / `enrichWithPayeeHistory` |
  | Rule scoring, unmapped currency | **Fail-closed**: amount rules fire REVIEW unconditionally — the rule refuses to stay silent when it has no calibrated threshold | `LargeSingleTransactionReviewRule`, `VelocityH1HighValueReviewRule`, `NewPayeeHighAmountReviewRule` |
  | ML shadow plane (feature store, ONNX inference) | **Fail-open, by design**: any failure is swallowed and logged at debug; the rules verdict returns byte-identical | `FraudScoringService.runShadow` |
  | ONNX model load / card verification | **Fail-closed for the shadow plane**: `session` left `null`, every `scoreShadow` returns `null` — shadow disabled, verdicts untouched | `OnnxFraudModel.loadEnvironment` / `loadSession` |
  | Fraud-hold side effect | **Fail-open**: hold-raising failure must never affect or slow the returned verdict | `FraudHoldService.maybeRaise` |
  | Caller adapters (outside this boundary) | **Fail-open**: unreachable scorer → synthetic ALLOW + WARN per payment | domestic-payment / sepa-payment / sepa-instant / fx-service shadow adapters |
  | Kafka signal consumer | Parse failure → drop + error log; aggregate write failure → retry then rethrow → DLQ; feature-store update → best-effort swallow | `TransactionSignalConsumer` |

- **What changes the day an agent gets write scope on this service.** Today every verdict
  input, rule threshold, model artifact and gate is changed only by a human-authored,
  money-path-governed PR. The ADR-0031 phase-4 pilot makes a fraud-dev-engineer agent the
  first machine author inside that loop. Every STRIDE row in §4 and every agent-specific
  threat in §6 then becomes reachable by a machine identity — the controls that must hold are
  all out-of-band of the agent: the agent PR guard's protected-path block, human money-path
  approvals, the OPA agent grants, and the recorded two-approver merge ceremony. This
  document is what those reviewers sign.

## 1. Scope & assets

The fraud service is the **behavioural risk layer** protecting the customer and the bank from
unauthorized or manipulated transactions (as opposed to ADR-0032 AML/sanctions, which protects
the financial system). At maturity it is consulted synchronously in the payment hot path and
its verdict is executed by the payment surface.

Assets protected, in priority order:

1. **Verdict integrity** — the ALLOW/CHALLENGE/REVIEW/DECLINE decision the payment surface will
   one day act on. A forged or tampered verdict directly enables fraud (false ALLOW) or
   denial of service (false DECLINE). Even today a poisoned verdict pollutes the review queue
   analysts work and the fraud-rate baseline RTS Art. 18 needs.
2. **Rule set + rule version** — the deterministic, versioned, code-reviewed rules (ADR-0084
   §3, ADR-0033). Rules are config-as-code, never UI-editable; an unreviewed rule change is a
   money-path change. `RULE_VERSION` is pinned into every persisted decision.
3. **Scoring audit trail** (`fraud_scores`) — the immutable per-decision record (amount,
   currency, rail, account/counterparty, verdict, score, reasons, rule version, timestamp);
   the reference fraud-rate dataset and the evidence for every verdict.
4. **Signal-plane aggregates** — `velocity_aggregates` (per-account/window/currency rolling
   counters and amounts) and `payee_history` (per-account/payee payment history, guarded by an
   applied-event set). These decide whether the velocity and new-payee rules fire at all; a
   corrupted aggregate silently weakens or invents detection.
5. **Online feature store** (Redis, ADR-0140) — windowed per-account feature values feeding
   the shadow ML plane; shadow-only, so a corrupted feature costs observability, not a verdict.
6. **Bundled ONNX model + model card** (`ml/baseline-fraud-v1.onnx` +
   `ml/baseline-fraud-v1.card.json`) — the shadow-scoring artifact and its digest-pinning
   card (ADR-0141).
7. **Fraud-hold state + `fraud.hold_changed` stream** (`fraud_hold` table, `fraud_outbox`) —
   the ADR-0220 D3.5 marketing-suppression signal; deliberately never an account/payment
   restriction, so its blast radius is bounded by design.
8. **Review-queue confidentiality** (`GET /api/v1/fraud/review-queue`, ADR-0230) — the newest
   REVIEW verdicts with amounts and counterparties; analyst work queue, not customer-facing.

## 2. Data flow (DFD)

```
        ┌──────────────────────────────── trust boundary: fraud-service (ns fraud) ─────────────────────────────┐
        │                                                                                                       │
 M2M   │                                                                                                       │
 payment│                                                                                                       │
 surfaces──(1)──▶ REST :8133 ──▶ FraudResource ──▶ FraudScoringService ──▶ FraudRuleEngine (pure domain,        │
 (shared  │      (namespaces      POST /api/v1/fraud/score  │  enrichWithVelocity /      RULE_VERSION v4,          │
  client  │       payments, fx,    @RolesAllowed(API,OPER,   ▼  enrichWithPayeeHistory     6 deterministic rules)  │
  JWT;    │       admin-ui per     ADMIN) @Authorize        VelocityAggregateRepositoryImpl                     │
  Network │       NetworkPolicy)   (fraud.score)            PayeeHistoryRepositoryImpl ──▶ (3) [Postgres]        │
 Policy   │             │                    │             FraudScoreRepositoryImpl ─────▶  fraud_scores          │
 allow-   │             │                    │             (immutable audit row per verdict)                      │
 list)    │             │                    ▼                                                                        │
        │             │               runShadow ──▶ OnlineFeatureStore (Redis, ADR-0140) ──(6)──▶ [Redis]       │
        │             │               (best-effort,          │ Fresh values only (Stale/Missing omitted)          │
        │             │                outcome discarded)    ▼                                                    │
        │             │               OnnxFraudModel (in-JVM ONNX Runtime native lib; model + ADR-0141 card     │
        │             │                verified at load; session null on any failure; shadow score logged +      │
        │             │                openbank.fraud.shadow.score metric)                                        │
        │             │                    │                                                                      │
        │             │               FraudHoldService.maybeRaise (fire-and-forget; never affects verdict) ──▶   │
        │             │                fraud_hold (Postgres) + fraud_outbox row (same tx) ──(5)──▶ [Kafka]       │
        │             │                                                                 openbank.fraud.hold.changed│
        │             │                                                                                           │
 Operator│      ─(1)──┼─▶ GET /api/v1/fraud/review-queue (ADR-0230)                                             │
 (COMPLIANCE/│           @RolesAllowed(COMPLIANCE,OPERATOR,ADMIN) @Authorize(fraud.review.read) ──▶ fraud_scores  │
 OPERATOR/  │                                                                                (newest REVIEW,      │
 ADMIN via  │                                                                                 limit clamped 200)  │
 admin-ui)  │                                                                                                       │
        │             │  @RolesAllowed + @Authorize ──(2)──▶ OPA sidecar :8181                                      │
        │             │   (AUTHZ_ENFORCE=true in gitops; deny-by-default; 403 deny / 503 outage)                     │
        │             │        bundle: rest.rego + fraud_rest_ext.rego + agents.rego (fraud-opa-bundle ConfigMap,   │
        │             │        policy-checksum annotation rolls pods on change)                                      │
        │             │                                                                                           │
        │      ─(4)──▶ [Kafka] openbank.transactions.transaction.initiated ──▶ TransactionSignalConsumer          │
 [Kafka] │       (mTLS + KafkaUser fraud-service ACL: Read; DLQ Write on                    │                      │
 mTLS    │        openbank.transactions.transaction.initiated.fraud.dlq; group               ▼                      │
        │        openbank-fraud-service-velocity from msg-override ConfigMap)     velocity_aggregates (upsert,     │
        │              │                                                          applied-event-set dedup, V5/V6)  │
        │              │                                                          payee_history (same guard)       │
        │              │                                                          FeatureOnlineUpdater ──(6)──▶ [Redis]│
        │              │                                                                                           │
        │      schedulers (replicas: 1, concurrentExecution=SKIP):                                                  │
        │        • fraud-outbox-dispatcher   every 5s  (Bulkhead+CircuitBreaker+Retry+Timeout)                      │
        │        • fraud-hold-expiry-sweep   hourly (liveness gauge; exceptions swallowed, success-only heartbeat)  │
        │              │                                                                                           │
        │      ─(7)──▶ [account-service] ◀── OIDC client-credentials (shared openbank-services client) ──           │
        │              AccountServiceClient.findPartyByAccountId (fraud-hold partyId lookup only;                  │
        │              failures swallowed; never on the scoring latency path)                                       │
        └───────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

Trust boundaries crossed: **(1)** caller → REST :8133 — OIDC bearer JWT (Keycloak); the
NetworkPolicy ingress allow-list (generated, `gen-network-policies.py`) admits only
namespaces `payments`, `fx` and `admin-ui` on 8133, same-namespace, and observability +
security-scanner on 8085. There is no 8443 mTLS listener on this service; caller
authentication is the bearer JWT alone. **(2)** REST → OPA sidecar :8181 (localhost),
**enforced** in gitops (`AUTHZ_ENFORCE=true`), deny-by-default, 403 on deny / fail-closed on
sidecar outage; the money-path canary analysis (`openbank-money-path-canary`) aborts the
Rollout if enforce regresses the error rate. **(3)** service → Postgres (`openbank_fraud`) —
credentials projected from the `fraud-db-app` secret (ESO, `postgres.yaml`); migrations
forward-only Flyway (V1–V8). **(4)** Kafka → service — mTLS with the `fraud-service`
KafkaUser ACLs (ADR-0137): literal Read/Describe on `openbank.transactions.transaction.initiated`,
Write/Describe on the explicit DLQ topic, Read on the consumer group; `group.id` /
`auto.offset.reset` / DLQ topic are set from the `fraud-service-msg-override` properties file
(QUARKUS_CONFIG_LOCATIONS), never as YAML keys — the dotted-YAML-key trap is documented in
both files. **(5)** service → Kafka — the `fraud-outbox-out` channel publishes
`openbank.fraud.hold.changed` via the ADR-0050 transactional outbox (Write/Describe ACL);
V7 carries the synthetic taint column, V8 bounds `created_at` plausibility. **(6)** service →
Redis — the ADR-0140 online feature store, in-namespace (`redis.yaml`), no AUTH; read only
from the shadow plane, written from the Kafka consumer. **(7)** service → account-service —
OIDC client-credentials as the **shared** `openbank-services` confidential client (S2/S3).
The domain layer (`FraudRuleEngine`, model objects) has **zero** framework imports
(ADR-0002), so verdict logic is unit-testable in isolation. The only unauthenticated surfaces
are the cluster probes (`/q/health`, management :8085) and swagger-ui (`/api/docs`,
`always-include: true`) — both cluster-internal by the NetworkPolicy.

## 3. Authn/Authz

**Authn.** Every `/api/*` route requires an OIDC bearer JWT (`quarkus-oidc`, Keycloak,
client `openbank-services`); there is no `@PermitAll` endpoint (locked by
`FraudResourceAuthzTest` / `FraudNegativeAuthProviderVerificationTest`). There is no second
listener and no client-certificate path: a certificate alone yields nothing here because no
certificate is ever requested. Schedulers carry no token (in-process); their writes are
hold-expiry state changes and outbox dispatch, not verdicts.

**Authz.** Two independent gates per endpoint: `@RolesAllowed` (RBAC, first) and
`@Authorize` → OPA sidecar (`data.openbank.rest.allow`, **deny-by-default**: 403 on deny,
fail-closed 503 on sidecar outage). `AUTHZ_ENFORCE=true` in gitops (`fraud-service.yaml`;
`application.yaml` still defaults `authz.enforce` to false, the env wins). Policy = base
`rest.rego` + `fraud_rest_ext.rego` (mounted in the same bundle; generated by
`gen-fraud-opa-bundle.sh`, checksum annotated on the Rollout). Base `matrix-allows` grants
`fraud.score` to `ROLE_OPERATOR` (it is a non-read action in `rules.yaml`'s
`authz.role_action_matrix`); the extension supplies the two write allow paths and the edge
veto. `AuthorizeInterceptor` classifies every client_credentials principal as `HUMAN`, never
`SERVICE`, so every M2M rule gates on `input.principal.id` (PR #403 / `rules.yaml:
authz_policy`).

| # | Resource | Endpoint | `@RolesAllowed` | OPA action | Allow reasons |
|---|----------|----------|-----------------|------------|---------------|
| FS1 | FraudResource | `POST /api/v1/fraud/score` | API, OPERATOR, ADMIN | `fraud.score` | `operator-fraud-write` (HUMAN, ROLE_OPERATOR/ADMIN, **excludes every `service-account-*`** — #3734) + `service-fraud-scoring` (identity == `service-account-openbank-services`, `fraud.score` only); `service-account-openbank-edge` vetoed by the `prohibited` clause at the allow head |
| FQ1 | FraudResource | `GET /api/v1/fraud/review-queue` | COMPLIANCE, OPERATOR, ADMIN | `fraud.review.read` | base `operator-read-any` (OPERATOR/ADMIN) + base `compliance-read-any` (ROLE_COMPLIANCE, `service-account-*` excluded); no per-service rule needed or wanted |

Notes and residuals on this matrix:

- **RBAC/OPA overlap on `fraud.score`.** `ROLE_API` is admitted at the RBAC layer solely so
  machine identities can reach OPA; for every `ROLE_API` holder OPA is the whole control, and
  the only M2M allow reason names the **shared** backend identity (S2) — OPA cannot
  distinguish *which* of the four payment surfaces is calling, only that some caller holds
  that client's token.
- **Review-queue M2M read over-grant (found still open by this full review, 2026-10-15).**
  `operator-read-any` in the deployed bundle carries **no** `service-account-*` exclusion
  (only `operator_read_any_excluded` action filtering), and the realm grants `ROLE_OPERATOR`
  to both M2M clients; so any backend service sharing the client can read the analyst queue
  (amounts, counterparties of flagged payments) even though `fraud_rest_ext.rego` describes
  this read as staff-facing. `compliance-read-any` got the exclusion; `operator-read-any` did
  not. Recorded in §5 — same family as the pre-#11480 write over-grants.
- **No four-eyes on this service yet.** ADR-0067 four-eyes (`four_eyes_required`) is wired
  into the shared policy machinery but no `fraud.*` action is flagged and there is no
  `ApprovalStore` here; money-path flips are currently governed by the PR-level
  two-approval rule (ADR-0030) and the review-queue is the human checkpoint for REVIEW
  verdicts (ADR-0068).

## 4. STRIDE analysis

| # | Element | Threat (STRIDE) | Mitigation | Residual |
|---|---------|-----------------|------------|----------|
| S1 | REST in | **Spoofing** — caller forges identity to obtain or influence a verdict | Bearer JWT (Keycloak); `@RolesAllowed("ROLE_API","ROLE_OPERATOR","ROLE_ADMIN")` on `score`, `(COMPLIANCE,OPERATOR,ADMIN)` on `review-queue`; no `@PermitAll`; **OPA now ENFORCED** (`AUTHZ_ENFORCE=true` in gitops, ADR-0034) — `fraud.score` is deny-by-default with exactly two allow reasons + an edge veto (§3); NetworkPolicy admits only `payments`/`fx`/`admin-ui` | None beyond S2: the only M2M identity admitted is the shared backend principal, which four services hold |
| S2 | OIDC client secret | **Spoofing (shared-credential blast radius)** — fraud reuses the shared `openbank-services` Keycloak confidential client / shared Vault key (inbound `quarkus.oidc` and outbound `quarkus.oidc-client` both read `OIDC_CLIENT_SECRET` from `fraud-service-oidc`, backed by the shared client). Compromise of that one key mints tokens accepted as `service-account-openbank-services` here — admitted to `fraud.score` by `service-fraud-scoring` — and across every other service sharing the client. | Secret ESO-projected (never in git/state); confidential (not public) client; endpoint additionally requires the OPA identity gate; `operator-fraud-write` excludes every `service-account-*` from the operator path so the shared key alone cannot ride `ROLE_OPERATOR` here for writes | **Shared-credential blast radius accepted for sandbox only.** Any of the ~30 backend services sharing the client can score (the rego rule's own comment says so). Dedicated Vault path + per-service Keycloak client = hardening before prod. **Prod go-live requires the second money-path approver to explicitly sign off this residual** (ADR-0030). — *open* |
| S3 | account-service REST call | **Spoofing** — `AccountServiceClient`'s M2M call (fraud-hold partyId lookup) is impersonated or its response trusted uncritically | Same shared `openbank-services` OIDC client every outbound caller in this fleet uses (S2 applies identically); response is used ONLY to resolve `partyId` for a marketing-suppression signal, never to gate a scoring verdict or a payment; lookup failures (including 404) are swallowed to `null`, so a hostile or broken account-service fails closed for the hold (hold simply does not raise) | Shared-credential blast radius — same *open* item as S2 |
| T1 | Verdict | **Tampering** — a false ALLOW waves through fraud, or a false DECLINE/REVIEW denies a legitimate payment | Verdict computed server-side in the pure domain from the versioned rule set; never accepted from the caller; every enrichment input (`velocityH1Count`, `velocityH24Count`, `velocityH1TotalAmount`, `isNewPayee`) is server-read from the signal plane or defaults to a documented fail-silent value — `isNewPayee` is set only by `FraudScoringService.enrichWithPayeeHistory`, never from the request; per-currency thresholds; unmapped currency fails CLOSED to REVIEW | The fail-silent default for missing aggregates is itself a detection hole (a caller whose signals never arrive is velocity-invisible) — accepted, documented, visible via `openbank.fraud.signal.missing.event.time`; no rule produces CHALLENGE/DECLINE yet, so worst case today is a wrong REVIEW/ALLOW in the audit trail |
| T2 | Rule set | **Tampering** — an unreviewed/malicious rule or threshold flips verdicts at scale | Rules are code-as-config (ADR-0033) — a `RULES`-list or `THRESHOLDS_BY_CURRENCY` edit is a source diff under the money-path PR gate (2 approvals + this threat model, ADR-0030); `RULE_VERSION` is bumped per change and pinned into every persisted decision, so post-hoc rule swaps are detectable against `fraud_scores.rule_version`; behaviour locked by `FraudRuleEngineTest`, the shadow zero-drift contract (`FraudScoringShadowZeroDriftTest`) and the ADR-0230 review eval harness (`FraudReviewEvalSuiteTest`) | Thresholds are unreviewable-by-machine calibration constants — only review discipline (and, prospectively, the §6 agent gates) stops a bad-but-plausible retune; signed rule-set provenance — *planned* |
| T3 | `fraud_scores` rows | **Tampering** — direct DB mutation of the audit trail | App-only write path (`FraudScoreRepositoryImpl.save`, insert-only); forward-only Flyway; DB creds ESO-projected (`fraud-db-app`); rows carry server-set `createdAt` and the V8 plausibility constraint bounds `fraud_outbox` timestamps | DB-admin insider — infra scope |
| T4 | Bundled ONNX model | **Tampering** — a modified `baseline-fraud-v1.onnx` changes shadow-score behaviour | Model file is repo-committed under `src/main/resources/ml/` (same PR review + signed-commit gate as code); loaded from the service classpath only, never a runtime path; at load, `OnnxFraudModel.verifyCard` checks the artifact's SHA-256 against `baseline-fraud-v1.card.json` and refuses scopes outside `fraud-shadow` — under `openbank.ml.require-signature=true` any mismatch or missing card fails closed (no session); in-image `OnnxServingSmoke.kt` runs the identical load path in the built image (with a musl negative control) so a library/platform swap that loads nowhere is caught in CI | The card is digest-pinned but **not cosign-signed** (ADR-0141 follow-up, stated on the card itself): artifact and card are one repo-committed pair, so a same-PR swap of both loads cleanly. Bounded today only by review + the fact that output stays shadow-only (never affects a verdict). Must close (detached signature) before any `*-enforce`-scope model can gate. — *open* |
| T5 | `fraud_hold` row / `fraud.hold_changed` event | **Tampering/DoS** — a forged or flooded hold event could suppress marketing indefinitely for arbitrary parties, or a compromised producer could fabricate holds | Trigger is server-computed only — repeated REVIEW verdicts already persisted in `fraud_scores` (`review-threshold: 3` within `window-days: 30`), never caller-supplied; raise + outbox row commit in one `Panache.withTransaction` (atomicity covered by `FraudOutboxAtomicityIT`); auto-expiry (`ttl-days: 30`) with no manual clear mechanism in this service; consuming side (engagement-service) treats the signal as advisory (targeting exclusion only) — never an account/payment restriction; Kafka mTLS + literal-topic Write ACL means a forged event needs the `fraud-service` KafkaUser identity | Kafka topic has no application-layer message signing — same posture as every other domain-event topic in this fleet; a stolen `fraud-service` KafkaUser key could emit forged holds (bounded to marketing suppression) — recorded in §5 |
| T6 | Signal-plane aggregates | **Tampering (replay/double-count)** — a redelivered `transaction.initiated` signal inflates `velocity_aggregates`, nudging accounts toward REVIEW (denial-of-service-by-flagging) or manufacturing velocity that hides in the noise | V5 last-writer marker superseded by V6: each row keeps `applied_transaction_ids`, a count-windowed set (`openbank.fraud.applied-signal-window`, default 100) unioned with `last_transaction_id` (covers the rolling-deploy cutover), tested by membership in the upsert's `WHERE`; every suppressed replay is counted (`openbank.fraud.signal.replay.suppressed{aggregate}`) so the guard's load is visible from outside the DB; bucket start derives from the event's `occurredAt`, never wall-clock, so an hour-boundary redelivery cannot escape the guard by landing in a different row | Bounded by design: a replay arriving after 100 further signals to the same (account, window, currency, bucket) row is re-applied; a signal with no `aggregateId` is applied unconditionally (no identity to dedup by) — both stated in V6 rather than hidden; missing `occurredAt` substitutes processing time and is **counted** (`recordSignalMissingEventTime`, #6044) so it is visible, not silent |
| T7 | Outbox events | **Tampering** — a real-money event mislabelled synthetic (or vice versa) pollutes the ADR-0252 taint population downstream | V7 puts `synthetic` on `fraud_outbox` with default false, propagated by the shared outbox machinery (server-side classification only, same as the rest of the fleet — no header/JWT claim is trusted); hold events originate here and are always real-money, so the column is forward-looking fleet consistency, not an active canary path | Low — same posture as every outbox table; a wrong default would itself be a T-row and is pinned by the fleet gate |
| R1 | Scoring decision | **Repudiation** — dispute over what verdict was returned for a payment | Every decision persisted immutably (`fraud_scores`: request context + verdict + `score` + `reasons_json` + `rule_version` + `created_at`, insert-only adapter); OPA decisions carry `reason` + `policy_version` into the audit trail; one row per `score()` call is the unit-test contract | Wire signed audit / evidence bundle (ADR-0029) for non-repudiation — *planned*; the row proves what the service decided, not what the caller did with it (shadow-only today: caller action is the caller's log) |
| I1 | Verdict reasons / review queue | **Information disclosure** — reason codes leak rule logic to a probing caller; the review queue exposes flagged payments' amounts and counterparties | Reasons are coarse rule names (`velocity-h1-cap`, `large-single-transaction`, …) returned only to authenticated service/operator/admin callers — never customer-facing; the queue requires COMPLIANCE/OPERATOR/ADMIN at RBAC plus OPA `fraud.review.read`; `limit` clamped to 200 server-side (`MAX_QUEUE_LIMIT`) so it cannot be turned into an unbounded scrape; customer-safe decline mapping remains a surface concern for the enforce phase | Reason-code taxonomy needs review as CHALLENGE/DECLINE rules land (§5); the M2M read over-grant in §3 exposes the queue to any shared-client holder — *open* |
| I2 | Metrics | **Information disclosure / DoS** — high-cardinality metric labels enable per-customer inference or exhaust Prometheus | `openbank.fraud.scores` is tagged `service` + the closed 4-value `verdict` set only for account/party/amount — those live in row columns (access-controlled), never labels, per ADR-0077; `openbank.fraud.shadow.score` is an untagged summary; `openbank.fraud.signal.replay.suppressed{aggregate}` and `openbank.fraud.signal.missing.event.time` carry fixed diagnostic tags; `/q/metrics` is cluster-internal (observability namespace only, NetworkPolicy) | **Found by this full review (2026-10-15): the `rail` tag breaks the cardinality contract.** `recordVerdict(verdict, rail)` tags the counter with `rail`, a free-form caller-supplied string (`ScoreFraudRequest.rail` is validated only non-blank), so any authenticated caller can mint unbounded label values — Prometheus cardinality/cost DoS and, worse, a quiet violation of the ADR-0077 contract this row claims. Recorded in §5; fix = allow-list rails at the HTTP boundary or drop the tag. — *open* |
| D1 | Scoring path | **DoS** — flood of `/score` calls starves the payment shadow path | Reactive non-blocking stack; `RateLimitFilter` (libs-runtime) caps in-flight requests at `openbank.rate-limit.max-concurrent-requests` (100 here) and answers 429 with `Retry-After`; per-service k8s limits (250m/310Mi requests, 1/1536Mi limits); Rollout canary with money-path analysis + `progressDeadlineAbort`; latency budget p99 ≤ 150 ms (ADR-0084); caller adapters are fail-open so scorer saturation degrades signal, not payments | Gateway rate-limiting — infra scope; load test before any enforce phase (§5) |
| D2 | ONNX Runtime native session | **DoS / availability** — a malformed/oversized model consumed pathologically by the native engine stalls or crashes the JVM; a native-library load failure (musl/glibc mismatch) takes the bean — and every endpoint sharing its CDI graph — down with it | Model is a fixed, repo-bundled artifact, never externally supplied; inference wrapped in try/catch, degrading to `null`; **the #3376 shape is fixed here**: `loadEnvironment`/`loadSession` catch `Throwable` (an `UnsatisfiedLinkError` is an `Error`, not an `Exception`), `environment`/`session` are nullable, so a dead native library disables shadow scoring only — `GET /api/v1/fraud/review-queue` (which never touches the model) stays up; the deploy image base moved to glibc (`eclipse-temurin:25-jre`) and `verify-image-native-libs.py` resolves every NEEDED entry before push; `OnnxServingSmoke.kt` proves a real inference round-trip inside the built image, with a musl negative-control leg that must fail | Native-library CVEs in `onnxruntime` — covered by the fleet's Trivy/SBOM scan (ADR-0121), not a fraud-service-specific gap |
| D3 | Schedulers | **DoS / stale state** — the outbox dispatcher or hold-expiry sweep wedges; expired holds never clear, or the outbox fills forever | Both are `suspend fun` on the event-loop scheduler with `concurrentExecution = SKIP` and `replicas: 1`; dispatcher is wrapped in Bulkhead(1) + CircuitBreaker + Retry + Timeout and honours `openbank.outbox.dispatch-enabled: true`; expiry sweep records liveness **only on success** (`registerWorkflowLiveness` — a swallowed-exception sweep is stale, never fake-healthy) and its `EXPECTED_INTERVAL` (1 h) matches the cron so the staleness alert is honest | The sweep swallows exceptions by design (holds must not break scoring) — a permanently broken sweep is visible only via the liveness gauge; the dispatcher's dead-letter/DEAD handling is the shared AbstractOutboxDispatcher posture (bounded retries + terminal state) |
| E1 | Roles | **Elevation** — a reader/operator role or M2M identity obtains a privileged action | Write (`fraud.score`) and read (`fraud.review.read`) tiers are separate RBAC sets; OPA enforced deny-by-default — a call with no matching `allowed_reasons` is 403; `fraud.score`'s only M2M reason names one identity and one action; the edge principal is vetoed at the allow head, beating any future matrix grant; `service-fraud-scoring` cannot reach any future `fraud.rules.*` management surface (the rule's own comment commits to that) | RBAC + OPA both key off the same role set for the operator path (no attribute conditions such as value-band on scoring) — same residual as the rest of the fleet; the §3 M2M read over-grant is the live instance — *open* |

## 4a. ML shadow plane & model artifact (ADR-0139/0140/0141) — STRIDE supplement

The shadow plane exists to produce evidence that ML can one day gate, without letting it
touch a verdict. `FraudScoringService.runShadow` runs after the verdict is computed and
persisted; its outcome is discarded — the returned `FraudScore` is byte-identical to
rules-only (`FraudScoringShadowZeroDriftTest` locks that). Feature values come from the
ADR-0140 `OnlineFeatureStore` (Redis, produced by `FeatureStoreConfig`); **Fresh** values
only are fed to the model — `Stale`/`Missing` are omitted, never approximated (ADR-0140).
The model is the deterministic logistic re-expression of the old pure-Kotlin baseline
(`OnnxFraudModel` KDoc; generator `gen_onnx_baseline_model.py`), so shadow/rule divergence
means a serving defect, not model insight.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller claims shadow scores that were never computed, or a swapped artifact impersonates the baseline model | Shadow output is internal-only (log + `openbank.fraud.shadow.score` summary); the artifact is bound to `baseline-fraud-v1.card.json` by SHA-256 and the card's `scope` must be `fraud-shadow` (`ALLOWED_SCOPES`) — a credit-decisioning model (ADR-0142) cannot be served here even with matching bytes |
| **T**ampering | Artifact or card swapped; features poisoned in Redis | Same-PR pair swap is possible (T4) but bounded by review + shadow-only output; Redis is in-namespace, no AUTH, reachable only from this service's pod (NetworkPolicy) and the api-fuzz-provisioned test harness; feature writes derive only from authenticated Kafka signals (mTLS + ACL) via `FeatureOnlineUpdater`, keyed by event time, deduped by an `occurredAt`-millis surrogate offset |
| **R**epudiation | "What did the model score for this payment?" is unanswerable | The shadow log line (`fraud-ml-shadow account=… features=… mlScore=… ruleVerdict=…`) + the summary metric give per-account traceability; no verdict ever depends on it, so this is observability, not evidence |
| **I**nfo disclosure | Feature values or shadow scores leak PII | Features are per-account velocity counts (no counterparty data); the log line carries `accountId` + scores — app logs, not metric labels; the shadow metric is untagged beyond `service` |
| **D**oS | A pathological feature tensor or model stalls scoring | Fixed 2-float input shape; inference try/catch degrades to rules-only; load failures leave `session = null` and skip the plane entirely |
| **E**oP | Shadow plane silently starts gating verdicts | There is no code path from `scoreShadow` to the verdict — `runShadow` returns Unit and its result is discarded; the only config that touches verdicts is the rule set (T2). Any wiring change is a money-path PR |

**Residual:** the card is digest-pinned, not signed (T4); `openbank.ml.require-signature`
defaults to false (advisory log-and-proceed) — flipping it true is safe direction (fails
closed) and must happen before any `*-enforce` scope model. A deployed-pod shadow score was
never observed until the image-base fix (#3354/#3618); treat shadow numbers as unverified
until one is seen in production telemetry.

## 4b. Key invariants (must never regress)

- The **verdict is always computed server-side** by `FraudRuleEngine.score`; it is never read
  from the request body, and no ML output can reach it (shadow result discarded).
- Every `score()` call **persists exactly one immutable `fraud_scores` row** carrying the
  `RULE_VERSION` that produced it — no verdict without an audit trail.
- Every scoring input beyond `amount`/`currency`/`rail` is **server-enriched** (velocity
  counters, payee history) or validated at the HTTP boundary (`currency` must match
  `^[A-Z]{3}$`, `rail` non-blank — the #3923 500→400 fix).
- **Unmapped currency fails closed** (REVIEW) in every amount-based rule; missing aggregates
  fail silent (zero / `isNewPayee = false`) — and that hole is documented, not hidden.
- Rules are **code-as-config** (ADR-0033) — no runtime-mutable rule engine; every rule change
  bumps `RULE_VERSION` and passes the money-path PR gate.
- **No endpoint is `@PermitAll`**; OPA is enforced (`AUTHZ_ENFORCE=true` in gitops).
- Metric labels are **low-cardinality and PII-free** per ADR-0077: verdict is a closed set —
  and `rail` must be allow-listed (or dropped) to restore that contract (I2).
- The shadow plane is **fail-open and side-effect-only**; model load/verification failure
  disables shadow, never the service (`catch (Throwable)`, nullable `session`).
- The fraud-hold path is **marketing-suppression-only**, server-triggered, auto-expiring, and
  never touches the returned verdict or the scoring latency.
- The Kafka consumer **retries then rethrows** for aggregate writes (DLQ'd) — only the
  feature-store update may swallow, because nothing decides on it; a deserialisation failure
  drops the message loudly (error log), never silently.
- Velocity bucketing derives from the **event's** `occurredAt`, never wall-clock (#6044), and
  a substituted event time is counted (`openbank.fraud.signal.missing.event.time`).
- The fraud-hold expiry sweep records workflow liveness **only on success** — a swallowed
  failure is stale, never heartbeat-fake.

## 4c. Signal plane (Kafka → aggregates → feature store) — STRIDE supplement

`TransactionSignalConsumer` (`@Incoming("transaction-signal")`, `@Blocking`) turns
`openbank.transactions.transaction.initiated` into the aggregates the rule engine reads.
This is the service's **detection memory**: a silent gap here weakens every velocity and
new-payee rule with no error anywhere, which is why its failure modes are deliberately loud.

| # | Threat (STRIDE) | Vector | Mitigation |
|---|-----------------|--------|------------|
| K1 | **Spoofing** | A non-transaction producer writes fake `transaction.initiated` signals to inflate velocity | Topic is produced only by transaction-service (Pact contract `TransactionInitiatedPactConsumerTest` locks the shape); Kafka mTLS + the `fraud-service` KafkaUser Read ACL means forging needs transaction-service's identity, not just network access; no header/claim in the payload is trusted beyond the contract |
| K2 | **Tampering** | A replayed or reordered signal double-counts velocity (A, B, A) | V6 `applied_transaction_ids` membership test per row, count-windowed at 100, unioned with the `last_transaction_id` marker for the rolling-deploy window; suppressed replays counted (`openbank.fraud.signal.replay.suppressed`); residual bound (replay after 100 later signals; NULL aggregateId applied unconditionally) stated in V6 and T6 |
| K3 | **Tampering** | An event with no `occurredAt` is bucketed by ingest time, silently degrading the dedup window | The substitution still writes the row (history must not be lost) but is **counted and warned** (#6044) — `recordSignalMissingEventTime` makes the defect visible from outside the DB; `occurredAt` is required on the producer contract so the expected value is flat zero |
| K4 | **DoS** | A poison payload (bad JSON, huge fields) wedges the consumer | Deserialisation failure drops the message with an error log + 200-char payload preview; `@Blocking` isolates the poll thread; aggregate writes retry via `EventRetry` then rethrow → the explicit DLQ topic (`openbank.transactions.transaction.initiated.fraud.dlq`, Write ACL) so a poison row cannot spin the consumer |
| K5 | **Information disclosure** | Aggregate/feature rows leak per-account behaviour | Same identifier class as `fraud_scores.account_id` (no new PII category); Postgres access-controlled; Redis in-namespace, no AUTH, pod-scoped by NetworkPolicy; metrics carry no account dimension |
| K6 | **Elevation** | The consumer path becomes a write path to money | It is not: aggregates only influence which REVIEW rules fire, the worst outcome is a flag for a human analyst; the consumer holds no Kafka Write ACL except the DLQ and the hold topic; feature-store gaps cost shadow observability only |

## 5. Residual risks / assumptions

**Open items (full-review state, 2026-10-15):**

- **`rail` metric-label cardinality (found by this review, I2):** `ScoreFraudRequest.rail` is
  caller-controlled and validated only non-blank, and `FraudMetricsAdapter.recordVerdict`
  tags `openbank.fraud.scores` with it. Any authenticated caller can mint unbounded label
  values (Prometheus cardinality/cost) and silently break the ADR-0077 contract. Fix =
  allow-list known rails in `ScoreFraudRequest.validate` or drop the tag. Fails neither
  closed nor open for verdicts — it is an observability-plane defect; recorded, deliberately
  not fixed in this docs-only PR. Tracked as a #12885 follow-up.
- **Review-queue M2M read over-grant (found still open by this review, §3):** base
  `operator-read-any` lacks the `service-account-*` exclusion its `compliance-read-any`
  sibling now has, so any shared-client holder with the realm's `ROLE_OPERATOR` reads the
  analyst queue. The write side of this was closed by #3734; the read side predates it
  (`fraud_rest_ext.rego` documents it as tracked). Fix = add the exclusion to
  `operator-read-any` in the shared base policy (a fleet-wide rest.rego change, its own PR).
- **Dedicated OIDC credential (S2/S3):** shared `openbank-services` client + shared Vault
  key; any backend service can call `fraud.score`, and the blast radius spans the money
  path. Sandbox risk-accepted; **prod go-live blocked on a per-service client/Vault path or
  an explicit second-approver sign-off** (ADR-0030).
- **Model artifact not signed (T4/§4a):** card pins SHA-256 but is itself unauthenticated;
  a same-PR artifact+card swap loads cleanly. Must land a detached signature (ADR-0141,
  reusing the ADR-0121 chain) before any `*-enforce`-scope model can gate; harmless while
  shadow-only.
- **Phase-1 detection limits:** no CHALLENGE/DECLINE rules; thresholds are uncalibrated
  first-pass constants; missing-signal fail-silent hole (T1); `fraud-monitoring` stays
  `partial` until Phase 2/3 (real behavioural rules, calibrated thresholds, enforce wiring).
- **Signed audit / evidence bundle (R1, ADR-0029 D2)** for non-repudiation of verdicts.
- **Kafka message signing** — neither the inbound signal topic nor `openbank.fraud.hold.changed`
  carries application-layer signatures; a stolen `fraud-service` KafkaUser key could emit
  forged holds (bounded: marketing suppression) or read the signal stream. Fleet posture,
  not fraud-specific.
- **Load test the p99 ≤ 150 ms latency budget** before flipping any surface to
  challenge/enforce (ADR-0084 §4); the four callers' fail-open adapters make this service's
  enforce-phase availability a payment-path concern for the first time.
- **Surface integration is caller-side:** enforcement flags have no reader in Kotlin today;
  flipping any surface to enforce is a separate, runbook-gated change in each caller with
  its own threat-model entry.

**Closed since the draft (recorded so the corrections are visible):**

- ~~OPA fine-grained authz advisory-only~~ — **closed:** `AUTHZ_ENFORCE=true` in gitops
  (ADR-0034), sidecar deployed, canary analysis guards the rollout; §3/§4 reflect the
  enforced state.
- ~~`velocity_aggregates` has no redelivery guard~~ — **closed (#5716/#5789, V5/V6):**
  last-writer marker superseded by the count-windowed applied-event set, with the NULL-id
  and eviction bounds stated in the migration itself.
- ~~DomainMetrics deferred (#850)~~ — **closed:** `FraudMetricsAdapter` emits the verdict
  counter, shadow summary and the two diagnostic counters (with the I2 `rail` finding).
- ~~ONNX runtime never loaded in a deployed image~~ — **closed (#3354/#3618):** glibc base
  image + `verify-image-native-libs.py` + in-image `OnnxServingSmoke.kt` with a musl negative
  control.

**Assumptions** (relied on as controls; outside the service's own scope):

- **Keycloak realm integrity** — role assignments match the deployed realm template (both
  M2M clients hold `ROLE_OPERATOR`; no realm client is granted COMPLIANCE outside CI, per the
  bundle comments); a realm change re-opens S2/E1 shapes no in-service code can see.
- **OPA bundle provenance** — the deployed bundle is generated from `rules.yaml` + the
  checked-in rego sources (`gen-fraud-opa-bundle.sh`), the Rollout's `policy-checksum`
  annotation matches, and `opa eval` evidence here is against the generated
  `fraud-opa-bundle.yaml`.
- **ESO/Vault (ADR-0017 family)** — `fraud-db-app`, `fraud-service-oidc`, and the Kafka
  keystore/truststore secrets are projected, access-controlled and rotated out of band
  (`deletionPolicy: Retain` avoids disappearance, not disclosure).
- **Postgres admin/DBA** — T3: a database superuser bypasses every application control;
  infra controls apply.
- **NetworkPolicy as generated** — `gen-network-policies.py` output matches the deployed
  caller set; an undeclared caller namespace is DROPPED (fails closed at the network layer).
- **transaction-service event contract** — `occurredAt` is always present and meaningful
  (counted substitution, K3); Pact consumer test guards the shape.
- **account-service partyId correctness** — the fraud-hold lookup trusts the returned
  `partyId`; a wrong mapping mis-targets marketing suppression (bounded, reversible).

## 6. AI agent contribution surface (ADR-0031 phase 4 pilot)

Context: the fraud-dev-engineer agent is the pilot's first money-path agent (gap analysis
#12885; fraud-service chosen for 92.4% coverage and shadow-only scoring per #4403). The
planned charter scope is **fraud-service source + its tests + its gitops component**
(`openbank-infra/gitops/components/fraud-service/`). Everything in that scope is also inside
the agent PR guard's protected paths: the guard derives its token set from `rules.yaml:
money_path_services`, and `openbank-fraud-service` plus
`openbank-infra/gitops/components/fraud-service/` both match. No agent authored any of this
service's code at review time.

| # | Threat | Why an agent makes it new | Mitigations that must hold |
|---|--------|---------------------------|----------------------------|
| A1 | **Poisoned model artifact** — a PR swaps `baseline-fraud-v1.onnx` (e.g. a model that shadow-scores everything low, training the rollout metrics toward "ML agrees with rules") | An agent can regenerate the artifact with `gen_onnx_baseline_model.py` and re-pin the card's `contentSha256` in one coherent, well-tested PR — the digest check (T4) passes because artifact and card moved together; the diff looks like routine model work | Reviewer reads artifact provenance, not just the card diff; `OnnxServingSmoke.kt` + the parity test prove the artifact loads and matches the declared logistic; the shadow zero-drift contract shows any verdict coupling instantly; the ADR-0141 detached-signature follow-up removes the same-PR-swap hole; and the money-path PR gate (2 human approvals + this threat model) is the load-bearing control today |
| A2 | **Silent rule flip / threshold retune** — a plausible-looking `THRESHOLDS_BY_CURRENCY` edit or a new rule that "tunes" detection, with `RULE_VERSION` bumped as hygiene | Machines are good at plausible diffs; a gradual retune across PRs evades any single diff review, and every verdict row pins a rule version that looks intentional | `FraudRuleEngineTest` + `FraudReviewEvalSuiteTest` (ADR-0230 eval harness) catch behaviour changes a human might skim past; `openbank.fraud.scores{verdict}` drift is the fleet-visible tripwire; the recorded two-approver merge ceremony (the pilot's merge control — its merge-ceremony ADR is still pending adoption, so it is deliberately not cited by number here: the ADR-registry gate fails dangling references) exists precisely so no single reviewer, human or agent, can self-certify a rule change |
| A3 | **Test-gaming to satisfy the pilot's admission bar** — weakening assertions, deleting discriminating tests, or adding vacuous ones so coverage stays ≥ 92.4% while the tests stop detecting | Coverage is a ratchet (never lower), so coverage alone cannot see weakened assertions; the pilot's "92.4% coverage" criterion is exactly the kind of number an optimizer satisfies cosmetically | Required human review of every test diff on a money-path PR; the eval harness self-test (`FraudReviewEvalHarnessSelfTest`) and the ITs against real Postgres/Redis (`VelocityAggregateRepositoryImplIT`, `PayeeHistoryRepositoryImplIT`, `FraudOutboxAtomicityIT`, `FraudHoldServiceIT`) keep behaviour pinned to infrastructure; the ADR-0030 D3 precedent (ledger's scoped pitest mutation-testing verification) is the model for auditing test *quality*, not just quantity, before the pilot's scope widens |
| A4 | **Gitops component edits** — widening the NetworkPolicy allow-list, flipping env flags (`AUTHZ_ENFORCE`, Kafka ACLs, retention), or editing the Rollout's analysis | The gitops component is in the charter scope and is the service's runtime control plane; a one-line namespace addition or an `AUTHZ_ENFORCE` flip changes what the deploy enforces | Both files are generated-or-checksummed: `network-policies.yaml` is `gen-network-policies.py` output (a hand-edit is detectable and reverted on the next regeneration), the OPA bundle carries the `policy-checksum` annotation, and `gen-fraud-opa-bundle.sh` reproduces the bundle; the guard treats the whole component directory as protected, so an agent PR touching it is blocked pending explicit owner approval on the current head |
| A5 | **Policy drift in `fraud_rest_ext.rego`** — a rule tweak re-admits a principal or widens `service-fraud-scoring` (the ext ships inside the charter-scoped component directory) | A rego diff is small, declarative and easy to rubber-stamp; the `prohibited` edge veto could be weakened one clause at a time | `fraud_rest_ext_test.rego` (`opa test`) falsifies every allow/deny path — stripping the identity gate or the veto turns must-deny tests red; the bundle is regenerated, not hand-deployed; authz-policy changes are trust-chain changes the agent PR guard refuses to let an agent self-authorize (separately adopted one-time human exception required) |
| A6 | **Config flips in `application.yaml`** — `openbank.ml.shadow.enabled=false` (kills the shadow signal quietly), `openbank.fraud-hold.*` tuning, `openbank.outbox.dispatch-enabled=false` (outbox fills forever, silently) | Each is a one-line change with a plausible justification; some fail safe, some fail silent (`shadow.enabled=false` removes the evidence stream the enforce-phase decision needs) | Config changes ride the same money-path PR gate; ADR-0067 four-eyes on money-path feature-flag flips is the planned dedicated control (not yet wired for these keys); the SLO/alerting plane (`openbank.fraud.shadow.score` going flat, dispatcher backlog) is the detective tripwire — alerting on the *quiet* state, per the accepted-noise lesson |

**What gates the agent, stated as the pilot's actual control stack (all out-of-band of the
agent itself):**

1. **Agent PR guard (`.github/scripts/check-agent-pr-guard.py`, gate `agent-pr-guard`,
   enforced block).** A PR whose author is a declared agent account
   (`openbank-agent-bot[bot]`) and that touches any protected path — this service's source,
   its tests, and its gitops component all qualify — is red until a repository-owner command
   naming the PR number and the complete current head SHA lands; every new head needs a fresh
   approval, and a machine account in neither `agent_accounts` nor `automation_accounts`
   fails the check (exit 2, never a pass). Note for the pilot: the branch-prefix scope is
   currently **empty** (`agent_branch_prefixes: []`, owner decision 2026-10-14 — agent PRs
   no longer require human sign-off *by prefix*), so coverage of the human-account agent
   (the state today) rests on author classification alone; do not rely on `agent/` prefixes.
   The per-account allowance that would replace the unconditional block with an enumerated
   grant is proposed in open PR #12914 — until it lands, the block is unconditional for any
   in-scope agent touching the money path.
2. **Money-path PR rules (ADR-0030):** 2 approvals + an up-to-date threat model for any
   change under `<service>/`; this document is the threat model the second approver reads.
3. **Recorded two-approver merge ceremony** (the pilot's merge control, ADR-0031 phase 4):
   both approvers are recorded against the merge; the ceremony ADR itself is pending
   adoption and is deliberately referenced by description, not number (see A2).
4. **OPA agent grants:** the fraud sidecar's bundle mounts `agents.rego` +
   `agents-opa-data.yaml`, and base `rest.rego` admits `principal.type == "AI_AGENT"` only
   through `agent-charter-allows` — a full `data.openbank.agents.allow` evaluation including
   hard-denied tiers, charter_denied and skill gating. At review time **no fraud-dev-engineer
   charter exists** in `agents.yaml`; adopting one is a governance-path change a declared
   agent cannot make for itself, and until it exists an AI-agent principal is deny-by-default
   on every `fraud.*` action.

**Conclusion.** The service is a well-bounded pilot target *because* its verdict output is
still shadow-only and its blast radius is observability plus marketing suppression. The
agent-surface threats above share one property: every effective mitigation is a human or
out-of-band control (guard block, recorded approvals, OPA charter, generated-artifact
checksums). The day the pilot's scope extends beyond source+tests+gitops, or any caller
moves to enforce, this section and §0 must be re-reviewed first — the enforce flip converts
A1/A2 from "polluted signal" into "executed money decision" with no code change in this
service at all.

## 7. Change log

- **2026-10-15** — **Draft → full STRIDE/DFD review (issue #12885, ADR-0031 phase 4
  prerequisite).** Status flipped from "Draft (lightweight, first pass)" to **Full —
  STRIDE/DFD (ADR-0030 D2), money-path**; last-reviewed advanced to 2026-10-15. Structure
  aligned to the fleet's full-review shape: §2 DFD redrawn with every current node (OPA
  sidecar enforced, Redis feature-store boundary, ONNX shadow plane, fraud-hold outbox,
  schedulers, generated NetworkPolicy allow-list, account-service outbound edge); new §3
  Authn/Authz endpoint × role × OPA-action matrix verified against source and the generated
  `fraud-opa-bundle.yaml`; §4 extended with T6 (replay dedup), T7 (synthetic taint), D2/D3
  and the enforced-authz corrections; §4a/§4b/§4c supplements for the ML shadow plane, key
  invariants and the signal plane; §5 rewritten as residual risks + explicit assumptions;
  new §6 "AI agent contribution surface" for the fraud-dev-engineer pilot. All prior entries
  preserved unchanged below. **Findings recorded, not fixed (docs-only PR):** (1) the `rail`
  metric label breaks the ADR-0077 cardinality contract — caller-controlled, unbounded (I2,
  §5); (2) the review-queue M2M read over-grant is still open — `operator-read-any` lacks the
  `service-account-*` exclusion its `compliance-read-any` sibling now has (§3, §5);
  (3) the model card is digest-pinned but unsigned, so a same-PR artifact+card swap loads
  cleanly — bounded by shadow-only output, must close before any `*-enforce` model (T4).
  **Corrections recorded:** the draft's §0/§5 "OPA advisory" item is closed (`AUTHZ_ENFORCE=true`
  in gitops); the draft's "no surface calls it" posture is history since #4221 (shadow-only
  but live, per the 2026-08-09 entry below). No code, policy, schema, privilege or
  trust-boundary change; rollback = revert this commit.

- **2026-10-03** — **SENT outbox rows are purged after 7 days** (ADR-0329, ADR-0327 D8). `fraud_outbox` kept every SENT row, payload included, indefinitely: `purgeSent` existed and nothing called it. The shared libs-runtime `OutboxSentRetentionJob` now deletes SENT rows whose `sent_at` is older than `openbank.outbox.retention.sent-days` (default 7) nightly in bounded batches; its v1 repository opts in by delegating `SentOutboxRetention` to `PanacheOutboxRetention`. PENDING, FAILED, DISPATCHING and DEAD rows are never touched. Information disclosure: shrinks the window in which a database read (replica, backup, operator query) exposes past event payloads. No new endpoint, caller or privilege; replaying an event older than 7 days now comes from the broker or audit-service, not this table.

- **2026-09-26** — **AuthzProducer replaced by the shared libs-runtime OPA PDP producer** (PR
  #10952). The service-local `infrastructure/authz/AuthzProducer.kt` is deleted;
  `application.yaml` now sets `openbank.authz.opa-pdp-producer.enabled: true` to opt into
  `OpaPolicyDecisionPointProducer` (openbank-libs-runtime), gated by that build
  property (`enableIfMissing = false`), so the wiring stays off for any service that does not set
  it. The producer is NOT `@DefaultBean`: if this service's own `src/main` ever produces a second
  `PolicyDecisionPoint` bean, `quarkusBuild` fails loudly on an ambiguous CDI dependency instead of
  one silently displacing the other. Same `opa.url`/`opa.path`/`opa.timeout-ms` defaults as the deleted producer
  (`http://localhost:8181`, `/v1/data/openbank/rest/allow`, 500 ms) and the same fail-closed
  behaviour on OPA sidecar failure — `OpaSidecarPolicyDecisionPoint` itself is unchanged, only its
  construction site moved from a per-service copy to the shared producer. No new caller, endpoint,
  network edge or privilege; no new trust boundary.

- **2026-08-09** — Boundary (1) gains its first real callers (issue #4221). No new *kind* of
  boundary: the inbound REST surface, its `@RolesAllowed`/`@Authorize` gates, the rule engine and
  every stored artefact are untouched. What changes is that boundary (1) — described above as the
  "(Phase 2 caller)" — had **never once been crossed in a deployed environment**. All four scoring
  clients (domestic-payment, sepa-payment, sepa-instant, fx-service) resolved
  `${FRAUD_SERVICE_URL:http://localhost:8133}` with the variable unset in every workload, so each
  dialled its own pod, was refused, and the fail-open `FraudScoringAdapter` returned a synthetic
  ALLOW. `fraud_scores` held 0 rows fleet-wide. Wiring the four workloads to
  `fraud-service.fraud.svc:8133` makes `gen-network-policies.py` derive the corresponding ingress
  edge, so this service's NetworkPolicy now admits namespaces `payments` and `fx` (previously
  `admin-ui` only). Security-relevant in both directions, and worth stating plainly:
  - **Reduces** a real risk. Every payment on all four rails was scored by a fail-open stub, and
    the WARN it emitted per payment ("shadow ALLOW") is indistinguishable in aggregate from a
    genuinely quiet risk engine. The RTS Art. 18 baseline ADR-0084 §4.1 assumes is now actually
    being collected rather than assumed.
  - **Adds** exposure worth naming. Two namespaces reach this service's REST surface that could
    not before, and `fraud_scores` starts receiving live money-path amounts, currencies and
    account identifiers at payment volume for the first time. Ingress remains least-privilege
    (derived per-namespace, not opened wildly), callers still present a Keycloak JWT and are still
    gated by `@RolesAllowed`, and the storage contract is unchanged.
  - **No verdict is acted on.** Every call site is shadow-only — `shadowFraudScore`
    (domestic/sepa), `scoreFraudShadow` (sepa-instant) — and logs a non-ALLOW outcome without
    influencing payment flow. The `*_FRAUD_ENFORCEMENT_ENABLED` flags that would change that have
    no reader in Kotlin today. Flipping enforcement is a separate, runbook-gated change and needs
    its own threat-model entry, including the load test §5 already requires before any surface
    moves to challenge/enforce.
  - **Rollback**: remove `FRAUD_SERVICE_URL` from the four workloads and regenerate network
    policies. That restores the fail-open stub, i.e. the state described above — which is a
    return to unmeasured risk, not to safety.
- **2026-08-08** — ADR-0220 D3.5 fraud-hold signal (issue #2749): two NEW trust boundaries,
  documented in S3/T5/I3 above. This is fraud-service's **first-ever Kafka producer** (every
  prior grant was Read-only, `openbank.fraud.hold.changed`, `kafka-fraud-mtls.yaml`) and its
  **first-ever outbound REST call** (`AccountServiceClient` → account-service, resolving
  `partyId` from `accountId`). Deliberately conservative on both the trigger and the target: the
  trigger is repeated REVIEW verdicts within a rolling window (no new DECLINE rule — `FraudRuleEngine`
  is unchanged, `ruleVersion` unbumped, and this cannot influence a scoring verdict, only observe
  ones already computed and persisted), and the signal is consumed by engagement-service ONLY as
  a marketing-targeting exclusion (`AdverseState.FRAUD_HOLD`) — it never touches account status,
  never freezes anything, and has no path back into the payment hot path. New `fraud_hold` table
  (one row per party, auto-expiring — no manual clear mechanism exists in this service, by
  design: there is no fraud-case/investigation lifecycle to resolve one from) and a standard
  ADR-0050 transactional outbox (`fraud_outbox`) publishing `fraud.hold_changed`
  (`{partyId, accountId, active, reason, ruleVersion, occurredAt}`) atomically with the hold
  write. Both the raise path (`FraudHoldService.maybeRaise`, called from `FraudScoringService.score`
  in the same fire-and-forget, exceptions-swallowed slot as the existing ML shadow pass) and the
  expiry sweep (`FraudHoldService.sweepExpired`, hourly `@Scheduled`) never affect the returned
  `FraudScore` or the money-path scoring latency. Rollback: revert the commit; drop `fraud_hold`/
  `fraud_outbox` (safe while inert — no other consumer reads either table); the account-service
  client and Kafka producer grant can be removed independently since nothing else depends on them.
- **2026-08-07** — Malformed `POST /api/v1/fraud/score` bodies answered 500, not 400 (#3923). Nothing validated `currency` at the HTTP boundary, and Jackson **coerces** a wrongly-typed JSON scalar into `String` rather than rejecting it — `{"currency": false}` deserialises to the five-character `"false"`. That reached `fraud_scores.currency`, a `varchar(3)`, and Postgres raised `22001 value too long`, surfacing as a Hibernate `DataException` and, with no mapper for it, a **500** from `GenericExceptionMapper`. Found by the 2026-08-03 `api-fuzz-authenticated` run as this service's only `Server error`. **No trust boundary moves:** the endpoint, its `@RolesAllowed`/`@Authorize` gates and every caller are unchanged, and the new guard runs AFTER authorization — the change is purely which status a rejected request carries. It is still security-relevant twice over: a 5xx on a money-path service charges its error budget for someone else's bad request, and it hid a real input reaching storage untyped. Validation is `require` (-> `IllegalArgumentException` -> 400 via libs-runtime's `CommonExceptionMappers`), never a service-local `ExceptionMapper` — a second mapper for one JDK type is picked non-deterministically per request (#526). The bound is the storage contract (exactly three upper-case ISO 4217 letters), so it cannot drift back from the column. Rollback: revert the commit, which restores the 500.
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
