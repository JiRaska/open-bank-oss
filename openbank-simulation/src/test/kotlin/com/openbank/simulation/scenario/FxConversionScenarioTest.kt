// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.fx.domain.model.FxConversionMath
import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Issue #14366 (Test Intelligence simulation gap, money path): exercises [FxConversionScenario]
 * directly against the REAL `fx-service` domain — the `FxRate.inverted()` side-swap the ČNB
 * FOREIGN→CZK-only fixing forces, and the `FxConversionMath` margin/target arithmetic — so the
 * harness's binding to the service is proven by an actual assertion, not merely by compiling.
 */
class FxConversionScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    @Test
    fun `a happy-path step settles the conversion priced off the real inverted-side bid`() {
        val world = newWorld()
        FxConversionScenario.step(world)

        assertThat(world.fxRates).hasSize(1)
        assertThat(world.fxConversions).hasSize(1)
        val rate = world.fxRates.single()
        val conversion = world.fxConversions.single()
        assertThat(conversion.status).isEqualTo(FxConversionStatus.SETTLED)
        assertThat(conversion.settledAt).isNotNull()
        assertThat(conversion.rateId).isEqualTo(rate.id)

        // The customer sold CZK (the world currency) for the foreign currency, so the applied
        // rate must be the REAL inverted-side bid: 1/ask of the stored FOREIGN→CZK fixing,
        // never a naive 1/bid.
        assertThat(conversion.fromCurrency).isEqualTo(world.currency)
        assertThat(conversion.appliedRate).isEqualTo(FxConversionScenario.expectedCustomerBid(rate.askRate))

        assertThat(MoneyPathInvariants.fxConversionCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.fxMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `a write-failure fault at booking marks the conversion FAILED without a settle stamp`() {
        // 100% write-failure rate forces the booking-rejection branch every time.
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        FxConversionScenario.step(world)

        val conversion = world.fxConversions.single()
        assertThat(conversion.status).isEqualTo(FxConversionStatus.FAILED)
        assertThat(conversion.settledAt).isNull()
        // The fixing was stored before the fault; only the conversion is rejected.
        assertThat(world.fxRates).hasSize(1)
        assertThat(MoneyPathInvariants.fxConversionCompleteness.check(world)).isNull()
    }

    @Test
    fun `a post-commit conflict reverses the conversion and clears the settle stamp`() {
        // 100% conflict rate forces the post-commit compensation branch every time.
        val world = newWorld(FaultProfile(lockConflictRate = 1.0))
        FxConversionScenario.step(world)

        val conversion = world.fxConversions.single()
        assertThat(conversion.status).isEqualTo(FxConversionStatus.REVERSED)
        assertThat(conversion.settledAt).isNull()
        assertThat(MoneyPathInvariants.fxConversionCompleteness.check(world)).isNull()
    }

    @Test
    fun `the real inverted math swaps the sides and the real conversion math prices exactly`() {
        // The scenario prices a CZK seller off FxRate.inverted(); if a regression dropped the
        // side-swap (or the scenario silently took 1/bid), this assertion against the REAL
        // inverted() is what catches the drift — the two differ on every non-zero spread.
        val now = Instant.now()
        val rate = FxRate(
            id = UUID.randomUUID(),
            baseCurrency = "EUR",
            quoteCurrency = "CZK",
            bidRate = BigDecimal("25.00"),
            askRate = BigDecimal("25.20"),
            rateType = RateType.INDICATIVE,
            source = RateSource.CNB,
            validFrom = now.minus(1, ChronoUnit.HOURS),
            validTo = now.plus(1, ChronoUnit.HOURS),
            createdAt = now,
        )
        val inverted = rate.inverted()

        assertThat(inverted.pair).isEqualTo("CZK/EUR")
        assertThat(inverted.bidRate).isEqualTo(RoundingPolicy.FX_RATE.divide(BigDecimal.ONE, rate.askRate))
        assertThat(inverted.askRate).isEqualTo(RoundingPolicy.FX_RATE.divide(BigDecimal.ONE, rate.bidRate))
        // The naive quote both-sides-at-1/bid is what inverted() must NOT produce: with a
        // non-zero spread the correct customer bid differs from it.
        val naiveBid = RoundingPolicy.FX_RATE.divide(BigDecimal.ONE, rate.bidRate)
        assertThat(inverted.bidRate).isNotEqualTo(naiveBid)

        // The real target-amount arithmetic, pinned end-to-end: 1000.00 CZK at 0.04 EUR/CZK is
        // 40.00 EUR, and the 0.5% margin on the source amount is 5.00 CZK.
        assertThat(
            FxConversionMath.convertedAmountMinorUnits(100_000L, "CZK", "EUR", BigDecimal("0.04")),
        ).isEqualTo(4_000L)
        assertThat(FxConversionMath.feeMinorUnits(100_000L)).isEqualTo(500L)
    }
}
