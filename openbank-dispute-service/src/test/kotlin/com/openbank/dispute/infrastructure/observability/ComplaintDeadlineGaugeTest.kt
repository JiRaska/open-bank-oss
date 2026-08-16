// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.dispute.infrastructure.observability

import com.openbank.dispute.application.port.out.ComplaintRepository
import com.openbank.dispute.domain.model.Complaint
import com.openbank.dispute.domain.model.ComplaintCategory
import com.openbank.dispute.domain.model.ComplaintChannel
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The three deadline gauges (ADR-0085 §2) must agree with the domain breach definition and bucket
 * open complaints into open / due-soon / breached. Buckets are pinned to a fixed clock so the math is
 * deterministic and independent of the real business-day calendar (due-soon cases sit exactly on
 * "today", which is on/before any positive business-day cutoff).
 */
class ComplaintDeadlineGaugeTest {

    private val zone = ZoneId.of("Europe/Prague")
    private val clock = Clock.fixed(Instant.parse("2026-06-15T09:00:00Z"), zone)
    private val today: LocalDate = LocalDate.now(clock)

    private fun complaint(dueDate: LocalDate): Complaint {
        val now = OffsetDateTime.now(clock)
        return Complaint(
            reference = "CMP-$dueDate",
            category = ComplaintCategory.PAYMENT_SERVICE,
            channel = ComplaintChannel.APP,
            description = "x",
            receivedDate = today.minusDays(5),
            dueDate = dueDate,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun gauge(
        repository: ComplaintRepository,
        registry: SimpleMeterRegistry?,
        liveness: WorkflowLivenessRecorder = mockk(relaxed = true),
    ): ComplaintDeadlineGauge {
        val metrics = mockk<DomainMetrics> {
            every { registerWorkflowLiveness(any(), any()) } returns liveness
        }
        return ComplaintDeadlineGauge(repository, registry, clock).apply { domainMetrics = metrics }
    }

    @Test
    fun `gauges bucket open complaints into open, due-soon and breached`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val repo = mockk<ComplaintRepository>()
        every { repo.findByStatus(any()) } returns Uni.createFrom().item(
            listOf(
                complaint(today.minusDays(2)), // breached: past due, still open
                complaint(today), //               due soon: due today, not yet breached
                complaint(today), //               due soon
                complaint(today.plusDays(30)), //  open only: well beyond the warning window
            ),
        )

        val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
        val gauge = gauge(repo, registry, liveness)
        gauge.register()
        gauge.refresh()

        assertThat(value(registry, "openbank.complaints.open")).isEqualTo(4.0)
        assertThat(value(registry, "openbank.complaints.due_breach")).isEqualTo(1.0)
        assertThat(value(registry, "openbank.complaints.due_soon")).isEqualTo(2.0)
        verify { liveness.recordSuccess() }
    }

    @Test
    fun `gauges read zero when there are no open complaints`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val repo = mockk<ComplaintRepository>()
        every { repo.findByStatus(any()) } returns Uni.createFrom().item(emptyList())

        val gauge = gauge(repo, registry)
        gauge.register()
        gauge.refresh()

        assertThat(value(registry, "openbank.complaints.open")).isZero()
        assertThat(value(registry, "openbank.complaints.due_breach")).isZero()
        assertThat(value(registry, "openbank.complaints.due_soon")).isZero()
    }

    @Test
    fun `register is a no-op when no meter registry is present`() {
        // Slim slices without a Prometheus registry must not crash the @Startup hook.
        val repo = mockk<ComplaintRepository>()
        gauge(repo, null).register()
    }

    private fun value(registry: SimpleMeterRegistry, name: String): Double =
        registry.get(name).tag("service", "dispute").gauge().value()
}
