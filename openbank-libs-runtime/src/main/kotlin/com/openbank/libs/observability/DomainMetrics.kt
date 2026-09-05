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

    companion object {
        /**
         * Gauge name for [registerStuckPaymentSagas]. Exposed so the emitting service and its
         * tests name the same series the alert rule does — a metric name repeated as a literal in
         * two places is how a rule ends up watching a series nothing emits (#5733).
         */
        const val STUCK_PAYMENT_SAGAS = "openbank.transaction.sagas.stuck"
    }

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

    /**
     * Increment once per sanctions-list import attempt, whatever the outcome.
     *
     * The `outcome` tag is the point (the #4348 rule — a skipped, failed or seed-fallback import
     * must never read as a working one): `imported` means the feed was fetched and its entries
     * upserted; every other value means the stored list was left untouched and names WHY
     * (`empty_feed` | `failed_kept_existing` | `skipped_not_entity_based` |
     * `seed_fallback_non_production`). Alert on the absence of `outcome=imported`, not on an
     * error rate — a list silently running on months-old seeds emits no error.
     *
     * @param listType  a `SanctionsListType` name (e.g. `EU_CONSOLIDATED`)
     * @param outcome   a `ListImportOutcome` name, lower-cased
     */
    fun sanctionsListImport(listType: String, outcome: String) {
        counter("openbank.sanctions.list.imports", "list_type", listType, "outcome", outcome)
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

    /**
     * Increment when two authorization stores that are meant to hold the same grant give
     * DIFFERENT answers to one access question (ADR-0232 D1 dual-run, issue #2993).
     *
     * A dual-run's risk is not "is each store written" but "can they disagree, and does anyone
     * find out". Nothing in this fleet answers the second half: a grant revoked in one store and
     * still live in the other is invisible, because the guards OR the two together and an OR
     * cannot report which arm carried it. This counter is that report, sampled at the decision
     * itself rather than by a nightly diff — so it observes the state the guard actually acted on.
     *
     * Emitting it is never a decision: the caller records the disagreement and then returns the
     * same verdict it would have returned anyway. A metric that changed the answer would make the
     * observation the thing being observed.
     *
     * @param question  the access question being answered, low-cardinality, e.g.
     *                  `account_delegated_payment`
     * @param direction which store was the permissive one — `legacy_only` (the store being
     *                  migrated FROM permits, the new one does not) or `delegation_only` (the
     *                  reverse). Never a store pair, an id, or a party: two values per question.
     */
    fun authorizationStoreDisagreement(question: String, direction: String) {
        counter("openbank.authz.store_disagreement", "question", question, "direction", direction)
    }

    // ── Outbox ────────────────────────────────────────────────────────────────

    /**
     * Increment each time the outbox dispatcher successfully publishes an event.
     *
     * @param service   service name (e.g. `sepa-payment`, `ledger`)
     * @param eventType the outbox entry's domain event type (e.g. `PARTY_ERASED`), **not** the
     *                  Kafka topic — every publisher in this fleet sends to one fixed topic per
     *                  service, so a `topic` tag would be constant per `service` and add nothing;
     *                  `eventType` is the actual per-row granularity this counter measures
     *                  (issue #5128 finding 1). If a service ever fans out to more than one topic,
     *                  add a separate `topic` parameter rather than overloading this one.
     */
    fun outboxDispatched(service: String, eventType: String) {
        counter("openbank.outbox.dispatched", "service", service, "event_type", eventType)
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

    /**
     * Register the **stuck payment saga** gauge: how many payment sagas have sat in a
     * non-terminal state (`PENDING` / `PROCESSING`) for longer than the service's stuck
     * threshold. A payment saga that wedges leaves money in a terminal-unknown state, so this
     * is the money-path signal `TransactionSagaStuck` (severity `critical`) pages on.
     *
     * **Registered eagerly, at startup, not on first non-zero reading.** A lazily created meter
     * publishes no series at all while the value is zero, and an absent series makes every
     * comparison in a rule match *nothing* rather than match zero — the alert would then be
     * silent in exactly the healthy-looking case it must distinguish from a dead scraper. Call
     * this once from a `@Startup` bean's `@PostConstruct`; re-registration with the same name is
     * a no-op, as for [registerOutboxBacklog].
     *
     * **What a fresh pod reports (the t=0 question).** `0` — a truthful healthy reading, because
     * "no saga is stuck" is genuinely what a pod with no observed stuck sagas knows. That is the
     * opposite of a sentinel like [java.time.Instant.EPOCH], which reads as a maximal *bad* value
     * at t=0 and fired `WorkflowLivenessStale` fleet-wide 15 minutes after every deploy (#2239).
     * Because the gauge only ever crosses `> 0` on a real observation, and the caller's supplier
     * is refreshed from the database on a schedule, a boot-time reading can under-report for one
     * refresh interval but can never over-report — the safe direction for a paging alert.
     *
     * @param stuck cheap, lock-free supplier of the current stuck-saga count (read from a cached
     *              value refreshed by a scheduled query — Micrometer samples this on the scrape
     *              thread and must not block on a reactive database call)
     */
    fun registerStuckPaymentSagas(stuck: () -> Number) {
        reg()?.let { r ->
            Gauge.builder(STUCK_PAYMENT_SAGAS, stuck) { it.invoke().toDouble() }
                .description("Payment sagas in a non-terminal state past the stuck threshold")
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

    // ── Workflow run duration (issue #6169) ────────────────────────────────────

    /**
     * Register the per-run DURATION instrument for a scheduled workflow: a timer the job records
     * once per run, and a gauge carrying the duration budget above which its mean run is degraded.
     *
     * **Why this exists next to [registerWorkflowLiveness] and not instead of it.** That primitive
     * answers *did it run*. This one answers *how long did it take*, and nothing in the fleet
     * answered it: the only duration signal for `agent-oversight-sweep` was
     * `traces_spanmetrics_latency_bucket`, whose top finite bucket is `5` — every sweep lands in
     * `(5s, +Inf]`, so `histogram_quantile(0.99, …)` returns exactly `5.00` forever and **no
     * threshold above 5s is expressible from that instrument at all** (#6169, measured in #6168).
     * A job that degrades from 6s to 300s is invisible there. Re-deriving a duration from traces is
     * the mistake; the job owning its own timer is the fix.
     *
     * **What the timer measures, stated exactly:** wall-clock elapsed *in this process*, from the
     * caller's start to its end, for the whole run — including anything it waits on. For a run that
     * makes an LLM call that is not the model's latency: it is this pod's view of the run, which is
     * what a scheduler alert wants (a run that overruns its slot overruns it regardless of which
     * hop was slow). Name it that way and no one mistakes it for an upstream SLI.
     *
     * **Both series exist from pod start, at zero.** The timer is registered here rather than
     * lazily on the first record, so an ABSENT `openbank_workflow_run_duration_seconds_count` means
     * "this workflow is not instrumented", not "it has never run" — the same distinction
     * [registerFeedFetch] documents for its five outcome counters. What a cold pod reports at t=0
     * is therefore `count = 0`, `sum = 0` and `budget = <declared>`, and the alert's own arithmetic
     * (`sum / count` over a window with no runs in it) yields no series rather than a `0` that
     * reads as "instant" or a breach that reads as "broken". A fresh pod is silent by construction,
     * with no `for:` needed to hide a boot-time value — which is the lesson `WorkflowLivenessStale`
     * cost (#2239, #4208): a metric whose t=0 value was never re-derived fired 15 minutes after
     * every deploy, on the control whose whole job is to make a dead scheduler visible.
     *
     * **No percentiles are published**, unlike [timer]. A 30-minute job puts ~4 observations into a
     * 2-hour window and a quantile over four samples is the maximum with extra steps — publishing
     * `{quantile="0.99"}` here would recreate the saturated number this primitive replaces. See
     * [WorkflowRunMetrics] for why `_max` is also not alertable.
     *
     * @param workflow  the SAME stable name passed to [registerWorkflowLiveness], so the two
     *                  signals join on one tag value and triage reads one workflow, not two.
     * @param budget    mean-run duration above which the run is degraded. Pick it against the
     *                  job's own cadence: a run approaching its period starts having the NEXT run
     *                  skipped (`ConcurrentExecution.SKIP`) with nothing logged, so the budget
     *                  belongs well below the period, not at it.
     */
    fun registerWorkflowRun(workflow: String, budget: Duration): WorkflowRunRecorder {
        val registry = reg() ?: return WorkflowRunRecorder(null, null)
        Gauge.builder(WorkflowRunMetrics.RUN_BUDGET_SECONDS) { budget.toSeconds().toDouble() }
            .description("Mean run duration above which this workflow is considered degraded")
            .tag(WorkflowRunMetrics.WORKFLOW_TAG, workflow)
            .strongReference(true)
            .register(registry)
        // Registered eagerly, both outcomes, so absence means "not instrumented" — see the KDoc.
        val timers = listOf(WorkflowRunMetrics.OUTCOME_SUCCESS, WorkflowRunMetrics.OUTCOME_FAILURE)
            .associateWith { outcome ->
                Timer.builder(WorkflowRunMetrics.RUN_DURATION)
                    .description("Wall-clock duration of one workflow run, as measured in this process")
                    .tag(WorkflowRunMetrics.WORKFLOW_TAG, workflow)
                    .tag(WorkflowRunMetrics.OUTCOME_TAG, outcome)
                    .register(registry)
            }
        return WorkflowRunRecorder(
            timers[WorkflowRunMetrics.OUTCOME_SUCCESS],
            timers[WorkflowRunMetrics.OUTCOME_FAILURE],
        )
    }

    // ── External-feed fetch outcome (ADR-0237 point 2, issue #4743) ─────────────

    /**
     * Register the fetch-outcome contract for an external feed, and with it the feed's own
     * freshness heartbeat.
     *
     * **Why a feed needs this on top of [registerWorkflowLiveness].** That primitive answers "did
     * the job run and finish without throwing". For a feed that is the wrong question, because a
     * fetch can succeed *as a job* while producing nothing usable: the ČNB fixing URL was a 404 for
     * 46 days while the downstream revaluation kept logging "no movement" (#2204). Worse, the
     * quietest failure raises no error at all — a feed that answers 200 with a well-formed document
     * containing none of the rows we asked for is, under a run/no-run heartbeat, **identical to a
     * healthy one**. [FeedFetchOutcome] enumerates the four ways a fetch ends; this method is what
     * makes them observable.
     *
     * **This sits beside the workflow heartbeat, it does not replace it.** Two registrations, two
     * `workflow` tag values, alerting independently — ADR-0237 point 2's design, unchanged:
     *
     *  - the caller keeps its own `registerWorkflowLiveness("<job-name>", …)` — *the scheduler ran*;
     *  - this method registers `registerWorkflowLiveness("feed-<feed>", …)` — *the feed delivered*,
     *    advanced **only** on [FeedFetchOutcome.FETCHED].
     *
     * The two disagreeing is the diagnosis: job fresh + feed stale means the scheduler is running
     * fine against a dead upstream, which is exactly the shape that went unnoticed for 46 days.
     *
     * Reusing the liveness primitive rather than inventing a second gauge family is deliberate and
     * buys three things already built and already argued: the existing `WorkflowLivenessStale`
     * PrometheusRule covers feed freshness with no new rule or threshold; the control-liveness
     * sentinel (ADR-0163) correlates it with everything else; and the age gauge is **seeded at
     * registration**, so a fresh pod reads its own uptime rather than the ~1.8e9 seconds that made
     * an alert fire 15 minutes after every deploy (#4208).
     *
     * **What a cold pod reads at t=0**, before any fetch has happened — a boot reading is a fourth
     * state beside healthy/degraded/absent and is worth stating rather than inferring:
     *
     *  - `openbank_workflow_last_success_age_seconds{workflow="feed-<feed>"}` ≈ *pod uptime in
     *    seconds*, so it cannot cross `2 * expectedInterval` until a genuine grace period has
     *    elapsed. Never decades.
     *  - `openbank_workflow_success_recorded{workflow="feed-<feed>"}` = `0` — "this pod has not seen
     *    this feed deliver", which triage reads and the alert deliberately does not.
     *  - `openbank_feed_fetch_total{feed="<feed>",outcome="…"}` = `0` for **every** outcome, present
     *    and zero rather than absent (see below).
     *
     * Every [FeedFetchOutcome] counter is created at registration so a feed that has never failed
     * still publishes `outcome="http_error"` at 0. A counter created lazily on first increment makes
     * "this never happened" and "this was never instrumented" the same empty vector, and a triage
     * query cannot tell them apart — the #2187 shape, where a consumer that could only ever report
     * "nothing wrong" read as reassurance.
     *
     * Call **once at startup**, then [FeedFetchRecorder.record] on every fetch attempt including the
     * failed ones — a recorder that is only called on the happy path measures traffic, not health.
     * A no-op recorder is returned when no [MeterRegistry] is resolvable, matching every method
     * above.
     *
     * @param feed              stable low-cardinality feed name, e.g. `cnb-daily-fixing` — the same
     *                          name the feed is declared under in `check-external-feeds.py`
     * @param expectedInterval  the feed's publication cadence; freshness alerts at 2x this
     */
    fun registerFeedFetch(feed: String, expectedInterval: Duration): FeedFetchRecorder {
        val freshness = registerWorkflowLiveness(FeedFetchMetrics.freshnessWorkflow(feed), expectedInterval)
        val counters = FeedFetchOutcome.entries.associateWith { outcome ->
            reg()?.let { r ->
                Counter.builder(FeedFetchMetrics.FETCH_TOTAL)
                    .tag(FeedFetchMetrics.FEED_TAG, feed)
                    .tag(FeedFetchMetrics.OUTCOME_TAG, outcome.name.lowercase())
                    .register(r)
            }
        }
        return FeedFetchRecorder(freshness, counters)
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
/**
 * Handle returned by [DomainMetrics.registerFeedFetch]; call [record] once per fetch **attempt**,
 * whatever the outcome.
 *
 * **The one invariant worth having a class for.** Freshness and outcome cannot be recorded
 * separately: [record] both increments the outcome counter and advances the freshness heartbeat, and
 * it advances the heartbeat **iff** the outcome is [FeedFetchOutcome.FETCHED]. So a caller cannot
 * mark a feed fresh without saying what it fetched, and cannot report a successful fetch that leaves
 * the feed looking stale. The two signals are physically unable to drift apart, which is the failure
 * mode this whole mechanism exists to catch — a heartbeat that says green about a feed that stopped
 * delivering.
 *
 * That is also why [FeedFetchOutcome.EMPTY] is a value here and not a `success` boolean set to
 * `true`. `PushResult.skipped()` carried `success = true`, so pushes that never left the process were
 * counted as delivered and the row committed `SENT` (ADR-0252 phase 0, #4348). The same shape applied
 * to a feed reads: fetched on schedule, produced nothing, every time, and no error anywhere.
 *
 * Not a CDI bean — a plain value object held by whatever owns the feed.
 */
class FeedFetchRecorder internal constructor(
    private val freshness: WorkflowLivenessRecorder,
    private val counters: Map<FeedFetchOutcome, Counter?>,
) {
    /**
     * Record one fetch attempt. Advances the feed's freshness heartbeat only for
     * [FeedFetchOutcome.FETCHED] — every other outcome lets the age gauge keep growing, which is
     * what eventually raises `WorkflowLivenessStale` for the feed while the job's own heartbeat
     * stays green.
     */
    fun record(outcome: FeedFetchOutcome) {
        counters[outcome]?.increment()
        if (outcome == FeedFetchOutcome.FETCHED) freshness.recordSuccess()
    }
}

/**
 * Handle returned by [DomainMetrics.registerWorkflowRun]; call [record] exactly once per run,
 * whatever the outcome — a run that threw still consumed wall-clock, and a job that fails slowly is
 * the case a duration alert exists for.
 *
 * Not a CDI bean — a plain value object held by the scheduled job that registered it, exactly like
 * [WorkflowLivenessRecorder]. Both timers are null when no [io.micrometer.core.instrument.MeterRegistry]
 * was resolvable, and [record] is then a silent no-op (the fleet-wide fallback of every method on
 * [DomainMetrics]).
 */
class WorkflowRunRecorder internal constructor(private val success: Timer?, private val failure: Timer?) {
    /**
     * Record one run.
     *
     * @param elapsed  wall-clock measured around the run IN THIS PROCESS — not an upstream latency.
     * @param succeeded false when the run threw; the sample is still recorded, under
     *                  `outcome="failure"`, so a fail-fast run cannot silently pull the mean down
     *                  without being separable at triage time.
     */
    fun record(elapsed: Duration, succeeded: Boolean = true) {
        (if (succeeded) success else failure)?.record(elapsed)
    }
}

class WorkflowLivenessRecorder internal constructor(
    private val lastSuccessEpochMillis: java.util.concurrent.atomic.AtomicLong,
    private val successRecorded: java.util.concurrent.atomic.AtomicLong,
) {
    fun recordSuccess() {
        lastSuccessEpochMillis.set(java.time.Instant.now().toEpochMilli())
        successRecorded.set(1)
    }
}
