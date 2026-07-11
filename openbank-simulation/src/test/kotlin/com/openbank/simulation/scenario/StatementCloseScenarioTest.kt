// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.StatementCloseKey
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

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

    /** The running-state fields [StatementCloseScenario] can move — snapshotted before each step. */
    private data class RunningState(val opening: BigDecimal, val netAtLastClose: BigDecimal, val nextSequence: Long)

    private fun snapshot(world: World, key: AccountCurrency): RunningState = RunningState(
        opening = world.statementCloses.openingBalanceOf(key, world.openingBookedOf(key)),
        netAtLastClose = world.statementCloses.netAtLastCloseOf(key),
        nextSequence = world.statementCloses.nextSequenceOf(key),
    )

    @Test
    fun `a reconciled close advances the running state and a mismatch leaves it byte-for-byte unchanged`() {
        var sawReconciled = false
        var sawMismatch = false
        (0L until 40L).forEach { seed ->
            val world = newWorld(seed)
            var seenAttempts = emptySet<StatementCloseKey>()

            repeat(10) {
                // Snapshot every customer account's running close state — cheap (4 accounts) —
                // so whichever one this step's seeded pick touches, its BEFORE state is on hand.
                val before = world.customerAccounts.associateWith { id ->
                    snapshot(world, AccountCurrency(id, world.currency))
                }

                StatementCloseScenario.step(world)

                val attempt = (world.statementCloses.attempts() - seenAttempts).single()
                seenAttempts = world.statementCloses.attempts()
                val key = AccountCurrency(attempt.accountId, attempt.currency)
                val beforeState = before.getValue(attempt.accountId)
                val afterState = snapshot(world, key)

                if (world.statementCloses.wasReconciled(attempt)) {
                    sawReconciled = true
                    assertThat(afterState.nextSequence)
                        .withFailMessage("a reconciled close must bump the sequence")
                        .isEqualTo(beforeState.nextSequence + 1)
                } else {
                    sawMismatch = true
                    // The claim under test: NOTHING moved when reconciliation refused the close.
                    assertThat(afterState.opening).isEqualByComparingTo(beforeState.opening)
                    assertThat(afterState.netAtLastClose).isEqualByComparingTo(beforeState.netAtLastClose)
                    assertThat(afterState.nextSequence)
                        .withFailMessage("a refused close must not bump the sequence")
                        .isEqualTo(beforeState.nextSequence)
                }
            }

            assertThat(MoneyPathInvariants.statementCloseIntegrity.check(world)).isNull()
            assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
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
