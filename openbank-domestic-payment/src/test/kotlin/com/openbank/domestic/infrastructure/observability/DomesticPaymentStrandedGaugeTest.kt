// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.infrastructure.observability

import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
 * Unit cover for the #3273 stranded-payment gauges.
 *
 * The point of the metric is that a stranded payment is invisible to every other signal — it
 * answered 2xx, emitted no error, and its pod is healthy — so the ONLY thing that can distinguish
 * it from a healthy payment is age. These assertions are therefore on the age arithmetic and on the
 * empty-state behaviour, not on "a gauge was registered".
 */
class DomesticPaymentStrandedGaugeTest {

    private val now: Instant = Instant.parse("2026-08-02T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    /** Repository stub: only the two methods the gauge uses are meaningful. */
    private class StubRepository(
        private val counts: Map<DomesticPaymentStatus, Long>,
        private val oldest: Map<DomesticPaymentStatus, Instant>,
    ) : DomesticPaymentRepository {
        override suspend fun countByStatus(status: DomesticPaymentStatus): Long = counts[status] ?: 0L
        override suspend fun oldestCreatedAt(status: DomesticPaymentStatus): Instant? = oldest[status]

        override suspend fun save(payment: DomesticPayment, outboxMessage: OutboxMessage) = error("unused")
        override suspend fun saveDelegated(
            payment: DomesticPayment,
            outboxMessage: OutboxMessage,
            boundAt: Instant,
            debitOwnerPartyId: UUID,
        ) = error("unused")
        override suspend fun findById(paymentId: UUID) = error("unused")
        override suspend fun findByIdempotencyKey(idempotencyKey: String) = error("unused")
        override suspend fun list(status: DomesticPaymentStatus?, debtorAccountId: UUID?, limit: Int, offset: Int) =
            error("unused")
        override suspend fun update(payment: DomesticPayment, outboxMessage: OutboxMessage) = error("unused")
        override suspend fun findRedrivable(maxAttempts: Int, minAge: Instant, limit: Int) = error("unused")
        override suspend fun recordRedriveAttempt(paymentId: UUID) = error("unused")
        override suspend fun claimSchemeDispatch(paymentId: UUID, dispatchedAt: Instant): Boolean = error("unused")
        override suspend fun clearSchemeDispatch(paymentId: UUID) = error("unused")
    }

    private fun gaugeValue(registry: SimpleMeterRegistry, name: String, status: DomesticPaymentStatus): Double =
        registry.get(name).tag("service", "domestic").tag("status", status.name).gauge().value()

    @Test
    fun `publishes the age of the oldest payment in each non-terminal state`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val repo = StubRepository(
            counts = mapOf(DomesticPaymentStatus.RECEIVED to 7L, DomesticPaymentStatus.VALIDATED to 3L),
            oldest = mapOf(
                // Six weeks, the real age of the oldest stranded payment that prompted #3273.
                DomesticPaymentStatus.RECEIVED to now.minus(Duration.ofDays(42)),
                DomesticPaymentStatus.VALIDATED to now.minus(Duration.ofMinutes(5)),
            ),
        )
        val gauge = DomesticPaymentStrandedGauge(repo, registry, clock, domainMetrics = mockk(relaxed = true))
        gauge.register()

        gauge.refresh()

        assertThat(gaugeValue(registry, "openbank.domestic.payments.non_terminal", DomesticPaymentStatus.RECEIVED))
            .isEqualTo(7.0)
        assertThat(
            gaugeValue(
                registry,
                "openbank.domestic.payments.non_terminal.oldest.age.seconds",
                DomesticPaymentStatus.RECEIVED,
            ),
        ).describedAs("42 days must cross the 3600s alert threshold, not merely be non-zero")
            .isEqualTo(Duration.ofDays(42).seconds.toDouble())
        assertThat(
            gaugeValue(
                registry,
                "openbank.domestic.payments.non_terminal.oldest.age.seconds",
                DomesticPaymentStatus.VALIDATED,
            ),
        ).isEqualTo(300.0)
    }

    /**
     * An emptied state must report 0, not the last age it saw. Holding the previous value would
     * keep the alert firing after the payments were cleared — which is how an alert earns being
     * ignored, and this whole issue exists because a signal nobody trusts is the same as no signal.
     */
    @Test
    fun `an emptied state reports zero rather than a stale age`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        var oldest = mapOf(DomesticPaymentStatus.RECEIVED to now.minus(Duration.ofHours(9)))
        val repo = object : DomesticPaymentRepository by StubRepository(emptyMap(), emptyMap()) {
            override suspend fun countByStatus(status: DomesticPaymentStatus) =
                if (oldest.containsKey(status)) 1L else 0L
            override suspend fun oldestCreatedAt(status: DomesticPaymentStatus) = oldest[status]
        }
        val gauge = DomesticPaymentStrandedGauge(repo, registry, clock, domainMetrics = mockk(relaxed = true))
        gauge.register()

        gauge.refresh()
        val name = "openbank.domestic.payments.non_terminal.oldest.age.seconds"
        assertThat(gaugeValue(registry, name, DomesticPaymentStatus.RECEIVED)).isEqualTo(32400.0)

        oldest = emptyMap()
        gauge.refresh()

        assertThat(gaugeValue(registry, name, DomesticPaymentStatus.RECEIVED)).isZero()
        assertThat(gaugeValue(registry, "openbank.domestic.payments.non_terminal", DomesticPaymentStatus.RECEIVED))
            .isZero()
    }

    /** Terminal states must not be published — their age only grows and would alert forever. */
    @Test
    fun `terminal states are not published`() {
        val registry = SimpleMeterRegistry()
        DomesticPaymentStrandedGauge(
            StubRepository(emptyMap(), emptyMap()),
            registry,
            clock,
            domainMetrics = mockk(relaxed = true),
        ).register()

        val published = registry.meters
            .filter { it.id.name == "openbank.domestic.payments.non_terminal" }
            .mapNotNull { it.id.getTag("status") }
            .toSet()

        assertThat(published).containsExactlyInAnyOrder("RECEIVED", "VALIDATED", "SENT_TO_CLEARING")
        assertThat(published).doesNotContain("SETTLED", "REJECTED", "CANCELLED", "RETURNED")
    }

    /** A missing Prometheus registry must not break startup — the gauge is optional telemetry. */
    @Test
    fun `registers nothing and does not throw when no registry is present`(): Unit = runBlocking {
        val gauge = DomesticPaymentStrandedGauge(
            StubRepository(emptyMap(), emptyMap()),
            null,
            clock,
            domainMetrics = mockk(relaxed = true),
        )
        gauge.register()
        gauge.refresh()
    }
}
