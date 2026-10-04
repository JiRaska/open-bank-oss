// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.irrbb

import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurvePillar
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.InstrumentKind
import com.openbank.risk.domain.model.Provenance
import com.openbank.risk.domain.model.TreasuryDeal
import com.openbank.risk.domain.model.TreasuryInstrumentMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Treasury money-market deals in the IRRBB projection (#11107): fixed rate to maturity, so one
 * flow of principal + ACT/360 interest at maturity, a repricing notional at maturity, and the
 * deal's share broken out in [IrrbbResult.treasury].
 *
 * The hand case: 1 000 000 CZK placed on as-of for 90 days at 3.5 %. Interest
 * 1 000 000 · 3.5 % · 90/360 = 8 750.00, so one inflow of 1 008 750.00 at t = 90/365. On a flat
 * 4 % (continuous) curve shocked +200 bp parallel:
 *   ΔEVE = 1 008 750 · (e^(−0.06·t) − e^(−0.04·t)) = −4 913.71 CZK;
 *   ΔNII = 1 000 000 · 0.02 · (1 − 90/365) = 15 068.49 CZK (re-placed at the shocked rate).
 */
class TreasuryIrrbbTest {

    private val asOf: LocalDate = Fixtures.AS_OF
    private val sizes = ShockSizes.parse("200/250/100")
    private val params = IrrbbParameters(mapOf("CZK" to sizes), "test", null, "test")

    private val curves = CurveSet(
        UUID.randomUUID(),
        asOf,
        Provenance.SYNTHETIC,
        "test",
        Instant.EPOCH,
        mapOf(
            CurveIndex.CZEONIA to Curve(
                CurveIndex.CZEONIA,
                asOf,
                listOf(
                    CurvePillar(asOf.plusYears(1), BigDecimal("0.04")),
                    CurvePillar(asOf.plusYears(10), BigDecimal("0.04")),
                ),
            ),
        ),
    )

    private fun deal(product: String, principal: String = "1000000.00", rate: String? = "3.5", days: Long = 90) =
        TreasuryInstrumentMapper.toInstrument(
            TreasuryDeal(
                dealId = UUID.randomUUID(),
                product = product,
                counterpartyId = "BANK-A",
                currency = "CZK",
                principal = BigDecimal(principal),
                rate = rate?.let(::BigDecimal),
                valueDate = asOf,
                maturityDate = asOf.plusDays(days),
                state = TreasuryDeal.SETTLED,
            ),
        )

    private fun run(instruments: List<Instrument>) =
        Irrbb.compute(emptyList(), instruments, asOf, curves, BehaviouralModel.NMD_PHASE0, params)

    private fun czk(r: IrrbbResult, s: ShockScenario) =
        r.scenarios.first { it.scenario == s }.currencies.single { it.currency == "CZK" }

    @Test
    fun `a 1M CZK 3M placement at 3,5 percent loses 4913,71 under parallel up and is an asset in the gap`() {
        val r = run(listOf(deal(TreasuryDeal.MM_PLACEMENT)))

        val up = czk(r, ShockScenario.PARALLEL_UP)
        assertThat(up.deltaEve).isCloseTo(BigDecimal("-4913.71"), within(BigDecimal("0.01")))
        assertThat(up.basePv).isCloseTo(BigDecimal("998849.59"), within(BigDecimal("0.01")))
        assertThat(up.deltaNii).isCloseTo(BigDecimal("15068.49"), within(BigDecimal("0.01")))
        // Parallel down mirrors it: the asset gains value, NII falls.
        assertThat(czk(r, ShockScenario.PARALLEL_DOWN).deltaEve.signum()).isPositive()
        assertThat(czk(r, ShockScenario.PARALLEL_DOWN).deltaNii!!.signum()).isNegative()

        val gap = r.gaps.single()
        assertThat(gap.totalAssets).isEqualByComparingTo("1000000.00")
        assertThat(gap.totalLiabilities).isEqualByComparingTo("0")
        val bucket = gap.buckets.single { it.assets.signum() != 0 }
        assertThat(bucket.bucket).isEqualTo(com.openbank.risk.domain.cashflow.TimeBucket.of(asOf.plusDays(90), asOf))

        val t = r.treasury.single()
        assertThat(t.deals).isEqualTo(1)
        assertThat(t.placements).isEqualByComparingTo("1000000.00")
        assertThat(t.borrowings).isEqualByComparingTo("0")
        // The breakdown is the deal's share of the SAME figure, not an addition to it.
        assertThat(t.deltaEve.getValue(ShockScenario.PARALLEL_UP)).isEqualByComparingTo(up.deltaEve)
        assertThat(t.basePv).isEqualByComparingTo(up.basePv)
        assertThat(r.aggregationCurrency).isEqualTo("CZK")
        assertThat(r.dataGaps.map { it.code }).doesNotContain(IrrbbGapCode.INSTRUMENTS_NOT_PROJECTED)
    }

    @Test
    fun `a borrowing of the same terms is a liability with the opposite delta EVE and NII`() {
        val placement = run(listOf(deal(TreasuryDeal.MM_PLACEMENT)))
        val borrowing = run(listOf(deal(TreasuryDeal.MM_BORROWING)))

        assertThat(czk(borrowing, ShockScenario.PARALLEL_UP).deltaEve)
            .isEqualByComparingTo(czk(placement, ShockScenario.PARALLEL_UP).deltaEve.negate())
        assertThat(czk(borrowing, ShockScenario.PARALLEL_UP).deltaNii)
            .isEqualByComparingTo(czk(placement, ShockScenario.PARALLEL_UP).deltaNii!!.negate())
        assertThat(borrowing.gaps.single().totalLiabilities).isEqualByComparingTo("1000000.00")
        assertThat(borrowing.treasury.single().borrowings).isEqualByComparingTo("1000000.00")
        // A liability loses nothing when rates rise: the worst case is a down scenario.
        assertThat(borrowing.worstScenario).isNotEqualTo(ShockScenario.PARALLEL_UP)
    }

    @Test
    fun `a matched placement and borrowing net to zero gap and zero delta EVE`() {
        val r = run(listOf(deal(TreasuryDeal.MM_PLACEMENT), deal(TreasuryDeal.MM_BORROWING)))
        assertThat(r.gaps.single().totalGap).isEqualByComparingTo("0")
        r.scenarios.forEach { s -> assertThat(s.currencies.single().deltaEve).isEqualByComparingTo("0") }
        assertThat(r.treasury.single().deals).isEqualTo(2)
    }

    @Test
    fun `an instrument kind still not projected is reported by kind, deals no longer are`() {
        val bond = deal(TreasuryDeal.MM_PLACEMENT).copy(id = "bond:1", kind = InstrumentKind.BOND)
        val r = run(listOf(deal(TreasuryDeal.MM_PLACEMENT), bond))
        val gap = r.dataGaps.single { it.code == IrrbbGapCode.INSTRUMENTS_NOT_PROJECTED }
        assertThat(gap.count).isEqualTo(1)
        assertThat(gap.detail).contains("1 BOND")
        // The bond is not in the figures: only the placement's million reprices.
        assertThat(r.gaps.single().totalAssets).isEqualByComparingTo("1000000.00")
    }
}
