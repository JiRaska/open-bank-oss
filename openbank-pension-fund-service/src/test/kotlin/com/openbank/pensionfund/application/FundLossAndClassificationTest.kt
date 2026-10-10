// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application

import com.openbank.pensionfund.application.port.MarketPricePort
import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.application.usecase.ClassificationCorrectionRequest
import com.openbank.pensionfund.application.usecase.FundAdministrationService
import com.openbank.pensionfund.application.usecase.FundDefinition
import com.openbank.pensionfund.application.usecase.FundReportingService
import com.openbank.pensionfund.application.usecase.NavCalculationRequest
import com.openbank.pensionfund.application.usecase.NavService
import com.openbank.pensionfund.application.usecase.PlaceOrderCommand
import com.openbank.pensionfund.application.usecase.PositionClassificationService
import com.openbank.pensionfund.application.usecase.PositionLine
import com.openbank.pensionfund.application.usecase.UnitRegisterService
import com.openbank.pensionfund.domain.model.ClassificationCorrectionStatus
import com.openbank.pensionfund.domain.model.ClassificationTarget
import com.openbank.pensionfund.domain.model.FourEyesViolationException
import com.openbank.pensionfund.domain.model.InstrumentClass
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

/**
 * Golden LOSS quarter (#12425): a bond falls 145 -> 135 in March while 50 of interest arrives in
 * February. The YTD P&L is a loss, and every line the ČNB P&L reports is still non-negative except
 * the result itself — a loss is a loss line, never a negative "income".
 *
 * Plus the instrument class: unclassified positions make the loans figure UNKNOWN, and the
 * four-eyes correction route is the only way to make it known.
 */
class FundLossAndClassificationTest {

    @Test
    fun `correction targets cover known classes and never permit unclassified`() {
        assertThat(ClassificationTarget.entries.map { it.instrumentClass() })
            .containsExactlyElementsOf(InstrumentClass.entries.filter { it != InstrumentClass.UNCLASSIFIED })
        assertThatThrownBy { ClassificationTarget.valueOf("UNCLASSIFIED") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(Instant.parse("2026-01-02T08:00:00Z"))
    private val store = InMemoryStore()
    private val prices = object : MarketPricePort {
        override suspend fun price(instrumentId: String, valuationDate: LocalDate, currency: String): BigDecimal? = null
    }
    private val admin = FundAdministrationService(store, clock, 30)
    private val navs = NavService(store, prices, clock)
    private val register = UnitRegisterService(store, clock)
    private val reporting = FundReportingService(store)
    private val classification = PositionClassificationService(store, clock)

    private fun at(instant: String) {
        clock.now = Instant.parse(instant)
    }

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

    private fun bond(price: String, cls: InstrumentClass? = InstrumentClass.DEBT_SECURITY) =
        PositionLine("CZ-BOND-1", BigDecimal("100"), BigDecimal(price), cls)

    private suspend fun seedLossQuarter(loanClass: InstrumentClass?): UUID {
        val fund = admin.createFund(
            FundDefinition(
                name = "Loss fund", isin = "CZ0000000037", lei = "315700ABCDEF12345678", depositaryReference = "DEP",
                custodyAccountReference = "CUST", currency = "CZK", riskClass = 3, mandatoryConservative = false,
                managementFeeRate = BigDecimal("0.01"), launchNavPerUnit = BigDecimal.ONE,
            ),
        ).id
        register.place(
            PlaceOrderCommand(UUID.randomUUID(), fund, OrderType.SUBSCRIBE, BigDecimal("15000"), null, null, "k1"),
        )
        strike(fund, "2026-01-31", "0", emptyList())
        // February: the 15 000 is invested in a bond and a loan; 50 of interest is in cash.
        val loan = PositionLine("LOAN-1", BigDecimal("1"), BigDecimal("500"), loanClass)
        strike(fund, "2026-02-28", "50", listOf(bond("145"), loan))
        // March: the bond falls 10 per unit; cash paid February's 11.55 fee.
        strike(fund, "2026-03-31", "38.45", listOf(bond("135"), loan))
        return fund
    }

    @Test
    fun `a loss quarter reports a loss line, not a negative income`(): Unit = runBlocking {
        val fund = seedLossQuarter(InstrumentClass.LOAN)
        val march = reporting.periodReport(fund, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"))
        val pl = march.profitAndLossYtd

        assertThat(pl.linesUnavailableReason).isNull()
        assertThat(pl.revaluationGains).isEqualByComparingTo("0.00")
        assertThat(pl.revaluationLosses).isEqualByComparingTo("1000.00")
        assertThat(pl.otherInvestmentResult).isEqualByComparingTo("50.00")
        // 15 050 x 1% x 28/365 = 11.55; 14 038.45 x 1% x 31/365 = 11.92.
        assertThat(pl.managementFees).isEqualByComparingTo("23.47")
        assertThat(pl.profitLoss).isEqualByComparingTo("-973.47")
        assertThat(pl.profitLoss).isEqualByComparingTo(
            pl.revaluationGains!! - pl.revaluationLosses!! + pl.otherInvestmentResult!! - pl.managementFees,
        )
        listOf(pl.revaluationGains, pl.revaluationLosses, pl.managementFees).forEach {
            assertThat(it!!.signum()).isGreaterThanOrEqualTo(0)
        }
    }

    @Test
    fun `a NAV struck before positions were recorded makes the P&L lines unknown, never zero`(): Unit = runBlocking {
        val fund = seedLossQuarter(InstrumentClass.LOAN)
        val feb = store.navs.values.single { it.fundId == fund && it.valuationDate == LocalDate.parse("2026-02-28") }
        store.commit(StoreChanges(navs = listOf(feb.copy(positionsRecorded = false))))

        val pl = reporting.periodReport(fund, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"))
            .profitAndLossYtd
        assertThat(pl.revaluationGains).isNull()
        assertThat(pl.revaluationLosses).isNull()
        assertThat(pl.otherInvestmentResult).isNull()
        assertThat(pl.linesUnavailableReason).contains("2026-02-28")
        // The measured result and the fee are still known.
        assertThat(pl.profitLoss).isEqualByComparingTo("-973.47")
    }

    @Test
    fun `loans are unknown while a closing position is unclassified, and known after a four-eyes correction`(): Unit =
        runBlocking {
            val fund = seedLossQuarter(loanClass = null)
            val from = LocalDate.parse("2026-01-01")
            val to = LocalDate.parse("2026-03-31")
            val before = reporting.periodReport(fund, from, to)
            assertThat(before.portfolio.unclassifiedCount).isEqualTo(1)
            assertThat(before.portfolio.loansOutstanding).isNull()

            val closingNav = before.closingNavId
            val loan = classification.positions(closingNav).single { it.instrumentId == "LOAN-1" }
            assertThat(loan.instrumentClass).isEqualTo(InstrumentClass.UNCLASSIFIED)

            val proposed = classification.propose(
                ClassificationCorrectionRequest(loan.id, InstrumentClass.LOAN, "depositary confirmation 2026-04-02"),
                "maker",
            )
            // Proposed is not effective.
            assertThat(reporting.periodReport(fund, from, to).portfolio.loansOutstanding).isNull()
            // The proposer cannot approve their own correction.
            assertThatThrownBy { runBlocking { classification.approve(proposed.id, "maker") } }
                .isInstanceOf(FourEyesViolationException::class.java)
            // Only one correction may await approval per position.
            assertThatThrownBy {
                runBlocking {
                    classification.propose(
                        ClassificationCorrectionRequest(loan.id, InstrumentClass.OTHER, "second opinion"),
                        "other-maker",
                    )
                }
            }.isInstanceOf(IllegalStateException::class.java)

            val approved = classification.approve(proposed.id, "checker")
            assertThat(approved.status).isEqualTo(ClassificationCorrectionStatus.APPROVED)

            val after = reporting.periodReport(fund, from, to)
            assertThat(after.portfolio.unclassifiedCount).isEqualTo(0)
            assertThat(after.portfolio.loansOutstanding).isEqualByComparingTo("500.00")
            assertThat(after.portfolio.carryingValueByClass)
                .containsKeys(InstrumentClass.LOAN, InstrumentClass.DEBT_SECURITY)
            // A reclassification is a restatement: the fingerprint moves.
            assertThat(after.fingerprint).isNotEqualTo(before.fingerprint)
            // The recorded position row is untouched.
            assertThat(store.positions.single { it.id == loan.id }.instrumentClass)
                .isEqualTo(InstrumentClass.UNCLASSIFIED)
        }

    @Test
    fun `a correction proposed against a class that has since changed cannot be approved`(): Unit = runBlocking {
        val fund = seedLossQuarter(loanClass = null)
        val nav = reporting.periodReport(fund, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"))
            .closingNavId
        val loan = classification.positions(nav).single { it.instrumentId == "LOAN-1" }
        val first = classification.propose(ClassificationCorrectionRequest(loan.id, InstrumentClass.LOAN, "r1"), "m1")
        classification.approve(first.id, "c1")
        val second = classification.propose(ClassificationCorrectionRequest(loan.id, InstrumentClass.OTHER, "r2"), "m2")
        assertThat(second.fromClass).isEqualTo(InstrumentClass.LOAN)
        // Simulate a stale proposal: its fromClass no longer matches the effective class.
        store.commit(StoreChanges(classificationCorrections = listOf(second.copy(fromClass = InstrumentClass.EQUITY))))
        assertThatThrownBy { runBlocking { classification.approve(second.id, "c2") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("propose again")
    }

    @Test
    fun `a correction to UNCLASSIFIED or to the current class is refused`() {
        assertThatThrownBy {
            com.openbank.pensionfund.domain.model.PositionClassificationCorrection(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                InstrumentClass.LOAN,
                InstrumentClass.UNCLASSIFIED,
                "r",
                "m",
                Instant.EPOCH,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            com.openbank.pensionfund.domain.model.PositionClassificationCorrection(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                InstrumentClass.LOAN,
                InstrumentClass.LOAN,
                "r",
                "m",
                Instant.EPOCH,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
