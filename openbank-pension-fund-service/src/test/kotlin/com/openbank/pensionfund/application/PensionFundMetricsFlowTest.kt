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
import com.openbank.pensionfund.application.usecase.StrategyChangeRequest
import com.openbank.pensionfund.application.usecase.StrategyDefinition
import com.openbank.pensionfund.application.usecase.UnitRegisterService
import com.openbank.pensionfund.domain.model.AllocationTarget
import com.openbank.pensionfund.domain.model.OrderType
import com.openbank.pensionfund.infrastructure.observability.MicrometerPensionFundMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
import java.util.concurrent.TimeUnit

/**
 * The use cases drive the Micrometer adapter with the values they ESTABLISHED: every assertion is
 * on a value, never on the mere presence of a meter, and nothing is counted for a step that failed.
 */
class PensionFundMetricsFlowTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val registry = SimpleMeterRegistry()
    private val metrics = MicrometerPensionFundMetrics(registry)
    private val clock = MutableClock(Instant.parse("2026-10-09T09:00:00Z"))
    private val store = InMemoryStore()
    private var feedDown = false
    private val prices = object : MarketPricePort {
        override suspend fun price(instrumentId: String, valuationDate: LocalDate, currency: String): BigDecimal? {
            check(!feedDown) { "price feed unreachable" }
            return if (instrumentId == "KNOWN") BigDecimal("10") else null
        }
    }
    private val admin = FundAdministrationService(store, clock, 30, metrics)
    private val navs = NavService(store, prices, clock, metrics)
    private val register = UnitRegisterService(store, clock, metrics)
    private val contract = UUID.randomUUID()

    private fun definition(isin: String) = FundDefinition(
        name = "Fund $isin", isin = isin, lei = "315700ABCDEF12345678", depositaryReference = "DEP",
        custodyAccountReference = "CUST-$isin", currency = "CZK", riskClass = 3, mandatoryConservative = false,
        managementFeeRate = BigDecimal.ZERO, launchNavPerUnit = BigDecimal.ONE,
    )

    private fun counter(name: String, vararg tags: String): Double =
        registry.find(name).tags(*tags).counters().sumOf { it.count() }

    private fun navEvents(isin: String, event: String, correction: Boolean) =
        counter("openbank.pension_fund.nav.events", "fund", isin, "event", event, "correction", correction.toString())

    private fun later(seconds: Long = 3600) {
        clock.now = clock.now.plusSeconds(seconds)
    }

    private suspend fun calculate(fundId: UUID, cash: String, date: String = "2026-10-09") = navs.calculate(
        fundId,
        NavCalculationRequest(LocalDate.parse(date), emptyList(), BigDecimal(cash), BigDecimal.ZERO),
        "maker",
    )

    @Test
    fun `nav pipeline, settlement money and publication lag are recorded by value`(): Unit = runBlocking {
        val fund = admin.createFund(definition("CZ0000000101"))
        val target = admin.createFund(definition("CZ0000000102"))
        register.place(PlaceOrderCommand(contract, fund.id, OrderType.SUBSCRIBE, BigDecimal("1000"), null, null, "k1"))
        // A retry is answered with the original order: a replay, not a second placement.
        register.place(PlaceOrderCommand(contract, fund.id, OrderType.SUBSCRIBE, BigDecimal("1000"), null, null, "k1"))

        assertThat(counter("openbank.pension_fund.orders", "fund", fund.isin, "type", "SUBSCRIBE", "status", "PENDING"))
            .isEqualTo(1.0)
        assertThat(counter("openbank.pension_fund.order.replays", "type", "SUBSCRIBE")).isEqualTo(1.0)

        val nav = calculate(fund.id, "0")
        later(LAG_SETUP_SECONDS)
        // 2026-10-09 ended at 2026-10-10T00:00Z; published at 2026-10-10T10:00Z -> 10h lag.
        navs.publish(nav.id, "checker")

        assertThat(navEvents(fund.isin, "calculated", false)).isEqualTo(1.0)
        assertThat(navEvents(fund.isin, "published", false)).isEqualTo(1.0)
        val lag = registry.find("openbank.pension_fund.nav.publication.lag").tag("fund", fund.isin).timer()!!
        assertThat(lag.count()).isEqualTo(1)
        assertThat(lag.totalTime(TimeUnit.HOURS)).isEqualTo(10.0)
        assertThat(counter("openbank.pension_fund.orders", "fund", fund.isin, "type", "SUBSCRIBE", "status", "SETTLED"))
            .isEqualTo(1.0)
        assertThat(
            counter("openbank.pension_fund.settled.amount", "fund", fund.isin, "type", "SUBSCRIBE", "currency", "CZK"),
        ).isEqualTo(1000.0)

        // A switch: the switch-out settles under the source fund, its switch-in leg is PENDING under the target.
        later()
        register.place(
            PlaceOrderCommand(contract, fund.id, OrderType.SWITCH_OUT, null, BigDecimal("400"), target.id, "k2"),
        )
        later()
        navs.publish(calculate(fund.id, "1000", "2026-10-10").id, "checker")
        assertThat(
            counter("openbank.pension_fund.orders", "fund", fund.isin, "type", "SWITCH_OUT", "status", "SETTLED"),
        )
            .isEqualTo(1.0)
        assertThat(
            counter("openbank.pension_fund.orders", "fund", target.isin, "type", "SWITCH_IN", "status", "PENDING"),
        )
            .isEqualTo(1.0)
        assertThat(
            counter("openbank.pension_fund.settled.amount", "fund", fund.isin, "type", "SWITCH_OUT", "currency", "CZK"),
        ).isEqualTo(400.0)
    }

    @Test
    fun `a correction is counted as one, with the transactions it re-priced, and a rejection as a rejection`(): Unit =
        runBlocking {
            val fund = admin.createFund(definition("CZ0000000103"))
            register.place(
                PlaceOrderCommand(contract, fund.id, OrderType.SUBSCRIBE, BigDecimal("500"), null, null, "k"),
            )
            later()
            navs.publish(calculate(fund.id, "0").id, "checker")

            later()
            val rejected = calculate(fund.id, "10")
            navs.reject(rejected.id, "checker")
            assertThat(navEvents(fund.isin, "rejected", true)).isEqualTo(1.0)

            later()
            navs.publish(calculate(fund.id, "10").id, "checker")
            assertThat(navEvents(fund.isin, "published", true)).isEqualTo(1.0)
            assertThat(navEvents(fund.isin, "published", false)).isEqualTo(1.0)
            assertThat(counter("openbank.pension_fund.nav.correction.repriced", "fund", fund.isin)).isEqualTo(1.0)
            // A correction's lag measures when the error was found, not the pipeline: not recorded.
            assertThat(registry.find("openbank.pension_fund.nav.publication.lag").timer()!!.count()).isEqualTo(1)
        }

    @Test
    fun `market price lookups are counted found, missing and failed, and a failed lookup is rethrown`(): Unit =
        runBlocking {
            val fund = admin.createFund(definition("CZ0000000104"))
            fun request(instrument: String) = NavCalculationRequest(
                LocalDate.parse("2026-10-08"),
                listOf(PositionLine(instrument, BigDecimal.ONE, null)),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
            )
            assertThatThrownBy { runBlocking { navs.calculate(fund.id, request("UNKNOWN"), "maker") } }
                .isInstanceOf(IllegalArgumentException::class.java)
            feedDown = true
            assertThatThrownBy { runBlocking { navs.calculate(fund.id, request("KNOWN"), "maker") } }
                .hasMessageContaining("price feed unreachable")
            feedDown = false
            navs.calculate(fund.id, request("KNOWN"), "maker")

            assertThat(counter("openbank.pension_fund.market_price.lookups", "outcome", "missing")).isEqualTo(1.0)
            assertThat(counter("openbank.pension_fund.market_price.lookups", "outcome", "failed")).isEqualTo(1.0)
            assertThat(counter("openbank.pension_fund.market_price.lookups", "outcome", "found")).isEqualTo(1.0)
            // Only the calculation that committed is counted.
            assertThat(navEvents(fund.isin, "calculated", false)).isEqualTo(1.0)
        }

    @Test
    fun `strategy changes are counted per four-eyes event`(): Unit = runBlocking {
        val fund = admin.createFund(definition("CZ0000000105"))
        val all = listOf(AllocationTarget(fund.id, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE))
        val strategy = admin.createStrategy(StrategyDefinition("S", all, emptyList()))
        val effective = LocalDate.parse("2026-11-09")
        val first = admin.submitChange(strategy.id, StrategyChangeRequest(all, emptyList(), "r", effective), "maker")
        val second = admin.submitChange(strategy.id, StrategyChangeRequest(all, emptyList(), "r", effective), "maker")
        admin.rejectChange(first.id, "checker")
        admin.approveChange(second.id, "checker")
        clock.now = Instant.parse("2026-11-10T09:00:00Z")
        admin.applyChange(second.id)

        assertThat(counter("openbank.pension_fund.strategy_changes", "event", "submitted")).isEqualTo(2.0)
        assertThat(counter("openbank.pension_fund.strategy_changes", "event", "rejected")).isEqualTo(1.0)
        assertThat(counter("openbank.pension_fund.strategy_changes", "event", "approved")).isEqualTo(1.0)
        assertThat(counter("openbank.pension_fund.strategy_changes", "event", "applied")).isEqualTo(1.0)
    }

    private companion object {
        /** From 09:00 on the valuation day to 10:00 the next day. */
        const val LAG_SETUP_SECONDS = 25L * 3600
    }
}
