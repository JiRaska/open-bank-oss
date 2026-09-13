---
date: 2026-06-12
decision-status: accepted
delivery-status: shipped
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [fraud, payments, kafka]
summary: "A new openbank-fraud-service scores payments in real time, returning ALLOW/CHALLENGE/REVIEW/DECLINE verdicts, and builds behavioural aggregates from Kafka events; it fails open behind a flag during rollout."
---

# Fraud detection bounded context — real-time transaction risk scoring

> **Implementation note 2026-06-23 — all three delivery phases complete.**
>
> - **`openbank-fraud-service`** — full hexagonal structure deployed; `gitops/apps/fraud.yaml` +
>   `gitops/components/fraud-service/fraud-service.yaml`.
> - **Real-time scoring (all 4 surfaces)**: sepa-payment ✅, domestic-payment ✅,
>   sepa-instant ✅, fx ✅ (each has `FraudScoringPort` + adapter + tests; `ruleVersion = "v2"`).
> - **Signal/enrichment plane (Kafka)**: ✅ PR #1876 — `TransactionSignalConsumer` consuming
>   `openbank.transactions.transaction.initiated`; per-account rolling velocity windows H1/H24/D7
>   (per-currency buckets); `VelocityH1ReviewRule` (≥10/h) + `VelocityH24ReviewRule` (≥50/24h)
>   in shadow mode. Threat model updated with new Kafka trust boundary + currency-isolation invariant.
>
> **Update 2026-07-07 — rule set expanded to `ruleVersion = "v3"` (issue #529).** Two new
> deterministic rules added to `FraudRuleEngine`, both grounded in signals already available —
> no new Kafka producer, no schema change: `LargeSingleTransactionReviewRule` (REVIEW when a single
> transaction's amount reaches a large threshold, using `ScoreRequest.amount` already present since
> Phase 1) and `VelocityH1HighValueReviewRule` (REVIEW when the rolling 1-hour *transacted amount*
> for the account/currency bucket reaches a cap, complementing the existing count-based
> `VelocityH1ReviewRule` — a smaller number of high-value transactions within the hour now also
> trips a control). The amount signal (`VelocityAggregate.totalAmount` per window) was already
> persisted by the Kafka signal plane in Flyway `V2__create_velocity_aggregates.sql`; this change
> only surfaces it to the rule engine via a new `ScoreRequest.velocityH1TotalAmount` field —
> **no new migration**. Still shadow mode only; no payment surface honours the verdict yet.
>
> **Update 2026-07-08 — v3 thresholds made per-currency (adversarial review fix, PR #546).** The
> initial v3 cut used a single currency-blind `BigDecimal` threshold per rule, which let a large
> EUR-denominated payment sail under a CZK-calibrated figure (CZK and EUR differ by roughly 25x in
> value — e.g. EUR 480,000 ≈ CZK 12,000,000 would not have fired against a raw CZK 500,000 cap).
> Both rules now key their threshold off `ScoreRequest.currency` via a `Map<String, BigDecimal>`:
> `LargeSingleTransactionReviewRule` CZK 500,000 / EUR 20,000; `VelocityH1HighValueReviewRule`
> CZK 1,000,000 / EUR 40,000. **These are a first-pass calibration, not risk-team-approved figures**
> — same disclosure standard as the PD/LGD placeholders in the lending ADRs — pending real
> shadow-mode data and risk-team input. **Any currency not in the map fails CLOSED** (fires REVIEW
> unconditionally) rather than silently never firing, matching the fail-closed convention used
> elsewhere in the repo (e.g. `WaiverEvaluator`). No FX-conversion call was added — that remains a
> deliberately separate, bigger architectural decision (new cross-service dependency, its own
> fail-open/closed posture) out of scope for this increment.
>
> **Update 2026-07-09 — rule set expanded to `ruleVersion = "v4"`: new-payee + high-amount
> combination rule (issue #625).** Closes the last item on the §3 roadmap list that was deferred at
> Phase-1 launch for lack of a payee-history signal. New Flyway migration
> `V3__create_payee_history.sql` adds a `payee_history` table keyed on `(account_id,
> payee_identifier)`, tracking first-seen timestamp, last-paid timestamp and payment count. The
> existing `TransactionSignalConsumer` (no new Kafka topic) now also reads `targetAccountId` from
> `openbank.transactions.transaction.initiated` — already published by transaction-service, simply
> not previously consumed here — as the payee identifier, and upserts `payee_history` via the new
> `PayeeHistoryRepositoryImpl`. Unlike the pre-existing `velocity_aggregates` upsert, this one is
> **idempotent against Kafka redelivery**: it guards on the signal's `aggregateId` (stored as
> `last_transaction_id`) so replaying the same event does not double-count `payment_count` — verified
> against a real Postgres in `PayeeHistoryRepositoryImplIT`, not just mocked.
> `ScoreRequest` gains a server-enriched `isNewPayee: Boolean` (default `false`, never accepted from
> the caller — same non-negotiable as every other scoring input), set by
> `FraudScoringService.enrichWithPayeeHistory` from a `payee_history` lookup keyed on
> `(accountId, counterpartyId)` — a payee is "new" exactly when no history row exists yet.
> `NewPayeeHighAmountReviewRule` fires REVIEW when `isNewPayee` is true and the amount exceeds a
> per-currency threshold **notably lower** than `LargeSingleTransactionReviewRule`'s — CZK 250,000 /
> EUR 10,000, roughly half of that rule's CZK 500,000 / EUR 20,000 — because a first-ever payment to
> a never-seen payee is inherently higher-risk than the same amount to an established payee, which is
> the entire reason this rule exists separately. **These are first-pass, non-calibrated figures**,
> same disclosure standard as the v3 thresholds, pending shadow-mode data and risk-team input. Same
> per-currency `Map<String, BigDecimal>` pattern as the v3 amount rules, including fail-CLOSED on an
> unmapped currency whenever `isNewPayee` is true. Still shadow mode only; no payment surface honours
> the verdict yet.

> **Update 2026-07-09 — §4.2: first payment surface honours the verdict (issue #667).**
> `openbank-domestic-payment` gains an enforcement mode for the fraud gate, flag-gated by
> `openbank.domestic.fraud.enforcement-enabled` (default `false` — a deliberate runbook-gated
> rollout flip, same convention as the four-eyes `authz.four-eyes.enforce` toggle; see the
> "these are first-pass, non-calibrated figures... pending risk-team input" disclosure on the v3/v4
> thresholds above — enforcement stays off until that calibration happens). With the flag on:
> `DomesticPaymentService.applyFraudGate` runs for every screening-cleared payment; a REVIEW or
> CHALLENGE verdict holds the payment in `RECEIVED` (opens a `FRAUD_REVIEW` case via the existing
> `AmlCasePort`/aml-service case store, mirroring the shape of an AML `REVIEW` hold — no new
> case-management system) for manual release; DECLINE rejects the payment outright with the new
> `DomesticRejectReason.FRAUD_SUSPECTED`. ALLOW — and the flag-off shadow path — validate exactly as
> before. The `FraudScoringAdapter` fail-open contract is unchanged: an unreachable fraud-service
> still scores ALLOW, so this gate can only ever add friction, never remove availability. Threat
> model updated (`docs/threat-models/openbank-domestic-payment.md`); no new trust boundary or data
> flow, same `fraud-service` call as the 2026-06-17 shadow wiring. The other three scored surfaces
> (sepa-payment, sepa-instant, fx) remain shadow-only — extending enforcement to them is a follow-up,
> not bundled with this increment.

> **Correction 2026-08-09 — the §4.2 enforcement mode above no longer exists (issue #4221).**
> `DomesticPaymentService.applyFraudGate` and the `fraudEnforcementEnabled` service flag were
> **deleted** by the Temporal migration (ADR-0120 Phase 6, issue #1917) when the in-service
> orchestration was retired; only the shadow `shadowFraudScore` activity was carried over into
> `DomesticPaymentWorkflow`. Nothing replaced the gate, and nothing noticed: the
> `openbank.domestic.fraud.enforcement-enabled` key stayed in `application.yaml` with **zero**
> readers in `src/main/kotlin`, so the runbook flip described above would have changed nothing and
> reported nothing, and the threat model went on crediting a mitigation that was not there. The key
> has now been removed rather than left as a lever that does not move.
>
> Restoring enforcement is not a config change. The verdict now arrives inside a Temporal activity
> that returns `Unit`, so acting on it needs a decision returned to the workflow and a hold state
> for the payment — a separately-reviewed increment on a money-path rail, tracked in #4403.
> Read the §4.2 block above as history, not as current behaviour: **all four scored surfaces
> (domestic-payment, sepa-payment, sepa-instant, fx) are shadow-only today.**
>
> What #4221 did land is the observability the original §4.2 assumed away. The line above —
> "an unreachable fraud-service still scores ALLOW, so this gate can only ever add friction, never
> remove availability" — is true and was the whole problem: that ALLOW was indistinguishable from a
> real one, so a scorer that had never run once looked exactly like a clean payment stream.
> `FraudScoreOutcome.synthetic`, the `openbank_fraud_scoring_degraded` gauge and the
> `openbank_fraud_scoring_outcomes_total` counters now separate the two, on every rail.

## Context

The platform has a **regulatory screening gate** (ADR-0032: synchronous sanctions/AML screening
in all four payment-execution surfaces) and **strong customer authentication** (ADR-0021: SCA
push/biometric with decoupled device approval). What it does not have is **fraud detection** —
the behavioural layer that protects the *customer and the bank* from unauthorized or manipulated
transactions, as opposed to the compliance layer that protects the *financial system* from
sanctioned/laundered money:

- A stolen credential + passed SCA challenge sails through every existing gate.
- There is no velocity control: 50 transfers in 5 minutes from one account look, to the
  platform, exactly like one transfer.
- A first-ever payee receiving the account's full balance at 03:00 from a device enrolled
  20 minutes ago raises nothing.

This is also a regulatory hole, not just a product one. **PSD2 RTS (EU) 2018/389 Art. 3(1)(d)**
requires payment service providers to operate *transaction monitoring mechanisms* that detect
unauthorized or fraudulent payment transactions, factoring in compromised authentication
elements, known fraud scenarios, and signs of malware infection. The SCA exemption regime
(**RTS Art. 18, transaction risk analysis**) is *only available* to PSPs that run such
monitoring with reference fraud rates. Today the compliance scorecard would honestly say:
**no control**.

Existing assets the design must reuse rather than duplicate: the ADR-0032 gate call pattern in
the four payment surfaces, SCA device/enrollment signals (ADR-0021, ADR-0073), the customer-edge
trust boundary (ADR-0065) which sees device/IP context, Kafka domain events + transactional
outbox (ADR-0003/0013), feature flags with four-eyes on money-path flips (ADR-0067), the
analytics layer (ADR-0022) and the DomainMetrics observability pattern (ADR-0077/0079).

## Decision

Introduce a new bounded context: **`openbank-fraud-service`** (namespace `fraud`,
money-path, hexagonal per ADR-0002), with two planes:

### 1. Real-time scoring plane (synchronous, in the payment path)

A `POST /api/v1/fraud/score` endpoint called by the same four payment surfaces that call the
ADR-0032 screening gate (sepa-payment, domestic-payment, sepa-instant, fx), *after* SCA and
*alongside* sanctions/AML screening. Input: payment intent (account, party, amount, currency,
creditor), context (device id, enrollment age, channel, IP-derived geo from customer-edge).
Output is a **verdict**, not a score the caller must interpret:

- `ALLOW` — proceed.
- `CHALLENGE` — require (re-)SCA / step-up before execution.
- `REVIEW` — hold the payment, open a case in the operator review queue (four-eyes release,
  reusing the ADR-0068 cockpit pattern).
- `DECLINE` — reject with a customer-safe reason code.

**Fail-open with a flag, fail-closed at maturity:** during rollout, scoring-service
unavailability degrades to `ALLOW` + alert (availability of payments beats an immature
control); the failure mode is itself a feature flag so the posture can be flipped to
fail-closed (`CHALLENGE`) once SLOs prove the service out. Latency budget: p99 ≤ 150 ms.

### 2. Signal/enrichment plane (asynchronous)

The service consumes existing Kafka domain events (payments executed, SCA challenges
issued/resolved, devices enrolled, parties/accounts changed) to maintain per-account and
per-party **behavioural aggregates**: rolling velocity counters (count/sum per 1h/24h/7d),
known-payee sets, typical-amount bands, device history. Postgres-per-service (ADR-0009);
no new infrastructure.

### 3. Rules first, ML later

Phase 1 is a **deterministic, versioned rule set** (velocity caps, new-payee + high-amount
combination, enrollment-age × amount, impossible-travel/geo-switch, night-time first
transfers). Rules are code-reviewed config — not a UI-editable rule engine — so every change
is a PR with the standard money-path controls. ML scoring (gradient-boosted model over the
ADR-0022 ClickHouse history) is an explicit later phase behind the same verdict contract and
falls under the AI governance plane (ADR-0031) when it lands.

### 4. Rollout: shadow → challenge → enforce

1. **Shadow** — surfaces call the scorer, log + emit metrics, ignore the verdict. Builds the
   reference fraud-rate baseline RTS Art. 18 needs.
2. **Challenge-only** — `CHALLENGE` honored; `REVIEW`/`DECLINE` still shadow.
3. **Enforce** — full verdict honored; flips are money-path feature flags requiring four-eyes
   (ADR-0067, rules.yaml `feature_flags`).

### 5. Governance

`openbank-fraud-service` joins `rules.yaml: money_path_services` (2 approvals + threat model
per ADR-0030) **when its first executable lands** — this ADR only reserves the decision.
DomainMetrics: `openbank_fraud_scores_total{verdict=…}`, `openbank_fraud_review_queue_depth`,
rule-hit breakdowns; Grafana dashboard + alert on review-queue staleness. A new
`fraud-monitoring` control enters `compliance-controls.yaml` as `planned` and is flipped by
the rollout phases — derived, never hand-faked (ADR-0074/0079 house rule).

## Alternatives considered

- **Extend aml-service with fraud rules.** Rejected: AML/CFT (financial-system integrity,
  SAR-shaped outcomes) and fraud (customer-asset protection, real-time verdicts in the payment
  hot path) have different regulators, different latency budgets, different data needs and
  different ops queues. Coupling them re-creates the mixed-concern monolith ADR-0032
  deliberately avoided.
- **Buy/integrate an external fraud engine (Feedzai/Featurespace class).** Rejected for now:
  contradicts the cloud-agnostic in-cluster OSS substrate (ADR-0027), and the platform lacks
  the volume to justify it. The verdict contract keeps the door open — an external engine
  could later sit behind the same port.
- **Inline rules in each payment surface.** Rejected: four divergent copies of velocity logic
  with no shared behavioural state is how fraud controls rot; the whole point is one place
  that sees *all* surfaces.
- **UI-editable rule engine for ops.** Rejected at this phase: runtime-mutable money-path
  behaviour conflicts with ADR-0033 (only break-glass and non-engineering policy qualify for
  runtime change); versioned config-as-code keeps the audit trail free.

## Consequences

- One new money-path service (threat model, 2-approval reviews, T0 tier candidate — it sits in
  the synchronous payment path), one new namespace, one new review queue surface in admin-ui.
- Payment surfaces gain a second synchronous pre-execution call; the latency budget and
  fail-open flag bound the blast radius, mirroring how ADR-0032 was introduced.
- The platform becomes *eligible* to ever use PSD2 TRA exemptions (RTS Art. 18) — impossible
  without monitoring + measured fraud rates.
- Honest compliance reporting: `fraud-monitoring` appears as `planned` instead of being
  silently absent.

## Compliance impact

- **PSD2 RTS (EU) 2018/389 Art. 3(1)(d)** — transaction monitoring mechanisms: this ADR is the
  decision that closes the gap; rollout phases map to `planned → partial → enforced`.
- **PSD2 RTS Art. 18** — transaction risk analysis exemption: enabled (optional, later).
- **DORA Art. 8–10** — ICT risk identification/protection/detection for the payment path.
- **EBA Guidelines on fraud reporting under PSD2 (EBA/GL/2018/05)** — the aggregates plane is
  the data source for statutory fraud reporting via the analytics layer (ADR-0022/0023).

## References

- ADR-0032 — synchronous sanctions/AML screening gate (the sibling, regulatory layer)
- ADR-0021 / ADR-0073 — SCA decoupled approval; hardware-backed device credentials
- ADR-0065 — customer-facing edge (device/IP context source)
- ADR-0067 — feature flags; money-path flips four-eyes
- ADR-0068 — operations cockpit pattern (review queue reuse)
- ADR-0022 / ADR-0023 — analytics layer (fraud reporting, later ML features)
- ADR-0031 — AI agent governance (applies when ML scoring lands)
- ADR-0077 — DomainMetrics observability pattern
- ADR-0030 — supply-chain/SSDLC hardening (threat-model mandate for money-path services)
