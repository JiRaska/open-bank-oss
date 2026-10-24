// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.`in`.AccrueInterestUseCase
import com.openbank.lending.application.port.out.AllowancePostings
import com.openbank.lending.application.port.out.CloseJobRunPort
import com.openbank.lending.application.port.out.LendingCloseEvidencePort
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
    private var allowance = AllowancePostings(pending = 0, posted = 0, dead = 0)
    private val postingsAsked = mutableListOf<LocalDate>()

    private fun scheduler(accrualEvery: String = "24h", provisioningEvery: String = "24h") =
        LendingCloseParticipationScheduler(
            evidence = object : LendingCloseEvidencePort, CloseJobRunPort by runs {
                override fun postingsFor(accountingDate: LocalDate): Uni<AllowancePostings> {
                    postingsAsked += accountingDate
                    return Uni.createFrom().item(allowance)
                }
            },
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
        ).apply { drainBudget = java.time.Duration.ofMinutes(10) }

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
    fun `completed accrual records disposition without trusting transient outcome counters`() {
        accrualScheduler(Uni.createFrom().item(AccrualOutcome(today, installmentsAccrued = 5, journalsPosted = 4)))
            .runAccrualPass().await().indefinitely()

        val run = runs.runs.getValue(today to LendingCloseJobs.ACCRUAL)
        assertThat(run.refused).isZero()
        assertThat(run.itemsProcessed).isZero()
        assertThat(run.journalsPosted).isZero()
    }

    @Test
    fun `a batch-limited pass that left installments due is REFUSED, never RAN`() {
        accrualScheduler(
            Uni.createFrom().item(
                AccrualOutcome(today, installmentsAccrued = 500, journalsPosted = 500, truncated = true),
            ),
        ).runAccrualPass().await().indefinitely()

        val run = runs.runs.getValue(today to LendingCloseJobs.ACCRUAL)
        assertThat(run.refused).isEqualTo(1)
        assertThat(run.refusalReason).isEqualTo(LendingCloseJobs.REASON_ACCRUAL_TRUNCATED)

        ran(today, LendingCloseJobs.PROVISIONING, items = 1, journals = 0)
        scheduler().reportDays(today.plusDays(1)).await().indefinitely()
        assertThat(reportFor(today).outcome).isEqualTo(CloseOutcome.REFUSED)
    }

    @Test
    fun `a pass just after Prague midnight accrues and records the Prague day, not the UTC date`() {
        // 2026-10-02T22:30Z is 00:30 on 2026-10-03 in Prague (CEST, UTC+2).
        val pastMidnight = Clock.fixed(Instant.parse("2026-10-02T22:30:00Z"), ZoneOffset.UTC)
        val pragueDay = LocalDate.of(2026, 10, 3)
        val asked = mutableListOf<LocalDate>()
        val accrual = mockk<AccrueInterestUseCase> {
            every { accrueDueInterest(any(), any(), any()) } answers {
                asked += firstArg<LocalDate>()
                Uni.createFrom().item(AccrualOutcome(firstArg(), installmentsAccrued = 1, journalsPosted = 1))
            }
        }
        InterestAccrualScheduler(accrual, 500, pastMidnight, noOpDomainMetrics(), runs).runAccrualPass()
            .await().indefinitely()

        assertThat(asked).containsExactly(pragueDay)
        assertThat(runs.runs.keys).containsExactly(pragueDay to LendingCloseJobs.ACCRUAL)
    }

    // ── review fixes: allowance outbox, in-flight marker, drain + catch-up ─────────────────────

    @Test
    fun `provisioning is not reported while its allowance journals are still in the outbox`() {
        val day = LocalDate.of(2026, 10, 2)
        ran(day, LendingCloseJobs.ACCRUAL, items = 1, journals = 1)
        ran(day, LendingCloseJobs.PROVISIONING, items = 4, journals = 0)
        allowance = AllowancePostings(pending = 2, posted = 1, dead = 0)

        scheduler().reportDays(today).await().indefinitely()

        assertThat(delivered.map { it.businessDate }).doesNotContain(day)
        assertThat(postingsAsked).contains(day)
    }

    @Test
    fun `delivered allowance journals count as provisioning's journals`() {
        val day = LocalDate.of(2026, 10, 2)
        ran(day, LendingCloseJobs.ACCRUAL, items = 1, journals = 1)
        ran(day, LendingCloseJobs.PROVISIONING, items = 4, journals = 0)
        allowance = AllowancePostings(pending = 0, posted = 3, dead = 0)

        scheduler().reportDays(today).await().indefinitely()

        assertThat(reportFor(day).outcome).isEqualTo(CloseOutcome.RAN)
        assertThat(reportFor(day).journalsPosted).isEqualTo(4)
    }

    @Test
    fun `an allowance journal the outbox gave up on makes the day REFUSED`() {
        val day = LocalDate.of(2026, 10, 2)
        ran(day, LendingCloseJobs.ACCRUAL, items = 1, journals = 1)
        ran(day, LendingCloseJobs.PROVISIONING, items = 4, journals = 0)
        allowance = AllowancePostings(pending = 0, posted = 2, dead = 1)

        scheduler().reportDays(today).await().indefinitely()

        assertThat(reportFor(day).outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(reportFor(day).reason).contains("1 ${LendingCloseJobs.REASON_ALLOWANCE_NOT_POSTED}")
    }

    @Test
    fun `a day with a pass still in flight is not reported, even over an earlier RAN row`() {
        val day = LocalDate.of(2026, 10, 2)
        ran(day, LendingCloseJobs.ACCRUAL, items = 1, journals = 1)
        ran(day, LendingCloseJobs.PROVISIONING, items = 4, journals = 0)
        // A second accrual pass for the same day started 10 minutes ago and has not finished.
        runs.markStarted(day, LendingCloseJobs.ACCRUAL, clock.instant().minusSeconds(600)).await().indefinitely()

        scheduler().reportDays(today).await().indefinitely()

        assertThat(delivered.map { it.businessDate }).doesNotContain(day)
    }

    @Test
    fun `a pass marked in flight past the stale threshold is reported REFUSED, never left hanging`() {
        val day = LocalDate.of(2026, 10, 2)
        ran(day, LendingCloseJobs.PROVISIONING, items = 4, journals = 0)
        runs.markStarted(day, LendingCloseJobs.ACCRUAL, clock.instant().minusSeconds(3 * 3600)).await().indefinitely()

        scheduler().reportDays(today).await().indefinitely()

        assertThat(reportFor(day).outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(reportFor(day).reason).contains(LendingCloseJobs.REASON_PASS_ABANDONED)
    }

    @Test
    fun `a pass marks itself in flight before accruing`() {
        val accrual = mockk<AccrueInterestUseCase>()
        every { accrual.accrueDueInterest(any(), any(), any()) } answers {
            assertThat(runs.inFlight.keys).contains(today to LendingCloseJobs.ACCRUAL)
            Uni.createFrom().item(AccrualOutcome(today, 0))
        }
        InterestAccrualScheduler(accrual, 500, clock, noOpDomainMetrics(), runs).runAccrualPass()
            .await().indefinitely()

        assertThat(runs.inFlight).isEmpty()
        assertThat(runs.runs.getValue(today to LendingCloseJobs.ACCRUAL).refused).isZero()
    }

    @Test
    fun `a truncated batch is drained within the pass and the day records RAN`() {
        val accrual = mockk<AccrueInterestUseCase>()
        every { accrual.accrueDueInterest(today, 2, any()) } returnsMany listOf(
            Uni.createFrom().item(AccrualOutcome(today, 2, 2, truncated = true)),
            Uni.createFrom().item(AccrualOutcome(today, 1, 1, truncated = false)),
        )
        InterestAccrualScheduler(accrual, 2, clock, noOpDomainMetrics(), runs).runAccrualPass()
            .await().indefinitely()

        val run = runs.runs.getValue(today to LendingCloseJobs.ACCRUAL)
        assertThat(run.refused).isZero()
        assertThat(run.itemsProcessed).isZero()
        assertThat(run.journalsPosted).isZero()
    }

    @Test
    fun `an earlier day left truncated is caught up as of that day and re-recorded RAN`() {
        val dayD = today.minusDays(1)
        ran(
            dayD,
            LendingCloseJobs.ACCRUAL,
            items = 500,
            journals = 500,
            refused = 1,
            reason = LendingCloseJobs.REASON_ACCRUAL_TRUNCATED,
        )
        val asked = mutableListOf<LocalDate>()
        val accrual = mockk<AccrueInterestUseCase>()
        every { accrual.accrueDueInterest(any(), any(), any()) } answers {
            asked += firstArg<LocalDate>()
            Uni.createFrom().item(AccrualOutcome(firstArg(), 7, 7))
        }
        InterestAccrualScheduler(accrual, 500, clock, noOpDomainMetrics(), runs).runAccrualPass()
            .await().indefinitely()

        assertThat(asked).containsExactly(dayD, today)
        val caughtUp = runs.runs.getValue(dayD to LendingCloseJobs.ACCRUAL)
        assertThat(caughtUp.refused).isZero()
        assertThat(caughtUp.refusalReason).isNull()
        assertThat(caughtUp.itemsProcessed).isEqualTo(500)
    }

    // #13708 review: a pass that crashed mid-way leaves running=TRUE; the next pass must heal the day.
    @Test
    fun `a day a crashed pass left in flight is re-run as of that day and re-recorded RAN`() {
        val dayD = today.minusDays(1)
        runs.markStarted(dayD, LendingCloseJobs.ACCRUAL, clock.instant().minusSeconds(3 * 3600)).await().indefinitely()
        val asked = mutableListOf<LocalDate>()
        val accrual = mockk<AccrueInterestUseCase>()
        every { accrual.accrueDueInterest(any(), any(), any()) } answers {
            asked += firstArg<LocalDate>()
            Uni.createFrom().item(AccrualOutcome(firstArg(), 2, 2))
        }
        InterestAccrualScheduler(accrual, 500, clock, noOpDomainMetrics(), runs).runAccrualPass()
            .await().indefinitely()

        assertThat(asked).containsExactly(dayD, today)
        assertThat(runs.inFlight).isEmpty()
        val healed = runs.runs.getValue(dayD to LendingCloseJobs.ACCRUAL)
        assertThat(healed.refused).isZero()
        assertThat(healed.itemsProcessed).isZero()
        // Mock outcomes are not durable receipts and must not invent or relocate counts.
        assertThat(healed.journalsPosted).isZero()
        assertThat(runs.runs.getValue(today to LendingCloseJobs.ACCRUAL).journalsPosted).isZero()
    }

    // #13708 security review: catch-up journals booked today must not clear today's refusal.
    @Test
    fun `late-day catch-up journals never turn a REFUSED today into a clean one`() {
        val dayD = today.minusDays(1)
        ran(
            today,
            LendingCloseJobs.ACCRUAL,
            items = 0,
            journals = 0,
            refused = 1,
            reason = LendingCloseJobs.REASON_DAY_CLOSED,
        )
        ran(
            dayD,
            LendingCloseJobs.ACCRUAL,
            items = 5,
            journals = 5,
            refused = 1,
            reason = LendingCloseJobs.REASON_ACCRUAL_TRUNCATED,
        )
        val accrual = mockk<AccrueInterestUseCase>()
        every { accrual.accrueDueInterest(dayD, any(), any()) } returns
            Uni.createFrom().item(AccrualOutcome(dayD, 3, 3)).invoke { _ ->
                // Model authoritative completion, not the scheduler's transient outcome count.
                runs.addJournals(today, LendingCloseJobs.ACCRUAL, 3)
            }
        every { accrual.accrueDueInterest(today, any(), any()) } returns
            Uni.createFrom().item(AccrualOutcome(today, 1, 1))
        // Today's own pass dies before writing its result: only the catch-up touches today's row.
        runs.crashOnRecord = today to LendingCloseJobs.ACCRUAL

        runCatching {
            InterestAccrualScheduler(
                accrual,
                500,
                clock,
                noOpDomainMetrics(),
                runs,
            ).runAccrualPass().await().indefinitely()
        }

        val todayRow = runs.runs.getValue(today to LendingCloseJobs.ACCRUAL)
        assertThat(todayRow.refused).describedAs("still REFUSED, never flipped by the catch-up").isEqualTo(1)
        assertThat(todayRow.refusalReason).isEqualTo(LendingCloseJobs.REASON_DAY_CLOSED)
        assertThat(todayRow.journalsPosted).isEqualTo(3)
        assertThat(runs.inFlight.keys).describedAs("today's dead pass still marked in flight")
            .contains(today to LendingCloseJobs.ACCRUAL)
        assertThat(runs.runs.getValue(dayD to LendingCloseJobs.ACCRUAL).refused).isZero()
    }

    @Test
    fun `adding late-day journals leaves the refusal and the in-flight marker untouched`() {
        ran(
            today,
            LendingCloseJobs.ACCRUAL,
            items = 0,
            journals = 0,
            refused = 1,
            reason = LendingCloseJobs.REASON_DAY_CLOSED,
        )
        runs.addJournals(today, LendingCloseJobs.ACCRUAL, 4).await().indefinitely()
        assertThat(runs.runs.getValue(today to LendingCloseJobs.ACCRUAL).refused).isEqualTo(1)

        val dayX = today.minusDays(2)
        runs.markStarted(dayX, LendingCloseJobs.ACCRUAL, clock.instant()).await().indefinitely()
        runs.addJournals(dayX, LendingCloseJobs.ACCRUAL, 2).await().indefinitely()
        assertThat(runs.inFlight.keys).contains(dayX to LendingCloseJobs.ACCRUAL)
    }

    @Test
    fun `a marker younger than the derived stale threshold is a live pass and is not re-run`() {
        val dayD = today.minusDays(1)
        // stale-after = drain budget 10m x (lookback 3 + 1) + 30m = 70m; 60m old is still live.
        runs.markStarted(dayD, LendingCloseJobs.ACCRUAL, clock.instant().minusSeconds(60 * 60)).await().indefinitely()
        val asked = mutableListOf<LocalDate>()
        val accrual = mockk<AccrueInterestUseCase>()
        every { accrual.accrueDueInterest(any(), any(), any()) } answers {
            asked += firstArg<LocalDate>()
            Uni.createFrom().item(AccrualOutcome(firstArg(), 0))
        }
        InterestAccrualScheduler(accrual, 500, clock, noOpDomainMetrics(), runs).runAccrualPass()
            .await().indefinitely()

        assertThat(asked).containsExactly(today)
        assertThat(LendingCloseJobs.staleAfter(java.time.Duration.ofMinutes(10), 3))
            .isEqualTo(java.time.Duration.ofMinutes(70))
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
        val accrual = mockk<AccrueInterestUseCase> { every { accrueDueInterest(any(), any(), any()) } returns result }
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
