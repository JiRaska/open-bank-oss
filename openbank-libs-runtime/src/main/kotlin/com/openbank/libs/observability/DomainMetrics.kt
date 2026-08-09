// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.math.BigDecimal
import java.time.Duration

/**
 * Central façade for all OpenBank domain metrics (ADR-0077 Phase 2).
 *
 * Services inject [DomainMetrics] and call the typed methods below — no raw Micrometer API
 * leaks into the domain or application layer. All metric names follow the convention
 * `openbank.<domain>.<noun>[.<verb>]` and carry only low-cardinality tags.
 *
 * **Cardinality contract:** never pass a payment ID, account ID, IBAN, amount, or any
 * high-cardinality value as a tag — use histograms (Timer/DistributionSummary) for amounts.
 *
 * ### Usage (service side)
 * ```kotlin
 * @ApplicationScoped
 * class SepaPaymentService(
 *     private val metrics: DomainMetrics,
 *     ...
 * ) {
 *     fun createPayment(...): SepaPayment {
 *         val payment = ...
 *         metrics.paymentSubmitted("sepa", payment.currency)
 *         return payment
 *     }
 * }
 * ```
 */
/**
 * CDI bean — safe to load in services that do **not** have `quarkus-micrometer-*` on the
 * classpath. When no `MeterRegistry` producer is present (e.g. balance-service, audit-service)
 * `registryInstance.isResolvable` is false and every metric method is a silent no-op.
 * Services that do ship a registry (sepa-payment-service, …) get full instrumentation.
 */
@ApplicationScoped
class DomainMetrics {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private fun reg(): MeterRegistry? = if (registryInstance.isResolvable) registryInstance.get() else null

    // ── Payments ─────────────────────────────────────────────────────────────

    /**
     * Increment when a payment instruction is durably persisted (RECEIVED state).
     *
     * @param type  payment rail: `sepa` | `sepa_instant` | `domestic` | `fx`
     * @param currency  ISO-4217 currency code (e.g. `EUR`, `CZK`)
     */
    fun paymentSubmitted(type: String, currency: String) {
        counter("openbank.payments.submitted", "type", type, "currency", currency)
    }

    /**
     * Increment when a payment reaches a terminal state.
     *
     * @param type      payment rail
     * @param currency  ISO-4217 currency code
     * @param outcome   `completed` | `rejected` | `returned` | `cancelled`
     */
    fun paymentCompleted(type: String, currency: String, outcome: String) {
        counter("openbank.payments.completed", "type", type, "currency", currency, "outcome", outcome)
    }

    /**
     * Record end-to-end processing duration from submission to terminal state.
     *
     * @param type      payment rail
     * @param outcome   `completed` | `rejected`
     * @param duration  wall-clock duration
     */
    fun paymentProcessingDuration(type: String, outcome: String, duration: Duration) {
        timer("openbank.payment.processing.duration", "type", type, "outcome", outcome)
            ?.record(duration)
    }

    // ── Accounts ─────────────────────────────────────────────────────────────

    /**
     * Increment when a new account is opened.
     *
     * @param productType  product type (e.g. `CURRENT`, `SAVINGS`, `FX_POCKET`)
     * @param currency     ISO-4217 currency code
     */
    fun accountCreated(productType: String, currency: String) {
        counter("openbank.accounts.created", "product_type", productType, "currency", currency)
    }

    /**
     * Increment when an account is closed or suspended.
     *
     * @param productType  product type
     * @param reason       `customer_request` | `regulatory` | `fraud` | `inactivity`
     */
    fun accountClosed(productType: String, reason: String) {
        counter("openbank.accounts.closed", "product_type", productType, "reason", reason)
    }

    // ── Parties ───────────────────────────────────────────────────────────────

    /**
     * Increment when a new party record is created.
     *
     * @param type  `individual` | `business`
     */
    fun partyCreated(type: String) {
        counter("openbank.parties.created", "type", type)
    }

    /**
     * Increment when a party passes identity verification.
     *
     * @param type  `individual` | `business`
     */
    fun partyVerified(type: String) {
        counter("openbank.parties.verified", "type", type)
    }

    // ── KYC ──────────────────────────────────────────────────────────────────

    /**
     * Increment when a KYC case is submitted.
     *
     * @param type  `individual` | `business`
     */
    fun kycSubmitted(type: String) {
        counter("openbank.kyc.submissions", "type", type)
    }

    /**
     * Increment when a KYC verdict is issued.
     *
     * @param type     `individual` | `business`
     * @param outcome  `approved` | `rejected` | `manual_review`
     */
    fun kycVerdict(type: String, outcome: String) {
        counter("openbank.kyc.verdicts", "type", type, "outcome", outcome)
    }

    // ── SCA ───────────────────────────────────────────────────────────────────

    /**
     * Increment when an SCA challenge is issued.
     *
     * @param method  `push` | `totp` | `biometric` | `sms`
     */
    fun scaChallengeIssued(method: String) {
        counter("openbank.sca.challenges", "method", method)
    }

    /**
     * Increment when an SCA challenge is resolved.
     *
     * @param method   `push` | `totp` | `biometric` | `sms`
     * @param outcome  `completed` | `failed` | `expired`
     */
    fun scaChallengeResolved(method: String, outcome: String) {
        counter("openbank.sca.completions", "method", method, "outcome", outcome)
    }

    // ── Ledger ────────────────────────────────────────────────────────────────

    /**
     * Increment when a ledger posting pair is applied.
     *
     * @param currency  ISO-4217 currency code
     * @param type      `payment` | `fee` | `interest` | `reversal`
     */
    fun ledgerPosting(currency: String, type: String) {
        counter("openbank.ledger.postings", "currency", currency, "type", type)
    }

    /**
     * Record the absolute monetary amount of a single ledger posting line (ADR-0077 Tier C).
     *
     * Uses a DistributionSummary (histogram) so Prometheus/Grafana can derive p50/p95/p99 posting
     * sizes and total posted volume per scrape interval without storing individual amounts as tags
     * (cardinality contract).
     *
     * @param currency    ISO-4217 currency code of the posting line
     * @param debitCredit `debit` | `credit`
     * @param amount      absolute (non-negative) monetary value
     */
    fun ledgerPostingAmount(currency: String, debitCredit: String, amount: BigDecimal) {
        summary("openbank.ledger.posting.amount", "currency", currency, "debit_credit", debitCredit)
            ?.record(amount.toDouble())
    }

    // ── Balance ───────────────────────────────────────────────────────────────

    /**
     * Increment when a balance position receives a booked delta from the ledger projection
     * (ADR-0039 Phase D / ADR-0077 Tier C). Covers all projection sources including FX revaluation.
     *
     * @param currency ISO-4217 currency code of the revalued position
     */
    fun balanceRevaluated(currency: String) {
        counter("openbank.balances.revaluations", "currency", currency)
    }

    // ── AML / Sanctions ───────────────────────────────────────────────────────

    /**
     * Increment on each sanctions screening attempt.
     *
     * @param role  `debtor` | `creditor` | `beneficiary`
     */
    fun sanctionsScreening(role: String) {
        counter("openbank.sanctions.screenings", "role", role)
    }

    /**
     * Increment when a screening returns a non-CLEAR verdict.
     *
     * @param role      `debtor` | `creditor`
     * @param severity  `review` | `block`
     */
    fun sanctionsHit(role: String, severity: String) {
        counter("openbank.sanctions.hits", "role", role, "severity", severity)
    }

    /**
     * Increment on each AML screening.
     *
     * @param type  `transaction` | `periodic`
     */
    fun amlScreening(type: String) {
        counter("openbank.aml.screenings", "type", type)
    }

    /**
     * Increment when AML flags a case.
     *
     * @param severity  `low` | `medium` | `high` | `critical`
     */
    fun amlHit(severity: String) {
        counter("openbank.aml.hits", "severity", severity)
    }

    // ── Authorization (ADR-0034 D5) ───────────────────────────────────────────

    /**
     * Increment on every `@Authorize` decision, whatever the outcome.
     *
     * This exists because advisory mode (`authz.enforce=false`) previously left **no signal but a
     * WARN log line**. Every service's gitops manifest states the rollout precondition as "flip to
     * true only after an observation window with a clean advisory report" — but with no metric,
     * that report was not obtainable for any service, so the precondition could not be evaluated at
     * all. The `enforced` tag is the point: `outcome=deny, enforced=false` is exactly the
     * "would DENY" population a rollout needs to be empty before flipping.
     *
     * @param action        the `@Authorize(action = ...)` value, e.g. `card.block` — a bounded set
     * @param outcome       `allow` | `deny` | `pdp_unavailable` | `pdp_unconfigured`
     * @param enforced      the `authz.enforce` value in effect for this call. `false` means the
     *                      call PROCEEDED regardless of `outcome`
     * @param principalType `HUMAN` | `AI_AGENT` | `ANONYMOUS` | `unknown` (unknown only when the
     *                      decision failed before a query could be built)
     */
    fun authzDecision(action: String, outcome: String, enforced: Boolean, principalType: String) {
        counter(
            "openbank.authz.decisions",
            "action", action,
            "outcome", outcome,
            "enforced", enforced.toString(),
            "principal_type", principalType,
        )
    }

    /**
     * Increment when OPA flags an allowed action `four_eyes_required` (ADR-0155).
     *
     * `required_not_enforced` is the one that matters: the policy asked for a second approver and
     * the interceptor proceeded anyway because `authz.four-eyes.enforce` is false. That is the
     * fleet's current default and it was previously invisible — the policy computes the flag, the
     * interceptor drops it silently, and nothing distinguishes that from "four-eyes not required".
     * A non-zero `required_not_enforced` is a live maker-checker gap, not a curiosity.
     *
     * @param action   the `@Authorize(action = ...)` value
     * @param outcome  `required_not_enforced` | `no_approval_store` | `pending_approval` |
     *                 `approval_satisfied`
     */
    fun authzFourEyes(action: String, outcome: String) {
        counter("openbank.authz.four_eyes", "action", action, "outcome", outcome)
    }

    // ── Outbox ────────────────────────────────────────────────────────────────

    /**
     * Increment each time the outbox dispatcher successfully publishes an event.
     *
     * @param service  service name (e.g. `sepa-payment`, `ledger`)
     * @param topic    Kafka topic
     */
    fun outboxDispatched(service: String, topic: String) {
        counter("openbank.outbox.dispatched", "service", service, "topic", topic)
    }

    /**
     * Increment each time an outbox event fails after all retries (DEAD status).
     *
     * @param service  service name
     */
    fun outboxDead(service: String) {
        counter("openbank.outbox.dead", "service", service)
    }

    /**
     * Register the outbox backlog gauge — the single most important operational signal
     * (ADR-0077): processable (PENDING + FAILED) rows not yet relayed to the broker. A
     * rising backlog means money/events are stuck. Call **once at startup** with a supplier
     * that reads the count cheaply (e.g. `SELECT count(*) ... WHERE status IN (...)`); the
     * registry samples the supplier on scrape. Re-registration with the same name+tag is a
     * no-op, so it is safe to call from a `@Startup` observer.
     *
     * @param service  service name (e.g. `ledger`, `sepa-payment`)
     * @param backlog  cheap supplier of the current processable row count
     */
    fun registerOutboxBacklog(service: String, backlog: () -> Number) {
        reg()?.let { r ->
            Gauge.builder("openbank.outbox.backlog", backlog) { it.invoke().toDouble() }
                .tag("service", service)
                .strongReference(true)
                .register(r)
        }
    }

    /**
     * Register the outbox **dead-letter** gauge: rows parked in terminal [
     * com.openbank.libs.persistence.outbox.OutboxStatus.DEAD] (ADR-0050 N5) and therefore excluded
     * from `listProcessable`/`claimProcessable` forever. `openbank.outbox.backlog` cannot see them
     * — its whole point is that DEAD is *not* backlog — so a service that has dead-lettered every
     * event it ever produced reads as a flat zero backlog, which is indistinguishable from healthy
     * (#4005).
     *
     * A **gauge and not the [outboxDead] counter**, because the counter answers "did we dead-letter
     * anything since this process started" and the operational question is "are there dead rows
     * sitting in the table right now". A pod restart resets the counter while the rows remain, and
     * a Micrometer counter is not even *created* until its first increment — so a service whose
     * dead-lettering happened before the current pod exports no `openbank_outbox_dead_total` series
     * at all, and any alert on it silently matches nothing.
     *
     * Same lifecycle contract as [registerOutboxBacklog]: call once at startup with a cheap
     * supplier (`SELECT count(*) ... WHERE status = 'DEAD'`), re-registration is a no-op.
     *
     * @param service      service name (e.g. `card-issuance`)
     * @param deadLettered cheap supplier of the current DEAD row count
     */
    fun registerOutboxDeadLettered(service: String, deadLettered: () -> Number) {
        reg()?.let { r ->
            Gauge.builder("openbank.outbox.dead_lettered", deadLettered) { it.invoke().toDouble() }
                .tag("service", service)
                .strongReference(true)
                .register(r)
        }
    }

    // ── Workflow liveness (ADR-0160 mechanism 3) ────────────────────────────────

    /**
     * Register the liveness gauges for a scheduled workflow (ADR-0160): age-of-last-success and
     * its expected interval, both in seconds.
     *
     * **What consumes them (#2239, ADR-0237).** Two readers, and the seed below is what lets both
     * be right at once. `openbank-control-liveness-sentinel`'s D1 detection (ADR-0163) files a
     * FINDING on any workflow past 2x its declared interval; `prometheus-rules-workflow-liveness`
     * ships `WorkflowLivenessStale` on the same comparison
     * (`openbank_workflow_last_success_age_seconds > 2 *
     * openbank_workflow_expected_interval_seconds`, `for: 15m`).
     *
     * **The age is seeded at REGISTRATION time, not at [java.time.Instant.EPOCH].** It used to be
     * EPOCH, which made a freshly started pod report ~1.8e9 seconds — decades — for every workflow
     * that had not yet recorded a success. That is survivable for a daily sentinel run and fatal
     * for an alert rule: `WorkflowLivenessStale` would fire 15 minutes after every deploy or
     * restart, for every daily workflow, and keep firing until that workflow's next success (up to
     * 24h), across all ~28 registration sites. No `for:` duration helps, because the condition
     * genuinely persists. This KDoc named the choice — "seed from persisted run state, gate on pod
     * uptime, or stay sentinel-only" — while the rule shipped asserting the seeding had already
     * happened; seeding here is what makes the rule's own comment and ADR-0237 true.
     *
     * The cost of the seed is one bit of information, and it is published rather than lost:
     * [WorkflowLivenessMetrics.SUCCESS_RECORDED] is `0` until the first [recordSuccess] and `1`
     * after, so triage can still separate "ran once, then stopped" from "has not succeeded since
     * this pod started". Detection is unaffected either way — a job that has never run crosses its
     * own 2x threshold once its grace elapses, exactly like one that stopped. What the seed does
     * give up is a job whose pod restarts *more often* than 2x its interval: that job can never
     * accumulate enough age to alert, and is covered statically instead, by the registration gate
     * (`check-scheduler-liveness.py`, ADR-0237 point 4) and the HR000068 guards.
     *
     * This exists because a scheduled job can fail SILENTLY (an exception swallowed after logging,
     * or simply stopping) and leave no record and no alarm — exactly how balance-service's daily
     * reconciliation ran zero rows for 41 days unnoticed (issue #855) before it got its own
     * bespoke, log-only watchdog. This is that watchdog, generalized to any `@Scheduled` job.
     *
     * Call **once at startup** (e.g. from the caller's constructor) with the workflow's own name
     * and expected run interval; call [WorkflowLivenessRecorder.recordSuccess] at the end of the
     * job's success path on every run. Re-registration with the same `workflow` tag is a no-op
     * gauge re-register (safe, matches [registerOutboxBacklog]); a no-op [WorkflowLivenessRecorder]
     * is returned when no [MeterRegistry] is resolvable (same fallback as every method above).
     *
     * @param workflow          stable low-cardinality name, e.g. `standing-order-execution`
     * @param expectedInterval  the job's normal run cadence (e.g. `Duration.ofDays(1)` for a daily
     *                          sweep) — the alert fires at 2x this, so pick the SCHEDULE interval,
     *                          not a tighter SLA; grace period is baked into the 2x multiplier.
     */
    fun registerWorkflowLiveness(workflow: String, expectedInterval: Duration): WorkflowLivenessRecorder {
        // Seeded at registration, so the age a fresh pod reports is its own uptime rather than the
        // ~1.8e9 seconds Instant.EPOCH produced. See the KDoc: the alert rule that reads this gauge
        // is only boot-safe because of this line.
        val lastSuccessEpochMillis = java.util.concurrent.atomic.AtomicLong(java.time.Instant.now().toEpochMilli())
        val successRecorded = java.util.concurrent.atomic.AtomicLong(0)
        reg()?.let { r ->
            // Names come from WorkflowLivenessMetrics, never a literal: the consumer side
            // (openbank-control-liveness-sentinel) queried a name nothing emitted for as long as
            // both sides spelled it themselves, so mechanism 3 collected an empty vector and could
            // only ever report "no stale heartbeats" (#2187 follow-up).
            Gauge.builder(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS) {
                Duration.between(java.time.Instant.ofEpochMilli(lastSuccessEpochMillis.get()), java.time.Instant.now())
                    .toSeconds().toDouble()
            }.tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow).strongReference(true).register(r)
            Gauge.builder(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS) {
                expectedInterval.toSeconds().toDouble()
            }.tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow).strongReference(true).register(r)
            Gauge.builder(WorkflowLivenessMetrics.SUCCESS_RECORDED) {
                successRecorded.get().toDouble()
            }.tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow).strongReference(true).register(r)
        }
        return WorkflowLivenessRecorder(lastSuccessEpochMillis, successRecorded)
    }

    // ── Reconciliation drift (ADR-0160 mechanism 4) ─────────────────────────────

    // One AtomicReference per (control, currency) seen so far — populated lazily since the set of
    // currencies isn't known at startup. ApplicationScoped singleton, so a mutable map field here
    // has the same lifecycle/thread-safety shape as registerOutboxBacklog's captured closures.
    private val driftHolders = java.util.concurrent.ConcurrentHashMap<
        Pair<String, String>,
        java.util.concurrent.atomic.AtomicReference<BigDecimal>,
        >()

    /**
     * Record one currency's signed drift from a control-account ⇄ sub-ledger (or any two
     * independent-writer) reconciliation run (ADR-0160 mechanism 4 — the revised design, see the
     * ADR's 2026-07-13 amendment). Publishes `openbank.balance.reconciliation.drift{control,
     * currency}` as a live gauge; a `PrometheusRule` with a `for:` clause (not this method — see
     * the accompanying gitops manifest) turns "drift present in this one snapshot" into "drift has
     * been sustained for N consecutive runs", which is what actually distinguishes a real defect
     * from a transient artifact (a snapshot taken mid-backfill was misread as a ~220k CZK integrity
     * crisis in issue #860 before this existed).
     *
     * Call on **every** reconciliation run, for every currency in the report — including a
     * currency that came back within tolerance (drift = zero), so the gauge reflects the current
     * state rather than freezing at the last non-zero value. Safe to call from the very first run:
     * the gauge is registered lazily on first sight of a (control, currency) pair.
     *
     * @param control   stable low-cardinality name for the reconciliation control, e.g.
     *                  `balance_deposit_control` — lets a future second independent-writer pair
     *                  reuse this same primitive under its own control name.
     * @param currency  ISO-4217 currency code
     * @param drift     signed difference (sub-ledger − ledger-control, or whichever side this
     *                  control defines as the delta); zero means the two writers agree
     */
    fun recordReconciliationDrift(control: String, currency: String, drift: BigDecimal) {
        reg()?.let { r ->
            driftHolders.computeIfAbsent(control to currency) {
                val ref = java.util.concurrent.atomic.AtomicReference(BigDecimal.ZERO)
                Gauge.builder("openbank.balance.reconciliation.drift") { ref.get().toDouble() }
                    .tag("control", control)
                    .tag("currency", currency)
                    .strongReference(true)
                    .register(r)
                ref
            }.set(drift)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Get-or-create the named counter and record one occurrence. Every call site is a
    // single domain event, so the increment lives here. (A bare register() — the prior
    // behaviour — left every counter pinned at 0, so the business dashboards read a
    // permanent flat line. Surfaced by DomainMetricsTest while wiring ADR-0082.)
    private fun counter(name: String, vararg tags: String) {
        reg()?.let { Counter.builder(name).tags(*tags).register(it).increment() }
    }

    private fun timer(name: String, vararg tags: String): Timer? = reg()?.let {
        Timer.builder(name)
            .tags(*tags)
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(it)
    }

    private fun summary(name: String, vararg tags: String): DistributionSummary? = reg()?.let {
        DistributionSummary.builder(name)
            .tags(*tags)
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(it)
    }
}

/**
 * Handle returned by [DomainMetrics.registerWorkflowLiveness]; call [recordSuccess] at the end of
 * the workflow's success path on every run. Not a CDI bean — a plain value object held by the
 * scheduled job that registered it.
 */
class WorkflowLivenessRecorder internal constructor(
    private val lastSuccessEpochMillis: java.util.concurrent.atomic.AtomicLong,
    private val successRecorded: java.util.concurrent.atomic.AtomicLong,
) {
    fun recordSuccess() {
        lastSuccessEpochMillis.set(java.time.Instant.now().toEpochMilli())
        successRecorded.set(1)
    }
}
