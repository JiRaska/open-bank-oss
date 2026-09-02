// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.settlement.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.SettlementStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit cover for the #5705 stranded-settlement gauges.
 *
 * The point of the metric is that a settlement whose saga stopped advancing is invisible to every
 * other signal — the originating request answered 2xx in milliseconds, the pod is healthy, and no
 * error is thrown anywhere — so the ONLY thing that distinguishes it from a healthy settlement is
 * age. These assertions are therefore on the age arithmetic, on the empty-state behaviour, and on
 * the exact PROMETHEUS-RENDERED series names the alert rules read; not on "a gauge was registered".
 */
class SettlementStrandedGaugeTest {

    private val now: Instant = Instant.parse("2026-08-20T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private class StubRepository(
        private val counts: Map<SettlementStatus, Long>,
        private val oldest: Map<SettlementStatus, Instant>,
    ) : SettlementRepository {
        override suspend fun countByStatus(status: SettlementStatus): Long = counts[status] ?: 0L
        override suspend fun oldestCreatedAt(status: SettlementStatus): Instant? = oldest[status]

        override suspend fun create(settlement: com.openbank.settlement.domain.model.Settlement) = error("unused")
        override suspend fun findById(id: UUID) = error("unused")
        override suspend fun updateStatus(id: UUID, status: SettlementStatus) = error("unused")
        override suspend fun claimForProcessing(id: UUID): Boolean = error("unused")
    }

    private fun gaugeValue(registry: SimpleMeterRegistry, name: String, status: SettlementStatus): Double =
        registry.get(name).tag("service", "settlement").tag("status", status.name).gauge().value()

    private fun newGauge(repo: SettlementRepository, registry: io.micrometer.core.instrument.MeterRegistry?) =
        SettlementStrandedGauge(repo, registry, clock, domainMetrics = mockk<DomainMetrics>(relaxed = true))

    @Test
    fun `publishes the age of the oldest settlement in each non-terminal state`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val repo = StubRepository(
            counts = mapOf(SettlementStatus.DEBITED to 4L, SettlementStatus.PENDING to 1L),
            oldest = mapOf(
                // DEBITED is the money-path failure: the payer is debited, the payee is not
                // credited, and the funds are in neither account.
                SettlementStatus.DEBITED to now.minus(Duration.ofHours(9)),
                SettlementStatus.PENDING to now.minus(Duration.ofMinutes(5)),
            ),
        )
        val gauge = newGauge(repo, registry)
        gauge.register()

        gauge.refresh()

        assertThat(gaugeValue(registry, SettlementStrandedGauge.NON_TERMINAL_METRIC, SettlementStatus.DEBITED))
            .isEqualTo(4.0)
        assertThat(gaugeValue(registry, SettlementStrandedGauge.OLDEST_AGE_METRIC, SettlementStatus.DEBITED))
            .describedAs("9h must cross the 10800s SettlementStrandedMidSaga threshold, not merely be non-zero")
            .isEqualTo(Duration.ofHours(9).seconds.toDouble())
        assertThat(gaugeValue(registry, SettlementStrandedGauge.OLDEST_AGE_METRIC, SettlementStatus.PENDING))
            .isEqualTo(300.0)
    }

    /**
     * An emptied state must report 0, not the last age it saw. Holding the previous value would
     * keep the alert firing after the settlements cleared — which is how an alert earns being
     * ignored, and this issue exists because a signal nobody trusts is the same as no signal.
     */
    @Test
    fun `an emptied state reports zero rather than a stale age`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        var oldest = mapOf(SettlementStatus.CREDITED to now.minus(Duration.ofHours(6)))
        val repo = object : SettlementRepository by StubRepository(emptyMap(), emptyMap()) {
            override suspend fun countByStatus(status: SettlementStatus) = if (oldest.containsKey(status)) 1L else 0L
            override suspend fun oldestCreatedAt(status: SettlementStatus) = oldest[status]
        }
        val gauge = newGauge(repo, registry)
        gauge.register()

        gauge.refresh()
        assertThat(gaugeValue(registry, SettlementStrandedGauge.OLDEST_AGE_METRIC, SettlementStatus.CREDITED))
            .isEqualTo(21600.0)

        oldest = emptyMap()
        gauge.refresh()

        assertThat(gaugeValue(registry, SettlementStrandedGauge.OLDEST_AGE_METRIC, SettlementStatus.CREDITED)).isZero()
        assertThat(gaugeValue(registry, SettlementStrandedGauge.NON_TERMINAL_METRIC, SettlementStatus.CREDITED))
            .isZero()
    }

    /** Terminal states must not be published — their age only grows and would alert forever. */
    @Test
    fun `terminal states are not published and every compensation state is`() {
        val registry = SimpleMeterRegistry()
        newGauge(StubRepository(emptyMap(), emptyMap()), registry).register()

        val published = registry.meters
            .filter { it.id.name == SettlementStrandedGauge.NON_TERMINAL_METRIC }
            .mapNotNull { it.id.getTag("status") }
            .toSet()

        assertThat(published).containsExactlyInAnyOrder(
            "PENDING",
            "DEBITED",
            "CREDITED",
            "REVERSED",
            "CREDITED_REVERSED",
            "LEDGER_REVERSED",
            // #6037's two new outcomes. REVERSAL_FAILED is the one that matters: the money moved
            // and did NOT come back, and it was the only money-path state with no age series.
            "REVERSAL_FAILED",
            "LEDGER_REVERSAL_UNSUPPORTED",
        )
        assertThat(published).doesNotContain("BOOKED", "REJECTED")
        // The published set must be exactly "every status minus the two terminal ones", derived
        // from the enum rather than restated — a status added later must not silently go unwatched.
        assertThat(published).isEqualTo(
            (SettlementStatus.entries.map { it.name } - setOf("BOOKED", "REJECTED")).toSet(),
        )
    }

    /**
     * The cold-pod contract, asserted on the exact strings the PrometheusRule reads.
     *
     * Registration alone puts every series at 0, so a `> threshold` rule cannot fire during or
     * after a deploy, and `absent()` over these names means "the pod is not exporting" rather than
     * "no settlements". Asserting the RENDERED text is the point: the rule spells
     * `openbank_settlement_non_terminal_oldest_age_seconds`, the code spells
     * `openbank.settlement.non_terminal.oldest.age.seconds`, and nothing else in CI checks that
     * those two are the same series (#5733 shipped a critical money-path alert that were not).
     */
    @Test
    fun `renders the exact series the alert rules read, at zero, before any refresh`() {
        val prometheus = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        newGauge(StubRepository(emptyMap(), emptyMap()), prometheus).register()

        val scraped = prometheus.scrape()

        assertThat(scraped).contains(
            """openbank_settlement_non_terminal_oldest_age_seconds{service="settlement",status="DEBITED"} 0.0""",
        )
        assertThat(scraped).contains(
            """openbank_settlement_non_terminal{service="settlement",status="PENDING"} 0.0""",
        )
    }

    /** A missing Prometheus registry must not break startup — the gauge is optional telemetry. */
    @Test
    fun `registers nothing and does not throw when no registry is present`(): Unit = runBlocking {
        val gauge = newGauge(StubRepository(emptyMap(), emptyMap()), null)
        gauge.register()
        gauge.refresh()
    }
}
