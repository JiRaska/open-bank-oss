// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.metrics

import com.openbank.statement.application.port.out.CloseRunRepository
import com.openbank.statement.domain.model.CloseRun
import com.openbank.statement.domain.model.CloseRunStatus
import com.openbank.statement.domain.model.CloseTrigger
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The last-run gauge fixes the retention-independent StatementCloseCadenceStalled false positive: a
 * gauge's current value is always scrapeable, unlike a counter over a [35d] window under 12h
 * retention. These tests pin its load-bearing properties — derived from the persisted run log,
 * monotonic (an in-flight RUNNING run never regresses it), and registered at zero before any run.
 */
class CloseLastRunGaugeTest {

    private val runs = mockk<CloseRunRepository>()

    private fun gauge(
        registry: SimpleMeterRegistry?,
        liveness: WorkflowLivenessRecorder = mockk(relaxed = true),
    ): CloseLastRunGauge {
        val metrics = mockk<DomainMetrics> {
            every { registerWorkflowLiveness(any(), any()) } returns liveness
        }
        return CloseLastRunGauge(runs, registry).apply { domainMetrics = metrics }
    }

    private fun finishedRun(finishedAt: Instant?, status: CloseRunStatus): CloseRun = CloseRun(
        id = UUID.randomUUID(), trigger = CloseTrigger.SCHEDULED, status = status,
        periodFrom = LocalDate.parse("2026-05-01"), periodTo = LocalDate.parse("2026-05-31"),
        accountsEnumerated = 1, pocketsClosed = 1, pocketsFailed = 0, pocketsSkipped = 0,
        startedAt = Instant.parse("2026-06-01T00:00:00Z"), finishedAt = finishedAt,
    )

    @Test
    fun `gauge is registered at zero before any refresh`() {
        val registry = SimpleMeterRegistry()
        gauge(registry).register()

        assertThat(registry.find(CloseLastRunGauge.GAUGE_NAME).gauge()?.value()).isZero()
    }

    @Test
    fun `refresh stamps the gauge with the latest finished run's epoch seconds`() {
        val registry = SimpleMeterRegistry()
        val finishedAt = Instant.parse("2026-06-20T10:00:00Z")
        every { runs.latestRun() } returns Uni.createFrom().item(finishedRun(finishedAt, CloseRunStatus.COMPLETED))

        val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
        val gauge = gauge(registry, liveness).apply { register() }
        gauge.refresh().await().indefinitely()

        assertThat(registry.get(CloseLastRunGauge.GAUGE_NAME).gauge().value().toLong())
            .isEqualTo(finishedAt.epochSecond)
        verify { liveness.recordSuccess() }
    }

    @Test
    fun `gauge stays at zero when the cadence has never run`() {
        val registry = SimpleMeterRegistry()
        every { runs.latestRun() } returns Uni.createFrom().nullItem()

        val gauge = gauge(registry).apply { register() }
        gauge.refresh().await().indefinitely()

        assertThat(registry.get(CloseLastRunGauge.GAUGE_NAME).gauge().value()).isZero()
    }

    @Test
    fun `an in-flight RUNNING latest run never regresses the gauge`() {
        val registry = SimpleMeterRegistry()
        val finishedAt = Instant.parse("2026-06-20T10:00:00Z")
        val gauge = gauge(registry).apply { register() }

        every { runs.latestRun() } returns Uni.createFrom().item(finishedRun(finishedAt, CloseRunStatus.COMPLETED))
        gauge.refresh().await().indefinitely()

        // A new run starts: latest is now RUNNING with no finishedAt — must not zero/regress the gauge.
        every { runs.latestRun() } returns Uni.createFrom().item(finishedRun(null, CloseRunStatus.RUNNING))
        gauge.refresh().await().indefinitely()

        assertThat(registry.get(CloseLastRunGauge.GAUGE_NAME).gauge().value().toLong())
            .isEqualTo(finishedAt.epochSecond)
    }

    @Test
    fun `register is a no-op when no meter registry is present`() {
        // Slim slices without a Prometheus registry must not crash the @Startup hook.
        gauge(null).register()
    }
}
