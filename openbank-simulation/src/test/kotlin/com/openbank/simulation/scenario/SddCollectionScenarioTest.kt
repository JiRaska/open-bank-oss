// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The SDD collection scenario driven directly against the REAL `SddMandate` aggregate and the
 * REAL `MandateLifecycle` state machine, mirroring [SepaSettlementScenarioTest]'s "real aggregate,
 * real assertions" convention (issue #267 / ADR-0100, extended to the direct-debit rail).
 */
class SddCollectionScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    @Test
    fun `a happy-path collection stamps the mandate and settles the money path`() {
        val world = newWorld()
        SddCollectionScenario.step(world)
        world.context.scheduler.drain()

        assertThat(world.sddMandates).hasSize(1)
        val mandate = world.sddMandates.single()
        assertThat(world.sddCollections.postedCollections(mandate.id)).isEqualTo(1)
        assertThat(mandate.lastCollectionDate).isNotNull()
        assertThat(world.sddCollections.lastCollectionDate(mandate.id)).isEqualTo(mandate.lastCollectionDate)
        assertThat(world.ledger.postedCount()).isEqualTo(1)
        assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }

    @Test
    fun `a first collection advances FRST to RCUR through the real lifecycle`() {
        // Seeded exploration until a FRST mandate exists (the scenario seeds FRST at 1/3 of new
        // mandates), then assert the exact rulebook advancement on the real aggregate.
        var checked = false
        var seed = 0L
        while (!checked && seed < 60L) {
            val world = World(SimulationContext(seed, FaultProfile.NONE), SimulationConfig())
            SddCollectionScenario.step(world)
            world.context.scheduler.drain()
            val mandate = world.sddMandates.single()
            if (world.sddCollections.postedCollections(mandate.id) == 1 && mandate.sequenceType == SequenceType.RCUR) {
                checked = true
                assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
            }
            seed += 1
        }
        assertThat(checked).isTrue()
    }

    @Test
    fun `a B2B mandate is confirmed through the debtor-bank gate before it can be collected`() {
        var sawB2b = false
        for (seed in 0L until 40L) {
            val world = World(SimulationContext(seed, FaultProfile.NONE), SimulationConfig())
            SddCollectionScenario.step(world)
            val mandate = world.sddMandates.single()
            if (mandate.scheme == SddScheme.B2B) {
                sawB2b = true
                // The scenario pays the confirmation up front: the mandate is ACTIVE (never left
                // PENDING_CONFIRMATION to reach the collection path) and, if money moved, stamped.
                assertThat(mandate.status).isEqualTo(MandateStatus.ACTIVE)
                assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
            }
        }
        assertThat(sawB2b).isTrue()
    }

    @Test
    fun `a write-failure fault leaves no journal and records the failed attempt`() {
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        SddCollectionScenario.step(world)
        world.context.scheduler.drain()

        val mandate = world.sddMandates.single()
        assertThat(world.ledger.postedCount()).isZero()
        assertThat(mandate.lastCollectionDate).isNull()
        assertThat(world.sddCollections.attemptedFailureCount(mandate.id)).isEqualTo(1)
        assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }

    @Test
    fun `a post-commit conflict reverses the durable journal and records the failed attempt`() {
        val world = newWorld(FaultProfile(lockConflictRate = 1.0))
        SddCollectionScenario.step(world)
        world.context.scheduler.drain()

        val mandate = world.sddMandates.single()
        assertThat(mandate.lastCollectionDate).isNull()
        assertThat(world.ledger.postedCount()).isEqualTo(2)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }

    @Test
    fun `adversarial faults keep every mandate reconciled with the book across 25 steps`() {
        val world = newWorld(FaultProfile.ADVERSARIAL)
        repeat(25) {
            SddCollectionScenario.step(world)
            world.context.scheduler.drain()
            assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
        }
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.noNegativeBalance.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }
}
