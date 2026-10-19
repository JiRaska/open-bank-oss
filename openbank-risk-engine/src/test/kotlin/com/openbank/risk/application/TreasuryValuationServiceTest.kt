// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.finance.calendar.Calendars
import com.openbank.libs.finance.leg.PayReceive
import com.openbank.libs.finance.product.DealTerms
import com.openbank.libs.finance.product.FixedLegTerms
import com.openbank.libs.finance.product.FloatingLegTerms
import com.openbank.libs.finance.product.LegTemplates
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.risk.application.port.`in`.PricingCommand
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.CurveSetRepository
import com.openbank.risk.application.port.out.FxFixingRate
import com.openbank.risk.application.port.out.FxFixingRepository
import com.openbank.risk.application.port.out.TreasuryCashFlowStore
import com.openbank.risk.application.port.out.TreasuryDealBook
import com.openbank.risk.application.port.out.TreasuryDealEvent
import com.openbank.risk.application.usecase.ReportingFixings
import com.openbank.risk.application.usecase.TreasuryValuationService
import com.openbank.risk.application.usecase.VarSettings
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.curve.ReferenceCurveSet
import com.openbank.risk.domain.model.Provenance
import com.openbank.risk.domain.model.SnapshotRun
import com.openbank.risk.domain.model.TieOutStatus
import com.openbank.risk.domain.model.TreasuryDeal
import com.openbank.risk.domain.valuation.BucketSet
import com.openbank.risk.domain.valuation.DealKind
import com.openbank.risk.domain.valuation.StoredFlowSet
import com.openbank.risk.infrastructure.rest.toResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class TreasuryValuationServiceTest {
    private val asOf = LocalDate.parse("2026-10-30")
    private val runId = UUID.randomUUID()
    private val run =
        SnapshotRun(
            runId,
            asOf,
            Instant.EPOCH,
            "sha256:run",
            Provenance.SYNTHETIC,
            TieOutStatus.TIED_OUT,
            0,
            emptyList(),
        )
    private val czk = Calendars.forCurrencies("CZK")

    /** Reference curves for [days] business days back to [asOf], each day's curve 1bp per day higher. */
    private fun history(days: Int): List<CurveSet> = (days downTo 0).map { k ->
        val date = asOf.minusDays(k.toLong())
        val set = ReferenceCurveSet.build(date, Instant.EPOCH)
        set.copy(
            id = UUID.nameUUIDFromBytes("h$date".toByteArray()),
            curves = set.curves.mapValues {
                it.value.parallelShift(BigDecimal(-k))
            },
        )
    }

    private class Book(val deals: MutableList<TreasuryDeal>) : TreasuryDealBook {
        override suspend fun dealsOnBook(asOf: LocalDate) = deals.filter { it.kind == DealKind.ACTUAL }

        override suspend fun valuationCandidates(asOf: LocalDate) = deals.toList()

        override suspend fun apply(event: TreasuryDealEvent) = false
    }

    private class Store(val sets: Map<UUID, StoredFlowSet> = emptyMap()) : TreasuryCashFlowStore {
        override suspend fun apply(set: StoredFlowSet) = false

        override suspend fun latest(dealIds: Collection<UUID>) = sets.filterKeys { it in dealIds }
    }

    private object NoFixings : FxFixingRepository {
        override suspend fun insertIfAbsent(rates: List<FxFixingRate>) = 0

        override suspend fun inEffect(
            source: String,
            currency: String,
            quoteCurrency: String,
            at: Instant,
        ): FxFixingRate? = null
    }

    private fun irs(id: UUID, kind: DealKind, book: String? = "B1", ccy: String = "CZK") = TreasuryDeal(
        dealId = id,
        product = "IRS",
        counterpartyId = "CP",
        currency = ccy,
        principal = BigDecimal("50000000"),
        rate = null,
        valueDate = asOf.minusDays(10),
        maturityDate = asOf.plusYears(5),
        state = TreasuryDeal.BOOKED,
        kind = kind,
        bookId = book,
        legs = LegTemplates.interestRateSwap(
            PayReceive.PAY,
            DealTerms(CurrencyCode.of(ccy), BigDecimal("50000000"), asOf.minusDays(10), asOf.plusYears(5), czk),
            FixedLegTerms(BigDecimal("0.035"), Frequency.ANNUAL, DayCount.ACT_360),
            FloatingLegTerms("PRIBOR_3M", Frequency.QUARTERLY, DayCount.ACT_360),
        ),
    )

    private fun service(deals: MutableList<TreasuryDeal>, historyDays: Int, minimum: Int): TreasuryValuationService {
        val sets = history(historyDays)
        val snapshots = mockk<SnapshotUseCase>()
        coEvery { snapshots.getInstruments(runId) } returns emptyList()
        coEvery { snapshots.getRun(runId) } returns run
        val curves = mockk<CurveSetRepository>()
        val today = sets.last()
        coEvery { curves.latestIdFor(asOf) } returns today.id
        coEvery { curves.findById(today.id) } returns today
        coEvery { curves.latestPerDayBetween(any(), any()) } returns sets
        return TreasuryValuationService(
            snapshots,
            curves,
            Book(deals),
            Store(),
            ReportingFixings(NoFixings),
            VarSettings(minimum, 30),
        )
    }

    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `only ACTUAL deals are valued, PLANNED reach the liquidity forecast only, output reruns byte-identically`() {
        val actual = UUID.randomUUID()
        val planned = UUID.randomUUID()
        val hypothetical = UUID.randomUUID()
        val deals =
            mutableListOf(
                irs(actual, DealKind.ACTUAL),
                irs(planned, DealKind.PLANNED),
                irs(hypothetical, DealKind.HYPOTHETICAL),
            )
        val svc = service(deals, 5, 3)
        val v = runBlocking { svc.valuation(runId, null, null) }
        assertThat(v.deals.map { it.dealId }).containsExactly(actual)
        assertThat(v.book).isNotNull
        val first = mapper.writeValueAsString(v.toResponse())
        val second = mapper.writeValueAsString(runBlocking { svc.valuation(runId, null, null) }.toResponse())
        assertThat(second).isEqualTo(first)

        val planFlows = runBlocking { svc.plannedFlows(asOf, history(0).single()) }
        assertThat(planFlows).isNotEmpty
        assertThat(planFlows.all { it.date.isAfter(asOf) }).isTrue()

        // A changed input is a changed hash — the reproducibility claim is not vacuous.
        deals += irs(UUID.randomUUID(), DealKind.ACTUAL)
        val changed = runBlocking { svc.valuation(runId, null, null) }
        assertThat(changed.context.valuationInputHash).isNotEqualTo(v.context.valuationInputHash)
        assertThat(runBlocking { svc.valuation(runId, "OTHER", null) }.deals).isEmpty()
    }

    @Test
    fun `VaR over enough history is a number with ES at or above it, too little history is a data gap`() {
        val deals = mutableListOf(irs(UUID.randomUUID(), DealKind.ACTUAL))
        val enough = runBlocking { service(deals, 5, 3).valueAtRisk(runId, null, BigDecimal("0.99"), 1) }
        assertThat(enough.dataGap).isNull()
        assertThat(enough.scenarioCount).isEqualTo(5)
        assertThat(enough.figures!!.expectedShortfall).isGreaterThanOrEqualTo(enough.figures!!.valueAtRisk)
        // Rates rise 1bp each day and the book pays fixed: every scenario is a gain, so VaR is negative.
        assertThat(enough.figures!!.valueAtRisk.signum()).isEqualTo(-1)

        val gap = runBlocking { service(deals, 5, 10).valueAtRisk(runId, null, BigDecimal("0.99"), 1) }
        assertThat(gap.figures).isNull()
        assertThat(gap.dataGap).contains("below the configured minimum of 10")
        assertThatThrownBy { runBlocking { service(deals, 5, 3).valueAtRisk(runId, null, BigDecimal("1.5"), 1) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a deal that cannot be valued leaves the book figures unstated, never computed over the rest`() {
        val deals =
            mutableListOf(irs(UUID.randomUUID(), DealKind.ACTUAL), irs(UUID.randomUUID(), DealKind.ACTUAL, ccy = "EUR"))
        val svc = service(deals, 5, 3)
        val v = runBlocking { svc.valuation(runId, null, null) }
        assertThat(v.book).isNull()
        assertThat(v.dataGap).contains("cannot be valued")
        assertThat(v.deals.count { it.unpriced != null }).isEqualTo(1)
        assertThat(runBlocking { svc.sensitivity(runId, null, null, BucketSet.IRRBB_STANDARD) }.result).isNull()
        assertThat(runBlocking { svc.valueAtRisk(runId, null, BigDecimal("0.99"), 1) }.figures).isNull()
    }

    @Test
    fun `pre-trade pricing prices HYPOTHETICAL legs with PV01 and buckets`() {
        val svc = service(mutableListOf(), 0, 1)
        val legs = irs(UUID.randomUUID(), DealKind.HYPOTHETICAL).legs!!
        val r = runBlocking { svc.price(PricingCommand(asOf, null, "CZK", legs)) }
        assertThat(r.sensitivity.pv01.signum()).isEqualTo(1)
        assertThat(r.sensitivity.dv01).hasSize(BucketSet.IRRBB_STANDARD.tenors.size)
        assertThat(r.provenance.wire).isEqualTo("synthetic")
        assertThatThrownBy { runBlocking { svc.price(PricingCommand(asOf, null, "EUR", legs)) } }
            .hasMessageContaining("cannot be priced")
    }
}
