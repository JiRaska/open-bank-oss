// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Covers the lending bounded-context primitives (ADR-0027): repayment-schedule amortization, IFRS 9
 * three-stage ECL impairment, and DPD/arrears classification. Pure libs, no boot.
 */
class LendingPrimitivesTest {

    private fun eur(v: String) = Money.of(v, "EUR")
    private val firstDue = LocalDate.parse("2026-06-30")

    // --- Amortization: annuity ------------------------------------------------------------------

    @Test
    fun `annuity schedule has a constant payment and closes to exactly zero`() {
        val schedule = Amortization.schedule(
            principal = eur("12000.00"),
            nominalAnnualRate = BigDecimal("0.12"), // 12% p.a. => 1% per month
            termPeriods = 12,
            firstDueDate = firstDue,
        )

        assertThat(schedule.installments).hasSize(12)
        // Standard annuity payment for 12000 @ 1%/mo over 12 mo = 1066.19.
        assertThat(schedule.installments.first().payment).isEqualTo(eur("1066.19"))
        // Principal repaid sums to exactly the disbursed amount (no lost/phantom cents).
        assertThat(schedule.totalPrincipal).isEqualTo(eur("12000.00"))
        // Total payment reconciles to principal + interest.
        assertThat(schedule.totalPayment).isEqualTo(schedule.totalPrincipal + schedule.totalInterest)
        // The loan is fully amortized.
        assertThat(schedule.installments.last().closingBalance).isEqualTo(eur("0.00"))
        // First installment opens at the full principal; due dates step monthly.
        assertThat(schedule.installments.first().openingBalance).isEqualTo(eur("12000.00"))
        assertThat(schedule.installments[1].dueDate).isEqualTo(LocalDate.parse("2026-07-30"))
    }

    @Test
    fun `annuity shifts the mix from interest toward principal over the term`() {
        val s = Amortization.schedule(eur("12000.00"), BigDecimal("0.12"), 12, firstDue)
        val first = s.installments.first()
        val last = s.installments.last()
        // Interest falls and principal rises as the balance amortizes.
        assertThat(first.interest.amount).isGreaterThan(last.interest.amount)
        assertThat(first.principal.amount).isLessThan(last.principal.amount)
        // Every row satisfies payment = principal + interest and closing = opening - principal.
        s.installments.forEach { i ->
            assertThat(i.payment).isEqualTo(i.principal + i.interest)
            assertThat(i.closingBalance).isEqualTo(i.openingBalance - i.principal)
        }
    }

    // --- Amortization: equal-principal & bullet -------------------------------------------------

    @Test
    fun `equal-principal schedule keeps principal flat and lets payment decline`() {
        val s = Amortization.schedule(
            eur("12000.00"),
            BigDecimal("0.12"),
            12,
            firstDue,
            method = AmortizationMethod.EQUAL_PRINCIPAL,
        )
        assertThat(s.installments.map { it.principal }).allMatch { it == eur("1000.00") }
        assertThat(s.installments.first().payment).isEqualTo(eur("1120.00")) // 1000 + 1% of 12000
        assertThat(s.installments.first().payment.amount).isGreaterThan(s.installments.last().payment.amount)
        assertThat(s.totalPrincipal).isEqualTo(eur("12000.00"))
        assertThat(s.installments.last().closingBalance).isEqualTo(eur("0.00"))
    }

    @Test
    fun `bullet schedule is interest-only until principal repays at maturity`() {
        val s = Amortization.schedule(
            eur("12000.00"),
            BigDecimal("0.12"),
            12,
            firstDue,
            method = AmortizationMethod.BULLET,
        )
        // All but the last installment carry zero principal and a constant interest charge.
        s.installments.dropLast(1).forEach {
            assertThat(it.principal).isEqualTo(eur("0.00"))
            assertThat(it.interest).isEqualTo(eur("120.00")) // 1% of 12000
        }
        val last = s.installments.last()
        assertThat(last.principal).isEqualTo(eur("12000.00"))
        assertThat(last.closingBalance).isEqualTo(eur("0.00"))
        assertThat(s.totalInterest).isEqualTo(eur("1440.00")) // 12 * 120
    }

    @Test
    fun `zero-rate annuity is simple straight-line repayment`() {
        val s = Amortization.schedule(eur("1200.00"), BigDecimal.ZERO, 12, firstDue)
        assertThat(s.installments).allMatch { it.interest == eur("0.00") }
        assertThat(s.installments.first().payment).isEqualTo(eur("100.00"))
        assertThat(s.totalInterest).isEqualTo(eur("0.00"))
    }

    @Test
    fun `amortization rejects nonsensical terms`() {
        assertThatThrownBy { Amortization.schedule(eur("0.00"), BigDecimal("0.1"), 12, firstDue) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Amortization.schedule(eur("1000.00"), BigDecimal("0.1"), 0, firstDue) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            Amortization.schedule(eur("1000.00"), BigDecimal("0.1"), 12, firstDue, periodsPerYear = 5)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- IFRS 9 staging -------------------------------------------------------------------------

    @Test
    fun `ifrs9 staging follows DPD and explicit risk signals`() {
        assertThat(Ifrs9.stage(daysPastDue = 0)).isEqualTo(Ifrs9Stage.STAGE_1)
        assertThat(Ifrs9.stage(daysPastDue = 45)).isEqualTo(Ifrs9Stage.STAGE_2) // > 30 DPD presumption
        assertThat(Ifrs9.stage(daysPastDue = 120)).isEqualTo(Ifrs9Stage.STAGE_3) // > 90 DPD = default
        assertThat(Ifrs9.stage(daysPastDue = 0, sicr = true)).isEqualTo(Ifrs9Stage.STAGE_2)
        assertThat(Ifrs9.stage(daysPastDue = 0, creditImpaired = true)).isEqualTo(Ifrs9Stage.STAGE_3)
    }

    @Test
    fun `ifrs9 ECL uses 12-month PD in stage 1 and lifetime PD in stages 2 and 3`() {
        val inputs = EclInputs(
            pd12Month = BigDecimal("0.02"),
            pdLifetime = BigDecimal("0.20"),
            lgd = BigDecimal("0.45"),
            exposureAtDefault = eur("10000.00"),
            modelVersion = "test-model-v1",
        )
        // Stage 1: 0.02 * 0.45 * 10000 = 90.00
        assertThat(Ifrs9.ecl(Ifrs9Stage.STAGE_1, inputs).expectedCreditLoss).isEqualTo(eur("90.00"))
        // Stage 2: 0.20 * 0.45 * 10000 = 900.00
        assertThat(Ifrs9.ecl(Ifrs9Stage.STAGE_2, inputs).expectedCreditLoss).isEqualTo(eur("900.00"))

        // Defaulted (Stage 3) at PD = 1: lifetime ECL = LGD * EAD = 4500.00
        val defaulted = inputs.copy(pdLifetime = BigDecimal.ONE)
        val result = Ifrs9.assess(daysPastDue = 100, inputs = defaulted)
        assertThat(result.stage).isEqualTo(Ifrs9Stage.STAGE_3)
        assertThat(result.horizon).isEqualTo(EclHorizon.LIFETIME)
        assertThat(result.expectedCreditLoss).isEqualTo(eur("4500.00"))
    }

    @Test
    fun `ifrs9 rejects out-of-range probabilities`() {
        assertThatThrownBy {
            EclInputs(BigDecimal("1.5"), BigDecimal("0.2"), BigDecimal("0.4"), eur("100.00"), "test-model-v1")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- Collateral-adjusted LGD (ADR-0028 D1, first increment) ----------------------------------

    @Test
    fun `zero collateral leaves LGD unchanged`() {
        val lgd = Ifrs9.collateralAdjustedLgd(
            lgd = BigDecimal("0.45"),
            haircutAdjustedCollateralValue = BigDecimal.ZERO,
            exposureAtDefault = BigDecimal("10000.00"),
        )
        assertThat(lgd).isEqualByComparingTo(BigDecimal("0.45"))
    }

    @Test
    fun `partial collateral cover reduces LGD proportionally to the exposure`() {
        // Coverage ratio = 4000 / 10000 = 0.40 -> LGD 0.45 - 0.40 = 0.05
        val lgd = Ifrs9.collateralAdjustedLgd(
            lgd = BigDecimal("0.45"),
            haircutAdjustedCollateralValue = BigDecimal("4000.00"),
            exposureAtDefault = BigDecimal("10000.00"),
        )
        assertThat(lgd).isEqualByComparingTo(BigDecimal("0.05"))
    }

    @Test
    fun `over-collateralization floors LGD at zero, never negative`() {
        val lgd = Ifrs9.collateralAdjustedLgd(
            lgd = BigDecimal("0.45"),
            haircutAdjustedCollateralValue = BigDecimal("50000.00"),
            exposureAtDefault = BigDecimal("10000.00"),
        )
        assertThat(lgd.signum()).isGreaterThanOrEqualTo(0)
        assertThat(lgd).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `collateral cover never pushes LGD above its unsecured input value`() {
        // A pathological coverage ratio would only ever subtract; the result is clamped to [0, lgd].
        val lgd = Ifrs9.collateralAdjustedLgd(
            lgd = BigDecimal("0.45"),
            haircutAdjustedCollateralValue = BigDecimal.ZERO,
            exposureAtDefault = BigDecimal("10000.00"),
        )
        assertThat(lgd).isLessThanOrEqualTo(BigDecimal("0.45"))
    }

    @Test
    fun `a zero exposure returns LGD unchanged rather than dividing by zero`() {
        val lgd = Ifrs9.collateralAdjustedLgd(
            lgd = BigDecimal("0.45"),
            haircutAdjustedCollateralValue = BigDecimal("1000.00"),
            exposureAtDefault = BigDecimal.ZERO,
        )
        assertThat(lgd).isEqualByComparingTo(BigDecimal("0.45"))
    }

    @Test
    fun `collateral-adjusted LGD rejects an out-of-range lgd or a negative collateral value`() {
        assertThatThrownBy {
            Ifrs9.collateralAdjustedLgd(BigDecimal("1.5"), BigDecimal.ZERO, BigDecimal("100.00"))
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            Ifrs9.collateralAdjustedLgd(BigDecimal("0.45"), BigDecimal("-1.00"), BigDecimal("100.00"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- Delinquency ----------------------------------------------------------------------------

    @Test
    fun `days past due is measured from the oldest unpaid due date`() {
        val asOf = LocalDate.parse("2026-01-31")
        assertThat(Delinquency.daysPastDue(null, asOf)).isEqualTo(0)
        assertThat(Delinquency.daysPastDue(LocalDate.parse("2026-02-15"), asOf)).isEqualTo(0) // future
        assertThat(Delinquency.daysPastDue(LocalDate.parse("2026-01-01"), asOf)).isEqualTo(30)
    }

    @Test
    fun `arrears buckets and the 90-DPD default trigger`() {
        assertThat(Delinquency.bucket(0)).isEqualTo(DelinquencyBucket.CURRENT)
        assertThat(Delinquency.bucket(15)).isEqualTo(DelinquencyBucket.DPD_1_30)
        assertThat(Delinquency.bucket(45)).isEqualTo(DelinquencyBucket.DPD_31_60)
        assertThat(Delinquency.bucket(75)).isEqualTo(DelinquencyBucket.DPD_61_90)
        assertThat(Delinquency.bucket(100)).isEqualTo(DelinquencyBucket.DPD_90_PLUS)
        assertThat(Delinquency.isDefaulted(90)).isFalse()
        assertThat(Delinquency.isDefaulted(91)).isTrue()
    }
}
