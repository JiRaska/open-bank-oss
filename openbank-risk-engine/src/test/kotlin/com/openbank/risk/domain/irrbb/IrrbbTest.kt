// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.irrbb

import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.Fixtures.lendingLoan
import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.cashflow.TimeBucket
import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurvePillar
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.LoanInstrumentMapper
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import com.openbank.risk.domain.model.Provenance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class IrrbbTest {

    private val asOf: LocalDate = Fixtures.AS_OF
    private val model = BehaviouralModel.NMD_PHASE0
    private val eurSizes = ShockSizes.parse("200/250/100")

    private fun params(sizes: Map<String, ShockSizes> = mapOf("EUR" to eurSizes), floor: PostShockFloor? = null) =
        IrrbbParameters(sizes, "test", floor, "test")

    private fun curve(index: CurveIndex, rate: String) = Curve(
        index,
        asOf,
        listOf(CurvePillar(asOf.plusYears(1), BigDecimal(rate)), CurvePillar(asOf.plusYears(10), BigDecimal(rate))),
    )

    private val curves = CurveSet(
        UUID.randomUUID(),
        asOf,
        Provenance.SYNTHETIC,
        "test",
        Instant.EPOCH,
        listOf(
            curve(CurveIndex.ESTR, "0.03"),
            curve(CurveIndex.EURIBOR_3M, "0.03"),
            curve(CurveIndex.CZEONIA, "0.04"),
        ).associateBy { it.index },
    )

    private fun fixedLoan(id: UUID = Fixtures.LOAN_A, currency: String = "EUR") = LoanInstrumentMapper.toInstrument(
        lendingLoan(
            id = id,
            principal = "100000.00",
            currency = currency,
            rate = "0.05",
            term = 120,
            paid = 0,
            firstDue = asOf.plusDays(15),
        ),
    )

    private fun floatingLoan() = LoanInstrumentMapper.toInstrument(
        lendingLoan(
            id = Fixtures.LOAN_B,
            principal = "100000.00",
            currency = "EUR",
            rate = "0.05",
            term = 120,
            paid = 0,
            firstDue = asOf.plusDays(15),
            floating = Triple("EURIBOR_3M", "0.02", 3),
            nextResetDate = asOf.plusMonths(2),
        ),
    )

    private fun deposit(amount: String, currency: String = "EUR") =
        Position(PositionKind.SUB_LEDGER, "2100", "LIABILITY", currency, UUID.randomUUID(), BigDecimal(amount).negate())

    private fun run(positions: List<Position>, instruments: List<Instrument>, p: IrrbbParameters = params()) =
        Irrbb.compute(positions, instruments, asOf, curves, model, p)

    private fun eve(r: IrrbbResult, s: ShockScenario, c: String = "EUR") =
        r.scenarios.first { it.scenario == s }.currencies.first { it.currency == c }

    @Test
    fun `zero shock gives exactly zero delta EVE and delta NII in every scenario`() {
        val r = run(
            listOf(deposit("50000.00")),
            listOf(fixedLoan(), floatingLoan()),
            params(
                mapOf(
                    "EUR" to ShockSizes.parse("0/0/0"),
                ),
            ),
        )
        r.scenarios.flatMap { it.currencies }.forEach {
            assertThat(it.deltaEve.signum()).isZero()
            it.deltaNii?.let { nii -> assertThat(nii.signum()).isZero() }
        }
        assertThat(r.worstScenario).isNull()
    }

    @Test
    fun `parallel up lowers EVE of a fixed-rate asset book and raises it on pure liabilities`() {
        val assets = run(emptyList(), listOf(fixedLoan()))
        assertThat(eve(assets, ShockScenario.PARALLEL_UP).deltaEve.signum()).isNegative()
        assertThat(eve(assets, ShockScenario.PARALLEL_DOWN).deltaEve.signum()).isPositive()
        assertThat(assets.worstScenario).isEqualTo(ShockScenario.PARALLEL_UP)

        val liabilities = run(listOf(deposit("100000.00")), emptyList())
        assertThat(eve(liabilities, ShockScenario.PARALLEL_UP).deltaEve.signum()).isPositive()
        assertThat(eve(liabilities, ShockScenario.PARALLEL_DOWN).deltaEve.signum()).isNegative()
    }

    @Test
    fun `a floating loan repricing at its next reset moves EVE far less than the same fixed loan`() {
        val fixed = eve(run(emptyList(), listOf(fixedLoan())), ShockScenario.PARALLEL_UP).deltaEve.abs()
        val floating = eve(run(emptyList(), listOf(floatingLoan())), ShockScenario.PARALLEL_UP).deltaEve.abs()
        assertThat(fixed).isGreaterThan(BigDecimal(5000))
        // measured: fixed ≈ 9 805, floating ≈ 834 (the old rate runs until the reset two months out)
        assertThat(floating.multiply(BigDecimal.TEN)).isLessThan(fixed)
    }

    @Test
    fun `gap buckets sum to the total, which is loans outstanding minus deposits`() {
        val loans = listOf(fixedLoan(), floatingLoan())
        val r = run(listOf(deposit("30000.00"), deposit("20000.00")), loans)
        val gap = r.gaps.single()
        val sum = gap.buckets.fold(BigDecimal.ZERO) { a, b -> a.add(b.gap) }
        val outstanding = loans.fold(BigDecimal.ZERO) { a, l -> a.add(l.outstanding) }
        assertThat(sum).isEqualByComparingTo(gap.totalGap)
        assertThat(gap.totalGap).isEqualByComparingTo(outstanding.subtract(BigDecimal("50000.00")))
        assertThat(gap.buckets.last().cumulativeGap).isEqualByComparingTo(gap.totalGap)
        // the floating loan reprices in full at its next reset (2 months) → 1-3M bucket
        val m1to3 = gap.buckets.first { it.bucket == TimeBucket.M1_TO_3M }
        assertThat(m1to3.assets).isGreaterThanOrEqualTo(floatingLoan().outstanding)
        // 30 % of deposits is volatile → overnight liabilities
        assertThat(gap.buckets.first { it.bucket == TimeBucket.OVERNIGHT }.liabilities).isEqualByComparingTo("15000.00")
    }

    @Test
    fun `delta NII follows the repricing - floating assets gain on parallel up, overnight deposits cost`() {
        val floating = run(emptyList(), listOf(floatingLoan()))
        assertThat(eve(floating, ShockScenario.PARALLEL_UP).deltaNii!!.signum()).isPositive()
        assertThat(eve(floating, ShockScenario.PARALLEL_DOWN).deltaNii!!.signum()).isNegative()
        assertThat(eve(floating, ShockScenario.STEEPENER).deltaNii).isNull()

        val deposits = run(listOf(deposit("100000.00")), emptyList())
        // volatile 30 000 reprices on day 1: ≈ −30 000 · 2 % · (1 − 1/365) ≈ −598
        assertThat(eve(deposits, ShockScenario.PARALLEL_UP).deltaNii!!.toDouble()).isLessThan(-598.0)
    }

    @Test
    fun `a currency without configured shock sizes is reported, not shocked with a guess`() {
        val r = run(listOf(deposit("1000.00", "CZK"), deposit("1000.00")), emptyList())
        assertThat(r.shockNotConfigured).containsExactly("CZK")
        assertThat(r.scenarios.flatMap { it.currencies }.map { it.currency }.toSet()).containsExactly("EUR")
        // multi-currency book: no cross-currency aggregate without FX conversion
        assertThat(r.aggregationCurrency).isNull()
        assertThat(r.scenarios.all { it.aggregateLoss == null }).isTrue()
        assertThat(r.worstByCurrency["EUR"]).isEqualTo(ShockScenario.PARALLEL_DOWN)
    }

    @Test
    fun `aggregate sums losses only and the worst scenario carries the largest`() {
        val r = run(listOf(deposit("40000.00")), listOf(fixedLoan()))
        r.scenarios.forEach { s ->
            val c = s.currencies.single()
            assertThat(s.aggregateLoss).isEqualByComparingTo(c.deltaEve.negate().max(BigDecimal.ZERO))
        }
        val max = r.scenarios.maxOf { it.aggregateLoss!! }
        assertThat(r.worstLoss).isEqualByComparingTo(max)
        assertThat(r.scenarios.first { it.scenario == r.worstScenario }.aggregateLoss).isEqualByComparingTo(max)
    }

    @Test
    fun `the floor limits parallel down on a low-rate curve`() {
        val floor = PostShockFloor.parse("-50/0")
        val big = mapOf("EUR" to ShockSizes.parse("1000/0/0"))
        val unfloored = eve(run(emptyList(), listOf(fixedLoan()), params(big)), ShockScenario.PARALLEL_DOWN).deltaEve
        val floored = eve(
            run(emptyList(), listOf(fixedLoan()), params(big, floor)),
            ShockScenario.PARALLEL_DOWN,
        ).deltaEve
        assertThat(floored).isLessThan(unfloored)
        assertThat(floored.signum()).isPositive()
    }
}
