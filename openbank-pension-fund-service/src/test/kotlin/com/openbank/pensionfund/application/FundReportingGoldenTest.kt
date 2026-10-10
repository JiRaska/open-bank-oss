// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application

import com.openbank.pensionfund.application.port.MarketPricePort
import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.application.usecase.FundAdministrationService
import com.openbank.pensionfund.application.usecase.FundDefinition
import com.openbank.pensionfund.application.usecase.FundReportingService
import com.openbank.pensionfund.application.usecase.NavCalculationRequest
import com.openbank.pensionfund.application.usecase.NavService
import com.openbank.pensionfund.application.usecase.PlaceOrderCommand
import com.openbank.pensionfund.application.usecase.PositionLine
import com.openbank.pensionfund.application.usecase.UnitRegisterService
import com.openbank.pensionfund.domain.model.ForwardPricer
import com.openbank.pensionfund.domain.model.FundPeriodReport
import com.openbank.pensionfund.domain.model.OrderType
import com.openbank.pensionfund.domain.model.PeriodNotReportableException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Golden month (#12425): one fund through September close and October, with subscriptions, a
 * redemption, a switch out, a unit fee, positions and a fee-accruing NAV. Every figure the ČNB
 * returns need is asserted against the identity it must satisfy, and the headline numbers are
 * pinned so a change in the arithmetic is a visible diff, not a silent restatement.
 */
class FundReportingGoldenTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(Instant.parse("2026-09-30T08:00:00Z"))
    private val store = InMemoryStore()
    private val prices = object : MarketPricePort {
        override suspend fun price(instrumentId: String, valuationDate: LocalDate, currency: String): BigDecimal? = null
    }
    private val admin = FundAdministrationService(store, clock, 30)
    private val navs = NavService(store, prices, clock)
    private val register = UnitRegisterService(store, clock)
    private val reporting = FundReportingService(store)
    private val c1 = UUID.randomUUID()
    private val c2 = UUID.randomUUID()
    private val c3 = UUID.randomUUID()

    private fun at(instant: String) {
        clock.now = Instant.parse(instant)
    }

    private fun definition(isin: String) = FundDefinition(
        name = "Fund $isin", isin = isin, lei = "315700ABCDEF12345678", depositaryReference = "DEP",
        custodyAccountReference = "CUST-$isin", currency = "CZK", riskClass = 3, mandatoryConservative = false,
        managementFeeRate = BigDecimal("0.01"), launchNavPerUnit = BigDecimal.ONE,
    )

    private suspend fun order(
        contract: UUID,
        fund: UUID,
        type: OrderType,
        amount: String?,
        units: String?,
        target: UUID? = null,
    ) = register.place(
        PlaceOrderCommand(
            contract,
            fund,
            type,
            amount?.let(::BigDecimal),
            units?.let(::BigDecimal),
            target,
            UUID.randomUUID().toString(),
        ),
    )

    private suspend fun strike(fund: UUID, date: String, cash: String, positions: List<PositionLine>): UUID {
        at("${date}T16:00:00Z")
        val nav = navs.calculate(
            fund,
            NavCalculationRequest(LocalDate.parse(date), positions, BigDecimal(cash), BigDecimal.ZERO),
            "maker",
        )
        at("${date}T17:00:00Z")
        navs.publish(nav.id, "checker")
        return nav.id
    }

    private suspend fun seedMonth(): Pair<UUID, UUID> {
        val fund = admin.createFund(definition("CZ0000000011")).id
        val target = admin.createFund(definition("CZ0000000029")).id
        // September close: two participants join at the launch price.
        order(c1, fund, OrderType.SUBSCRIBE, "10000", null)
        order(c2, fund, OrderType.SUBSCRIBE, "5000", null)
        strike(fund, "2026-09-30", "0", emptyList())
        // Mid-October: a third participant joins, c2 redeems 1 000 units, priced at 15 October.
        at("2026-10-15T08:00:00Z")
        order(c3, fund, OrderType.SUBSCRIBE, "2000", null)
        order(c2, fund, OrderType.REDEEM, null, "1000")
        val mid =
            strike(fund, "2026-10-15", "0", listOf(PositionLine("CZ-BOND-1", BigDecimal("100"), BigDecimal("151"))))
        // A unit fee on c1 at the mid-month NAV.
        val nav = store.navs.getValue(mid)
        val (fee, holding) = ForwardPricer.chargeFee(
            store.holdings.getValue(c1 to fund),
            BigDecimal("50"),
            nav,
            UUID.randomUUID(),
        )
        store.commit(StoreChanges(transactions = listOf(fee), holdings = listOf(holding)))
        // Month end: c1 switches 500 units out to the target fund, priced at 31 October.
        at("2026-10-20T08:00:00Z")
        order(c1, fund, OrderType.SWITCH_OUT, null, "500", target)
        strike(
            fund,
            "2026-10-31",
            "943.75",
            listOf(
                PositionLine("CZ-BOND-1", BigDecimal("100"), BigDecimal("152")),
                PositionLine("CZ-EQ-1", BigDecimal("10"), BigDecimal("110")),
            ),
        )
        return fund to target
    }

    private fun assertReconciles(r: FundPeriodReport) {
        // Balance sheet: assets = liabilities + equity.
        assertThat(r.balanceSheet.totalAssets)
            .isEqualByComparingTo(r.balanceSheet.totalLiabilities + r.balanceSheet.totalEquity)
        // P&L: profit = income - expenses.
        assertThat(r.profitAndLossYtd.profitLoss)
            .isEqualByComparingTo(r.profitAndLossYtd.income - r.profitAndLossYtd.expenses)
        // Unit roll-forward.
        assertThat(r.units.closing).isEqualByComparingTo(r.units.opening + r.units.issued - r.units.cancelled)
        // Entitlement roll-forward; closing entitlements are the fund's equity.
        assertThat(r.entitlements.closing)
            .isEqualByComparingTo(r.entitlements.opening + r.entitlements.increase - r.entitlements.decrease)
        assertThat(r.entitlements.closing).isEqualByComparingTo(r.balanceSheet.totalEquity)
    }

    @Test
    fun `october figures reconcile and match the golden values`(): Unit = runBlocking {
        val (fund, _) = seedMonth()
        val october = reporting.periodReport(fund, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))

        assertReconciles(october)
        // Units: 15 000 at September close; 2 000 / 1.006253 issued to c3; 1 000 redeemed + 49.689293 fee (rounded UP) + 500 switched out.
        assertThat(october.units.opening).isEqualByComparingTo("15000")
        assertThat(october.units.issued).isEqualByComparingTo("1987.571714")
        assertThat(october.units.cancelled).isEqualByComparingTo("1549.689293")
        assertThat(october.units.closing).isEqualByComparingTo("15437.882421")
        assertThat(october.units.unitValuePeriodMax).isGreaterThanOrEqualTo(october.units.unitValue.min(BigDecimal.TEN))
        // Flows priced in October, by kind.
        assertThat(october.flows.subscriptions).isEqualByComparingTo("2000.00")
        assertThat(october.flows.redemptions).isEqualByComparingTo("1006.25")
        assertThat(october.flows.unitFees).isEqualByComparingTo("50.00")
        assertThat(
            october.flows.switchesOut,
        ).isEqualByComparingTo(
            october.units.unitValue.multiply(BigDecimal("500")).setScale(2, java.math.RoundingMode.HALF_EVEN),
        )
        // Portfolio at the closing NAV: two instruments, 15 200 + 1 100.
        assertThat(october.portfolio.holdingsCount).isEqualTo(2)
        assertThat(october.portfolio.carryingValue).isEqualByComparingTo("16300.00")
        assertThat(october.portfolio.cash).isEqualByComparingTo("943.75")
        // Participants: three holders at month end; one subscribed in October.
        assertThat(october.participants.holders).isEqualTo(3)
        assertThat(october.participants.subscribing).isEqualTo(1)
        // No participant identifier is part of the report.
        assertThat(october.toString()).doesNotContain(c1.toString(), c2.toString(), c3.toString())
    }

    @Test
    fun `the quarter and the year-to-date P&L see the September inflows as capital, not profit`(): Unit = runBlocking {
        val (fund, _) = seedMonth()
        val q4 = reporting.periodReport(fund, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"))
        assertReconciles(q4)
        // Year to date: equity went 0 -> closing; the 15 000 subscribed in September is capital.
        val ytd = q4.profitAndLossYtd
        val closingEquity = q4.balanceSheet.totalEquity
        assertThat(ytd.profitLoss).isLessThan(closingEquity)
        assertThat(ytd.expenses.signum()).isPositive()
        val september = reporting.periodReport(fund, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"))
        assertReconciles(september)
        assertThat(september.units.issued).isEqualByComparingTo("15000")
        assertThat(september.profitAndLossYtd.profitLoss).isEqualByComparingTo("0")
        // No positions were recorded on an all-cash NAV: an empty, KNOWN portfolio.
        assertThat(september.portfolio.holdingsCount).isEqualTo(0)
    }

    @Test
    fun `the answer is reproducible, and a NAV correction changes the fingerprint`(): Unit = runBlocking {
        val (fund, _) = seedMonth()
        val from = LocalDate.parse("2026-10-01")
        val to = LocalDate.parse("2026-10-31")
        val first = reporting.periodReport(fund, from, to)
        assertThat(reporting.periodReport(fund, from, to)).isEqualTo(first)

        // Correct the mid-month NAV: transactions re-price, the register restates, the fingerprint moves.
        at("2026-11-02T08:00:00Z")
        val correction = navs.calculate(
            fund,
            NavCalculationRequest(
                LocalDate.parse("2026-10-15"),
                listOf(PositionLine("CZ-BOND-1", BigDecimal("100"), BigDecimal("150"))),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
            ),
            "maker",
        )
        at("2026-11-02T09:00:00Z")
        navs.publish(correction.id, "checker")
        val restated = reporting.periodReport(fund, from, to)
        assertReconciles(restated)
        assertThat(restated.fingerprint).isNotEqualTo(first.fingerprint)
    }

    @Test
    fun `a period with no published NAV is not reportable rather than reported as zero`(): Unit = runBlocking {
        val (fund, _) = seedMonth()
        assertThatThrownBy {
            runBlocking { reporting.periodReport(fund, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30")) }
        }.isInstanceOf(PeriodNotReportableException::class.java)
    }
}
