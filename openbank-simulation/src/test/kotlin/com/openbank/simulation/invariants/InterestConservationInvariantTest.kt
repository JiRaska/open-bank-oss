// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.invariants

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Isolation coverage for [MoneyPathInvariants.interestCapitalizationConservation] (ADR-0033,
 * issue #667): the invariant must hold on correct bookkeeping AND catch each defect class on its
 * own — over-capitalization, a rounding split that creates money, and a dropped journal leg
 * (mirrors [BillingFeeConservationInvariantTest]'s two-sided proof; the seeded-scenario side
 * lives in `InterestAccrualScenarioTest` and the `DstSimulationTest` sweep).
 */
class InterestConservationInvariantTest {

    private fun world(): World = World(SimulationContext(seed = 1L, FaultProfile.NONE), SimulationConfig())

    private fun key(): AccountCurrency = AccountCurrency(UUID.randomUUID(), "CZK")

    @Test
    fun `holds trivially when no interest has accrued`() {
        assertThat(MoneyPathInvariants.interestCapitalizationConservation.check(world())).isNull()
    }

    @Test
    fun `holds when the withheld split posted both legs exactly`() {
        val w = world()
        val k = key()
        w.interest.recordAccrued(k, BigDecimal("100.500000"))
        // 15 % of a whole-CZK base of 100: tax 15, net 85.50 — the real policy's arithmetic.
        w.interest.recordCapitalized(k, BigDecimal("100.50"), BigDecimal("85.50"), BigDecimal("15"))
        w.interest.recordPosted(k, BigDecimal("85.50"), BigDecimal("15"))

        assertThat(MoneyPathInvariants.interestCapitalizationConservation.check(w)).isNull()
    }

    @Test
    fun `holds for a gross credit — legal entity, no withholding, no tax leg`() {
        val w = world()
        val k = key()
        w.interest.recordAccrued(k, BigDecimal("42.000000"))
        w.interest.recordCapitalized(k, BigDecimal("42.00"), BigDecimal("42.00"), BigDecimal.ZERO)
        w.interest.recordPosted(k, BigDecimal("42.00"), BigDecimal.ZERO)

        assertThat(MoneyPathInvariants.interestCapitalizationConservation.check(w)).isNull()
    }

    @Test
    fun `capitalizing more than ever accrued is a violation`() {
        val w = world()
        val k = key()
        w.interest.recordAccrued(k, BigDecimal("10.000000"))
        w.interest.recordCapitalized(k, BigDecimal("11.00"), BigDecimal("11.00"), BigDecimal.ZERO)
        w.interest.recordPosted(k, BigDecimal("11.00"), BigDecimal.ZERO)

        val violation = MoneyPathInvariants.interestCapitalizationConservation.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.invariant).isEqualTo("interest-capitalization-conservation")
    }

    @Test
    fun `a split whose rounding creates money is a violation`() {
        val w = world()
        val k = key()
        w.interest.recordAccrued(k, BigDecimal("100.000000"))
        // net 86.00 + tax 15 == 101.00 != gross 100.00 — one haléř materialized from rounding.
        w.interest.recordCapitalized(k, BigDecimal("100.00"), BigDecimal("86.00"), BigDecimal("15"))
        w.interest.recordPosted(k, BigDecimal("86.00"), BigDecimal("15"))

        assertThat(MoneyPathInvariants.interestCapitalizationConservation.check(w)).isNotNull()
    }

    @Test
    fun `posting the customer net but dropping the tax-payable leg is a violation`() {
        val w = world()
        val k = key()
        w.interest.recordAccrued(k, BigDecimal("100.000000"))
        w.interest.recordCapitalized(k, BigDecimal("100.00"), BigDecimal("85.00"), BigDecimal("15"))
        // The classic ADR-0033 defect class: the journal that landed carried only the net leg.
        // Per-entry balance validation cannot see it — only this cross-side reconciliation can.
        w.interest.recordPosted(k, BigDecimal("85.00"), BigDecimal.ZERO)

        val violation = MoneyPathInvariants.interestCapitalizationConservation.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.detail).contains("tax")
    }

    @Test
    fun `a capitalization stuck in the outbox — never posted — is a violation`() {
        val w = world()
        val k = key()
        w.interest.recordAccrued(k, BigDecimal("50.000000"))
        w.interest.recordCapitalized(k, BigDecimal("50.00"), BigDecimal("50.00"), BigDecimal.ZERO)
        // recordPosted never called — the dispatch failed and no redrive landed.

        assertThat(MoneyPathInvariants.interestCapitalizationConservation.check(w)).isNotNull()
    }
}
