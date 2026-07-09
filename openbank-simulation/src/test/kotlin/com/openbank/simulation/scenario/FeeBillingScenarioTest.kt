// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0143 phase 2d/2e: exercises [FeeBillingScenario] directly, proving it actually feeds
 * [World.billingFees] (previously always empty, so `MoneyPathInvariants.billingFeeConservation`
 * held vacuously — see the KDoc on `World.billingFees` / `BillingFeeConservationInvariantTest`)
 * and that the fee-charge and fee-reversal journals it posts are real, balanced
 * `openbank-ledger-service` `JournalEntry` postings, not just bookkeeping in `BillingFeeLedger`.
 */
class FeeBillingScenarioTest {

    private fun newWorld(seed: Long = 42L): World = World(SimulationContext(seed, FaultProfile.NONE), SimulationConfig())

    @Test
    fun `a step assesses and posts a fee, and the conservation invariant holds`() {
        val world = newWorld()
        FeeBillingScenario.step(world)

        assertThat(world.billingFees.keys()).hasSize(1)
        val key = world.billingFees.keys().single()
        assertThat(world.billingFees.assessedAmount(key)).isGreaterThan(java.math.BigDecimal.ZERO)
        assertThat(world.billingFees.assessedAmount(key)).isEqualByComparingTo(world.billingFees.postedAmount(key))
        assertThat(MoneyPathInvariants.billingFeeConservation.check(world)).isNull()
    }

    @Test
    fun `the charge journal is a real balanced JournalEntry — conservation-of-money holds`() {
        val world = newWorld()
        FeeBillingScenario.step(world)

        assertThat(world.ledger.postedCount()).isGreaterThanOrEqualTo(1)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
    }

    @Test
    fun `charging a fee never drives the customer balance below its overdraft floor`() {
        val world = newWorld()
        repeat(50) { FeeBillingScenario.step(world) }

        assertThat(MoneyPathInvariants.noNegativeBalance.check(world)).isNull()
    }

    @Test
    fun `across many steps every assessed fee still reconciles to its posted journal`() {
        val world = newWorld(seed = 7L)
        repeat(200) { FeeBillingScenario.step(world) }

        assertThat(MoneyPathInvariants.billingFeeConservation.check(world)).isNull()
        assertThat(world.billingFees.keys()).isNotEmpty()
    }

    @Test
    fun `a seeded run eventually reverses at least one charge, posting a second balanced journal`() {
        // 200 steps at a 25% reversal rate makes "at least one reversal" overwhelmingly likely
        // for this fixed seed — a flaky 0-reversals run would itself be a red flag worth
        // investigating (the seed is fixed precisely so this is deterministic, not flaky).
        val world = newWorld(seed = 99L)
        repeat(200) { FeeBillingScenario.step(world) }

        val postedCount = world.ledger.postedCount()
        // Every step posts at least a charge; a reversed step posts a SECOND journal, so more
        // journals than steps proves at least one reversal actually happened this run.
        assertThat(postedCount).isGreaterThan(200)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.billingFeeConservation.check(world)).isNull()
    }

    @Test
    fun `is deterministic — the same seed produces the same number of postings`() {
        val first = newWorld(seed = 123L)
        repeat(30) { FeeBillingScenario.step(first) }

        val second = newWorld(seed = 123L)
        repeat(30) { FeeBillingScenario.step(second) }

        assertThat(first.ledger.postedCount()).isEqualTo(second.ledger.postedCount())
        assertThat(first.billingFees.keys()).isEqualTo(second.billingFees.keys())
    }
}
