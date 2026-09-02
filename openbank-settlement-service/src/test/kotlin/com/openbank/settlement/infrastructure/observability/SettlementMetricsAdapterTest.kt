// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.observability

import com.openbank.settlement.application.port.out.OriginationOutcome
import com.openbank.settlement.application.port.out.SettlementStep
import com.openbank.settlement.application.port.out.SettlementStepOutcome
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Asserts the adapter against a **real** [PrometheusMeterRegistry] and, for the alert-bearing
 * series, against the rendered scrape text — not against a mock and not against Micrometer's
 * in-memory meter names. The alert expressions in
 * `openbank-infra/gitops/components/payments/prometheus-rules.yaml` (the
 * `openbank-settlement-alerts` document) are written over the
 * *scraped* names (`openbank_settlement_terminal_total`), and only the rendered output can show
 * that Micrometer's dot-to-underscore mapping plus the `_total` suffix produce exactly those.
 *
 * The load-bearing case is [`the alert-bearing series exist at zero before any settlement happens`]:
 * Micrometer creates a counter on first increment, so a lazily-registered
 * `openbank_settlement_terminal_total{outcome="booked"}` is **absent** — not zero — on a service
 * that has never booked anything, and `increase(...[6h]) == 0` then matches nothing at all. That is
 * precisely the state the alert exists to catch, so a lazy counter would make the alert silent in
 * the only case it is for. Deleting the `bindTo` body must turn that test red.
 */
class SettlementMetricsAdapterTest {

    private val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val adapter = SettlementMetricsAdapter().apply { bindTo(registry) }

    private fun counter(name: String, vararg tags: Pair<String, String>): Double? = registry.find(name)
        .let { search -> tags.fold(search) { s, (k, v) -> s.tag(k, v) } }
        .counter()
        ?.count()

    @Test
    fun `the alert-bearing series exist at zero before any settlement happens`() {
        // Rendered scrape output, i.e. what Prometheus would actually store and the alert query.
        val scrape = registry.scrape()

        assertThat(scrape)
            .contains("openbank_settlement_terminal_total")
            .contains("""openbank_settlement_terminal_total{outcome="booked",service="settlement"} 0.0""")
            .contains("""openbank_settlement_terminal_total{outcome="rejected",service="settlement"} 0.0""")

        // Every saga step/outcome combination too — a `failed` series that only appears once
        // something has already failed cannot support a "failures are climbing" query either.
        SettlementStep.entries.forEach { step ->
            SettlementStepOutcome.entries.forEach { outcome ->
                assertThat(
                    counter(
                        SettlementMetricsAdapter.SAGA_STEPS_METRIC,
                        "step" to step.name.lowercase(),
                        "outcome" to outcome.name.lowercase(),
                    ),
                ).describedAs("saga step %s/%s must be registered eagerly", step, outcome)
                    .isEqualTo(0.0)
            }
        }
    }

    @Test
    fun `every series the settlement alerts read renders under the name the alert uses`() {
        // The alert expressions in gitops/components/payments/prometheus-rules.yaml are written over
        // these exact strings. Micrometer's dot->underscore mapping and the `_total` suffix on a
        // counter are the things that could silently drift, and a rule referencing a name that
        // renders differently is a rule that matches nothing while looking perfectly correct.
        adapter.settlementOriginated("CZK", OriginationOutcome.CREATED)
        adapter.settlementBooked("CZK", BigDecimal.ONE, Duration.ofSeconds(1))

        val scrape = registry.scrape()

        // SettlementSagaNotCompleting reads these two.
        assertThat(scrape).contains("openbank_settlement_originated_total")
        assertThat(scrape).contains("openbank_settlement_terminal_total")
        // SettlementSagaStepsFailing reads this one.
        assertThat(scrape).contains("openbank_settlement_saga_steps_total")
        // And the outcome tag values the expressions filter on must render as written.
        assertThat(scrape).contains("""outcome="created"""")
        assertThat(scrape).contains("""outcome="booked"""")
        assertThat(scrape).contains("""outcome="rejected"""")
        assertThat(scrape).contains("""outcome="failed"""")
    }

    @Test
    fun `booking records the terminal counter, the cycle timer and the amount summary`() {
        adapter.settlementBooked("CZK", BigDecimal("250.00"), Duration.ofSeconds(12))

        assertThat(counter(SettlementMetricsAdapter.TERMINAL_METRIC, "outcome" to "booked")).isEqualTo(1.0)
        // Negative control: booking must not move the rejected leg.
        assertThat(counter(SettlementMetricsAdapter.TERMINAL_METRIC, "outcome" to "rejected")).isEqualTo(0.0)

        val timer = registry.find(SettlementMetricsAdapter.CYCLE_DURATION_METRIC).tag("outcome", "booked").timer()
        assertThat(timer).isNotNull
        assertThat(timer!!.count()).isEqualTo(1L)
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(12.0)

        val amounts = registry.find(SettlementMetricsAdapter.BOOKED_AMOUNT_METRIC).tag("currency", "CZK").summary()
        assertThat(amounts).isNotNull
        assertThat(amounts!!.totalAmount()).isEqualTo(250.0)
    }

    @Test
    fun `rejection records the rejected terminal counter and its own cycle timer`() {
        adapter.settlementRejected("EUR", Duration.ofSeconds(3))

        assertThat(counter(SettlementMetricsAdapter.TERMINAL_METRIC, "outcome" to "rejected")).isEqualTo(1.0)
        assertThat(counter(SettlementMetricsAdapter.TERMINAL_METRIC, "outcome" to "booked")).isEqualTo(0.0)
        assertThat(
            registry.find(SettlementMetricsAdapter.CYCLE_DURATION_METRIC).tag("outcome", "rejected").timer()?.count(),
        ).isEqualTo(1L)
    }

    @Test
    fun `a negative cycle duration is clamped to zero rather than corrupting the timer`() {
        // createdAt/updatedAt come from two different rows' clocks; a small backwards skew is
        // possible and Micrometer would otherwise record a negative sample.
        adapter.settlementBooked("CZK", BigDecimal.ONE, Duration.ofSeconds(-5))

        val timer = registry.find(SettlementMetricsAdapter.CYCLE_DURATION_METRIC).tag("outcome", "booked").timer()
        assertThat(timer!!.totalTime(TimeUnit.SECONDS)).isEqualTo(0.0)
    }

    @Test
    fun `origination separates a created row from an idempotent replay`() {
        adapter.settlementOriginated("CZK", OriginationOutcome.CREATED)
        adapter.settlementOriginated("CZK", OriginationOutcome.REPLAYED)
        adapter.settlementOriginated("CZK", OriginationOutcome.REPLAYED)

        assertThat(counter(SettlementMetricsAdapter.ORIGINATED_METRIC, "outcome" to "created")).isEqualTo(1.0)
        assertThat(counter(SettlementMetricsAdapter.ORIGINATED_METRIC, "outcome" to "replayed")).isEqualTo(2.0)
    }

    @Test
    fun `an unbound adapter records nothing and throws nothing`() {
        // No MeterRegistry bean resolvable (a profile with micrometer disabled): the adapter must
        // stay inert rather than NPE on the money path.
        val unbound = SettlementMetricsAdapter()

        unbound.settlementBooked("CZK", BigDecimal.ONE, Duration.ofSeconds(1))
        unbound.settlementRejected("CZK", Duration.ofSeconds(1))
        unbound.settlementOriginated("CZK", OriginationOutcome.CREATED)
        unbound.sagaStep(SettlementStep.DEBIT, SettlementStepOutcome.COMPLETED)

        // And nothing leaked into the bound registry either.
        assertThat(counter(SettlementMetricsAdapter.TERMINAL_METRIC, "outcome" to "booked")).isEqualTo(0.0)
        assertThat(counter(SettlementMetricsAdapter.TERMINAL_METRIC, "outcome" to "rejected")).isEqualTo(0.0)
        assertThat(counter(SettlementMetricsAdapter.ORIGINATED_METRIC, "outcome" to "created")).isNull()
    }
}
