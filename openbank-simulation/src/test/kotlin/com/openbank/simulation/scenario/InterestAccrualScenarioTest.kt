// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Issue #667 (E2E money-path): exercises [InterestAccrualScenario] directly against the REAL
 * `interest-service` domain (`InterestAccrual`/`InterestCapitalization`/`WithholdingTaxPolicy`,
 * ADR-0033) and the real ledger aggregate, so the harness's binding to the interest path is
 * proven by actual assertions — the statutory gross/net/tax split conserves money to the haléř,
 * the posted journal legs reconcile with what the policy decided, and a failed first dispatch
 * still settles through the outbox redrive.
 */
class InterestAccrualScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE, seed: Long = 42L): World =
        World(SimulationContext(seed, faultProfile), SimulationConfig())

    private fun settle(world: World) = world.context.scheduler.drain()

    @Test
    fun `a happy-path step capitalizes, posts a balanced journal and credits the customer the net`() {
        val world = newWorld()
        val before = world.customerAccounts.associateWith { id ->
            world.balances.get(AccountCurrency(id, world.currency)).bookedAmount
        }
        InterestAccrualScenario.step(world)
        settle(world)

        val key = world.interest.keys().single()
        val net = world.interest.postedNetAmount(key)
        assertThat(net).isGreaterThan(BigDecimal.ZERO)
        assertThat(world.interest.postedNetAmount(key)).isEqualByComparingTo(
            world.interest.capitalizedNetAmount(key),
        )
        // The customer's booked balance grew by exactly the NET amount — the tax never touches it.
        val after = world.balances.get(key).bookedAmount
        assertThat(after).isEqualByComparingTo(before.getValue(key.accountId) + net)
        // Per-entry double-entry balance AND the cross-side conservation both hold.
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.interestCapitalizationConservation.check(world)).isNull()
    }

    @Test
    fun `a failed first dispatch settles through the outbox redrive under the same idempotency key`() {
        // 100% write-failure rate: the FIRST dispatch of every capitalization journal fails and
        // the redrive (a zero-delay scheduler task) must land it before the step settles.
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        InterestAccrualScenario.step(world)
        settle(world)

        val key = world.interest.keys().single()
        assertThat(world.interest.postedNetAmount(key))
            .isEqualByComparingTo(world.interest.capitalizedNetAmount(key))
        assertThat(world.ledger.postedCount()).isEqualTo(1)
        assertThat(MoneyPathInvariants.interestCapitalizationConservation.check(world)).isNull()
    }

    @Test
    fun `the seeded beneficiary mix exercises both withheld and gross-credit policy branches`() {
        var sawWithheld = false
        var sawGross = false
        (0L until 20L).forEach { seed ->
            val world = newWorld(seed = seed)
            repeat(10) {
                InterestAccrualScenario.step(world)
                settle(world)
            }
            assertThat(MoneyPathInvariants.interestCapitalizationConservation.check(world)).isNull()
            assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
            world.interest.keys().forEach { key ->
                if (world.interest.postedTaxAmount(key).signum() > 0) sawWithheld = true
                if (world.interest.capitalizedGrossAmount(key)
                        .compareTo(world.interest.capitalizedNetAmount(key)) == 0
                ) {
                    sawGross = true
                }
            }
        }
        // Both statutory outcomes must occur across the sweep: withholding at source (individual)
        // and a gross credit (legal entity / exemption / sub-1-CZK base rounding to zero tax).
        assertThat(sawWithheld).withFailMessage("no seed ever withheld tax").isTrue()
        assertThat(sawGross).withFailMessage("no seed ever credited gross").isTrue()
    }

    @Test
    fun `a seed reproduces the exact same interest book deterministically`() {
        fun bookSnapshot(seed: Long): Map<String, List<BigDecimal>> {
            val world = newWorld(seed = seed)
            repeat(10) {
                InterestAccrualScenario.step(world)
                settle(world)
            }
            return world.interest.keys().associate { key ->
                key.toString() to listOf(
                    world.interest.accruedAmount(key),
                    world.interest.capitalizedGrossAmount(key),
                    world.interest.capitalizedNetAmount(key),
                    world.interest.taxWithheldAmount(key),
                    world.interest.postedNetAmount(key),
                    world.interest.postedTaxAmount(key),
                )
            }
        }
        assertThat(bookSnapshot(7L)).isEqualTo(bookSnapshot(7L))
    }
}
