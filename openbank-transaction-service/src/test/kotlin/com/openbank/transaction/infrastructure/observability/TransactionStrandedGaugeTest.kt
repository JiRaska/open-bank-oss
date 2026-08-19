// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.transaction.infrastructure.observability

import com.openbank.transaction.application.port.out.StrandedSagaQueryPort
import com.openbank.transaction.domain.model.TransactionStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit cover for the #5733 stranded-saga gauges.
 *
 * A wedged saga is invisible to every other signal this service has — it answered 2xx, emitted no
 * error span, took normal latency and sits on a healthy pod — so the ONLY thing separating it from
 * a healthy one is age. These assertions are therefore on the age arithmetic and the empty-state
 * behaviour, never on "a gauge was registered": the alert this feeds
 * (`TransactionSagaStuck`, severity critical) spent its whole life registered and unable to fire,
 * which is precisely the failure a registration-only assertion would have agreed with.
 */
class TransactionStrandedGaugeTest {

    private val now: Instant = Instant.parse("2026-08-19T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    /** Repository stub: only the two methods the gauge uses are meaningful.
     *
     * Mutable, so a single gauge instance can be ticked across a CHANGING database. Handing the
     * second state to a second gauge object would not exercise anything: the registry holds a
     * strong reference to the first instance's AtomicLong, so the new object's refresh updates a
     * holder nothing reads — a test that passes against a production bug it cannot see. */
    private class StubRepository(
        var counts: Map<TransactionStatus, Long>,
        var oldest: Map<TransactionStatus, Instant>,
    ) : StrandedSagaQueryPort {
        override suspend fun countByStatus(status: TransactionStatus): Long = counts[status] ?: 0L
        override suspend fun oldestInitiatedAt(status: TransactionStatus): Instant? = oldest[status]
    }

    private fun gaugeValue(registry: SimpleMeterRegistry, name: String, status: TransactionStatus): Double =
        registry.get(name).tag("service", "transaction").tag("status", status.name).gauge().value()

    private fun newGauge(repo: StrandedSagaQueryPort, registry: SimpleMeterRegistry) =
        TransactionStrandedGauge(repo, registry, clock, domainMetrics = mockk(relaxed = true))

    @Test
    fun `publishes the age of the oldest saga in each non-terminal state`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val repo = StubRepository(
            counts = mapOf(TransactionStatus.PENDING to 4L, TransactionStatus.PROCESSING to 2L),
            oldest = mapOf(
                TransactionStatus.PENDING to now.minus(Duration.ofHours(3)),
                TransactionStatus.PROCESSING to now.minus(Duration.ofMinutes(7)),
            ),
        )
        val gauge = newGauge(repo, registry)
        gauge.register()

        gauge.refresh()

        assertThat(gaugeValue(registry, "openbank.transactions.non_terminal", TransactionStatus.PENDING))
            .isEqualTo(4.0)
        assertThat(gaugeValue(registry, "openbank.transactions.non_terminal", TransactionStatus.PROCESSING))
            .isEqualTo(2.0)
        // The age is the whole signal: 3h and 7m must be distinguishable, so this asserts the
        // arithmetic, not merely that the series is non-zero.
        assertThat(
            gaugeValue(registry, "openbank.transactions.non_terminal.oldest.age.seconds", TransactionStatus.PENDING),
        ).isEqualTo(Duration.ofHours(3).seconds.toDouble())
        assertThat(
            gaugeValue(registry, "openbank.transactions.non_terminal.oldest.age.seconds", TransactionStatus.PROCESSING),
        ).isEqualTo(Duration.ofMinutes(7).seconds.toDouble())
    }

    @Test
    fun `an empty state reports age zero, and does not keep the previous incident's age`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        // One gauge, one registry, a database that CHANGES between ticks — which is the only
        // arrangement that exercises the cached holder the registry actually reads.
        val repo = StubRepository(
            counts = mapOf(TransactionStatus.PENDING to 1L),
            oldest = mapOf(TransactionStatus.PENDING to now.minus(Duration.ofDays(2))),
        )
        val gauge = newGauge(repo, registry)
        gauge.register()
        gauge.refresh()
        assertThat(
            gaugeValue(registry, "openbank.transactions.non_terminal.oldest.age.seconds", TransactionStatus.PENDING),
        ).isEqualTo(Duration.ofDays(2).seconds.toDouble())

        // The stuck saga clears: the repository now reports nothing in that state.
        repo.counts = emptyMap()
        repo.oldest = emptyMap()
        gauge.refresh()

        // Falsifying assertion: a wiring that leaves the cached AtomicLong untouched when the query
        // returns null would still read two days here, and `TransactionSagaStuck` would then fire
        // forever after a single resolved incident — alert fatigue on the one control that exists
        // to be believed.
        assertThat(
            gaugeValue(registry, "openbank.transactions.non_terminal.oldest.age.seconds", TransactionStatus.PENDING),
        ).isEqualTo(0.0)
        assertThat(gaugeValue(registry, "openbank.transactions.non_terminal", TransactionStatus.PENDING))
            .isEqualTo(0.0)
    }

    @Test
    fun `terminal states are never published — their age only grows`() {
        val registry = SimpleMeterRegistry()
        newGauge(StubRepository(counts = emptyMap(), oldest = emptyMap()), registry).register()

        // COMPLETED/FAILED/REVERSED are outcomes, not backlog. Publishing them would produce a
        // series that rises forever and an alert nobody can ever clear.
        listOf(TransactionStatus.COMPLETED, TransactionStatus.FAILED, TransactionStatus.REVERSED).forEach { st ->
            assertThat(
                registry.find("openbank.transactions.non_terminal").tag("status", st.name).gauge(),
            ).describedAs("terminal status %s must not be published", st).isNull()
        }
        assertThat(TransactionStrandedGauge.NON_TERMINAL)
            .containsExactly(TransactionStatus.PENDING, TransactionStatus.PROCESSING)
    }
}
