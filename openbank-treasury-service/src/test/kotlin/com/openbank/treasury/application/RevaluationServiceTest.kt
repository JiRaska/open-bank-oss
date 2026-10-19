// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application

import com.openbank.treasury.application.port.out.BookValuation
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.DayCompletionRepository
import com.openbank.treasury.application.port.out.DealFairValue
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.RevaluationRecord
import com.openbank.treasury.application.port.out.RevaluationRepository
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
import com.openbank.treasury.domain.model.Frequency
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
        override suspend fun cumulativeBefore(dealId: UUID, asOf: LocalDate) =
            rows.values.filter { it.dealId == dealId && it.asOf < asOf }.maxByOrNull { it.asOf }?.cumulative
                ?: BigDecimal.ZERO
        override suspend fun forDate(asOf: LocalDate, limit: Int) = rows.values.filter { it.asOf == asOf }.take(limit)
        override suspend fun save(record: RevaluationRecord, journal: LedgerJournalRef?): Boolean {
            if (rows.containsKey(record.dealId to record.asOf)) return false
            rows[record.dealId to record.asOf] = record
            journal?.let { journals += it }
            return true
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
}
