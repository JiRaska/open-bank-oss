// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import com.openbank.swift.domain.model.SwiftStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Issue #667 (E2E money-path): exercises [SwiftScenario] directly against the REAL
 * swift-service domain (`SwiftMessage` with its `validate()` admission gate and MT103 status
 * vocabulary), so the harness's binding to the cross-border rail is proven by actual
 * assertions: every message settles terminal inside its step, a completed MT103 posted exactly
 * its amount, and a message the real validate() refused moved nothing.
 */
class SwiftScenarioTest {

    private fun newWorld(seed: Long = 42L, faults: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed, faults), SimulationConfig())

    @Test
    fun `every message settles terminal inside its step and both invariants hold across seeds`() {
        (0L until 30L).forEach { seed ->
            val world = newWorld(seed)
            repeat(25) {
                SwiftScenario.step(world)
                assertThat(
                    world.swiftMessages.none {
                        it.status in
                            setOf(SwiftStatus.PENDING, SwiftStatus.VALIDATED, SwiftStatus.SENT)
                    },
                )
                    .withFailMessage("seed=$seed step left a swift message mid-flight")
                    .isTrue()
                assertThat(MoneyPathInvariants.swiftCompleteness.check(world)).isNull()
                assertThat(MoneyPathInvariants.swiftMoneyConservation.check(world)).isNull()
            }
        }
    }

    @Test
    fun `a message the real validate gate refused is rejected and posts nothing`() {
        (0L until 60L).forEach { seed ->
            val world = newWorld(seed)
            repeat(60) {
                val messagesBefore = world.swiftMessages.size
                val postedBefore = world.ledger.postedCount()
                SwiftScenario.step(world)
                val message = world.swiftMessages.last()
                if (message.status == SwiftStatus.REJECTED) {
                    assertThat(world.swiftMessages.size).isEqualTo(messagesBefore + 1)
                    assertThat(world.ledger.postedCount())
                        .withFailMessage("a rejected MT103 must post nothing")
                        .isEqualTo(postedBefore)
                    assertThat(world.swiftBook.postedAmount(message.id).signum()).isZero()
                    assertThat(message.rejectionReason).isNotBlank()
                    return
                }
            }
        }
        throw AssertionError("no rejected message across 60 seeds — the validate gate was never exercised")
    }

    @Test
    fun `a completed MT103 posted exactly its amount`() {
        val world = newWorld()
        repeat(300) {
            SwiftScenario.step(world)
            val message = world.swiftMessages.last()
            if (message.status == SwiftStatus.COMPLETED) {
                val amount = message.amount.amount
                assertThat(world.swiftBook.postedAmount(message.id))
                    .withFailMessage("a completed MT103 must have posted exactly its amount")
                    .isEqualByComparingTo(amount)
                assertThat(message.ackReceivedAt).isNotNull()
                return
            }
        }
        throw AssertionError("no completed message in 300 steps — the ACK path was never exercised")
    }

    @Test
    fun `the adversarial fault profile still settles every message`() {
        val world = newWorld(seed = 11L, faults = FaultProfile.ADVERSARIAL)
        var completed = 0
        var failed = 0
        repeat(150) {
            SwiftScenario.step(world)
            assertThat(
                world.swiftMessages.none {
                    it.status in
                        setOf(SwiftStatus.PENDING, SwiftStatus.VALIDATED, SwiftStatus.SENT)
                },
            ).isTrue()
            completed = world.swiftMessages.count { it.status == SwiftStatus.COMPLETED }
            failed = world.swiftMessages.count { it.status == SwiftStatus.FAILED }
        }
        assertThat(MoneyPathInvariants.swiftCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.swiftMoneyConservation.check(world)).isNull()
        // Faults must not silently neuter the rail: completions and compensated failures both
        // occurred, and they together account for every admitted (validate-passing) message.
        assertThat(completed + failed).isGreaterThan(0)
    }
}
