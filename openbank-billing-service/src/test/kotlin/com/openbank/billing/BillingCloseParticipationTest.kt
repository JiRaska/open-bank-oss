// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.port.out.BillableAccountDiscoveryPort
import com.openbank.billing.application.port.out.BillableAccountsPage
import com.openbank.billing.application.port.out.BillingCloseEvidencePort
import com.openbank.billing.application.port.out.CloseJobRunPort
import com.openbank.billing.application.port.out.CycleFeePostings
import com.openbank.billing.application.usecase.BillingCycleService
import com.openbank.billing.application.usecase.BillingCycleService.CycleOutcome
import com.openbank.billing.infrastructure.scheduler.BillingCloseJobs
import com.openbank.billing.infrastructure.scheduler.BillingCloseParticipationScheduler
import com.openbank.billing.infrastructure.scheduler.BillingCycleScheduler
import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.observability.DomainMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional

/**
 * ADR-0342 participant side for billing: the cycle is due only on the days its cron fires; a due day
 * is reported only once the sweep recorded its run AND no fee of the cycle is still pending in the
 * outbox; failed accounts, an aborted sweep and FAILED fees (a closed-day refusal included) make it
 * REFUSED, never RAN. The sweep files its evidence under the Prague accounting day it billed for.
 */
class BillingCloseParticipationTest {

    private class Runs : CloseJobRunPort {
        val rows = linkedMapOf<Pair<LocalDate, String>, CloseJobRun>()
        override fun record(run: CloseJobRun): Uni<Unit> {
            rows[run.businessDate to run.job] = run
            return Uni.createFrom().item(Unit)
        }

        override fun findBetween(from: LocalDate, to: LocalDate): Uni<List<CloseJobRun>> =
            Uni.createFrom().item(rows.values.filter { it.businessDate in from..to })
    }

    private val runs = Runs()
    private var postings = CycleFeePostings(pending = 0, posted = 0, failed = 0)
    private val postingsAsked = mutableListOf<String>()
    private val delivered = mutableListOf<CloseParticipationReport>()

    // 2026-07-03T10:00Z: the lookback covers 2026-06-30 .. 2026-07-02; the cycle fires on 2026-07-01.
    private val clock = Clock.fixed(Instant.parse("2026-07-03T10:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 7, 3)
    private val cycleDay = LocalDate.of(2026, 7, 1)

    private fun reporter(enabled: Boolean = true) = BillingCloseParticipationScheduler(
        evidence = object : BillingCloseEvidencePort, CloseJobRunPort by runs {
            override fun postingsFor(cycleId: String): Uni<CycleFeePostings> {
                postingsAsked += cycleId
                return Uni.createFrom().item(postings)
            }
        },
        sink = { participant, report ->
            assertThat(participant).isEqualTo(BillingCloseJobs.PARTICIPANT)
            delivered += report
            Uni.createFrom().item(Unit)
        },
        meterRegistry = SimpleMeterRegistry(),
        clock = clock,
        domainMetrics = noOpDomainMetrics(),
        cycleEnabled = enabled,
        cycleCron = "0 0 3 1 * ?",
        lookbackDays = 3,
    )

    private fun noOpDomainMetrics(): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns false
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun cycleRan(items: Int = 2, refused: Int = 0, reason: String? = null) {
        runs.record(CloseJobRun(cycleDay, BillingCloseJobs.CYCLE, items, 0, refused, reason, clock.instant()))
    }

    private fun reportFor(day: LocalDate) = delivered.single { it.businessDate == day }

    @Test
    fun `the cycle is due only on the day its cron fires, and never when the sweep is off`() {
        assertThat(reporter().isDue(cycleDay)).isTrue()
        assertThat(reporter().isDue(LocalDate.of(2026, 7, 2))).isFalse()
        assertThat(reporter(enabled = false).isDue(cycleDay)).isFalse()
    }

    @Test
    fun `days the cycle is not due are SKIPPED`() {
        reporter().reportDays(today).await().indefinitely()
        assertThat(reportFor(LocalDate.of(2026, 6, 30)).outcome).isEqualTo(CloseOutcome.SKIPPED)
        assertThat(reportFor(LocalDate.of(2026, 7, 2)).outcome).isEqualTo(CloseOutcome.SKIPPED)
    }

    @Test
    fun `a due day whose cycle has not run is not reported, so the ledger holds it`() {
        reporter().reportDays(today).await().indefinitely()
        assertThat(delivered.map { it.businessDate }).doesNotContain(cycleDay)
    }

    @Test
    fun `a cycle whose fees are still pending in the outbox is not reported yet`() {
        cycleRan()
        postings = CycleFeePostings(pending = 1, posted = 1, failed = 0)

        reporter().reportDays(today).await().indefinitely()

        assertThat(delivered.map { it.businessDate }).doesNotContain(cycleDay)
        assertThat(postingsAsked).containsExactly("2026-07")
    }

    @Test
    fun `a finished cycle with every fee booked is RAN with the booked journals`() {
        cycleRan(items = 2)
        postings = CycleFeePostings(pending = 0, posted = 3, failed = 0)

        reporter().reportDays(today).await().indefinitely()

        val report = reportFor(cycleDay)
        assertThat(report.outcome).isEqualTo(CloseOutcome.RAN)
        assertThat(report.journalsPosted).isEqualTo(3)
        assertThat(report.itemsProcessed).isEqualTo(2)
    }

    @Test
    fun `a fee the ledger refused into a closed day makes the day REFUSED`() {
        cycleRan(items = 2)
        postings = CycleFeePostings(pending = 0, posted = 2, failed = 1)

        reporter().reportDays(today).await().indefinitely()

        val report = reportFor(cycleDay)
        assertThat(report.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(report.journalsPosted).isZero()
        assertThat(report.reason).contains("1 ${BillingCloseJobs.REASON_FEES_NOT_POSTED}")
    }

    @Test
    fun `accounts that failed to assess make the day REFUSED even when every fee posted`() {
        cycleRan(items = 1, refused = 1, reason = BillingCloseJobs.REASON_ACCOUNTS_FAILED)
        postings = CycleFeePostings(pending = 0, posted = 1, failed = 0)

        reporter().reportDays(today).await().indefinitely()

        assertThat(reportFor(cycleDay).outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(reportFor(cycleDay).reason).contains(BillingCloseJobs.REASON_ACCOUNTS_FAILED)
    }

    // ── what the sweep records ──────────────────────────────────────────────────────────────

    private fun sweep(
        clockAt: Clock,
        csv: String?,
        discovery: BillableAccountDiscoveryPort? = null,
        svc: BillingCycleService,
    ) = BillingCycleScheduler().apply {
        billingCycleService = svc
        clock = clockAt
        enabled = true
        accountIdsCsv = Optional.ofNullable(csv)
        currency = "CZK"
        discoveryEnabled = discovery != null
        discoveryPageSize = 2
        if (discovery != null) accountDiscovery = discovery
        domainMetrics = noOpDomainMetrics()
        closeJobRuns = runs
    }

    @Test
    fun `a sweep just after Prague midnight bills and records the Prague day, not the UTC date`(): Unit = runBlocking {
        // 2026-06-30T22:30Z is 00:30 on 2026-07-01 in Prague: the UTC date is still June.
        val pastMidnight = Clock.fixed(Instant.parse("2026-06-30T22:30:00Z"), ZoneOffset.UTC)
        val svc = mockk<BillingCycleService>()
        coEvery { svc.runCycleWithOutcome(any(), any(), any(), any()) } returns CycleOutcome(1, 0)

        sweep(pastMidnight, "acc-1", svc = svc).runSweep()

        coVerify(exactly = 1) { svc.runCycleWithOutcome("2026-07", listOf("acc-1"), "CZK", "MONTHLY") }
        assertThat(runs.rows.keys).containsExactly(cycleDay to BillingCloseJobs.CYCLE)
    }

    @Test
    fun `failed accounts are recorded as a refusal with a fixed category`(): Unit = runBlocking {
        val svc = mockk<BillingCycleService>()
        coEvery { svc.runCycleWithOutcome(any(), any(), any(), any()) } returns CycleOutcome(processed = 1, failed = 2)

        sweep(Clock.fixed(Instant.parse("2026-07-01T01:00:00Z"), ZoneOffset.UTC), "a,b,c", svc = svc).runSweep()

        val row = runs.rows.getValue(cycleDay to BillingCloseJobs.CYCLE)
        assertThat(row.itemsProcessed).isEqualTo(1)
        assertThat(row.refused).isEqualTo(2)
        assertThat(row.refusalReason).isEqualTo(BillingCloseJobs.REASON_ACCOUNTS_FAILED)
    }

    @Test
    fun `an aborted discovered sweep is recorded as a refusal, never a clean run`(): Unit = runBlocking {
        val svc = mockk<BillingCycleService>()
        val discovery = mockk<BillableAccountDiscoveryPort>()
        coEvery { discovery.activeAccounts(2, null) } returns BillableAccountsPage(listOf("a", "b"), nextCursor = "c1")
        coEvery { discovery.activeAccounts(2, "c1") } throws IllegalStateException("account-service unreachable")
        coEvery { svc.runCycleWithOutcome(any(), any(), any(), any()) } returns CycleOutcome(2, 0)

        sweep(Clock.fixed(Instant.parse("2026-07-01T01:00:00Z"), ZoneOffset.UTC), null, discovery, svc).runSweep()

        val row = runs.rows.getValue(cycleDay to BillingCloseJobs.CYCLE)
        assertThat(row.refused).isEqualTo(1)
        assertThat(row.refusalReason).isEqualTo(BillingCloseJobs.REASON_SWEEP_ABORTED)
        assertThat(row.refusalReason).doesNotContain("unreachable")
    }

    // ── catch-up for a missed cycle (#13709 review) ─────────────────────────────────────────

    private fun catchUpScheduler(clockAt: Clock, svc: BillingCycleService) =
        sweep(clockAt, "acc-1", svc = svc).apply { cron = "0 0 3 1 * ?" }

    @Test
    fun `a cycle missed on the 1st is caught up on the 2nd and filed under the due day`(): Unit = runBlocking {
        // 2026-07-02T08:00Z: the 03:00 Prague firing on 1 July has passed and no run is recorded.
        val svc = mockk<BillingCycleService>()
        coEvery { svc.runCycleWithOutcome(any(), any(), any(), any()) } returns CycleOutcome(1, 0)
        val s = catchUpScheduler(Clock.fixed(Instant.parse("2026-07-02T08:00:00Z"), ZoneOffset.UTC), svc)

        s.catchUp()

        coVerify(exactly = 1) { svc.runCycleWithOutcome("2026-07", listOf("acc-1"), "CZK", "MONTHLY") }
        assertThat(runs.rows.keys).containsExactly(cycleDay to BillingCloseJobs.CYCLE)
    }

    @Test
    fun `a cycle that already ran this month is not run again`(): Unit = runBlocking {
        cycleRan()
        val svc = mockk<BillingCycleService>()
        val s = catchUpScheduler(Clock.fixed(Instant.parse("2026-07-02T08:00:00Z"), ZoneOffset.UTC), svc)

        s.catchUp()

        coVerify(exactly = 0) { svc.runCycleWithOutcome(any(), any(), any(), any()) }
    }

    @Test
    fun `before this month's firing there is nothing to catch up`(): Unit = runBlocking {
        // 2026-07-01T00:30Z is 02:30 in Prague: the 03:00 firing has not happened; last month's has.
        val svc = mockk<BillingCycleService>()
        val s = catchUpScheduler(Clock.fixed(Instant.parse("2026-07-01T00:30:00Z"), ZoneOffset.UTC), svc)

        assertThat(s.missedDueDay()).isNull()
        s.catchUp()
        coVerify(exactly = 0) { svc.runCycleWithOutcome(any(), any(), any(), any()) }
    }
}
