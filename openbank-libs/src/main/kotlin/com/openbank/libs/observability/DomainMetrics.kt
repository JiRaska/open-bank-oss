// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
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
}
