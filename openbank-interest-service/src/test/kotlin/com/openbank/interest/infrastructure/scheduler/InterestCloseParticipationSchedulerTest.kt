// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.scheduler

import com.openbank.interest.application.port.`in`.AccrualRunOutcome
import com.openbank.interest.application.port.`in`.AccrueInterestUseCase
import com.openbank.interest.application.port.`in`.CapitalizationRunOutcome
import com.openbank.interest.application.port.`in`.CapitalizeInterestUseCase
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.close.CloseParticipationSink
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

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
        sink = { _, report ->
            delivered += report
            Uni.createFrom().item(Unit)
        },
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

        ran(
            oct1,
            InterestCloseJobs.CAPITALIZATION,
            items = 3,
            journals = 3,
            refused = 2,
            reason = "HTTP 409 ACCOUNTING_DAY_CLOSED",
        )
        scheduler.reportDays(today).await().indefinitely()
        val refused = delivered.last { it.businessDate == oct1 }
        assertThat(refused.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(refused.reason).contains("2 posting(s) refused")
    }

    @Test
    fun `the accrual scheduler records a run only for a successful tick`() {
        val accrual = object : AccrueInterestUseCase {
            override fun accrue(request: AccrualRequest): Uni<InterestAccrual> = error("unused")
            override fun accrueAll(date: LocalDate): Uni<Int> =
                Uni.createFrom().failure(IllegalStateException("db down"))
        }
        InterestAccrualScheduler(accrual, clock, noOpDomainMetrics(), runs).runDailyAccrual().await().indefinitely()
        assertThat(runs.runs).isEmpty()
    }

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun successRecorded(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "interest-close-participation")
        .gauge()?.value()

    private fun schedulerWith(sink: CloseParticipationSink, registry: MeterRegistry, at: Clock = clock) =
        InterestCloseParticipationScheduler(
            closeJobRuns = runs,
            sink = sink,
            meterRegistry = SimpleMeterRegistry(),
            clock = at,
            domainMetrics = metricsOver(registry),
            accrualCron = "0 0 1 * * ?",
            capitalizationCron = "0 0 2 1 * ?",
            lookbackDays = 3,
        ).also { it.onStart(StartupEvent()) }

    @Test
    fun `liveness is recorded only when the ledger accepted the reports`() {
        ran(LocalDate.of(2026, 10, 2), InterestCloseJobs.ACCRUAL, items = 7, journals = 0)
        for (failure in listOf("unreachable", "HTTP 403", "HTTP 404")) {
            val registry = SimpleMeterRegistry()
            val scheduler = schedulerWith(
                { _, _ -> Uni.createFrom().failure(IllegalStateException(failure)) },
                registry,
            )
            scheduler.reportRecentDays().await().indefinitely()
            assertThat(successRecorded(registry)).describedAs(failure).isEqualTo(0.0)
        }

        val registry = SimpleMeterRegistry()
        schedulerWith({ _, _ -> Uni.createFrom().item(Unit) }, registry).reportRecentDays().await().indefinitely()
        assertThat(successRecorded(registry)).describedAs("accepted").isEqualTo(1.0)
    }

    // 2026-10-02T22:30Z is 00:30 on 2026-10-03 in Europe/Prague (CEST, +02:00): the UTC date is
    // still the 2nd, the accounting day the ledger runs is the 3rd.
    private val pragueHalfPastMidnight = Clock.fixed(Instant.parse("2026-10-02T22:30:00Z"), ZoneOffset.UTC)

    @Test
    fun `an accrual run at 00-30 Prague is recorded under the Prague business date, not the UTC one`() {
        var accruedFor: LocalDate? = null
        val accrual = object : AccrueInterestUseCase {
            override fun accrue(request: AccrualRequest): Uni<InterestAccrual> = error("unused")
            override fun accrueAll(date: LocalDate): Uni<Int> {
                accruedFor = date
                return Uni.createFrom().item(4)
            }
        }
        InterestAccrualScheduler(accrual, pragueHalfPastMidnight, noOpDomainMetrics(), runs)
            .runDailyAccrual().await().indefinitely()

        val prague = LocalDate.of(2026, 10, 3)
        assertThat(accruedFor).isEqualTo(prague)
        assertThat(runs.runs.keys).containsExactly(prague to InterestCloseJobs.ACCRUAL)
    }

    @Test
    fun `a capitalization run at 00-30 Prague on the 1st is the 1st, and a failed pair is not RAN`() {
        val firstOfMonth = Clock.fixed(Instant.parse("2026-09-30T22:30:00Z"), ZoneOffset.UTC)
        val capitalize = object : CapitalizeInterestUseCase {
            override fun capitalize(
                accountId: UUID,
                productId: String,
                toDate: LocalDate,
            ): Uni<InterestCapitalization> = error("unused")
            override fun capitalizeAll(toDate: LocalDate): Uni<Int> = error("unused")
            override fun recoverOutstandingClaims(): Uni<Int> = Uni.createFrom().item(0)
            override fun capitalizeAllWithOutcome(toDate: LocalDate): Uni<CapitalizationRunOutcome> =
                Uni.createFrom().item(CapitalizationRunOutcome(5, 0, null, failed = 2, failureReason = "ledger 503"))
        }
        InterestCapitalizationScheduler(capitalize, firstOfMonth, noOpDomainMetrics(), runs)
            .runMonthlyCapitalization().await().indefinitely()

        val oct1 = LocalDate.of(2026, 10, 1)
        val run = runs.runs.getValue(oct1 to InterestCloseJobs.CAPITALIZATION)
        assertThat(run.refused).describedAs("failed pairs hold the day like refusals").isEqualTo(2)
        assertThat(run.refusalReason).contains("2 pair(s) failed").contains("ledger 503")

        ran(oct1, InterestCloseJobs.ACCRUAL, items = 7, journals = 0)
        scheduler.reportDays(today).await().indefinitely()
        assertThat(delivered.single { it.businessDate == oct1 }.outcome).isEqualTo(CloseOutcome.REFUSED)
    }

    @Test
    fun `the report tick at 00-30 Prague treats the Prague date as today`() {
        // Prague 2026-10-03 00:30: yesterday (2026-10-02) is complete and must be offered NOW;
        // against the UTC date (10-02) it would still be today and wait until the next day.
        ran(LocalDate.of(2026, 10, 2), InterestCloseJobs.ACCRUAL, items = 7, journals = 0)
        schedulerWith({ _, r ->
            delivered += r
            Uni.createFrom().item(Unit)
        }, SimpleMeterRegistry(), pragueHalfPastMidnight)
            .reportRecentDays().await().indefinitely()
        assertThat(delivered.map { it.businessDate }).contains(LocalDate.of(2026, 10, 2))
    }

    @Test
    fun `account-service unavailable for every account reports REFUSED and the day is not complete`() {
        val stubbed = object : AccrueInterestUseCase {
            override fun accrue(request: AccrualRequest): Uni<InterestAccrual> = error("unused")
            override fun accrueAll(date: LocalDate): Uni<Int> =
                error("the outcome variant is the one the scheduler uses")
            override fun accrueAllWithOutcome(date: LocalDate): Uni<AccrualRunOutcome> =
                Uni.createFrom().item(AccrualRunOutcome(0, 0, 3, "balance unavailable for account x"))
        }
        InterestAccrualScheduler(stubbed, pragueHalfPastMidnight, noOpDomainMetrics(), runs)
            .runDailyAccrual().await().indefinitely()

        val day = LocalDate.of(2026, 10, 3)
        val run = runs.runs.getValue(day to InterestCloseJobs.ACCRUAL)
        assertThat(run.refused).isEqualTo(3)
        assertThat(run.refusalReason).contains("3 unavailable")

        // Report it the way the participation tick does: accrual is the only job due that day.
        val reportsFor = mutableListOf<CloseParticipationReport>()
        InterestCloseParticipationScheduler(
            closeJobRuns = runs,
            sink = { _, r ->
                reportsFor += r
                Uni.createFrom().item(Unit)
            },
            meterRegistry = SimpleMeterRegistry(),
            clock = Clock.fixed(Instant.parse("2026-10-03T22:30:00Z"), ZoneOffset.UTC),
            domainMetrics = noOpDomainMetrics(),
            accrualCron = "0 0 1 * * ?",
            capitalizationCron = "off",
            lookbackDays = 1,
        ).reportRecentDays().await().indefinitely()
        assertThat(reportsFor.single { it.businessDate == day }.outcome).isEqualTo(CloseOutcome.REFUSED)
    }

    @Test
    fun `an incomplete accrual tick does not refresh liveness (balances, listing, accruals)`() {
        val outcomes = listOf(
            AccrualRunOutcome(0, 0, 3, "balance unavailable"),
            AccrualRunOutcome(0, 0, 0, "account listing failed", listingFailed = true),
            AccrualRunOutcome(1, 2, 0, "accrual failed"),
        )
        for (outcome in outcomes) {
            val stubbed = object : AccrueInterestUseCase {
                override fun accrue(request: AccrualRequest): Uni<InterestAccrual> = error("unused")
                override fun accrueAll(date: LocalDate): Uni<Int> = error("unused")
                override fun accrueAllWithOutcome(date: LocalDate): Uni<AccrualRunOutcome> =
                    Uni.createFrom().item(outcome)
            }
            val registry = SimpleMeterRegistry()
            val accrualRuns = RecordingCloseJobRuns()
            val scheduler =
                InterestAccrualScheduler(stubbed, pragueHalfPastMidnight, metricsOver(registry), accrualRuns)
            scheduler.onStart(StartupEvent())
            scheduler.runDailyAccrual().await().indefinitely()

            val gauge = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "interest-accrual").gauge()?.value()
            assertThat(gauge).describedAs("$outcome").isEqualTo(0.0)
            val run = accrualRuns.runs.getValue(LocalDate.of(2026, 10, 3) to InterestCloseJobs.ACCRUAL)
            assertThat(run.refused).describedAs("$outcome").isGreaterThan(0)
        }
    }

    @Test
    fun `every interest cron is evaluated in the zone the close reporter assumes`() {
        assertThat(java.time.ZoneId.of(InterestCloseJobs.SCHEDULE_ZONE)).isEqualTo(AccountingClock.bank(clock).zone)
        val pinned = listOf(
            InterestAccrualScheduler::class.java.getMethod("runDailyAccrual"),
            InterestCapitalizationScheduler::class.java.getMethod("runMonthlyCapitalization"),
            InterestCloseParticipationScheduler::class.java.getMethod("reportRecentDays"),
        )
        for (method in pinned) {
            val scheduled = method.getAnnotation(io.quarkus.scheduler.Scheduled::class.java)
            assertThat(scheduled.timeZone).describedAs(method.declaringClass.simpleName)
                .isEqualTo(InterestCloseJobs.SCHEDULE_ZONE)
        }
    }
}
