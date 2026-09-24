// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.cashflow

import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.Amortization
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurvePillar
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.model.Position
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

private val AS_OF: LocalDate = LocalDate.parse("2026-09-30")

private fun sum(flows: List<CashFlow>, kind: CashFlowKind? = null) =
    flows.filter { kind == null || it.kind == kind }.fold(BigDecimal.ZERO) { a, f -> a.add(f.amount) }

private fun flat(index: CurveIndex, rate: String) =
    Curve(index, AS_OF, listOf(CurvePillar(AS_OF.plusYears(1), BigDecimal(rate))))

class AmortisingLoanCashFlowsTest {

    private fun loan(method: AmortizationMethod, rate: LoanRate) = AmortisingLoan(
        currency = "CZK",
        outstandingPrincipal = BigDecimal("250000.00"),
        rate = rate,
        periodsPerYear = 12,
        remainingPeriods = 60,
        method = method,
        nextDueDate = LocalDate.parse("2026-10-15"),
    )

    private fun libsSchedule(method: AmortizationMethod) = Amortization.schedule(
        principal = Money.of("250000.00", "CZK"),
        nominalAnnualRate = BigDecimal("0.069"),
        termPeriods = 60,
        firstDueDate = LocalDate.parse("2026-10-15"),
        periodsPerYear = 12,
        method = method,
    ).installments.flatMap {
        listOf(
            CashFlow(it.dueDate, "CZK", CashFlowKind.PRINCIPAL, it.principal.amount),
            CashFlow(it.dueDate, "CZK", CashFlowKind.INTEREST, it.interest.amount),
        )
    }

    @ParameterizedTest
    @EnumSource(AmortizationMethod::class)
    fun `FIXED equals the libs Amortization schedule and principal sums to the outstanding`(
        method: AmortizationMethod,
    ) {
        val flows = AmortisingLoanCashFlows.expand(loan(method, LoanRate.Fixed(BigDecimal("0.069"))))
        assertThat(flows).isEqualTo(libsSchedule(method))
        assertThat(sum(flows, CashFlowKind.PRINCIPAL)).isEqualByComparingTo("250000.00")
        assertThat(flows).allMatch { it.amount.signum() >= 0 } // a loan is an asset: inflows
    }

    @ParameterizedTest
    @EnumSource(AmortizationMethod::class)
    fun `FLOATING on a flat curve at fixed minus spread reproduces the fixed schedule to the cent`(
        method: AmortizationMethod,
    ) {
        val floating = LoanRate.Floating(CurveIndex.PRIBOR_3M, BigDecimal("0.015"))
        val flows = AmortisingLoanCashFlows.expand(loan(method, floating), flat(CurveIndex.PRIBOR_3M, "0.054"))
        assertThat(flows).isEqualTo(libsSchedule(method))
    }

    @Test
    fun `FLOATING follows the curve - a higher curve means more interest, the same principal`() {
        val floating = LoanRate.Floating(CurveIndex.PRIBOR_3M, BigDecimal("0.015"))
        val base = AmortisingLoanCashFlows.expand(
            loan(AmortizationMethod.ANNUITY, floating),
            flat(CurveIndex.PRIBOR_3M, "0.054"),
        )
        val upCurve = Curve(
            CurveIndex.PRIBOR_3M,
            AS_OF,
            listOf(
                CurvePillar(AS_OF.plusMonths(1), BigDecimal("0.054")),
                CurvePillar(AS_OF.plusYears(3), BigDecimal("0.080")),
            ),
        )
        val up = AmortisingLoanCashFlows.expand(loan(AmortizationMethod.ANNUITY, floating), upCurve)
        assertThat(sum(up, CashFlowKind.PRINCIPAL)).isEqualByComparingTo("250000.00")
        assertThat(sum(up, CashFlowKind.INTEREST)).isGreaterThan(sum(base, CashFlowKind.INTEREST))
    }

    @Test
    fun `a floating loan needs the curve of its own index`() {
        val floating = LoanRate.Floating(CurveIndex.PRIBOR_3M, BigDecimal("0.015"))
        assertThatThrownBy { AmortisingLoanCashFlows.expand(loan(AmortizationMethod.ANNUITY, floating)) }
            .hasMessageContaining("needs a curve")
        assertThatThrownBy {
            AmortisingLoanCashFlows.expand(loan(AmortizationMethod.ANNUITY, floating), flat(CurveIndex.CZEONIA, "0.05"))
        }.hasMessageContaining("floats on PRIBOR_3M")
    }
}

class NonMaturityDepositCashFlowsTest {
    private val model = BehaviouralModel.NMD_PHASE0

    @Test
    fun `principal flows sum to minus the balance, split 70-30 core-volatile`() {
        val flows = NonMaturityDepositCashFlows.expand(BigDecimal("1000.00"), "CZK", AS_OF, model)
        assertThat(sum(flows, CashFlowKind.PRINCIPAL)).isEqualByComparingTo("-1000.00")
        assertThat(flows).allMatch { it.amount.signum() < 0 } // a deposit is a liability: outflows
        val volatile = flows.first()
        assertThat(volatile.date).isEqualTo(LocalDate.parse("2026-10-01"))
        assertThat(volatile.amount).isEqualByComparingTo("-300.00")
        assertThat(sum(flows.drop(1))).isEqualByComparingTo("-700.00")
    }

    @Test
    fun `the core runs off monthly and ends exactly N years after asOf`() {
        val flows = NonMaturityDepositCashFlows.expand(BigDecimal("1000.00"), "CZK", AS_OF, model)
        assertThat(flows).hasSize(1 + 60)
        assertThat(flows.maxOf { it.date }).isEqualTo(AS_OF.plusYears(5))
        val three = model.copy(version = "test-3y", coreRunoffYears = 3)
        assertThat(NonMaturityDepositCashFlows.expand(BigDecimal("1000.00"), "CZK", AS_OF, three).maxOf { it.date })
            .isEqualTo(AS_OF.plusYears(3))
    }

    @Test
    fun `rounding drift is absorbed by the last slice, never lost`() {
        val flows = NonMaturityDepositCashFlows.expand(BigDecimal("1000.01"), "CZK", AS_OF, model)
        assertThat(sum(flows, CashFlowKind.PRINCIPAL)).isEqualByComparingTo("-1000.01")
    }

    @Test
    fun `volatile leaves on the first business day - a Friday as-of pays out on Monday`() {
        val friday = LocalDate.parse("2026-10-02")
        val flows = NonMaturityDepositCashFlows.expand(BigDecimal("100.00"), "CZK", friday, model)
        assertThat(flows.first().date).isEqualTo(LocalDate.parse("2026-10-05"))
    }

    @Test
    fun `deposit interest is zero by default and an outflow on the core when the model pays it`() {
        assertThat(NonMaturityDepositCashFlows.expand(BigDecimal("1000.00"), "CZK", AS_OF, model))
            .noneMatch { it.kind == CashFlowKind.INTEREST }
        val paying = model.copy(version = "test-paying", annualDepositRate = BigDecimal("0.012"))
        val flows = NonMaturityDepositCashFlows.expand(BigDecimal("1000.00"), "CZK", AS_OF, paying)
        val interest = flows.filter { it.kind == CashFlowKind.INTEREST }
        assertThat(interest).hasSize(60).allMatch { it.amount.signum() <= 0 }
        assertThat(interest.first().amount).isEqualByComparingTo("-0.70") // 700 × 1.2 % / 12
        assertThat(sum(flows, CashFlowKind.PRINCIPAL)).isEqualByComparingTo("-1000.00")
    }

    @Test
    fun `the model is named and versioned, and its parameters are validated`() {
        assertThat(model.id).isEqualTo("nmd-linear-core")
        assertThat(model.version).isEqualTo("1.0.0")
        assertThatThrownBy { model.copy(coreRatio = BigDecimal("1.1")) }.hasMessageContaining("coreRatio")
        assertThatThrownBy { model.copy(coreRunoffYears = 0) }.hasMessageContaining("coreRunoffYears")
        assertThatThrownBy { model.copy(version = " ") }.hasMessageContaining("version")
    }
}

class CashFlowAggregationTest {

    @Test
    fun `buckets partition the flows - every bucket present and the sum equals the total`() {
        val loan = AmortisingLoanCashFlows.expand(
            AmortisingLoan(
                "CZK",
                BigDecimal("900000.00"),
                LoanRate.Fixed(BigDecimal("0.05")),
                12,
                180,
                AmortizationMethod.ANNUITY,
                LocalDate.parse("2026-10-15"),
            ),
        )
        val deposit = NonMaturityDepositCashFlows.expand(
            BigDecimal("12345.67"),
            "CZK",
            AS_OF,
            BehaviouralModel.NMD_PHASE0,
        )
        val flows = loan + deposit
        val buckets = CashFlowAggregation.bucket(flows, AS_OF)
        assertThat(buckets.keys).containsExactly(*TimeBucket.entries.toTypedArray())
        assertThat(buckets.values.fold(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(sum(flows))
        assertThat(buckets.getValue(TimeBucket.OVER_10Y).signum()).isPositive() // 15-year loan tail
    }

    @Test
    fun `bucket boundaries are upper-inclusive`() {
        assertThat(TimeBucket.of(AS_OF, AS_OF)).isEqualTo(TimeBucket.OVERNIGHT)
        assertThat(TimeBucket.of(AS_OF.plusDays(1), AS_OF)).isEqualTo(TimeBucket.OVERNIGHT)
        assertThat(TimeBucket.of(AS_OF.plusMonths(1), AS_OF)).isEqualTo(TimeBucket.UP_TO_1M)
        assertThat(TimeBucket.of(AS_OF.plusMonths(1).plusDays(1), AS_OF)).isEqualTo(TimeBucket.M1_TO_3M)
        assertThat(TimeBucket.of(AS_OF.plusYears(10), AS_OF)).isEqualTo(TimeBucket.Y5_TO_10Y)
        assertThat(TimeBucket.of(AS_OF.plusYears(10).plusDays(1), AS_OF)).isEqualTo(TimeBucket.OVER_10Y)
    }

    @Test
    fun `PV of a single flow is amount times DF, and a zero curve leaves it undiscounted`() {
        val flow = CashFlow(AS_OF.plusYears(1), "CZK", CashFlowKind.PRINCIPAL, BigDecimal("-1000.00"))
        val curve = flat(CurveIndex.CZEONIA, "0.05")
        val pv = CashFlowAggregation.presentValue(listOf(flow), curve, 2)
        assertThat(pv).isEqualByComparingTo("-951.23") // −1000·e^−0.05
        assertThat(CashFlowAggregation.presentValue(listOf(flow), flat(CurveIndex.CZEONIA, "0"), 2))
            .isEqualByComparingTo("-1000.00")
        assertThatThrownBy { CashFlowAggregation.presentValue(listOf(flow.copy(currency = "EUR")), curve, 2) }
            .hasMessageContaining("cannot be discounted")
    }
}

class SnapshotCashFlowProjectionTest {

    private fun dep(currency: String, amount: String) =
        Position(PositionKind.SUB_LEDGER, "2100", "LIABILITY", currency, UUID.randomUUID(), BigDecimal(amount))

    @Test
    fun `deposits are expanded per currency, GL positions counted, a currency without a curve is unpriced`() {
        val positions = listOf(
            dep("CZK", "-1000.00"),
            dep("CZK", "-500.00"),
            dep("USD", "-200.00"),
            Position(PositionKind.GL_ACCOUNT, "1001", "ASSET", "CZK", null, BigDecimal("1700.00")),
        )
        val set = CurveSet(
            UUID.randomUUID(),
            AS_OF,
            Provenance.SYNTHETIC,
            "test",
            Instant.EPOCH,
            mapOf(
                CurveIndex.CZEONIA to flat(CurveIndex.CZEONIA, "0.035"),
            ),
        )
        val result = SnapshotCashFlowProjection.project(positions, AS_OF, set, BehaviouralModel.NMD_PHASE0)

        assertThat(result.expanded).isEqualTo(3)
        assertThat(result.notExpanded).isEqualTo(1)
        val czk = result.currencies.single { it.currency == "CZK" }
        assertThat(czk.total).isEqualByComparingTo("-1500.00")
        assertThat(czk.buckets.values.fold(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("-1500.00")
        assertThat(czk.presentValue!!).isGreaterThan(BigDecimal("-1500.00")).isLessThan(BigDecimal.ZERO)
        val usd = result.currencies.single { it.currency == "USD" }
        assertThat(usd.presentValue).isNull()
        assertThat(usd.total).isEqualByComparingTo("-200.00")
        assertThat(result.unpriced).containsExactly("USD")
    }
}
