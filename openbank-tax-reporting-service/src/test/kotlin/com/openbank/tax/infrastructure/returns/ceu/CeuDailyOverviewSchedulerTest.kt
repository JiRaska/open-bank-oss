// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.ceu

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.tax.application.port.out.StatutoryReturnRepository
import com.openbank.tax.application.usecase.ReportingEntities
import com.openbank.tax.application.usecase.StatutoryReturnService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException

/** The scheduler's decision per assembly outcome, with the reactive stack mocked away (the IT drives the real cron). */
class CeuDailyOverviewSchedulerTest {
    private val service = mockk<StatutoryReturnService>()
    private val returns = mockk<StatutoryReturnRepository>()
    private val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
    private val metrics = mockk<DomainMetrics> { every { registerWorkflowLiveness(any(), any()) } returns liveness }
    private val registry = SimpleMeterRegistry()

    // Tuesday 2026-11-10 11:30 Prague: a working day, past the 11:00 cutoff.
    private val scheduler = CeuDailyOverviewScheduler(
        service,
        returns,
        ReportingEntities("company", emptyList(), null, mapOf("cz-ceu" to LocalDate.parse("2026-11-01"))),
        metrics,
        registry,
        Clock.fixed(Instant.parse("2026-11-10T10:30:00Z"), ZoneOffset.UTC),
    ).also { it.register() }

    init {
        coEvery { returns.latestRevision(any(), any(), any(), any()) } returns null
    }

    private fun assembleThrows(e: Throwable) {
        coEvery { service.assemble(any(), any(), any(), any(), any()) } throws e
    }

    private fun unfiled() = registry.find("openbank_ceu_overview_unfiled_past_cutoff").gauge()?.value()

    @Test
    fun `an unanswered predecessor holds the filing with a distinct outcome and keeps the alert up`(): Unit =
        runBlocking {
            assembleThrows(CeuFilingHeldException(CeuHoldReason.WAITING_FOR_ACKNOWLEDGEMENT, "held"))

            assertThat(scheduler.tick()).isEqualTo(CeuTickOutcome.WAITING_FOR_ACKNOWLEDGEMENT)
            assertThat(unfiled()).isEqualTo(1.0)
            verify { liveness.recordSuccess() }
        }

    @Test
    fun `an unattested initial load is refused with a distinct outcome`(): Unit = runBlocking {
        assembleThrows(CeuFilingHeldException(CeuHoldReason.INITIAL_LOAD_NOT_ATTESTED, "attest"))

        assertThat(scheduler.tick()).isEqualTo(CeuTickOutcome.INITIAL_LOAD_NOT_ATTESTED)
        assertThat(unfiled()).isEqualTo(1.0)
    }

    @Test
    fun `cancellation propagates instead of reading as a failed tick`(): Unit = runBlocking {
        assembleThrows(CancellationException("shutdown"))

        assertThrows<CancellationException> { scheduler.tick() }
        verify(exactly = 0) { liveness.recordSuccess() }
    }

    @Test
    fun `an unexpected failure is FAILED and still publishes the unfiled gauge`(): Unit = runBlocking {
        assembleThrows(IllegalStateException("boom"))

        assertThat(scheduler.tick()).isEqualTo(CeuTickOutcome.FAILED)
        assertThat(unfiled()).isEqualTo(1.0)
        verify(exactly = 0) { liveness.recordSuccess() }
    }
}
