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
        return delivered.lastOrNull {
            it.first == TreasuryCloseJobs.PARTICIPANT && it.second.businessDate == DAY
        }?.second
    }

    private fun ran(refused: Int = 0, reason: String? = null, items: Int = 2) {
        evidence.runs[DAY to TreasuryCloseJobs.ACCRUAL] =
            CloseJobRun(DAY, TreasuryCloseJobs.ACCRUAL, items, 0, refused, reason, CLOCK.instant())
    }

    @Test
    fun `an observed missing job stays required in a new reporter with scheduling off`() {
        assertThat(report()!!.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(report(accrualEnabled = false)!!.outcome).isEqualTo(CloseOutcome.REFUSED)
        ran()
        assertThat(report(accrualEnabled = false)!!.outcome).isEqualTo(CloseOutcome.RAN)
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
    fun `an acknowledged journal with an unapplied lifecycle holds even after a complete pass`() {
        ran()
        evidence.postings[DAY] = DayJournalPostings(1, 0, 0, unapplied = 1)
        assertThat(report()!!.outcome).isEqualTo(CloseOutcome.REFUSED)
    }

    @Test
    fun `an unapplied lifecycle also holds when accrual is switched off`() {
        evidence.postings[DAY] = DayJournalPostings(1, 0, 0, unapplied = 1)
        assertThat(report(accrualEnabled = false)!!.outcome).isEqualTo(CloseOutcome.REFUSED)
    }

    // --- #13883: an open posting intent is a journal the ledger may hold that treasury has not applied ---

    @Test
    fun `an open posting intent valued on the day holds it even after a complete pass`() {
        ran()
        evidence.postings[DAY] = DayJournalPostings(1, 0, 0, openIntents = 1)
        assertThat(report()!!.outcome).isEqualTo(CloseOutcome.REFUSED)
    }

    @Test
    fun `an open posting intent also holds the day when accrual is switched off`() {
        // No journal recorded at all: without the intent the day would be reported SKIPPED, short.
        evidence.postings[DAY] = DayJournalPostings(0, 0, 0, openIntents = 1)
        assertThat(report(accrualEnabled = false)!!.outcome).isEqualTo(CloseOutcome.REFUSED)
        evidence.postings[DAY] = DayJournalPostings(0, 0, 0)
        assertThat(report(accrualEnabled = false)!!.outcome).isEqualTo(CloseOutcome.SKIPPED)
    }

    @Test
    fun `no accrual run explicitly refuses the day`() {
        assertThat(report()!!.outcome).isEqualTo(CloseOutcome.REFUSED)
    }

    @Test
    fun `the accrual switched off reports the day SKIPPED`() {
        assertThat(report(accrualEnabled = false)!!.outcome).isEqualTo(CloseOutcome.SKIPPED)
    }

    // --- #13882 review item 1: accrual off must not fail open ---------------------------------------

    @Test
    fun `with the accrual off a day with pending journal events is still held`() {
        evidence.postings[DAY] = DayJournalPostings(journals = 1, pending = 1, dead = 0)
        assertThat(report(accrualEnabled = false)!!.outcome).isEqualTo(CloseOutcome.REFUSED)
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
        assertThat(report()!!.outcome).isEqualTo(CloseOutcome.REFUSED)
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
    fun `a later pass that was REFUSED does not release the settlement hold`() {
        ran()
        evidence.started[DAY to TreasuryCloseJobs.ACCRUAL] = CLOCK.instant().minus(Duration.ofHours(10))
        evidence.runs[TODAY to TreasuryCloseJobs.ACCRUAL] = CloseJobRun(
            TODAY,
            TreasuryCloseJobs.ACCRUAL,
            1,
            0,
            3,
            "3 ${TreasuryCloseJobs.REASON_ACCRUAL_UNFINISHED}",
            CLOCK.instant(),
        )
        evidence.started[TODAY to TreasuryCloseJobs.ACCRUAL] = CLOCK.instant().minus(Duration.ofHours(1))
        evidence.postings[DAY] =
            DayJournalPostings(1, 0, 0, lastSettlementAt = CLOCK.instant().minus(Duration.ofHours(9)))
        assertThat(report()!!.outcome).isEqualTo(CloseOutcome.REFUSED)
    }

    @Test
    fun `the day is held while its journal events are still pending in the outbox`() {
        ran()
        evidence.postings[DAY] = DayJournalPostings(journals = 5, pending = 1, dead = 0)
        assertThat(report()!!.outcome).isEqualTo(CloseOutcome.REFUSED)
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
        assertThat(report()!!.outcome).isEqualTo(CloseOutcome.REFUSED)
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
    fun `new pending lifecycle work revokes earlier completion with a stable refusal receipt`() {
        ran()
        val poller = scheduler()
        poller.reportDays(TODAY).await().indefinitely()
        val before = delivered.last { it.second.businessDate == DAY }.second
        evidence.postings[DAY] = DayJournalPostings(1, 0, 0, openIntents = 1)
        poller.reportDays(TODAY).await().indefinitely()
        val refused = delivered.last { it.second.businessDate == DAY }.second
        assertThat(before.outcome).isEqualTo(CloseOutcome.RAN)
        assertThat(refused.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(refused.sourceRunId).matches("[a-f0-9]{64}").isNotEqualTo(before.sourceRunId)
        assertThat(refused.exercisedJobs).isEmpty()
        assertThat(refused.journalsPosted).isZero()
        poller.reportDays(TODAY).await().indefinitely()
        assertThat(delivered.count { it.second.businessDate == DAY }).isEqualTo(2)
    }

    @Test
    fun `non scheduled journal receipts keep their identity until durable evidence changes`() {
        evidence.postings[DAY] = DayJournalPostings(2, 0, 0)
        val first = report(accrualEnabled = false)!!
        assertThat(report(accrualEnabled = false)!!.sourceRunId).isEqualTo(first.sourceRunId)
        assertThat(first.sourceRunId).matches("[a-f0-9]{64}")
        evidence.postings[DAY] = DayJournalPostings(3, 0, 0)
        assertThat(report(accrualEnabled = false)!!.sourceRunId).isNotEqualTo(first.sourceRunId)
    }

    @Test
    fun `a marker just inside the derived stale threshold still holds the day`() {
        evidence.inFlight[DAY to TreasuryCloseJobs.ACCRUAL] =
            CLOCK.instant().minus(TreasuryCloseJobs.staleAfter(BUDGET)).plusSeconds(1)
        assertThat(report()!!.outcome).isEqualTo(CloseOutcome.REFUSED)
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC)
        val TODAY: LocalDate = LocalDate.parse("2026-10-21")
        val DAY: LocalDate = LocalDate.parse("2026-10-20")
        val BUDGET: Duration = Duration.ofMinutes(20)
    }
}
