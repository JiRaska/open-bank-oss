// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.cashflow

import com.openbank.libs.lending.AmortizationMethod
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.Fixtures.lendingLoan
import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurvePillar
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.model.LoanExtension
import com.openbank.risk.domain.model.LoanInstrumentMapper
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionBuilder
import com.openbank.risk.domain.model.PositionKind
import com.openbank.risk.domain.model.Provenance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0314 D4 + D6: loans from the snapshot through the cash-flow engine.
 *
 * THE ORACLE is lending's own schedule: [lendingLoan] builds it with libs `Amortization`, the
 * function lending books a loan with, and drops the paid installments. A FIXED loan's projected
 * flows must be exactly those remaining installments, principal and interest per date.
 */
class LoanInstrumentCashFlowsTest {

    private val asOf: LocalDate = Fixtures.AS_OF

    private fun flat(index: CurveIndex, rate: String) =
        Curve(index, asOf, listOf(CurvePillar(asOf.plusYears(1), BigDecimal(rate))))

    private fun set(vararg curves: Curve) =
        CurveSet(UUID.randomUUID(), asOf, Provenance.SYNTHETIC, "test", Instant.EPOCH, curves.associateBy { it.index })

    private fun expected(loan: com.openbank.risk.domain.model.LoanContract) = loan.remainingInstallments.flatMap {
        listOf(
            CashFlow(it.dueDate, loan.currency, CashFlowKind.PRINCIPAL, it.principal),
            CashFlow(it.dueDate, loan.currency, CashFlowKind.INTEREST, it.interest),
        )
    }

    private fun expand(loan: com.openbank.risk.domain.model.LoanContract, curve: Curve? = null): List<CashFlow> {
        val instrument = LoanInstrumentMapper.toInstrument(loan)
        val ext = instrument.extension as LoanExtension
        val terms = instrument.rateTerms!!
        val rate = if (terms.index == null) {
            LoanRate.Fixed(terms.currentAnnualRate!!)
        } else {
            LoanRate.Floating(
                terms.index!!,
                terms.spread!!,
                terms.currentAnnualRate,
                terms.resetFrequencyMonths,
                terms.nextResetDate,
            )
        }
        return AmortisingLoanCashFlows.expand(
            AmortisingLoan(
                currency = instrument.currency,
                outstandingPrincipal = instrument.outstanding,
                rate = rate,
                periodsPerYear = ext.periodsPerYear,
                remainingPeriods = ext.remainingPeriods,
                method = ext.method,
                nextDueDate = ext.nextDueDate!!,
                contractualSchedule = ext.remainingInstallments,
            ),
            curve,
        )
    }

    @ParameterizedTest
    @EnumSource(AmortizationMethod::class)
    fun `FIXED loan flows are exactly lending's remaining installments, at every point of its life`(
        method: AmortizationMethod,
    ) {
        for (paid in 0 until 24) {
            val loan = lendingLoan(principal = "250000.00", rate = "0.069", term = 24, paid = paid, method = method)
            assertThat(expand(loan)).describedAs("$method after $paid paid").isEqualTo(expected(loan))
        }
    }

    @Test
    fun `re-deriving a FIXED loan from its outstanding does NOT reproduce lending's schedule mid-life`() {
        // Measured, and the reason FIXED projects the contractual schedule rather than recomputing
        // one: libs Amortization rounds the annuity payment once, at origination. Recomputed from a
        // mid-life balance over the remaining periods, the payment can round to a different cent,
        // and every later installment shifts. This test fails if that ever stops being true — then
        // the contractual path is merely equal, not necessary, and the KDoc should say so.
        val drifted = (1 until 24).count { paid ->
            val loan = lendingLoan(principal = "250000.00", rate = "0.069", term = 24, paid = paid)
            val rederived = AmortisingLoanCashFlows.expand(
                AmortisingLoan(
                    currency = "CZK",
                    outstandingPrincipal = loan.outstandingPrincipal,
                    rate = LoanRate.Fixed(BigDecimal("0.069")),
                    periodsPerYear = 12,
                    remainingPeriods = loan.remainingInstallments.size,
                    method = AmortizationMethod.ANNUITY,
                    nextDueDate = loan.remainingInstallments.first().dueDate,
                ),
            )
            rederived != expected(loan)
        }
        assertThat(drifted).isGreaterThan(0)
    }

    @Test
    fun `a contractual schedule that does not sum to the outstanding is refused`() {
        val loan = lendingLoan()
        val broken = AmortisingLoan(
            currency = "CZK",
            outstandingPrincipal = loan.outstandingPrincipal.add(BigDecimal.ONE),
            rate = LoanRate.Fixed(BigDecimal("0.06")),
            periodsPerYear = 12,
            remainingPeriods = loan.remainingInstallments.size,
            method = AmortizationMethod.ANNUITY,
            nextDueDate = loan.remainingInstallments.first().dueDate,
            contractualSchedule = loan.remainingInstallments,
        )
        assertThatThrownBy { AmortisingLoanCashFlows.expand(broken) }.hasMessageContaining("contractual schedule")
    }

    @ParameterizedTest
    @EnumSource(AmortizationMethod::class)
    fun `FLOATING with quarterly resets on a flat curve reproduces the fixed schedule to the cent`(
        method: AmortizationMethod,
    ) {
        // Current rate 6.9 % = flat 5.4 % + 1.5 % spread: the reset changes nothing, so the flows
        // must be lending's FIXED schedule at 6.9 % — the flat-curve == fixed property, now with
        // resetFrequencyMonths in play.
        val fixed =
            lendingLoan(
                principal = "250000.00",
                rate = "0.069",
                term = 24,
                paid = 0,
                method = method,
                firstDue = asOf.plusDays(15),
            )
        val floating = fixed.copy(
            rateType = "FLOATING",
            rateIndex = "PRIBOR_3M",
            spread = BigDecimal("0.015"),
            resetFrequencyMonths = 3,
            nextResetDate = asOf.plusDays(15).plusMonths(2),
        )
        assertThat(expand(floating, flat(CurveIndex.PRIBOR_3M, "0.054"))).isEqualTo(expected(fixed))
    }

    @Test
    fun `FLOATING holds its rate for the whole reset period, and the current rate until the first reset`() {
        val firstDue = asOf.plusDays(15)
        val loan = lendingLoan(principal = "120000.00", rate = "0.050", term = 12, paid = 0, firstDue = firstDue)
            .copy(
                rateType = "FLOATING",
                rateIndex = "PRIBOR_3M",
                spread = BigDecimal("0.010"),
                resetFrequencyMonths = 3,
                nextResetDate = firstDue.plusMonths(1),
            )
        val steep = Curve(
            CurveIndex.PRIBOR_3M,
            asOf,
            listOf(
                CurvePillar(asOf.plusMonths(1), BigDecimal("0.02")),
                CurvePillar(asOf.plusYears(2), BigDecimal("0.08")),
            ),
        )
        val flows = expand(loan, steep)
        val interest = flows.filter { it.kind == CashFlowKind.INTEREST }.map { it.amount }
        val principal = flows.filter { it.kind == CashFlowKind.PRINCIPAL }.map { it.amount }
        var opening = BigDecimal("120000.00")
        val rates = interest.indices.map { k ->
            val r = interest[k].divide(opening, java.math.MathContext.DECIMAL64)
            opening = opening.subtract(principal[k])
            r
        }
        // Periods 1..2 start before the first reset (due+1M): current 5 %/12.
        assertThat(rates[0].toDouble()).isCloseTo(0.05 / 12, org.assertj.core.data.Offset.offset(1e-6))
        assertThat(rates[1].toDouble()).isCloseTo(0.05 / 12, org.assertj.core.data.Offset.offset(1e-6))
        // Then quarterly fixings: 3 periods share one rate, and a steep curve makes each higher.
        for (group in listOf(2..4, 5..7, 8..10)) {
            val g = group.map { rates[it].toDouble() }
            assertThat(g.max() - g.min()).isLessThan(1e-6)
        }
        assertThat(rates[5]).isGreaterThan(rates[2])
        assertThat(rates[8]).isGreaterThan(rates[5])
        // With per-payment-period resets (no reset terms) every period would differ instead.
        assertThat(principal.fold(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("120000.00")
    }

    @Test
    fun `snapshot projection - loans are inflows, deposits outflows, and GL positions only are not expanded`() {
        val a = lendingLoan(id = Fixtures.LOAN_A)
        val b = lendingLoan(id = Fixtures.LOAN_B, principal = "6000.00")
        val instruments = listOf(a, b).map(LoanInstrumentMapper::toInstrument)
        val total = a.outstandingPrincipal.add(b.outstandingPrincipal)
        val positions: List<Position> = PositionBuilder.build(Fixtures.tiedOutWithLoans(total), instruments)

        val result = SnapshotCashFlowProjection.project(
            positions,
            asOf,
            set(flat(CurveIndex.CZEONIA, "0.035")),
            BehaviouralModel.NMD_PHASE0,
            instruments,
        )

        val loanInflows = (expected(a) + expected(b)).fold(BigDecimal.ZERO) { acc, f -> acc.add(f.amount) }
        val czk = result.currencies.single()
        assertThat(czk.total).isEqualByComparingTo(loanInflows.subtract(BigDecimal("1500.00")))
        assertThat(czk.positions).isEqualTo(4) // 2 deposits + 2 loans
        assertThat(result.expanded).isEqualTo(4)
        // 1001 nostro and 3000 equity; NOT 1200, which the loans carry.
        assertThat(result.notExpanded).isEqualTo(2)
        assertThat(positions.count { it.kind == PositionKind.GL_ACCOUNT }).isEqualTo(2)
    }

    @Test
    fun `a floating loan whose index has no curve in the set cannot be projected`() {
        val loan = lendingLoan(floating = Triple("PRIBOR_3M", "0.01", 3), nextResetDate = asOf.plusMonths(1))
        val instruments = listOf(LoanInstrumentMapper.toInstrument(loan))
        assertThatThrownBy {
            SnapshotCashFlowProjection.project(
                emptyList(),
                asOf,
                set(flat(CurveIndex.CZEONIA, "0.03")),
                BehaviouralModel.NMD_PHASE0,
                instruments,
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PRIBOR_3M")
    }
}
