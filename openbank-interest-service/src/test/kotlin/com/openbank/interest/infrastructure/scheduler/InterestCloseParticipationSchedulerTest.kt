// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.scheduler

import com.openbank.interest.application.port.`in`.AccrueInterestUseCase
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.observability.DomainMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * ADR-0342 participant side: interest reports a day only when every job DUE that day has a recorded
 * run. An accrual that has not run (late, or failed) leaves the day unreported, which is what holds
 * the ledger's close; the moment the accrual records its run, the next tick reports it.
 */
class InterestCloseParticipationSchedulerTest {

    // "today" is 2026-10-03; the lookback covers 2026-09-30 .. 2026-10-02, and 2026-10-01 is a
    // capitalization day under the production cron "0 0 2 1 * ?".
    private val clock = Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 3)
    private val runs = RecordingCloseJobRuns()
    private val delivered = mutableListOf<CloseParticipationReport>()

    private val scheduler = InterestCloseParticipationScheduler(
        closeJobRuns = runs,
        sink = { _, report -> delivered += report; Uni.createFrom().item(Unit) },
        meterRegistry = SimpleMeterRegistry(),
        clock = clock,
        domainMetrics = noOpDomainMetrics(),
        accrualCron = "0 0 1 * * ?",
        capitalizationCron = "0 0 2 1 * ?",
        lookbackDays = 3,
    )

    private fun noOpDomainMetrics(): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns false
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun ran(day: LocalDate, job: String, items: Int, journals: Int, refused: Int = 0, reason: String? = null) {
        runs.record(CloseJobRun(day, job, items, journals, refused, reason, clock.instant())).await().indefinitely()
    }

    @Test
    fun `capitalization is due only on the 1st, accrual every day`() {
        assertThat(scheduler.dueJobs(LocalDate.of(2026, 10, 1)))
            .containsExactlyInAnyOrder(InterestCloseJobs.ACCRUAL, InterestCloseJobs.CAPITALIZATION)
        assertThat(scheduler.dueJobs(LocalDate.of(2026, 10, 2))).containsExactly(InterestCloseJobs.ACCRUAL)
    }

    @Test
    fun `a day whose accrual has not run is not reported, and is reported once it runs late`() {
        val oct2 = LocalDate.of(2026, 10, 2)
        ran(LocalDate.of(2026, 9, 30), InterestCloseJobs.ACCRUAL, items = 7, journals = 0)

        scheduler.reportDays(today).await().indefinitely()
        assertThat(delivered.map { it.businessDate }).containsExactly(LocalDate.of(2026, 9, 30))

        // Late accrual for Oct 2 (a manual POST /accrue/all, or the scheduler finally succeeding).
        ran(oct2, InterestCloseJobs.ACCRUAL, items = 7, journals = 0)
        scheduler.reportDays(today).await().indefinitely()

        assertThat(delivered.map { it.businessDate })
            .describedAs("Sep 30 is not re-sent unchanged; Oct 1 still lacks its capitalization")
            .containsExactly(LocalDate.of(2026, 9, 30), oct2)
        assertThat(delivered.last().outcome).isEqualTo(CloseOutcome.RAN)
    }

    @Test
    fun `a capitalization day reports its journals, or REFUSED when the ledger refused the day`() {
        val oct1 = LocalDate.of(2026, 10, 1)
        ran(oct1, InterestCloseJobs.ACCRUAL, items = 7, journals = 0)
        ran(oct1, InterestCloseJobs.CAPITALIZATION, items = 5, journals = 5)
        scheduler.reportDays(today).await().indefinitely()
        assertThat(delivered.single { it.businessDate == oct1 })
            .isEqualTo(CloseParticipationReport(oct1, CloseOutcome.RAN, journalsPosted = 5, itemsProcessed = 12))

        ran(oct1, InterestCloseJobs.CAPITALIZATION, items = 3, journals = 3, refused = 2, reason = "HTTP 409 ACCOUNTING_DAY_CLOSED")
        scheduler.reportDays(today).await().indefinitely()
        val refused = delivered.last { it.businessDate == oct1 }
        assertThat(refused.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(refused.reason).contains("2 posting(s) refused")
    }

    @Test
    fun `the accrual scheduler records a run only for a successful tick`() {
        val accrual = object : AccrueInterestUseCase {
            override fun accrue(request: AccrualRequest): Uni<InterestAccrual> = error("unused")
            override fun accrueAll(date: LocalDate): Uni<Int> = Uni.createFrom().failure(IllegalStateException("db down"))
        }
        InterestAccrualScheduler(accrual, clock, noOpDomainMetrics(), runs).runDailyAccrual().await().indefinitely()
        assertThat(runs.runs).isEmpty()
    }
}
