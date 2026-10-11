// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application

import com.openbank.pensionfund.application.port.MarketPricePort
import com.openbank.pensionfund.application.usecase.FundAdministrationService
import com.openbank.pensionfund.application.usecase.FundDefinition
import com.openbank.pensionfund.application.usecase.NavCalculationRequest
import com.openbank.pensionfund.application.usecase.NavService
import com.openbank.pensionfund.application.usecase.PlaceOrderCommand
import com.openbank.pensionfund.application.usecase.PositionLine
import com.openbank.pensionfund.application.usecase.UnitRegisterService
import com.openbank.pensionfund.domain.model.FourEyesViolationException
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.OrderStatus
import com.openbank.pensionfund.domain.model.OrderType
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

/** Use cases end to end over an in-memory store, with a clock the test advances. */
class UnitRegisterFlowTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(Instant.parse("2026-10-09T09:00:00Z"))
    private val store = InMemoryStore()
    private val prices = object : MarketPricePort {
        override suspend fun price(instrumentId: String, valuationDate: LocalDate, currency: String): BigDecimal? =
            if (instrumentId == "KNOWN") BigDecimal("10") else null
    }
    private val admin = FundAdministrationService(store, clock, 30)
    private val navs = NavService(store, prices, clock)
    private val register = UnitRegisterService(store, clock)
    private val contract = UUID.randomUUID()

    private fun key() = UUID.randomUUID().toString()

    private fun definition(isin: String) = FundDefinition(
        name = "Fund $isin", isin = isin, lei = "315700ABCDEF12345678", depositaryReference = "DEP",
        custodyAccountReference = "CUST-$isin", currency = "CZK", riskClass = 3, mandatoryConservative = false,
        managementFeeRate = BigDecimal.ZERO, launchNavPerUnit = BigDecimal.ONE,
    )

    private fun later(seconds: Long = 3600) {
        clock.now = clock.now.plusSeconds(seconds)
    }

    private suspend fun publish(fundId: UUID, cash: String, date: String = "2026-10-09"): UUID {
        val nav = navs.calculate(
            fundId,
            NavCalculationRequest(LocalDate.parse(date), emptyList(), BigDecimal(cash), BigDecimal.ZERO),
            "maker",
        )
        later()
        navs.publish(nav.id, "checker")
        return nav.id
    }

    @Test
    fun `orders queue until the next nav, then settle at it`(): Unit = runBlocking {
        val fund = admin.createFund(definition("CZ0000000011"))
        val order = register.place(
            PlaceOrderCommand(contract, fund.id, OrderType.SUBSCRIBE, BigDecimal("1000"), null, null, key()),
        )
        assertThat(order.status).isEqualTo(OrderStatus.PENDING)
        assertThat(register.valuation(contract).holdings).isEmpty()

        later()
        publish(fund.id, cash = "0") // launch NAV 1.000000
        val valuation = register.valuation(contract)
        assertThat(valuation.pendingOrders).isEmpty()
        assertThat(valuation.holdings.single().units).isEqualByComparingTo("1000")
        assertThat(valuation.holdings.single().value).isEqualByComparingTo("1000.00")
    }

    @Test
    fun `an order placed while a nav awaits approval settles at that nav`(): Unit = runBlocking {
        val fund = admin.createFund(definition("CZ0000000029"))
        val nav = navs.calculate(
            fund.id,
            NavCalculationRequest(LocalDate.parse("2026-10-09"), emptyList(), BigDecimal.ZERO, BigDecimal.ZERO),
            "maker",
        )
        later()
        register.place(PlaceOrderCommand(contract, fund.id, OrderType.SUBSCRIBE, BigDecimal("10"), null, null, key()))
        later()
        val publication = navs.publish(nav.id, "checker")
        // placed before publication -> settled at it (forward: the price was not yet known).
        assertThat(publication.settledOrders).isEqualTo(1)
    }

    @Test
    fun `a switch settles in two forward-priced legs`(): Unit = runBlocking {
        val source = admin.createFund(definition("CZ0000000037"))
        val target = admin.createFund(definition("CZ0000000045"))
        register.place(
            PlaceOrderCommand(contract, source.id, OrderType.SUBSCRIBE, BigDecimal("500"), null, null, key()),
        )
        later()
        publish(source.id, "0")
        later()
        register.place(
            PlaceOrderCommand(contract, source.id, OrderType.SWITCH_OUT, null, BigDecimal("200"), target.id, key()),
        )
        later()
        publish(source.id, "500", "2026-10-10") // NAV 1.000000 on 500 units
        assertThat(register.valuation(contract).pendingOrders.single().type).isEqualTo(OrderType.SWITCH_IN)

        later()
        publish(target.id, "0", "2026-10-10")
        val holdings = register.valuation(contract).holdings.associateBy { it.fundId }
        assertThat(holdings.getValue(source.id).units).isEqualByComparingTo("300")
        assertThat(holdings.getValue(target.id).units).isEqualByComparingTo("200")
    }

    @Test
    fun `cannot redeem units already committed to a queued redemption`(): Unit = runBlocking {
        val fund = admin.createFund(definition("CZ0000000052"))
        register.place(PlaceOrderCommand(contract, fund.id, OrderType.SUBSCRIBE, BigDecimal("100"), null, null, key()))
        later()
        publish(fund.id, "0")
        register.place(PlaceOrderCommand(contract, fund.id, OrderType.REDEEM, null, BigDecimal("60"), null, key()))
        assertThatThrownBy {
            runBlocking {
                register.place(
                    PlaceOrderCommand(contract, fund.id, OrderType.REDEEM, null, BigDecimal("60"), null, key()),
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a nav correction supersedes the original and re-prices its transactions`(): Unit = runBlocking {
        val fund = admin.createFund(definition("CZ0000000060"))
        register.place(PlaceOrderCommand(contract, fund.id, OrderType.SUBSCRIBE, BigDecimal("1000"), null, null, key()))
        later()
        val first = publish(fund.id, "0")
        register.place(PlaceOrderCommand(contract, fund.id, OrderType.SUBSCRIBE, BigDecimal("1000"), null, null, key()))
        later()
        // 1000 units outstanding, net 2000 -> NAV 2.000000; the second subscription buys 500 units.
        val wrong = publish(fund.id, "2000", "2026-10-10")
        assertThat(register.valuation(contract).holdings.single().units).isEqualByComparingTo("1500")

        // Corrected to 2500 net assets on the SAME 1000 units -> NAV 2.500000 -> 400 units.
        val correction = navs.calculate(
            fund.id,
            NavCalculationRequest(LocalDate.parse("2026-10-10"), emptyList(), BigDecimal("2500"), BigDecimal.ZERO),
            "maker",
        )
        assertThat(correction.correctsNavId).isEqualTo(wrong)
        assertThat(correction.navPerUnit).isEqualByComparingTo("2.5")
        later()
        val publication = navs.publish(correction.id, "checker")
        assertThat(publication.corrections.single().unitsDelta).isEqualByComparingTo("-100")
        assertThat(store.navs.getValue(wrong).status).isEqualTo(NavStatus.SUPERSEDED)
        assertThat(store.navs.getValue(first).status).isEqualTo(NavStatus.PUBLISHED)
        assertThat(register.valuation(contract).holdings.single().units).isEqualByComparingTo("1400")
    }

    @Test
    fun `nav publication is four-eyes and unpriced positions are refused`(): Unit = runBlocking {
        val fund = admin.createFund(definition("CZ0000000078"))
        val nav = navs.calculate(
            fund.id,
            NavCalculationRequest(
                LocalDate.parse("2026-10-09"),
                listOf(PositionLine("KNOWN", BigDecimal("3"), null)),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
            ),
            "maker",
        )
        assertThat(nav.figures.grossAssets).isEqualByComparingTo("30")
        assertThatThrownBy {
            runBlocking { navs.publish(nav.id, "maker") }
        }.isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy {
            runBlocking {
                navs.calculate(
                    fund.id,
                    NavCalculationRequest(
                        LocalDate.parse("2026-10-11"),
                        listOf(PositionLine("UNKNOWN", BigDecimal.ONE, null)),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                    ),
                    "maker",
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a retried placement returns the original order and a reused key with another order is refused`(): Unit =
        runBlocking {
            val fund = admin.createFund(definition("CZ0000000086"))
            val first = register.place(
                PlaceOrderCommand(contract, fund.id, OrderType.SUBSCRIBE, BigDecimal("100"), null, null, "k-1"),
            )
            val retry = register.place(
                PlaceOrderCommand(contract, fund.id, OrderType.SUBSCRIBE, BigDecimal("100.00"), null, null, "k-1"),
            )
            assertThat(retry.id).isEqualTo(first.id)
            assertThat(register.orders(contract)).hasSize(1)
            assertThatThrownBy {
                runBlocking {
                    register.place(
                        PlaceOrderCommand(contract, fund.id, OrderType.SUBSCRIBE, BigDecimal("999"), null, null, "k-1"),
                    )
                }
            }.isInstanceOf(IllegalStateException::class.java)
        }
}
