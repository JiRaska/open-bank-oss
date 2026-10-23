// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.`in`.AccrueInterestUseCase
import com.openbank.lending.domain.model.AccrualOutcome
import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.observability.DomainMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.function.Supplier

/**
 * ADR-0342 participant side for lending: a day is reported only when BOTH interval jobs recorded a
 * run for it; a refused or failed pass is REFUSED (and so holds the ledger's close); both jobs
 * switched off is SKIPPED. The accrual tests drive the real [InterestAccrualScheduler] pass and
 * check what it records, including the ledger's closed-day 409.
 */
class LendingCloseParticipationSchedulerTest {

    // 2026-10-03T10:00Z is 12:00 in Prague: the lookback covers 2026-09-30 .. 2026-10-02.
    private val clock = Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 3)
    private val runs = RecordingCloseJobRuns()
    private val delivered = mutableListOf<CloseParticipationReport>()

    private fun scheduler(accrualEvery: String = "24h", provisioningEvery: String = "24h") =
        LendingCloseParticipationScheduler(
            closeJobRuns = runs,
            sink = { participant, report ->
                assertThat(participant).isEqualTo(LendingCloseJobs.PARTICIPANT)
                delivered += report
                Uni.createFrom().item(Unit)
            },
            meterRegistry = SimpleMeterRegistry(),
            clock = clock,
            domainMetrics = noOpDomainMetrics(),
            accrualEvery = accrualEvery,
            provisioningEvery = provisioningEvery,
            lookbackDays = 3,
        )

    @BeforeEach
    fun stubPanacheSession() {
        // runAccrualPass wraps its body in Panache.withSession; run the body directly.
        mockkStatic(Panache::class)
        every { Panache.withSession(any<Supplier<Uni<Void>>>()) } answers {
            firstArg<Supplier<Uni<Void>>>().get()
        }
    }

    @AfterEach
    fun unstub() = unmockkStatic(Panache::class)

    private fun noOpDomainMetrics(): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns false
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun ran(day: LocalDate, job: String, items: Int, journals: Int, refused: Int = 0, reason: String? = null) {
        runs.record(CloseJobRun(day, job, items, journals, refused, reason, clock.instant())).await().indefinitely()
    }

    private fun reportFor(day: LocalDate) = delivered.single { it.businessDate == day }

    @Test
    fun `both interval jobs are due every day they are on`() {
        assertThat(scheduler().dueJobs())
            .containsExactlyInAnyOrder(LendingCloseJobs.ACCRUAL, LendingCloseJobs.PROVISIONING)
        assertThat(scheduler(provisioningEvery = "off").dueJobs()).containsExactly(LendingCloseJobs.ACCRUAL)
        assertThat(scheduler(accrualEvery = "disabled", provisioningEvery = "off").dueJobs()).isEmpty()
    }

    @Test
    fun `a day with both jobs run is reported RAN with the summed counts`() {
        val day = LocalDate.of(2026, 10, 2)
        ran(day, LendingCloseJobs.ACCRUAL, items = 4, journals = 3)
        ran(day, LendingCloseJobs.PROVISIONING, items = 10, journals = 0)

        scheduler().reportDays(today).await().indefinitely()

        val report = reportFor(day)
        assertThat(report.outcome).isEqualTo(CloseOutcome.RAN)
        assertThat(report.journalsPosted).isEqualTo(3)
        assertThat(report.itemsProcessed).isEqualTo(14)
    }

    @Test
    fun `a day whose provisioning has not run is not reported, so the ledger holds it`() {
        val day = LocalDate.of(2026, 10, 2)
        ran(day, LendingCloseJobs.ACCRUAL, items = 4, journals = 3)

        val result = scheduler().reportDays(today).await().indefinitely()

        assertThat(delivered.map { it.businessDate }).doesNotContain(day)
        assertThat(result.attempted).isZero()
    }

    @Test
    fun `a second run on the same day adds to the counts instead of overwriting them`() {
        val day = LocalDate.of(2026, 10, 2)
        ran(day, LendingCloseJobs.ACCRUAL, items = 4, journals = 3)
        ran(day, LendingCloseJobs.ACCRUAL, items = 0, journals = 0) // restart: nothing left to accrue
        ran(day, LendingCloseJobs.PROVISIONING, items = 1, journals = 0)

        scheduler().reportDays(today).await().indefinitely()

        assertThat(reportFor(day).journalsPosted).isEqualTo(3)
    }

    @Test
    fun `both jobs off is reported SKIPPED with the reason`() {
        scheduler(accrualEvery = "off", provisioningEvery = "off").reportDays(today).await().indefinitely()

        assertThat(delivered).hasSize(3)
        assertThat(delivered).allSatisfy {
            assertThat(it.outcome).isEqualTo(CloseOutcome.SKIPPED)
            assertThat(it.reason).contains("off")
        }
    }

    @Test
    fun `an accrual the ledger refused with ACCOUNTING_DAY_CLOSED reports the day REFUSED`() {
        accrualScheduler(Uni.createFrom().failure(closedDay())).let { s ->
            runCatching { s.runAccrualPass().await().indefinitely() }
        }
        // the recorded day is the Prague accounting day of the run, 2026-10-03
        val run = runs.runs.getValue(today to LendingCloseJobs.ACCRUAL)
        assertThat(run.refused).isEqualTo(1)
        assertThat(run.refusalReason).isEqualTo(LendingCloseJobs.REASON_DAY_CLOSED)

        ran(today, LendingCloseJobs.PROVISIONING, items = 1, journals = 0)
        scheduler().reportDays(today.plusDays(1)).await().indefinitely()

        val report = reportFor(today)
        assertThat(report.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(report.journalsPosted).isZero()
        assertThat(report.reason).contains("ACCOUNTING_DAY_CLOSED")
    }

    @Test
    fun `any other accrual failure is a REFUSED day with a fixed category, never RAN`() {
        accrualScheduler(Uni.createFrom().failure(IllegalStateException("loan 42 exploded"))).let { s ->
            runCatching { s.runAccrualPass().await().indefinitely() }
        }

        val run = runs.runs.getValue(today to LendingCloseJobs.ACCRUAL)
        assertThat(run.refused).isEqualTo(1)
        assertThat(run.refusalReason).isEqualTo(LendingCloseJobs.REASON_ACCRUAL_FAILED)
        assertThat(run.refusalReason).doesNotContain("42")
    }

    @Test
    fun `a completed accrual records RAN evidence with installments and journals`() {
        accrualScheduler(Uni.createFrom().item(AccrualOutcome(today, installmentsAccrued = 5, journalsPosted = 4)))
            .runAccrualPass().await().indefinitely()

        val run = runs.runs.getValue(today to LendingCloseJobs.ACCRUAL)
        assertThat(run.refused).isZero()
        assertThat(run.itemsProcessed).isEqualTo(5)
        assertThat(run.journalsPosted).isEqualTo(4)
    }

    @Test
    fun `a 409 without the closed-day code is not a closed-day refusal`() {
        val otherConflict = WebApplicationException(
            Response.status(CONFLICT).entity("""{"code":"IDEMPOTENCY_CONFLICT"}""").build(),
        )
        assertThat(LendingCloseJobs.isClosedDayRejection(otherConflict)).isFalse()
        assertThat(LendingCloseJobs.isClosedDayRejection(RuntimeException("wrapped", closedDay()))).isTrue()
    }

    private fun closedDay() = WebApplicationException(
        Response.status(CONFLICT)
            .entity("""{"code":"ACCOUNTING_DAY_CLOSED","message":"accounting day 2026-10-03 is LOCKED"}""")
            .build(),
    )

    private fun accrualScheduler(result: Uni<AccrualOutcome>): InterestAccrualScheduler {
        val accrual = mockk<AccrueInterestUseCase> { every { accrueDueInterest(any(), any()) } returns result }
        return InterestAccrualScheduler(
            accrual,
            batchSize = 500,
            clock = clock,
            domainMetrics = noOpDomainMetrics(),
            closeJobRuns = runs,
        )
    }

    private companion object {
        const val CONFLICT = 409
    }
}
