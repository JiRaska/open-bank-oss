// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.fx.domain.cnb.CnbFixingRate
import com.openbank.fx.domain.model.FxConversionMath
import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import com.openbank.fx.domain.screening.ScreeningDecision
import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningPolicy
import com.openbank.fx.domain.screening.ScreeningResult
import com.openbank.fx.domain.screening.ScreeningRole
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.model.FxSettlementPosting
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Money-path FX adoption: exercises [FxConversionScenario] directly against the REAL fx-service
 * domain — the `CnbFixingRate` per-unit normalisation, the `FxRate.inverted()` spread-side swap,
 * the cross-scale `FxConversionMath` margin arithmetic and the fail-closed `ScreeningPolicy` — so
 * the harness's binding to fx-service is proven by an actual assertion, not merely by compiling,
 * the same shape as [SwiftMessageScenarioTest]/[SddCollectionScenarioTest].
 *
 * The forced-fault branches are deterministic (`FaultProfile(1.0)` fires its draw with certainty);
 * the seeded branches (sanctions block, review outcome) are located by scanning fixed seeds, so
 * the test stays green if the scenario's draw order changes.
 */
class FxConversionScenarioTest {

    private val now: Instant = Instant.parse("2026-10-17T12:00:00Z")

    private fun newWorld(seed: Long, faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed, faultProfile), SimulationConfig())

    private fun newRate(bid: String = "25.00", ask: String = "25.20") = FxRate(
        id = UUID.randomUUID(),
        baseCurrency = "EUR",
        quoteCurrency = "CZK",
        bidRate = BigDecimal(bid),
        askRate = BigDecimal(ask),
        rateType = RateType.SPOT,
        source = RateSource.CNB,
        validFrom = now.minus(Duration.ofHours(1)),
        validTo = now.plus(Duration.ofHours(1)),
        createdAt = now,
    )

    @Test
    fun `the real conversion math scales cross-minor-unit conversions exactly`() {
        // EUR(2dp) -> JPY(0dp): 100.00 EUR at 165 JPY/EUR is 16500 JPY — a naive minor-units
        // multiply would come out 100x too large, which is exactly what this math exists to prevent.
        assertThat(
            FxConversionMath.convertedAmountMinorUnits(EUR_100_MINOR, "EUR", "JPY", BigDecimal("165")),
        ).isEqualTo(JPY_16500_MINOR)
        // JPY(0dp) -> EUR(2dp): the same 16500 JPY back at 1/165 EUR-per-JPY is 100.00 EUR again.
        val jpyToEur = BigDecimal.ONE.divide(BigDecimal("165"), INVERSE_RATE_SCALE, RoundingMode.HALF_UP)
        assertThat(
            FxConversionMath.convertedAmountMinorUnits(JPY_16500_MINOR, "JPY", "EUR", jpyToEur),
        ).isEqualTo(EUR_100_MINOR)
    }

    @Test
    fun `the real fee math rounds the 0-5 percent margin HALF_UP in source minor units`() {
        assertThat(FxConversionMath.feeMinorUnits(FEE_BASE_MINOR)).isEqualTo(FEE_EXPECTED)
        assertThat(FxConversionMath.feeMinorUnits(HALF_ROUND_MINOR)).isEqualTo(HALF_ROUND_UP)
        assertThat(FxConversionMath.feeMinorUnits(QUARTER_ROUND_MINOR)).isEqualTo(QUARTER_ROUND_DOWN)
    }

    @Test
    fun `the real inverted rate swaps the spread sides, never the naive reciprocal`() {
        val rate = newRate(bid = "25.00", ask = "25.20")
        val inverted = rate.inverted()
        assertThat(inverted.baseCurrency).isEqualTo("CZK")
        assertThat(inverted.quoteCurrency).isEqualTo("EUR")
        // bid = 1/ask, ask = 1/bid — the customer's CZK sale prices at 1/ask, the WORSE side.
        // The 1e-8 slack is the FX_RATE rounding scale, not a semantic tolerance.
        assertThat(inverted.bidRate.multiply(rate.askRate).subtract(BigDecimal.ONE).abs())
            .isLessThan(FX_RATE_ROUNDING_SLACK)
        assertThat(inverted.askRate.multiply(rate.bidRate).subtract(BigDecimal.ONE).abs())
            .isLessThan(FX_RATE_ROUNDING_SLACK)
        assertThat(inverted.bidRate).isLessThan(inverted.askRate)
        // a naive 1/bid-for-both inversion would land on the wrong side of the spread
        val naive = BigDecimal.ONE.divide(rate.bidRate, inverted.bidRate.scale(), RoundingMode.HALF_UP)
        assertThat(inverted.bidRate).isNotEqualByComparingTo(naive)
    }

    @Test
    fun `the real CnbFixingRate normalises per-N quotes to per-unit and guards its invariants`() {
        val jpy = CnbFixingRate("JPY", 100, BigDecimal("16.40"))
        assertThat(jpy.ratePerUnit).isEqualByComparingTo(BigDecimal("0.164"))

        val eur = CnbFixingRate("EUR", 1, BigDecimal("25.20"))
        assertThat(eur.ratePerUnit).isEqualByComparingTo(BigDecimal("25.2"))

        assertThatThrownBy { CnbFixingRate("EUR", 0, BigDecimal("25.20")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CnbFixingRate("EUR", 1, BigDecimal("0")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the real screening policy blocks hits and high scores, reviews the rest, clears the clean`() {
        fun result(status: ScreeningMatchStatus, score: Double) =
            ScreeningResult("Subject", ScreeningRole.DEBTOR, status, score, null)

        assertThat(ScreeningPolicy.decide(listOf(result(ScreeningMatchStatus.HIT, 0.1))))
            .isEqualTo(ScreeningDecision.BLOCK)
        assertThat(ScreeningPolicy.decide(listOf(result(ScreeningMatchStatus.ESCALATED, 0.1))))
            .isEqualTo(ScreeningDecision.BLOCK)
        assertThat(ScreeningPolicy.decide(listOf(result(ScreeningMatchStatus.POTENTIAL_HIT, 0.90))))
            .isEqualTo(ScreeningDecision.BLOCK)
        assertThat(ScreeningPolicy.decide(listOf(result(ScreeningMatchStatus.POTENTIAL_HIT, 0.85))))
            .isEqualTo(ScreeningDecision.REVIEW)
        assertThat(ScreeningPolicy.decide(listOf(result(ScreeningMatchStatus.CLEAR, 0.0))))
            .isEqualTo(ScreeningDecision.CLEAR)
        assertThat(ScreeningPolicy.decide(listOf(result(ScreeningMatchStatus.WHITELISTED, 0.0))))
            .isEqualTo(ScreeningDecision.CLEAR)
        assertThat(ScreeningPolicy.decide(emptyList())).isEqualTo(ScreeningDecision.CLEAR)
    }

    @Test
    fun `a happy-path step settles the conversion with a live journal pair that re-derives`() {
        val world = worldWithStatus(FxConversionStatus.SETTLED)
        val conversion = world.fx.conversions.first { it.status == FxConversionStatus.SETTLED }
        val posting = world.fx.postingOf(conversion.id)
        assertThat(posting).isNotNull()
        assertThat(posting!!.czkJournalId).isNotNull()
        assertThat(posting.foreignJournalId).isNotNull()
        assertThat(posting.czkReversed).isFalse()
        assertThat(posting.foreignReversed).isFalse()
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.fxCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.fxMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `a sanctions block fails the conversion before any money moved`() {
        val world = worldWithStatus(FxConversionStatus.FAILED)
        val failed = world.fx.conversions.first { it.status == FxConversionStatus.FAILED }
        assertThat(world.fx.postingOf(failed.id)).isNull()
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.fxCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.fxMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `a write fault at the settlement post fails the conversion before any money moved`() {
        // 100% write-failure rate forces the settlement fault on every admitted conversion.
        val world = newWorld(seed = 42L, faultProfile = FaultProfile(writeFailureRate = 1.0))
        repeat(SEED_STEPS) { FxConversionScenario.step(world) }

        assertThat(world.fx.conversions).isNotEmpty()
        assertThat(world.ledger.postedCount()).isEqualTo(0)
        assertThat(world.fx.conversions.filter { it.status == FxConversionStatus.FAILED })
            .allMatch { world.fx.postingOf(it.id) == null }
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.fxCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.fxMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `a post-commit conflict reverses both durable journals and reverses the conversion`() {
        // 100% lock-conflict rate forces the reversal after every journal pair booked, so every
        // settled conversion ends REVERSED with exactly four durable entries (2 original + 2 reversals).
        val world = newWorld(seed = 42L, faultProfile = FaultProfile(lockConflictRate = 1.0))
        repeat(SEED_STEPS) { FxConversionScenario.step(world) }

        val reversed = world.fx.conversions.filter { it.status == FxConversionStatus.REVERSED }
        assertThat(reversed).isNotEmpty()
        assertThat(reversed).allMatch { world.fx.postingOf(it.id)?.czkReversed == true }
        assertThat(reversed).allMatch { world.fx.postingOf(it.id)?.foreignReversed == true }
        assertThat(world.ledger.postedCount()).isEqualTo(reversed.size * 4)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.fxCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.fxMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `the money conservation invariant catches a journal on a failed conversion`() {
        val world = worldWithStatus(FxConversionStatus.FAILED)
        val failed = world.fx.conversions.first { it.status == FxConversionStatus.FAILED }
        // mutation: a failed conversion must never have moved money — give it a live journal pair
        world.fx.recordPosting(
            FxSettlementPosting(
                conversionId = failed.id,
                czkJournalId = UUID.randomUUID(),
                foreignJournalId = UUID.randomUUID(),
            ),
        )
        assertThat(MoneyPathInvariants.fxMoneyConservation.check(world)).isNotNull()
    }

    /** Scan fixed seeds until a conversion reaches [status], so the test survives draw-order changes. */
    private fun worldWithStatus(status: FxConversionStatus): World {
        for (seed in 0L..300L) {
            val world = newWorld(seed)
            repeat(SEED_STEPS) { FxConversionScenario.step(world) }
            if (world.fx.conversions.any { it.status == status }) return world
        }
        error("no seed in 0..300 produced a $status fx conversion")
    }

    companion object {
        private const val SEED_STEPS = 10
        private const val EUR_100_MINOR = 10_000L
        private const val JPY_16500_MINOR = 16_500L
        private const val FEE_BASE_MINOR = 20_000L
        private const val FEE_EXPECTED = 100L
        private const val HALF_ROUND_MINOR = 100L
        private const val HALF_ROUND_UP = 1L
        private const val QUARTER_ROUND_MINOR = 50L
        private const val QUARTER_ROUND_DOWN = 0L
        private const val INVERSE_RATE_SCALE = 10
        private val FX_RATE_ROUNDING_SLACK = BigDecimal("0.00000001")
    }
}
