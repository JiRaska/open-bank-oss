// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.close

import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.close.CloseParticipationSink
import com.openbank.libs.observability.DomainMetrics
import com.openbank.treasury.application.port.out.DayJournalPostings
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Treasury's day-close fold (ADR-0342): what each day is reported as, from the run evidence, the
 * in-flight marker and the day's journal events in the outbox.
 */
class TreasuryCloseParticipationSchedulerTest {

    private val evidence = RecordingCloseEvidence()
    private val delivered = mutableListOf<Pair<String, CloseParticipationReport>>()
    private val sink = CloseParticipationSink { p, r ->
        delivered += p to r
        Uni.createFrom().item(Unit)
    }

    private fun scheduler(accrualEnabled: Boolean = true) = TreasuryCloseParticipationScheduler(
        evidence,
        sink,
        SimpleMeterRegistry(),
        CLOCK,
        DomainMetrics(),
        accrualEnabled,
        BUDGET,
        1,
    )

    private fun report(accrualEnabled: Boolean = true): CloseParticipationReport? {
        scheduler(accrualEnabled).reportDays(TODAY).await().indefinitely()
        return delivered.singleOrNull { it.first == TreasuryCloseJobs.PARTICIPANT }?.second
    }

    private fun ran(refused: Int = 0, reason: String? = null, items: Int = 2) {
        evidence.runs[DAY to TreasuryCloseJobs.ACCRUAL] =
            CloseJobRun(DAY, TreasuryCloseJobs.ACCRUAL, items, 0, refused, reason, CLOCK.instant())
    }

    @Test
    fun `a completed accrual reports the day RAN with the day's journals`() {
        ran()
        evidence.postings[DAY] = DayJournalPostings(journals = 5, pending = 0, dead = 0)
        val r = report()!!
        assertThat(r.outcome).isEqualTo(CloseOutcome.RAN)
        assertThat(r.journalsPosted).isEqualTo(5)
        assertThat(r.itemsProcessed).isEqualTo(2)
    }

    @Test
    fun `no accrual run for the day reports nothing, so the ledger holds the day`() {
        assertThat(report()).isNull()
    }

    @Test
    fun `the accrual switched off reports the day SKIPPED`() {
        assertThat(report(accrualEnabled = false)!!.outcome).isEqualTo(CloseOutcome.SKIPPED)
    }

    // --- #13882 review item 1: accrual off must not fail open ---------------------------------------

    @Test
    fun `with the accrual off a day with pending journal events is still held`() {
        evidence.postings[DAY] = DayJournalPostings(journals = 1, pending = 1, dead = 0)
        assertThat(report(accrualEnabled = false)).isNull()
    }

    @Test
    fun `with the accrual off a DEAD journal event is REFUSED, never SKIPPED`() {
        evidence.postings[DAY] = DayJournalPostings(journals = 0, pending = 0, dead = 1)
        val r = report(accrualEnabled = false)!!
        assertThat(r.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(r.reason).contains(TreasuryCloseJobs.REASON_JOURNAL_EVENTS_DEAD)
    }

    @Test
    fun `with the accrual off a day with journals is RAN with them`() {
        evidence.postings[DAY] = DayJournalPostings(journals = 2, pending = 0, dead = 0)
        val r = report(accrualEnabled = false)!!
        assertThat(r.outcome).isEqualTo(CloseOutcome.RAN)
        assertThat(r.journalsPosted).isEqualTo(2)
    }

    // --- #13882 review item 4: a deal settled after the last pass holds its day ---------------------

    @Test
    fun `a settlement recorded after the last completed pass holds the day`() {
        ran()
        evidence.started[DAY to TreasuryCloseJobs.ACCRUAL] = CLOCK.instant().minus(Duration.ofHours(10))
        evidence.postings[DAY] =
            DayJournalPostings(1, 0, 0, lastSettlementAt = CLOCK.instant().minus(Duration.ofHours(9)))
        assertThat(report()).isNull()
    }

    @Test
    fun `a pass after midnight that started after the settlement releases the day`() {
        ran()
        evidence.started[DAY to TreasuryCloseJobs.ACCRUAL] = CLOCK.instant().minus(Duration.ofHours(10))
        evidence.runs[TODAY to TreasuryCloseJobs.ACCRUAL] =
            CloseJobRun(TODAY, TreasuryCloseJobs.ACCRUAL, 1, 0, 0, null, CLOCK.instant())
        evidence.started[TODAY to TreasuryCloseJobs.ACCRUAL] = CLOCK.instant().minus(Duration.ofHours(1))
        evidence.postings[DAY] =
            DayJournalPostings(1, 0, 0, lastSettlementAt = CLOCK.instant().minus(Duration.ofHours(9)))
        assertThat(report()!!.outcome).isEqualTo(CloseOutcome.RAN)
    }

    @Test
    fun `the day is held while its journal events are still pending in the outbox`() {
        ran()
        evidence.postings[DAY] = DayJournalPostings(journals = 5, pending = 1, dead = 0)
        assertThat(report()).isNull()
    }

    @Test
    fun `a journal event the outbox gave up on makes the day REFUSED`() {
        ran()
        evidence.postings[DAY] = DayJournalPostings(journals = 5, pending = 0, dead = 2)
        val r = report()!!
        assertThat(r.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(r.reason).contains("2 ${TreasuryCloseJobs.REASON_JOURNAL_EVENTS_DEAD}")
    }

    @Test
    fun `an accrual refused with ACCOUNTING_DAY_CLOSED reports the day REFUSED with that category`() {
        ran(refused = 1, reason = "1 ${TreasuryCloseJobs.REASON_DAY_CLOSED}")
        val r = report()!!
        assertThat(r.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(r.reason).contains("ACCOUNTING_DAY_CLOSED")
        assertThat(r.journalsPosted).isZero()
    }

    @Test
    fun `a pass still in flight holds the day even over an earlier RAN row`() {
        ran()
        evidence.inFlight[DAY to TreasuryCloseJobs.ACCRUAL] = CLOCK.instant().minus(Duration.ofMinutes(5))
        assertThat(report()).isNull()
    }

    @Test
    fun `a marker older than the derived stale threshold is a dead pass, reported REFUSED`() {
        val stale = TreasuryCloseJobs.staleAfter(BUDGET)
        assertThat(stale).isEqualTo(BUDGET.plus(TreasuryCloseJobs.STALE_MARGIN))
        evidence.inFlight[DAY to TreasuryCloseJobs.ACCRUAL] = CLOCK.instant().minus(stale).minusSeconds(1)
        val r = report()!!
        assertThat(r.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(r.reason).contains(TreasuryCloseJobs.REASON_PASS_ABANDONED)
    }

    @Test
    fun `a marker just inside the derived stale threshold still holds the day`() {
        evidence.inFlight[DAY to TreasuryCloseJobs.ACCRUAL] =
            CLOCK.instant().minus(TreasuryCloseJobs.staleAfter(BUDGET)).plusSeconds(1)
        assertThat(report()).isNull()
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC)
        val TODAY: LocalDate = LocalDate.parse("2026-10-21")
        val DAY: LocalDate = LocalDate.parse("2026-10-20")
        val BUDGET: Duration = Duration.ofMinutes(20)
    }
}
