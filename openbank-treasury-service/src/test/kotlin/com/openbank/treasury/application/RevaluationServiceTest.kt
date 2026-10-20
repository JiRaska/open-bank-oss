// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application

import com.openbank.libs.finance.schedule.Frequency
import com.openbank.treasury.application.port.out.BookValuation
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.DayCompletionRepository
import com.openbank.treasury.application.port.out.DealFairValue
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.PriorRevaluationUnpostedException
import com.openbank.treasury.application.port.out.RevaluationClaim
import com.openbank.treasury.application.port.out.RevaluationOutOfOrderException
import com.openbank.treasury.application.port.out.RevaluationRecord
import com.openbank.treasury.application.port.out.RevaluationRepository
import com.openbank.treasury.application.port.out.UnpostedRevaluations
import com.openbank.treasury.application.port.out.ValuationPort
import com.openbank.treasury.application.port.out.ValuationUnavailableException
import com.openbank.treasury.application.usecase.RevaluationService
import com.openbank.treasury.domain.DealFixtures
import com.openbank.treasury.domain.DealFixtures.NOW
import com.openbank.treasury.domain.DealFixtures.dealer
import com.openbank.treasury.domain.DealFixtures.withinLimit
import com.openbank.treasury.domain.model.DayCountConvention
import com.openbank.treasury.domain.model.DayStep
import com.openbank.treasury.domain.model.DayStepRecord
import com.openbank.treasury.domain.model.DayStepStatus
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealHeader
import com.openbank.treasury.domain.model.DealKind
import com.openbank.treasury.domain.model.LegRate
import com.openbank.treasury.domain.model.LegSpec
import com.openbank.treasury.domain.model.PayReceive
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.RateType
import com.openbank.treasury.domain.model.Side
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class RevaluationServiceTest {
    private val start = LocalDate.parse("2026-11-02")
    private val day = LocalDate.parse("2026-11-10")
    private val legs = listOf(
        LegSpec(
            PayReceive.PAY, "CZK", BigDecimal("10000000.00"), start, start.plusYears(2), DayCountConvention.ACT_360,
            LegRate.fixed(BigDecimal("4.00")), accrualFrequency = Frequency.ANNUAL, paymentFrequency = Frequency.ANNUAL,
        ),
        LegSpec(
            PayReceive.RECEIVE, "CZK", BigDecimal("10000000.00"), start, start.plusYears(2), DayCountConvention.ACT_360,
            LegRate(RateType.FLOATING, index = "PRIBOR_6M", resetFrequency = Frequency.SEMI_ANNUAL),
            accrualFrequency = Frequency.SEMI_ANNUAL,
            paymentFrequency = Frequency.SEMI_ANNUAL,
        ),
    )

    private fun bookedIrs(): Deal {
        val d = Deal.draft(
            id = UUID.randomUUID(),
            product = ProductType.IRS,
            counterpartyId = "SIMBK-A",
            currency = "CZK",
            principal = BigDecimal("10000000.00"),
            rate = BigDecimal("4.00"),
            tradeDate = LocalDate.parse("2026-10-29"),
            valueDate = start,
            maturityDate = start.plusYears(2),
            actor = dealer,
            at = NOW,
            kind = DealKind.ACTUAL,
            legs = legs,
            header = DealHeader(bookId = "TRADING-BOOK", calendars = listOf("CERTIS")),
        )
        return d.submit(dealer, withinLimit(d), NOW, DealFixtures.withinProduct)
            .approve(DealFixtures.approver, withinLimit(d), NOW, DealFixtures.withinProduct)
    }

    private class Store : RevaluationRepository {
        val rows = linkedMapOf<Pair<UUID, LocalDate>, RevaluationRecord>()
        val journals = mutableListOf<LedgerJournalRef>()
        override suspend fun find(dealId: UUID, asOf: LocalDate) = rows[dealId to asOf]
        override suspend fun forDate(asOf: LocalDate, limit: Int) = rows.values.filter { it.asOf == asOf }.take(limit)
        override suspend fun claim(
            dealId: UUID,
            asOf: LocalDate,
            snapshotId: UUID,
            fairValue: BigDecimal,
            carrying: BigDecimal,
            target: BigDecimal,
            keyOf: (BigDecimal) -> String?,
            at: java.time.Instant,
        ): RevaluationClaim {
            val latest = rows.values.filter { it.dealId == dealId }.maxByOrNull { it.asOf }
            val owed = latest != null && latest.idempotencyKey != null && latest.journalId == null
            if (owed && latest!!.asOf != asOf) throw PriorRevaluationUnpostedException(dealId, asOf, latest.asOf)
            if (latest != null && !latest.asOf.isBefore(asOf)) {
                val sameValue = latest.snapshotId == snapshotId && latest.fairValue.compareTo(fairValue) == 0
                if (owed || (latest.asOf == asOf && sameValue)) {
                    return RevaluationClaim.Existing(latest)
                }
                throw RevaluationOutOfOrderException(dealId, asOf, latest.asOf)
            }
            val change = target - (latest?.cumulative ?: BigDecimal.ZERO)
            val r =
                RevaluationRecord(
                    dealId, asOf, snapshotId, fairValue, carrying, target, change,
                    keyOf(
                        change,
                    ),
                    null, at,
                )
            rows[dealId to asOf] = r
            return RevaluationClaim.Claimed(r)
        }
        override suspend fun owed(limit: Int) = rows.values.filter {
            it.idempotencyKey != null && it.journalId == null
        }.sortedBy { it.recordedAt }.take(limit)
        override suspend fun unposted() = owed(Int.MAX_VALUE).let {
            UnpostedRevaluations(it.size, it.firstOrNull()?.recordedAt)
        }
        override suspend fun attachJournal(dealId: UUID, asOf: LocalDate, journal: LedgerJournalRef) {
            val r = rows.getValue(dealId to asOf)
            if (r.journalId == null) {
                rows[dealId to asOf] = r.copy(journalId = journal.journalId)
                journals += journal
            }
        }
    }

    private class Days : DayCompletionRepository {
        val steps = mutableListOf<DayStepRecord>()
        override suspend fun record(r: DayStepRecord) {
            steps += r
        }
        override suspend fun day(date: LocalDate) = steps.filter { it.date == date }
        override suspend fun between(from: LocalDate, to: LocalDate) = steps
    }

    private class Engine : ValuationPort {
        val books = mutableMapOf<LocalDate, BookValuation>()
        var down = false
        override suspend fun latestAsOf() = books.keys.maxOrNull()
        override suspend fun valuation(asOf: LocalDate) =
            if (down) throw ValuationUnavailableException("scripted outage") else books[asOf]
    }

    private val deals = InMemoryDeals()
    private val ledger = RecordingLedger()
    private val store = Store()
    private val days = Days()
    private val engine = Engine()
    private val service = RevaluationService(
        deals,
        mockk<CashFlowRepository>(),
        engine,
        store,
        ledger,
        Clock.fixed(NOW, ZoneOffset.UTC),
        dayCompletion = days,
    )

    private fun book(asOf: LocalDate, vararg fv: DealFairValue, currency: String = "CZK") =
        BookValuation(UUID.randomUUID(), asOf, currency, "sha256:t", fv.associateBy { it.dealId }, null)

    private fun fv(deal: Deal, clean: String?, unpriced: String? = null) =
        DealFairValue(deal.id, clean?.let(::BigDecimal), clean?.let(::BigDecimal), unpriced)

    private fun step() = days.steps.last { it.step == DayStep.REVALUATION }

    @Test
    fun `no fair-value deal - the step is SKIPPED and nothing is read or posted`() = runBlocking<Unit> {
        val run = service.revalue(day)
        assertThat(run.revalued).isEmpty()
        assertThat(step().status).isEqualTo(DayStepStatus.SKIPPED)
        assertThat(ledger.posted).isEmpty()
    }

    @Test
    fun `every kind of data gap is reported with its reason, never posted as zero, and FAILS the step`() =
        runBlocking<Unit> {
            val irs = bookedIrs().also { deals.rows[it.id] = it }

            service.revalue(day)
            assertThat(step().status).isEqualTo(DayStepStatus.FAILED)
            assertThat(step().detail).contains("no TIED_OUT risk snapshot")

            engine.books[day] = book(day)
            assertThat(service.revalue(day).gaps.single().reason).contains("not in the risk engine's valuation")
            engine.books[day] = book(day, fv(irs, null, "no PRIBOR_6M curve"))
            assertThat(service.revalue(day).gaps.single().reason).contains("unpriced")
            engine.books[day] = book(day, fv(irs, null))
            assertThat(service.revalue(day).gaps.single().reason).contains("no clean value")
            engine.books[day] = book(day, fv(irs, "100.00"), currency = "EUR")
            assertThat(service.revalue(day).gaps.single().reason).contains("valued in EUR")
            engine.down = true
            assertThat(service.revalue(day).gaps.single().reason).contains("valuation unavailable")

            assertThat(ledger.posted).isEmpty()
            assertThat(store.rows).isEmpty()
            assertThat(step().status).isEqualTo(DayStepStatus.FAILED)
        }

    @Test
    fun `a valued derivative posts its change to P&L once, a replay posts nothing, the next date only the delta`() =
        runBlocking<Unit> {
            val irs = bookedIrs().also { deals.rows[it.id] = it }
            engine.books[day] = book(day, fv(irs, "125000.00"))
            val first = service.revalue(day)
            assertThat(first.journals).isEqualTo(1)
            assertThat(step().status).isEqualTo(DayStepStatus.DONE)
            val spec = ledger.posted.single()
            assertThat(spec.event).isEqualTo(PostingEvent.REVALUATION)
            assertThat(spec.idempotencyKey).isEqualTo("treasury:${irs.id}:revaluation:$day")
            assertThat(
                spec.lines.filter {
                    it.side == Side.CREDIT
                }.sumOf { it.amount },
            ).isEqualByComparingTo("125000.00")

            val replay = service.revalue(day)
            assertThat(replay.journals).isZero()
            assertThat(replay.revalued.single()).isEqualTo(first.revalued.single())
            assertThat(ledger.posted).hasSize(1)

            val next = day.plusDays(1)
            engine.books[next] = book(next, fv(irs, "100000.00"))
            val second = service.revalue(next).revalued.single()
            assertThat(second.change).isEqualByComparingTo("-25000.00")
            assertThat(second.cumulative).isEqualByComparingTo("100000.00")
            assertThat(ledger.posted).hasSize(2)
        }

    @Test
    fun `an unchanged fair value records the date without posting`() = runBlocking<Unit> {
        val irs = bookedIrs().also { deals.rows[it.id] = it }
        engine.books[day] = book(day, fv(irs, "0.00"))
        val run = service.revalue(day)
        assertThat(run.journals).isZero()
        assertThat(run.revalued.single().journalId).isNull()
        assertThat(ledger.posted).isEmpty()
    }

    @Test
    fun `a single-deal run records no day step`() = runBlocking<Unit> {
        val irs = bookedIrs().also { deals.rows[it.id] = it }
        engine.books[day] = book(day, fv(irs, "10.00"))
        service.revalue(day, irs.id)
        assertThat(days.steps).isEmpty()
    }

    @Test
    fun `an earlier date after a later one is refused, never back-filled, the cumulative stays`() = runBlocking<Unit> {
        val irs = bookedIrs().also { deals.rows[it.id] = it }
        val d1 = day
        val d2 = day.plusDays(1)
        engine.books[d2] = book(d2, fv(irs, "100000.00"))
        engine.books[d1] = book(d1, fv(irs, "40000.00"))
        service.revalue(d2)
        assertThatThrownBy { runBlocking { service.revalue(d1, irs.id) } }
            .isInstanceOf(RevaluationOutOfOrderException::class.java)
        val book = service.revalue(d1)
        assertThat(book.failures.single()).isInstanceOf(RevaluationOutOfOrderException::class.java)
        assertThat(step().status).isEqualTo(DayStepStatus.FAILED)
        assertThat(ledger.posted).hasSize(1)
        assertThat(store.rows.values.single().cumulative).isEqualByComparingTo("100000.00")
        // A replay of D2 posts nothing new; a different value for D2 is refused, not re-measured.
        service.revalue(d2)
        assertThat(ledger.posted).hasSize(1)
        engine.books[d2] = book(d2, fv(irs, "90000.00"))
        assertThat(service.revalue(d2).failures.single()).isInstanceOf(RevaluationOutOfOrderException::class.java)
    }

    @Test
    fun `an unposted claim blocks the next date until the sweep posts it, then the next delta is right`() =
        runBlocking<Unit> {
            val irs = bookedIrs().also { deals.rows[it.id] = it }
            val d1 = day
            val d2 = day.plusDays(1)
            engine.books[d1] = book(d1, fv(irs, "500.00"))
            engine.books[d2] = book(d2, fv(irs, "800.00"))
            ledger.failFor = irs.id
            val first = service.revalue(d1)
            assertThat(first.failures).hasSize(1)
            assertThat(first.owed).isEqualTo(1)
            assertThat(step().status).isEqualTo(DayStepStatus.FAILED)
            assertThat(store.rows.values.single().journalId).isNull()

            // Ledger still down: D2 is refused (single deal 409), never measured against D1.
            assertThatThrownBy { runBlocking { service.revalue(d2, irs.id) } }
                .isInstanceOf(PriorRevaluationUnpostedException::class.java)
            assertThat(store.rows).hasSize(1)

            ledger.failFor = null
            val second = service.revalue(d2)
            assertThat(second.reposted).isEqualTo(1)
            assertThat(second.owed).isZero()
            assertThat(step().status).isEqualTo(DayStepStatus.DONE)
            assertThat(ledger.posted.map { it.idempotencyKey })
                .containsExactly("treasury:${irs.id}:revaluation:$d1", "treasury:${irs.id}:revaluation:$d2")
            assertThat(store.rows.getValue(irs.id to d2).change).isEqualByComparingTo("300.00")
            // The ledger's cumulative equals D2's fair value.
            // Both changes are gains (500, then 300): the credited unrealised gain sums to D2's fair value.
            val net = ledger.posted.sumOf { spec -> spec.lines.filter { it.side == Side.CREDIT }.sumOf { it.amount } }
            assertThat(net).isEqualByComparingTo("800.00")
            assertThat(store.journals).hasSize(2)
        }

    @Test
    fun `a retry of an owed row posts its stored change whatever the engine now says`() = runBlocking<Unit> {
        val irs = bookedIrs().also { deals.rows[it.id] = it }
        engine.books[day] = book(day, fv(irs, "500.00"))
        ledger.failFor = irs.id
        service.revalue(day)
        ledger.failFor = null
        engine.books[day] = book(day, fv(irs, "650.00"))
        val r = service.revalue(day, irs.id)
        assertThat(r.revalued.single().change).isEqualByComparingTo("500.00")
        assertThat(ledger.posted.single().lines.filter { it.side == Side.CREDIT }.sumOf { it.amount })
            .isEqualByComparingTo("500.00")
    }
}
