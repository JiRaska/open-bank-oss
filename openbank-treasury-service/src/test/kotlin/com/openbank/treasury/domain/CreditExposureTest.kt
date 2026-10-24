// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.libs.finance.schedule.Frequency
import com.openbank.treasury.domain.DealFixtures.NOW
import com.openbank.treasury.domain.DealFixtures.bankA
import com.openbank.treasury.domain.DealFixtures.dealer
import com.openbank.treasury.domain.DealFixtures.seededCreditTerms
import com.openbank.treasury.domain.model.CollateralTerms
import com.openbank.treasury.domain.model.CreditExposure
import com.openbank.treasury.domain.model.CreditMeasureMissingException
import com.openbank.treasury.domain.model.CreditTerms
import com.openbank.treasury.domain.model.DayCountConvention
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealHeader
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.LegRate
import com.openbank.treasury.domain.model.LegSpec
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.PayReceive
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.RateType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0315 D4: every product that carries counterparty credit risk consumes its line, measured by
 * ONE function; a derivative or collateralised repo that cannot be measured is refused, never zero.
 */
class CreditExposureTest {
    private val trade = LocalDate.parse("2026-10-29")
    private val start = LocalDate.parse("2026-11-02")

    private fun leg(pr: PayReceive, ccy: String, notional: String, end: LocalDate, rate: LegRate) = LegSpec(
        pr, ccy, BigDecimal(notional), start, end, DayCountConvention.ACT_360, rate,
        accrualFrequency = Frequency.ANNUAL, paymentFrequency = Frequency.ANNUAL,
    )

    private fun deal(product: ProductType, legs: List<LegSpec>, header: DealHeader = DealHeader()) = Deal.draft(
        id = UUID.randomUUID(),
        product = product,
        counterpartyId = bankA.id,
        currency = legs.first().currency,
        principal = legs.filter { it.currency == legs.first().currency }.maxOf { it.notional },
        rate = BigDecimal("4.00"),
        tradeDate = trade,
        valueDate = legs.minOf { it.accrualStart },
        maturityDate = legs.maxOf { it.accrualEnd },
        actor = dealer,
        at = NOW,
        legs = legs,
        header = header,
    )

    private fun irs(notional: String, years: Long) = deal(
        ProductType.IRS,
        listOf(
            leg(PayReceive.PAY, "CZK", notional, start.plusYears(years), LegRate.fixed(BigDecimal("4.00"))),
            leg(
                PayReceive.RECEIVE,
                "CZK",
                notional,
                start.plusYears(years),
                LegRate(RateType.FLOATING, index = "PRIBOR_6M", resetFrequency = Frequency.SEMI_ANNUAL),
            ),
        ),
    )

    private fun fxForward() = deal(
        ProductType.FX_FORWARD,
        listOf(
            leg(PayReceive.RECEIVE, "EUR", "2000000.00", start, LegRate.NONE)
                .copy(
                    accrualFrequency = Frequency.Zero,
                    paymentFrequency = Frequency.Zero,
                    finalNotionalExchange = true,
                ),
            leg(PayReceive.PAY, "CZK", "48949778.00", start, LegRate.NONE)
                .copy(
                    accrualFrequency = Frequency.Zero,
                    paymentFrequency = Frequency.Zero,
                    finalNotionalExchange = true,
                ),
        ),
    )

    private fun reverseRepo(cash: String, nominal: String, dirtyPrice: String?, haircut: String = "3") = deal(
        ProductType.REVERSE_REPO,
        listOf(
            leg(PayReceive.RECEIVE, "CZK", cash, start.plusDays(14), LegRate.fixed(BigDecimal("3.45")))
                .copy(accrualFrequency = Frequency.Zero, paymentFrequency = Frequency.Zero),
        ),
        DealHeader(
            collateral = CollateralTerms(
                "CZ0001005243",
                BigDecimal(nominal),
                BigDecimal(haircut),
                dirtyPrice?.let(::BigDecimal),
            ),
        ),
    )

    @Test
    fun `an IRS consumes notional x factor by residual maturity, and one that would exceed the line is refused`() {
        // 2y residual -> the <=5y bucket, 1.0 %.
        assertThat(CreditExposure.of(irs("10000000.00", 2), trade, seededCreditTerms)).isEqualByComparingTo("100000.00")
        // 10y residual -> the open bucket, 1.5 %: 100 M notional consumes 1.5 M on a 1 M CZK line.
        val big = irs("100000000.00", 10)
        val amount = CreditExposure.of(big, trade, seededCreditTerms)
        assertThat(amount).isEqualByComparingTo("1500000.00")
        val check = LimitCheck.of(bankA, big, BigDecimal.ZERO, amount)
        assertThat(check.breached).isTrue()
        assertThatThrownBy {
            big.submit(
                dealer,
                check,
                NOW,
                DealFixtures.withinProduct,
            ).approve(DealFixtures.approver, check, NOW, DealFixtures.withinProduct)
        }
            .isInstanceOf(com.openbank.treasury.domain.model.LimitBreachedException::class.java)
        assertThat(
            big.submit(dealer, check, NOW, DealFixtures.withinProduct).copy(state = DealState.BOOKED).consumesLimit,
        ).isTrue()
    }

    @Test
    fun `the residual-maturity bucket boundaries at 1y and 5y hold on both sides`() {
        fun irsDays(days: Long): Deal {
            val end = trade.plusDays(days)
            return deal(
                ProductType.IRS,
                listOf(
                    leg(PayReceive.PAY, "CZK", "10000000.00", end, LegRate.fixed(BigDecimal("4.00"))),
                    leg(PayReceive.RECEIVE, "CZK", "10000000.00", end, LegRate.fixed(BigDecimal("4.00"))),
                ),
            )
        }
        fun measure(days: Long) = CreditExposure.of(irsDays(days), trade, seededCreditTerms)
        // ACT/365F: 365 d is exactly 1y (<=1y bucket, 0.5 %), 366 d falls into the <=5y bucket (1.0 %).
        assertThat(measure(365)).isEqualByComparingTo("50000.00")
        assertThat(measure(366)).isEqualByComparingTo("100000.00")
        // 1825 d is exactly 5y (1.0 %), 1826 d falls into the open bucket (1.5 %).
        assertThat(measure(1825)).isEqualByComparingTo("100000.00")
        assertThat(measure(1826)).isEqualByComparingTo("150000.00")
    }

    @Test
    fun `an FX forward consumes its CZK notional x factor on the CZK line`() {
        val d = fxForward()
        assertThat(d.limitCurrency).isEqualTo("CZK")
        // 3 days residual -> the <=1y bucket, 2.0 % of the CZK leg.
        assertThat(CreditExposure.of(d, trade, seededCreditTerms)).isEqualByComparingTo("978995.56")
    }

    @Test
    fun `a derivative with no effective factor is refused, never measured as zero`() {
        listOf(irs("10000000.00", 2), fxForward()).forEach { d ->
            assertThatThrownBy { CreditExposure.of(d, trade, CreditTerms.NONE) }
                .isInstanceOf(CreditMeasureMissingException::class.java)
                .extracting("code").isEqualTo("CONVERSION_FACTOR_MISSING")
            // The default (no-terms) limit amount refuses the same way.
            assertThatThrownBy { d.limitAmount }.isInstanceOf(CreditMeasureMissingException::class.java)
        }
        val irsOnly = seededCreditTerms.copy(
            factors = seededCreditTerms.factors.filter {
                it.product != ProductType.IRS
            },
        )
        assertThatThrownBy { CreditExposure.of(irs("10000000.00", 2), trade, irsOnly) }
            .isInstanceOf(CreditMeasureMissingException::class.java)
    }

    @Test
    fun `a fully collateralised reverse repo consumes about nothing, an under-collateralised one the gap`() {
        // 50 M nominal at 101.125 = 50.5625 M, less the stricter 10 % haircut = 45.50625 M of cover.
        assertThat(CreditExposure.of(reverseRepo("45000000.00", "50000000.00", "101.125"), trade, seededCreditTerms))
            .isEqualByComparingTo("0.00")
        assertThat(CreditExposure.of(reverseRepo("49045625.00", "50000000.00", "101.125"), trade, seededCreditTerms))
            .isEqualByComparingTo("3539375.00")
        // An unpriced collateral is no cover at all.
        assertThat(CreditExposure.of(reverseRepo("49045625.00", "50000000.00", null), trade, seededCreditTerms))
            .isEqualByComparingTo("49045625.00")
        assertThatThrownBy {
            CreditExposure.of(reverseRepo("45000000.00", "50000000.00", "101.125"), trade, CreditTerms.NONE)
        }
            .isInstanceOf(CreditMeasureMissingException::class.java)
            .extracting("code").isEqualTo("COLLATERAL_HAIRCUT_MISSING")
    }

    @Test
    fun `the legacy products keep their measure`() {
        assertThat(
            CreditExposure.of(DealFixtures.placement(), trade, CreditTerms.NONE),
        ).isEqualByComparingTo("100000.00")
        assertThat(
            CreditExposure.of(DealFixtures.placement(product = ProductType.MM_BORROWING), trade, CreditTerms.NONE),
        )
            .isEqualByComparingTo("0")
        val fx = DealFixtures.fxSpot()
        assertThat(CreditExposure.of(fx, trade, CreditTerms.NONE)).isEqualByComparingTo(fx.fx!!.counterAmount)
    }
}
