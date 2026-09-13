// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.observability

import com.openbank.settlement.application.port.out.OriginationOutcome
import com.openbank.settlement.application.port.out.SettlementMetricsPort
import com.openbank.settlement.application.port.out.SettlementStep
import com.openbank.settlement.application.port.out.SettlementStepOutcome
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.math.BigDecimal
import java.time.Duration

/**
 * Micrometer adapter for [SettlementMetricsPort] (issue #5705). Emits, all tagged
 * `service="settlement"`:
 *
 *  - `openbank_settlement_originated_total{currency,outcome}` — acceptance rate, split
 *    `created` / `replayed`.
 *  - `openbank_settlement_saga_steps_total{step,outcome}` — every activity attempt of the saga,
 *    forward legs and compensations, `completed` / `failed`.
 *  - `openbank_settlement_terminal_total{outcome}` — settlements that reached `booked` or
 *    `rejected`. This is the series the "nothing has settled" alert reads.
 *  - `openbank_settlement_cycle_duration_seconds{outcome}` — origination → terminal latency.
 *  - `openbank_settlement_booked_amount{currency}` — amount distribution of booked settlements.
 *
 * ### Why the counters are registered eagerly, and why that is the point
 *
 * Micrometer does not create a counter until its first increment, so
 * `increase(openbank_settlement_terminal_total{outcome="booked"}[6h]) == 0` written against a
 * lazily-created counter matches **nothing at all** on a service that has never booked anything —
 * which is exactly the state the alert exists to catch. Every series an alert reads is therefore
 * bound in [bindTo] at `@PostConstruct`, present at `0.0` from the first scrape. `@Startup` because
 * `@ApplicationScoped` is lazy: without it the bean, and so the meters, would not exist until the
 * first settlement request.
 *
 * The per-currency amount summary is the one meter left to lazy registration: its label set is not
 * knowable at boot, and no alert reads it.
 *
 * Service-local `MeterRegistry` via [Instance] exactly like libs `DomainMetrics` and the fleet's
 * other per-service adapters: settlement-saga shape is settlement-specific, so adding it to the
 * shared libs facade would force a fleet-wide rebuild for a one-service concern.
 *
 * Field injection of `Instance<MeterRegistry>` rather than a nullable constructor parameter: a
 * nullable parameter needs a second `@Inject` constructor, and ArC registers no bean at all when it
 * sees two plain constructors.
 */
@Startup
@ApplicationScoped
class SettlementMetricsAdapter : SettlementMetricsPort {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private var registry: MeterRegistry? = null

    @PostConstruct
    fun register() {
        if (registryInstance.isResolvable) bindTo(registryInstance.get())
    }

    /**
     * Bind the meters to [registry]. Called once at startup by [register]; exposed so a test can
     * bind a real Prometheus registry and assert the rendered series names and label sets, rather
     * than assuming how Micrometer maps them.
     */
    fun bindTo(registry: MeterRegistry) {
        this.registry = registry
        // Everything an alert expression reads is created here, before any traffic.
        SettlementStep.entries.forEach { step ->
            SettlementStepOutcome.entries.forEach { outcome -> sagaStepCounter(registry, step, outcome) }
        }
        TERMINAL_OUTCOMES.forEach { outcome ->
            terminalCounter(registry, outcome)
            cycleTimer(registry, outcome)
        }
    }

    override fun settlementOriginated(currency: String, outcome: OriginationOutcome) {
        registry?.let { r ->
            Counter.builder(ORIGINATED_METRIC)
                .tag("service", SERVICE)
                .tag("currency", currency)
                .tag("outcome", outcome.name.lowercase())
                .description("Settlement origination requests accepted, split new vs idempotent replay")
                .register(r)
                .increment()
        }
    }

    override fun sagaStep(step: SettlementStep, outcome: SettlementStepOutcome) {
        registry?.let { r -> sagaStepCounter(r, step, outcome).increment() }
    }

    override fun settlementBooked(currency: String, amount: BigDecimal, cycleDuration: Duration) {
        registry?.let { r ->
            terminalCounter(r, OUTCOME_BOOKED).increment()
            cycleTimer(r, OUTCOME_BOOKED).record(cycleDuration.coerceAtLeast(Duration.ZERO))
            DistributionSummary.builder(BOOKED_AMOUNT_METRIC)
                .tag("service", SERVICE)
                .tag("currency", currency)
                .publishPercentiles(P50, P95, P99)
                .publishPercentileHistogram()
                .description("Amount of settlements booked to the ledger")
                .register(r)
                .record(amount.toDouble())
        }
    }

    override fun settlementRejected(currency: String, cycleDuration: Duration) {
        registry?.let { r ->
            terminalCounter(r, OUTCOME_REJECTED).increment()
            cycleTimer(r, OUTCOME_REJECTED).record(cycleDuration.coerceAtLeast(Duration.ZERO))
        }
    }

    private fun sagaStepCounter(
        registry: MeterRegistry,
        step: SettlementStep,
        outcome: SettlementStepOutcome,
    ): Counter = Counter.builder(SAGA_STEPS_METRIC)
        .tag("service", SERVICE)
        .tag("step", step.name.lowercase())
        .tag("outcome", outcome.name.lowercase())
        .description("Settlement saga activity attempts, forward legs and compensations")
        .register(registry)

    private fun terminalCounter(registry: MeterRegistry, outcome: String): Counter = Counter.builder(TERMINAL_METRIC)
        .tag("service", SERVICE)
        .tag("outcome", outcome)
        .description("Settlements that reached a terminal state")
        .register(registry)

    private fun cycleTimer(registry: MeterRegistry, outcome: String): Timer = Timer.builder(CYCLE_DURATION_METRIC)
        .tag("service", SERVICE)
        .tag("outcome", outcome)
        .publishPercentiles(P50, P95, P99)
        .publishPercentileHistogram()
        .description("Time from settlement origination to its terminal state")
        .register(registry)

    companion object {
        const val SERVICE = "settlement"

        const val ORIGINATED_METRIC = "openbank.settlement.originated"
        const val SAGA_STEPS_METRIC = "openbank.settlement.saga.steps"
        const val TERMINAL_METRIC = "openbank.settlement.terminal"
        const val CYCLE_DURATION_METRIC = "openbank.settlement.cycle.duration"
        const val BOOKED_AMOUNT_METRIC = "openbank.settlement.booked.amount"

        const val OUTCOME_BOOKED = "booked"
        const val OUTCOME_REJECTED = "rejected"

        private val TERMINAL_OUTCOMES = listOf(OUTCOME_BOOKED, OUTCOME_REJECTED)

        // The fleet-standard percentile set (libs DomainMetrics publishes the same three).
        // Declared as constants: detekt MagicNumber fires on each literal in publishPercentiles.
        private const val P50 = 0.5
        private const val P95 = 0.95
        private const val P99 = 0.99
    }
}
