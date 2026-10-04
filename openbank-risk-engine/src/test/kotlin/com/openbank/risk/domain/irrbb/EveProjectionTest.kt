// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.irrbb

import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.Fixtures.lendingLoan
import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.cashflow.CashFlow
import com.openbank.risk.domain.cashflow.CashFlowAggregation
import com.openbank.risk.domain.cashflow.CashFlowKind
import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurvePillar
import com.openbank.risk.domain.curve.CurveSet
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

/**
 * Hand-computable EVE cases: one zero-coupon flow on a flat curve, where every figure is
 * `N · exp(−(r + Δr) · t)` and can be checked on a calculator, plus the data gaps the IRRBB read
 * must state instead of hiding (flat extrapolation past the last pillar, behavioural gaps).
 */
class EveProjectionTest {

    private val asOf: LocalDate = Fixtures.AS_OF
    private val sizes = ShockSizes.parse("200/250/100") // Delegated Regulation (EU) 2024/856 Annex Part A, EUR/CZK

    private fun set(vararg pillarYears: Long, rate: String = "0.03") = CurveSet(
        UUID.randomUUID(),
        asOf,
        Provenance.SYNTHETIC,
        "test",
        Instant.EPOCH,
        mapOf(
            CurveIndex.ESTR to Curve(
                CurveIndex.ESTR,
                asOf,
                pillarYears.map { CurvePillar(asOf.plusYears(it), BigDecimal(rate)) },
            ),
        ),
    )

    /** 1 000 000 EUR received exactly 730 days (t = 2.0 years ACT/365F) after as-of. */
    private val zeroCoupon = listOf(CashFlow(asOf.plusDays(730), "EUR", CashFlowKind.PRINCIPAL, BigDecimal("1000000")))

    private fun pv(set: CurveSet) = CashFlowAggregation.presentValue(zeroCoupon, set.discountCurveFor("EUR")!!, 2)

    private fun shockedPv(set: CurveSet, scenario: ShockScenario) =
        pv(SupervisoryShocks.shock(set, "EUR", scenario, sizes, null))

    @Test
    fun `zero-coupon bond on a flat 3 percent curve has the textbook PV and delta EVE`() {
        val base = set(1, 10)
        // 1e6 · e^(−0.03·2) = 941 764.53
        assertThat(pv(base)).isEqualByComparingTo("941764.53")
        // parallel up 200 bp: 1e6 · e^(−0.05·2) = 904 837.42 → ΔEVE = −36 927.11 (a loss)
        val up = shockedPv(base, ShockScenario.PARALLEL_UP)
        assertThat(up).isEqualByComparingTo("904837.42")
        assertThat(up.subtract(pv(base))).isEqualByComparingTo("-36927.11")
        // parallel down 200 bp: 1e6 · e^(−0.01·2) = 980 198.67
        assertThat(shockedPv(base, ShockScenario.PARALLEL_DOWN)).isEqualByComparingTo("980198.67")
        // short up at t = 2: 250 · e^(−2/4) = 151.633 bp → 1e6 · e^(−(0.03 + 0.0151633)·2) = 913 632.81
        assertThat(shockedPv(base, ShockScenario.SHORT_UP)).isEqualByComparingTo("913632.81")
    }

    @Test
    fun `scenario shapes follow d368 Annex 2 at the short and long end`() {
        val t0 = BigDecimal.ZERO
        val tLong = BigDecimal("200") // S_short ≈ 0, S_long ≈ 1
        fun bp(s: ShockScenario, t: BigDecimal) = SupervisoryShocks.shockBp(s, sizes, t)
        assertThat(bp(ShockScenario.PARALLEL_UP, t0)).isEqualByComparingTo("200")
        assertThat(bp(ShockScenario.PARALLEL_DOWN, tLong)).isEqualByComparingTo("-200")
        assertThat(bp(ShockScenario.SHORT_UP, t0)).isEqualByComparingTo("250")
        assertThat(bp(ShockScenario.SHORT_DOWN, t0)).isEqualByComparingTo("-250")
        // steepener: −0.65·250 at t=0, +0.9·100 at the long end
        assertThat(bp(ShockScenario.STEEPENER, t0)).isEqualByComparingTo("-162.5")
        assertThat(bp(ShockScenario.STEEPENER, tLong).setScale(6, java.math.RoundingMode.HALF_EVEN))
            .isEqualByComparingTo("90")
        // flattener: +0.8·250 at t=0, −0.6·100 at the long end
        assertThat(bp(ShockScenario.FLATTENER, t0)).isEqualByComparingTo("200")
        assertThat(bp(ShockScenario.FLATTENER, tLong).setScale(6, java.math.RoundingMode.HALF_EVEN))
            .isEqualByComparingTo("-60")
    }

    @Test
    fun `a flow beyond the last pillar is reported as flat extrapolation with its PV`() {
        val oneYear = set(1)
        // Flat extrapolation: the 2-year flow is still discounted at the 1Y rate, so the PV is the same …
        assertThat(pv(oneYear)).isEqualByComparingTo("941764.53")
        // … but it is never silent.
        val gap = IrrbbDataGaps.extrapolation("EUR", zeroCoupon, emptyList(), oneYear).single()
        assertThat(gap.code).isEqualTo(IrrbbGapCode.CURVE_EXTRAPOLATED_FLAT)
        assertThat(gap.curveIndex).isEqualTo(CurveIndex.ESTR)
        assertThat(gap.lastPillarDate).isEqualTo(asOf.plusYears(1))
        assertThat(gap.lastFlowDate).isEqualTo(asOf.plusDays(730))
        assertThat(gap.flowsBeyond).isEqualTo(1)
        assertThat(gap.basePvBeyond).isEqualByComparingTo("941764.53")

        assertThat(IrrbbDataGaps.extrapolation("EUR", zeroCoupon, emptyList(), set(1, 10))).isEmpty()
    }

    @Test
    fun `a full IRRBB run on a one-year curve set states every gap it has`() {
        val loan = LoanInstrumentMapper.toInstrument(
            lendingLoan(
                id = Fixtures.LOAN_A,
                principal = "100000.00",
                currency = "EUR",
                rate = "0.05",
                term = 60,
                paid = 0,
                firstDue = asOf.plusDays(15),
            ),
        )
        val deposit =
            Position(PositionKind.SUB_LEDGER, "2100", "LIABILITY", "EUR", UUID.randomUUID(), BigDecimal("-50000"))
        val r = Irrbb.compute(
            listOf(deposit),
            listOf(loan),
            asOf,
            set(1),
            BehaviouralModel.NMD_PHASE0,
            IrrbbParameters(mapOf("EUR" to sizes), "test", null, "test"),
        )
        assertThat(r.dataGaps.map { it.code }).contains(
            IrrbbGapCode.CURVE_EXTRAPOLATED_FLAT,
            IrrbbGapCode.PREPAYMENT_NOT_MODELLED,
            IrrbbGapCode.NMD_BEHAVIOUR_SIMPLIFIED,
            IrrbbGapCode.COMMERCIAL_MARGIN_INCLUDED,
        )
        val flat = r.dataGaps.single { it.code == IrrbbGapCode.CURVE_EXTRAPOLATED_FLAT }
        assertThat(flat.flowsBeyond!!).isPositive()
        assertThat(flat.lastFlowDate!!).isAfter(asOf.plusYears(1))
        // ΔNII under the two parallel scenarios, the other four carry none.
        r.scenarios.forEach { s ->
            val nii = s.currencies.single().deltaNii
            if (s.scenario in Irrbb.NII_SCENARIOS) assertThat(nii).isNotNull() else assertThat(nii).isNull()
        }
    }
}
