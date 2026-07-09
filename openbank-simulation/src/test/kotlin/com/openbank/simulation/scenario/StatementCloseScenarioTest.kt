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
 * Issue #667 (E2E money-path): exercises [StatementCloseScenario] directly against the REAL
 * `statement-service` domain (`StatementPeriod`, `ReconciliationPolicy`, ADR-0035/0078), so the
 * harness's binding to the statement-close path is proven by actual assertions — every step
 * records exactly one close attempt, the running close state only advances when reconciliation
 * actually succeeded, and the seeded fault (a phantom haléř standing in for a booked-entries
 * read-port gap) is refused rather than silently minting an inconsistent period.
 */
class StatementCloseScenarioTest {

    private fun newWorld(seed: Long = 42L): World =
        World(SimulationContext(seed, FaultProfile.NONE), SimulationConfig())

    @Test
    fun `a single step records exactly one close attempt`() {
        val world = newWorld()
        StatementCloseScenario.step(world)

        assertThat(world.statementCloses.attempts()).hasSize(1)
        assertThat(MoneyPathInvariants.statementCloseIntegrity.check(world)).isNull()
    }

    @Test
    fun `a reconciled close advances the running state and a mismatch leaves it untouched`() {
        var sawReconciled = false
        var sawMismatch = false
        (0L until 40L).forEach { seed ->
            val world = newWorld(seed)
            repeat(10) { StatementCloseScenario.step(world) }

            assertThat(MoneyPathInvariants.statementCloseIntegrity.check(world)).isNull()
            assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()

            world.statementCloses.attempts().forEach { attempt ->
                if (world.statementCloses.wasReconciled(attempt)) sawReconciled = true else sawMismatch = true
            }
        }
        // The seeded phantom-haléř fault must fire on some attempts and not others across the
        // sweep, exercising both the Reconciled and Mismatch branches every run.
        assertThat(sawReconciled).withFailMessage("no seed ever reconciled a close").isTrue()
        assertThat(sawMismatch).withFailMessage("no seed ever hit the seeded mismatch fault").isTrue()
    }

    @Test
    fun `a seed reproduces the exact same close outcomes deterministically`() {
        fun outcomes(seed: Long): List<Boolean> {
            val world = newWorld(seed)
            repeat(10) { StatementCloseScenario.step(world) }
            return world.statementCloses.attempts().sortedBy { it.attemptId }
                .map { world.statementCloses.wasReconciled(it) }
        }
        assertThat(outcomes(7L)).isEqualTo(outcomes(7L))
    }
}
